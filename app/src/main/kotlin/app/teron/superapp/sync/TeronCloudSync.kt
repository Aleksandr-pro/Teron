package app.teron.superapp.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.teron.superapp.core.DirectoryUser
import app.teron.superapp.core.MeshRelayPacket
import app.teron.superapp.core.TeronDatabase
import app.teron.superapp.core.Theme
import app.teron.superapp.mesh.TeronMeshEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Global Teron Directory, Privacy-Preserving Contact Sync (SHA-256 Phone Hashes),
 * and Zero-Knowledge E2EE Packet Relay (ECDH P-256 + AES-256-GCM + ECDSA).
 */
class TeronCloudSync(
    private val context: Context,
    private val db: TeronDatabase,
    private val meshEngine: TeronMeshEngine
) {
    interface Listener {
        fun onCloudStateChanged()
    }

    companion object {
        private const val RPC_BASE_URL = "https://pacoftzcfuqfpeavuost.supabase.co/rest/v1/rpc"
        private const val ANON_PUBLIC_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBhY29mdHpjZnVxZnBlYXZ1b3N0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTMxMTgzMjUsImV4cCI6MjEwODY5NDMyNX0.Su4uFgY83KL5UL7sBPyzmWrVPfR7tuSBff7HwSKhMb8"
        private const val KV_LAST_SEQ = "cloud_relay_last_seq"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val netPool = Executors.newFixedThreadPool(3)
    private val listeners = Collections.synchronizedSet(LinkedHashSet<Listener>())

    @Volatile var isOnlineConnected: Boolean = false
        private set
    @Volatile var isSyncingContacts: Boolean = false
        private set
    @Volatile var lastSyncStatus: String = "Ожидание подключения"
        private set
    @Volatile private var runningLoop = false
    @Volatile private var lastProfilePushMs = 0L

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyChanged() {
        mainHandler.post {
            for (l in listeners.toList()) l.onCloudStateChanged()
        }
    }

    fun start() {
        if (runningLoop) return
        runningLoop = true
        schedulePollTick(300L)
    }

    fun stop() {
        runningLoop = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!runningLoop) return
            netPool.execute {
                try {
                    if (db.isOnboarded()) {
                        val now = System.currentTimeMillis()
                        if (now - lastProfilePushMs > 60_000L || !isOnlineConnected) {
                            if (pushOwnProfileBlocking()) {
                                lastProfilePushMs = now
                            }
                        }
                        flushOutboxQueueBlocking()
                        pullIncomingPacketsBlocking()
                    }
                } catch (_: Throwable) {}
            }
            if (runningLoop) {
                mainHandler.postDelayed(this, 5_500L)
            }
        }
    }

    private fun schedulePollTick(delayMs: Long) {
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, delayMs)
    }

    fun syncOwnProfileAsync(callback: ((Boolean, String) -> Unit)? = null) {
        netPool.execute {
            val ok = pushOwnProfileBlocking()
            if (ok) lastProfilePushMs = System.currentTimeMillis()
            val msg = if (ok) "Профиль синхронизирован с сетью Teron" else "Нет доступа к сети — активен автономный режим AirMesh"
            if (callback != null) {
                mainHandler.post { callback(ok, msg) }
            }
        }
    }

    private fun pushOwnProfileBlocking(): Boolean {
        if (!db.isOnboarded()) return false
        val me = try { db.getProfile() } catch (_: Throwable) { return false }
        if (me.publicKeyB64.isBlank()) return false

        val phoneHash = PhoneContactsHelper.computePhoneHash(me.phoneNumber)
        val body = JSONObject().apply {
            put("p_node_id", me.nodeId)
            put("p_display_name", me.displayName)
            put("p_handle", me.handle)
            put("p_bio", me.bio)
            put("p_public_key", me.publicKeyB64)
            put("p_phone_hash", phoneHash)
            put("p_avatar_color", me.avatarColor)
            put("p_btc_address", me.btcAddress)
            put("p_eth_address", me.walletAddress)
            put("p_trx_address", me.trxAddress)
        }
        val raw = postRpc("upsert_teron_user", body) ?: run {
            if (isOnlineConnected) {
                isOnlineConnected = false
                notifyChanged()
            }
            return false
        }
        return try {
            val res = JSONObject(raw)
            val ok = res.optBoolean("ok", false)
            if (ok) {
                val canonicalHandle = res.optString("handle", "")
                if (canonicalHandle.isNotBlank() && canonicalHandle != me.handle) {
                    db.updateCanonicalHandle(canonicalHandle)
                }
                val wasOffline = !isOnlineConnected
                isOnlineConnected = true
                lastSyncStatus = "В сети (${Theme.formatTime(System.currentTimeMillis())})"
                if (wasOffline) notifyChanged()
            }
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Searches global Teron directory by @handle, display name, phone number (+7 / 8...), Node ID, or wallet address.
     * When query is blank, returns recently active users in Teron.
     */
    fun searchUsersAsync(
        rawQuery: String,
        limit: Int = 35,
        callback: (success: Boolean, users: List<DirectoryUser>) -> Unit
    ) {
        netPool.execute {
            val meId = try { db.getProfile().nodeId } catch (_: Throwable) { "" }
            val q = rawQuery.trim()
            val phoneHash = PhoneContactsHelper.computePhoneHash(q)
            // If input looks purely like a formatted phone number with spaces/parens, pass phoneHash and trimmed text
            val req = JSONObject().apply {
                put("p_query", q)
                put("p_phone_hash", phoneHash)
                put("p_exclude_node_id", meId)
                put("p_limit", limit)
            }
            val raw = postRpc("search_teron_users", req)
            if (raw == null) {
                mainHandler.post { callback(false, emptyList()) }
                return@execute
            }
            val parsed = parseDirectoryUsers(raw)
            // Refresh public keys for any already-saved contacts
            val existingIds = db.getContacts().map { it.nodeId }.toSet()
            for (u in parsed) {
                if (u.nodeId in existingIds && u.publicKeyB64.isNotBlank()) {
                    db.upsertContact(u.nodeId, u.displayName, u.handle, u.publicKeyB64, u.avatarColor, u.bio, u.lastSeenEpochMs)
                }
            }
            isOnlineConnected = true
            mainHandler.post { callback(true, parsed) }
        }
    }

    fun resolveNodeAsync(queryOrNodeId: String, callback: (DirectoryUser?) -> Unit) {
        val target = queryOrNodeId.trim()
        if (target.isEmpty()) {
            callback(null)
            return
        }
        searchUsersAsync(target, limit = 10) { ok, list ->
            if (!ok || list.isEmpty()) {
                callback(null)
                return@searchUsersAsync
            }
            val exact = list.firstOrNull {
                it.nodeId.equals(target, ignoreCase = true) ||
                    it.handle.equals(target.removePrefix("@"), ignoreCase = true) ||
                    it.handle.substringBefore("@").equals(target.removePrefix("@"), ignoreCase = true)
            } ?: list.first()
            callback(exact)
        }
    }

    /**
     * Reads phone book contacts (when READ_CONTACTS granted), computes SHA-256 hashes,
     * matches them against registered Teron users, and saves verified contacts with ECDH keys.
     */
    fun syncPhoneBookContactsAsync(
        callback: ((success: Boolean, matchedInTeron: Int, totalPhoneContacts: Int, message: String) -> Unit)? = null
    ) {
        if (isSyncingContacts) return
        isSyncingContacts = true
        notifyChanged()

        netPool.execute {
            try {
                pushOwnProfileBlocking()
                val entries = PhoneContactsHelper.readDeviceContacts(context)
                db.savePhoneBookEntries(entries)

                if (entries.isEmpty()) {
                    isSyncingContacts = false
                    mainHandler.post {
                        notifyChanged()
                        callback?.invoke(true, 0, 0, "В телефонной книге нет номеров для сопоставления")
                    }
                    return@execute
                }

                val meId = db.getProfile().nodeId
                val matchedByHash = HashMap<String, DirectoryUser>()
                var rpcSucceeded = false

                val uniqueHashes = entries.map { it.phoneHash }.filter { it.isNotEmpty() }.distinct()
                for (chunk in uniqueHashes.chunked(400)) {
                    val hashesJson = JSONArray()
                    for (h in chunk) hashesJson.put(h)
                    val req = JSONObject().apply {
                        put("p_hashes", hashesJson)
                        put("p_exclude_node_id", meId)
                    }
                    val resp = postRpc("match_teron_phone_hashes", req)
                    if (resp != null) {
                        rpcSucceeded = true
                        val arr = JSONArray(resp)
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val mh = o.optString("matched_hash", "")
                            val u = DirectoryUser(
                                nodeId = o.optString("node_id", ""),
                                displayName = o.optString("display_name", ""),
                                handle = o.optString("handle", ""),
                                bio = o.optString("bio", ""),
                                publicKeyB64 = o.optString("public_key", ""),
                                avatarColor = o.optInt("avatar_color", Theme.brand),
                                lastSeenEpochMs = o.optLong("last_seen_epoch_ms", System.currentTimeMillis())
                            )
                            if (mh.isNotEmpty() && u.nodeId.isNotEmpty()) {
                                matchedByHash[mh] = u
                            }
                        }
                    }
                }

                val matchedCount = if (matchedByHash.isNotEmpty()) {
                    db.applyPhoneHashMatches(matchedByHash)
                } else 0

                isSyncingContacts = false
                val msg = if (rpcSucceeded) {
                    "Синхронизировано номеров: ${entries.size} • Найдено в Teron: $matchedCount"
                } else {
                    "Загружено номеров с телефона: ${entries.size} (онлайн-реестр недоступен)"
                }
                mainHandler.post {
                    notifyChanged()
                    callback?.invoke(rpcSucceeded, matchedCount, entries.size, msg)
                }
            } catch (t: Throwable) {
                isSyncingContacts = false
                mainHandler.post {
                    notifyChanged()
                    callback?.invoke(false, 0, 0, "Ошибка синхронизации: ${t.message ?: "неизвестно"}")
                }
            }
        }
    }

    /**
     * Pushes an already-E2EE-encrypted MeshRelayPacket through the Teron Cloud Relay
     * so two users can communicate across any distance when internet is available.
     */
    fun pushPacketAsync(pkt: MeshRelayPacket) {
        netPool.execute {
            pushPacketBlocking(pkt)
        }
    }

    private fun pushPacketBlocking(pkt: MeshRelayPacket): Boolean {
        val body = JSONObject().apply {
            put("p_packet_id", pkt.packetId)
            put("p_kind", pkt.kind)
            put("p_sender_node_id", pkt.senderNodeId)
            put("p_sender_name", pkt.senderName)
            put("p_sender_handle", pkt.senderHandle)
            put("p_sender_pub_key", pkt.senderPubKey)
            put("p_target_node_id", pkt.targetNodeId)
            put("p_ttl", pkt.ttl)
            put("p_hop_count", pkt.hopCount)
            put("p_via_nodes", pkt.viaNodes)
            put("p_cipher_text_b64", pkt.cipherTextB64)
            put("p_iv_b64", pkt.ivB64)
            put("p_signature_b64", pkt.signatureB64)
            put("p_meta_subject", pkt.metaSubject)
            put("p_created_at_ms", pkt.createdAtMs)
        }
        val raw = postRpc("push_teron_packet", body) ?: return false
        isOnlineConnected = true
        db.markRelayPacketDelivered(pkt.packetId)
        if (pkt.kind == "CHAT" || pkt.kind == "BROADCAST" || pkt.kind == "SOS") {
            db.updateMessageDeliveryState(pkt.packetId, "Отправлено • E2EE Сеть", "Сеть Teron E2EE")
        } else if (pkt.kind == "MAIL") {
            db.promoteMailOutboxToSent(pkt.packetId)
        }
        notifyChanged()
        // Trigger fast follow-up pull to catch recipient's ACK quickly
        schedulePollTick(2_000L)
        return raw.isNotBlank()
    }

    private fun flushOutboxQueueBlocking() {
        val pendingFrames = db.getPendingRelayPackets(15)
        if (pendingFrames.isEmpty()) return
        for (rawJson in pendingFrames) {
            val pkt = meshEngine.parseRelayPacketJson(rawJson) ?: continue
            if (!pushPacketBlocking(pkt)) break
        }
    }

    private fun pullIncomingPacketsBlocking() {
        val me = try { db.getProfile() } catch (_: Throwable) { return }
        val sinceSeq = db.getKv(KV_LAST_SEQ, "0").toLongOrNull() ?: 0L
        val req = JSONObject().apply {
            put("p_target_node_id", me.nodeId)
            put("p_since_seq", sinceSeq)
            put("p_limit", 60)
        }
        val raw = postRpc("pull_teron_packets", req) ?: return
        isOnlineConnected = true
        val arr = try { JSONArray(raw) } catch (_: Throwable) { return }
        if (arr.length() == 0) return

        var maxSeq = sinceSeq
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val seq = o.optLong("seq_id", 0L)
            if (seq > maxSeq) maxSeq = seq

            val pktJson = JSONObject().apply {
                put("packetId", o.optString("packet_id"))
                put("kind", o.optString("kind"))
                put("senderNodeId", o.optString("sender_node_id"))
                put("senderName", o.optString("sender_name"))
                put("senderHandle", o.optString("sender_handle"))
                put("senderPubKey", o.optString("sender_pub_key"))
                put("targetNodeId", o.optString("target_node_id"))
                put("ttl", o.optInt("ttl", 4))
                put("hopCount", o.optInt("hop_count", 0))
                put("viaNodes", o.optString("via_nodes"))
                put("cipherTextB64", o.optString("cipher_text_b64"))
                put("ivB64", o.optString("iv_b64"))
                put("signatureB64", o.optString("signature_b64"))
                put("metaSubject", o.optString("meta_subject"))
                put("createdAtMs", o.optLong("created_at_ms", System.currentTimeMillis()))
            }.toString()

            meshEngine.processIncomingPacketJson(pktJson, "Сеть Teron E2EE")
        }
        if (maxSeq > sinceSeq) {
            db.putKv(KV_LAST_SEQ, maxSeq.toString())
            notifyChanged()
        }
    }

    private fun parseDirectoryUsers(rawJson: String): List<DirectoryUser> {
        val out = mutableListOf<DirectoryUser>()
        try {
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val nid = o.optString("node_id", "").trim()
                if (nid.isEmpty()) continue
                out.add(
                    DirectoryUser(
                        nodeId = nid,
                        displayName = o.optString("display_name", nid),
                        handle = o.optString("handle", "${nid.lowercase()}@teron.mesh"),
                        bio = o.optString("bio", ""),
                        publicKeyB64 = o.optString("public_key", ""),
                        avatarColor = o.optInt("avatar_color", Theme.brand),
                        btcAddress = o.optString("btc_address", ""),
                        ethAddress = o.optString("eth_address", ""),
                        trxAddress = o.optString("trx_address", ""),
                        lastSeenEpochMs = o.optLong("last_seen_epoch_ms", 0L)
                    )
                )
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun postRpc(fnName: String, payload: JSONObject): String? {
        val conn = try {
            (URL("$RPC_BASE_URL/$fnName").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 6_500
                readTimeout = 8_000
                setRequestProperty("apikey", ANON_PUBLIC_KEY)
                setRequestProperty("Authorization", "Bearer $ANON_PUBLIC_KEY")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "TeronAndroid/2.1")
            }
        } catch (_: Throwable) {
            return null
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload.toString())
                it.flush()
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
