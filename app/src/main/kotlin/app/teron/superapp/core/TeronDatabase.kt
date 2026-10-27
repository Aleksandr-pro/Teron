package app.teron.superapp.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.teron.superapp.R
import app.teron.superapp.crypto.Secp256k1Wallet

class TeronDatabase(context: Context) : SQLiteOpenHelper(context, "teron_superapp.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE profile (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                node_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                handle TEXT NOT NULL,
                bio TEXT NOT NULL,
                phone_number TEXT NOT NULL DEFAULT '',
                avatar_color INTEGER NOT NULL,
                avatar_glyph TEXT NOT NULL,
                public_key TEXT NOT NULL,
                private_key TEXT NOT NULL,
                seed_phrase TEXT NOT NULL,
                wallet_address TEXT NOT NULL,
                btc_address TEXT NOT NULL DEFAULT '',
                trx_address TEXT NOT NULL DEFAULT '',
                max_relay_hops INTEGER NOT NULL DEFAULT 4,
                dark_theme INTEGER NOT NULL DEFAULT 1,
                bg_daemon INTEGER NOT NULL DEFAULT 0,
                onboarded INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE contacts (
                node_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                handle TEXT NOT NULL,
                bio TEXT NOT NULL DEFAULT '',
                public_key TEXT NOT NULL,
                avatar_color INTEGER NOT NULL,
                added_at INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        createPhoneBookCacheTable(db)

        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                packet_id TEXT UNIQUE NOT NULL,
                conversation_id TEXT NOT NULL,
                sender_node_id TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                recipient_node_id TEXT NOT NULL,
                body TEXT NOT NULL,
                cipher_preview TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                is_outgoing INTEGER NOT NULL,
                transport TEXT NOT NULL,
                hop_count INTEGER NOT NULL DEFAULT 0,
                via_path TEXT NOT NULL DEFAULT '',
                delivery_state TEXT NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE mesh_relay_queue (
                packet_id TEXT PRIMARY KEY,
                packet_json TEXT NOT NULL,
                target_node_id TEXT NOT NULL,
                ttl INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                delivered INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE mails (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                packet_id TEXT UNIQUE NOT NULL,
                folder TEXT NOT NULL,
                from_name TEXT NOT NULL,
                from_address TEXT NOT NULL,
                to_address TEXT NOT NULL,
                subject TEXT NOT NULL,
                body TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0,
                is_starred INTEGER NOT NULL DEFAULT 0,
                via_airmesh INTEGER NOT NULL DEFAULT 1,
                hop_count INTEGER NOT NULL DEFAULT 0,
                priority TEXT NOT NULL DEFAULT 'NORMAL'
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE music_favorites (
                track_key TEXT PRIMARY KEY,
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE hanzi_progress (
                card_id INTEGER PRIMARY KEY,
                mastery_level INTEGER NOT NULL DEFAULT 0,
                last_reviewed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        createWalletTablesV2(db)

        db.execSQL(
            """
            CREATE TABLE kv_store (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            )
            """.trimIndent()
        )

        initializeCleanIdentity(db)
    }

    private fun createWalletTablesV2(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS wallet_balances")
        db.execSQL("DROP TABLE IF EXISTS wallet_txs")

        db.execSQL(
            """
            CREATE TABLE wallet_balances (
                asset_key TEXT PRIMARY KEY,
                symbol TEXT NOT NULL,
                network_label TEXT NOT NULL,
                name TEXT NOT NULL,
                balance REAL NOT NULL DEFAULT 0.0,
                rate_usd REAL NOT NULL DEFAULT 0.0,
                rate_rub REAL NOT NULL DEFAULT 0.0,
                accent_color INTEGER NOT NULL,
                decimals_display INTEGER NOT NULL DEFAULT 4
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE wallet_txs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tx_hash TEXT UNIQUE NOT NULL,
                symbol TEXT NOT NULL,
                network TEXT NOT NULL,
                amount REAL NOT NULL,
                counterparty_name TEXT NOT NULL,
                counterparty_address TEXT NOT NULL,
                is_incoming INTEGER NOT NULL,
                memo TEXT NOT NULL,
                status TEXT NOT NULL,
                via_airmesh INTEGER NOT NULL,
                timestamp_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createPhoneBookCacheTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS phone_book_cache (
                canonical_phone TEXT PRIMARY KEY,
                raw_phone TEXT NOT NULL,
                display_name TEXT NOT NULL,
                phone_hash TEXT NOT NULL,
                matched_node_id TEXT NOT NULL DEFAULT '',
                matched_handle TEXT NOT NULL DEFAULT '',
                synced_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE profile ADD COLUMN btc_address TEXT NOT NULL DEFAULT ''") } catch (_: Throwable) {}
            try { db.execSQL("ALTER TABLE profile ADD COLUMN trx_address TEXT NOT NULL DEFAULT ''") } catch (_: Throwable) {}

            // Remove all v1 demo/seeded messages & mails while preserving real user messages
            db.delete("messages", "packet_id IN ('welcome-airmesh-1', 'welcome-notes-1') OR packet_id LIKE 'sim-%'", null)
            db.delete("mails", "packet_id IN ('mail-welcome-1', 'mail-welcome-2')", null)

            // Replace v1 demo wallet tables with clean 0.00 real blockchain asset rows
            createWalletTablesV2(db)
            initRealAssetRows(db)

            // Upgrade non-BIP39 v1 seed to canonical BIP-39 multi-chain wallet
            db.rawQuery("SELECT display_name, seed_phrase FROM profile WHERE id = 1", null).use { c ->
                if (c.moveToFirst()) {
                    val curName = c.getString(0) ?: ""
                    val curSeed = c.getString(1) ?: ""
                    val id = if (Secp256k1Wallet.validateMnemonic(curSeed)) {
                        TeronCrypto.generateIdentity(curSeed)
                    } else {
                        TeronCrypto.generateIdentity(null)
                    }
                    val cv = ContentValues().apply {
                        put("node_id", id.nodeId)
                        put("seed_phrase", id.seedPhrase)
                        put("wallet_address", id.walletAddress)
                        put("btc_address", id.btcAddress)
                        put("trx_address", id.trxAddress)
                        put("public_key", id.publicKeyB64)
                        put("private_key", id.privateKeyB64)
                        put("avatar_glyph", Theme.initialsFor(curName.ifBlank { "T" }))
                        if (curName.startsWith("Пилот ") || curName.isBlank()) {
                            put("onboarded", 0)
                        }
                    }
                    db.update("profile", cv, "id = 1", null)
                }
            }
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE profile ADD COLUMN phone_number TEXT NOT NULL DEFAULT ''") } catch (_: Throwable) {}
            try { db.execSQL("ALTER TABLE contacts ADD COLUMN bio TEXT NOT NULL DEFAULT ''") } catch (_: Throwable) {}
            try { db.execSQL("ALTER TABLE contacts ADD COLUMN last_seen_ms INTEGER NOT NULL DEFAULT 0") } catch (_: Throwable) {}
            createPhoneBookCacheTable(db)
        }
    }

    private fun initializeCleanIdentity(db: SQLiteDatabase) {
        val id = TeronCrypto.generateIdentity(null)
        val shortTag = id.nodeId.substringAfter("TRN-").lowercase()
        db.insert("profile", null, ContentValues().apply {
            put("id", 1)
            put("node_id", id.nodeId)
            put("display_name", "")
            put("handle", "$shortTag@teron.mesh")
            put("bio", "")
            put("avatar_color", Theme.brand)
            put("avatar_glyph", "T")
            put("public_key", id.publicKeyB64)
            put("private_key", id.privateKeyB64)
            put("seed_phrase", id.seedPhrase)
            put("wallet_address", id.walletAddress)
            put("btc_address", id.btcAddress)
            put("trx_address", id.trxAddress)
            put("max_relay_hops", 4)
            put("dark_theme", 1)
            put("bg_daemon", 0)
            put("onboarded", 0)
        })
        initRealAssetRows(db)
    }

    private fun initRealAssetRows(db: SQLiteDatabase) {
        data class Spec(val key: String, val sym: String, val net: String, val name: String, val color: Int, val dec: Int)
        val specs = listOf(
            Spec("USDT_TRC20", "USDT", "TRC-20 • TRON", "Tether USD", Theme.colorUsdt, 2),
            Spec("USDT_ERC20", "USDT", "ERC-20 • Ethereum", "Tether USD", Theme.colorUsdt, 2),
            Spec("BTC", "BTC", "Bitcoin • SegWit", "Bitcoin", Theme.colorBtc, 6),
            Spec("ETH", "ETH", "Ethereum • Mainnet", "Ethereum", Theme.colorEth, 5),
            Spec("TRX", "TRX", "TRON • Mainnet", "TRON", Theme.colorTrx, 2)
        )
        for (s in specs) {
            db.insertWithOnConflict("wallet_balances", null, ContentValues().apply {
                put("asset_key", s.key)
                put("symbol", s.sym)
                put("network_label", s.net)
                put("name", s.name)
                put("balance", 0.0)
                put("rate_usd", 0.0)
                put("rate_rub", 0.0)
                put("accent_color", s.color)
                put("decimals_display", s.dec)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun getProfile(): UserProfile {
        readableDatabase.rawQuery("SELECT * FROM profile WHERE id = 1", null).use { c ->
            if (c.moveToFirst()) {
                val rawName = c.getString(c.getColumnIndexOrThrow("display_name"))
                val nodeId = c.getString(c.getColumnIndexOrThrow("node_id"))
                val phoneIdx = c.getColumnIndex("phone_number")
                return UserProfile(
                    nodeId = nodeId,
                    displayName = rawName.ifBlank { nodeId },
                    handle = c.getString(c.getColumnIndexOrThrow("handle")),
                    bio = c.getString(c.getColumnIndexOrThrow("bio")),
                    phoneNumber = if (phoneIdx >= 0) c.getString(phoneIdx).orEmpty() else "",
                    avatarColor = c.getInt(c.getColumnIndexOrThrow("avatar_color")),
                    avatarGlyph = c.getString(c.getColumnIndexOrThrow("avatar_glyph")).ifBlank { Theme.initialsFor(rawName.ifBlank { nodeId }) },
                    publicKeyB64 = c.getString(c.getColumnIndexOrThrow("public_key")),
                    privateKeyB64 = c.getString(c.getColumnIndexOrThrow("private_key")),
                    seedPhrase = c.getString(c.getColumnIndexOrThrow("seed_phrase")),
                    walletAddress = c.getString(c.getColumnIndexOrThrow("wallet_address")),
                    btcAddress = c.getString(c.getColumnIndexOrThrow("btc_address")),
                    trxAddress = c.getString(c.getColumnIndexOrThrow("trx_address")),
                    maxRelayHops = c.getInt(c.getColumnIndexOrThrow("max_relay_hops")),
                    darkTheme = c.getInt(c.getColumnIndexOrThrow("dark_theme")) == 1,
                    backgroundDaemon = c.getInt(c.getColumnIndexOrThrow("bg_daemon")) == 1
                )
            }
        }
        throw IllegalStateException("Profile missing")
    }

    fun isOnboarded(): Boolean {
        readableDatabase.rawQuery("SELECT onboarded FROM profile WHERE id = 1", null).use { c ->
            return c.moveToFirst() && c.getInt(0) == 1
        }
    }

    fun completeOnboarding(
        displayName: String,
        handleInput: String,
        bio: String,
        mnemonicOrNull: String?,
        phoneNumber: String = ""
    ) {
        val cur = getProfile()
        val id = if (!mnemonicOrNull.isNullOrBlank() && Secp256k1Wallet.validateMnemonic(mnemonicOrNull)) {
            TeronCrypto.generateIdentity(mnemonicOrNull)
        } else {
            if (!mnemonicOrNull.isNullOrBlank()) TeronCrypto.generateIdentity(mnemonicOrNull)
            else TeronCrypto.generateIdentity(cur.seedPhrase)
        }
        val cleanName = displayName.trim().ifBlank { id.nodeId }
        val normalizedHandle = handleInput.trim().lowercase().let { h ->
            when {
                h.isBlank() -> "${id.nodeId.substringAfter("TRN-").lowercase()}@teron.mesh"
                h.contains("@") -> h
                else -> "$h@teron.mesh"
            }
        }
        val color = Theme.avatarPalette[(cleanName.hashCode() and 0x7FFFFFFF) % Theme.avatarPalette.size]
        val initials = Theme.initialsFor(cleanName)

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("profile", ContentValues().apply {
                put("node_id", id.nodeId)
                put("display_name", cleanName)
                put("handle", normalizedHandle)
                put("bio", bio.trim())
                put("phone_number", phoneNumber.trim())
                put("avatar_color", color)
                put("avatar_glyph", initials)
                put("public_key", id.publicKeyB64)
                put("private_key", id.privateKeyB64)
                put("seed_phrase", id.seedPhrase)
                put("wallet_address", id.walletAddress)
                put("btc_address", id.btcAddress)
                put("trx_address", id.trxAddress)
                put("onboarded", 1)
            }, "id = 1", null)

            if (id.walletAddress != cur.walletAddress) {
                db.update("wallet_balances", ContentValues().apply { put("balance", 0.0) }, null, null)
                db.delete("wallet_txs", null, null)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun importWalletSeed(mnemonic: String): Boolean {
        if (!Secp256k1Wallet.validateMnemonic(mnemonic)) return false
        val id = TeronCrypto.generateIdentity(mnemonic)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("profile", ContentValues().apply {
                put("node_id", id.nodeId)
                put("seed_phrase", id.seedPhrase)
                put("wallet_address", id.walletAddress)
                put("btc_address", id.btcAddress)
                put("trx_address", id.trxAddress)
                put("public_key", id.publicKeyB64)
                put("private_key", id.privateKeyB64)
            }, "id = 1", null)
            db.update("wallet_balances", ContentValues().apply { put("balance", 0.0) }, null, null)
            db.delete("wallet_txs", null, null)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun updateProfile(
        displayName: String,
        handle: String,
        bio: String,
        avatarColor: Int,
        avatarGlyph: String,
        maxRelayHops: Int,
        darkTheme: Boolean,
        backgroundDaemon: Boolean,
        phoneNumber: String = getProfile().phoneNumber
    ) {
        val cur = getProfile()
        val cleanName = displayName.trim().ifBlank { cur.displayName }
        val cleanHandle = handle.trim().lowercase().let {
            when {
                it.isBlank() -> cur.handle
                it.contains("@") -> it
                else -> "$it@teron.mesh"
            }
        }
        writableDatabase.update("profile", ContentValues().apply {
            put("display_name", cleanName)
            put("handle", cleanHandle)
            put("bio", bio.trim())
            put("phone_number", phoneNumber.trim())
            put("avatar_color", avatarColor)
            put("avatar_glyph", avatarGlyph.trim().take(2).uppercase().ifBlank { Theme.initialsFor(cleanName) })
            put("max_relay_hops", maxRelayHops.coerceIn(1, 6))
            put("dark_theme", if (darkTheme) 1 else 0)
            put("bg_daemon", if (backgroundDaemon) 1 else 0)
            put("onboarded", 1)
        }, "id = 1", null)
    }

    fun updateCanonicalHandle(handle: String) {
        if (handle.isBlank()) return
        writableDatabase.update("profile", ContentValues().apply {
            put("handle", handle.trim().lowercase())
        }, "id = 1", null)
    }

    // --- Contacts & Phone Book Sync ---
    fun upsertContact(
        nodeId: String,
        displayName: String,
        handle: String,
        publicKeyB64: String,
        avatarColor: Int = Theme.brand,
        bio: String = "",
        lastSeenMs: Long = 0L
    ) {
        val cleanId = nodeId.trim()
        if (cleanId.isBlank() || cleanId == getProfile().nodeId) return
        val existingPk = getContactPublicKey(cleanId).orEmpty()
        var existingName = ""
        var existingHandle = ""
        var existingBio = ""
        var existingAddedAt = System.currentTimeMillis()
        readableDatabase.rawQuery(
            "SELECT display_name, handle, bio, added_at FROM contacts WHERE node_id = ?",
            arrayOf(cleanId)
        ).use { c ->
            if (c.moveToFirst()) {
                existingName = c.getString(0).orEmpty()
                existingHandle = c.getString(1).orEmpty()
                existingBio = c.getString(2).orEmpty()
                existingAddedAt = c.getLong(3)
            }
        }

        val finalName = displayName.trim().let {
            if (it.isBlank() || (it == cleanId && existingName.isNotBlank() && existingName != cleanId)) existingName.ifBlank { cleanId }
            else it
        }
        val finalHandle = handle.trim().ifBlank { existingHandle.ifBlank { "${cleanId.lowercase()}@teron.mesh" } }
        val finalPk = publicKeyB64.trim().ifBlank { existingPk }
        val finalBio = bio.trim().ifBlank { existingBio }
        val color = Theme.avatarPalette[(cleanId.hashCode() and 0x7FFFFFFF) % Theme.avatarPalette.size]

        writableDatabase.insertWithOnConflict("contacts", null, ContentValues().apply {
            put("node_id", cleanId)
            put("display_name", finalName)
            put("handle", finalHandle)
            put("bio", finalBio)
            put("public_key", finalPk)
            put("avatar_color", if (avatarColor == Theme.brand) color else avatarColor)
            put("added_at", existingAddedAt)
            put("last_seen_ms", if (lastSeenMs > 0L) lastSeenMs else System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteContact(nodeId: String) {
        writableDatabase.delete("contacts", "node_id = ?", arrayOf(nodeId))
    }

    fun getContacts(searchQuery: String = ""): List<MeshPeer> {
        val list = mutableListOf<MeshPeer>()
        val q = searchQuery.trim().lowercase().removePrefix("@")
        readableDatabase.rawQuery("SELECT * FROM contacts ORDER BY display_name COLLATE NOCASE ASC", null).use { c ->
            while (c.moveToNext()) {
                val nid = c.getString(c.getColumnIndexOrThrow("node_id"))
                val name = c.getString(c.getColumnIndexOrThrow("display_name"))
                val hnd = c.getString(c.getColumnIndexOrThrow("handle"))
                val pk = c.getString(c.getColumnIndexOrThrow("public_key"))
                val seenIdx = c.getColumnIndex("last_seen_ms")
                val seen = if (seenIdx >= 0) c.getLong(seenIdx) else 0L
                if (q.isEmpty() ||
                    nid.lowercase().contains(q) ||
                    name.lowercase().contains(q) ||
                    hnd.lowercase().contains(q)
                ) {
                    list.add(
                        MeshPeer(
                            nodeId = nid,
                            displayName = name,
                            handle = hnd,
                            publicKeyB64 = pk,
                            transport = if (pk.isNotBlank()) "В Teron • E2EE" else "Ожидает ключ",
                            rssi = 0,
                            distanceMeters = 0.0,
                            hops = 1,
                            ipAddress = null,
                            bleAddress = null,
                            lastSeenMs = if (seen > 0L) seen else c.getLong(c.getColumnIndexOrThrow("added_at")),
                            isTrustedContact = true
                        )
                    )
                }
            }
        }
        return list
    }

    fun savePhoneBookEntries(entries: List<PhoneBookEntry>) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        // Keep existing matched_node_id / matched_handle across refreshes
        val prevMatches = mutableMapOf<String, Pair<String, String>>()
        db.rawQuery("SELECT canonical_phone, matched_node_id, matched_handle FROM phone_book_cache WHERE matched_node_id <> ''", null).use { c ->
            while (c.moveToNext()) {
                prevMatches[c.getString(0)] = c.getString(1) to c.getString(2)
            }
        }

        db.beginTransaction()
        try {
            db.delete("phone_book_cache", null, null)
            for (e in entries) {
                val prev = prevMatches[e.canonicalPhone]
                db.insertWithOnConflict("phone_book_cache", null, ContentValues().apply {
                    put("canonical_phone", e.canonicalPhone)
                    put("raw_phone", e.rawPhone)
                    put("display_name", e.displayName)
                    put("phone_hash", e.phoneHash)
                    put("matched_node_id", e.matchedNodeId.ifBlank { prev?.first.orEmpty() })
                    put("matched_handle", e.matchedHandle.ifBlank { prev?.second.orEmpty() })
                    put("synced_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun applyPhoneHashMatches(matchesByHash: Map<String, DirectoryUser>): Int {
        if (matchesByHash.isEmpty()) return 0
        var matchedCount = 0
        val entries = getAllPhoneBookEntries()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (entry in entries) {
                val matched = matchesByHash[entry.phoneHash] ?: continue
                matchedCount++
                db.update(
                    "phone_book_cache",
                    ContentValues().apply {
                        put("matched_node_id", matched.nodeId)
                        put("matched_handle", matched.handle)
                    },
                    "canonical_phone = ?",
                    arrayOf(entry.canonicalPhone)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        for (entry in entries) {
            val matched = matchesByHash[entry.phoneHash] ?: continue
            val preferredName = entry.displayName.ifBlank { matched.displayName }
            upsertContact(
                nodeId = matched.nodeId,
                displayName = preferredName,
                handle = matched.handle,
                publicKeyB64 = matched.publicKeyB64,
                avatarColor = matched.avatarColor,
                bio = matched.bio,
                lastSeenMs = matched.lastSeenEpochMs
            )
        }
        return matchedCount
    }

    fun getAllPhoneBookEntries(searchQuery: String = ""): List<PhoneBookEntry> {
        val out = mutableListOf<PhoneBookEntry>()
        val q = searchQuery.trim().lowercase()
        readableDatabase.rawQuery(
            "SELECT * FROM phone_book_cache ORDER BY CASE WHEN matched_node_id <> '' THEN 0 ELSE 1 END, display_name COLLATE NOCASE ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val entry = PhoneBookEntry(
                    canonicalPhone = c.getString(c.getColumnIndexOrThrow("canonical_phone")),
                    rawPhone = c.getString(c.getColumnIndexOrThrow("raw_phone")),
                    displayName = c.getString(c.getColumnIndexOrThrow("display_name")),
                    phoneHash = c.getString(c.getColumnIndexOrThrow("phone_hash")),
                    matchedNodeId = c.getString(c.getColumnIndexOrThrow("matched_node_id")),
                    matchedHandle = c.getString(c.getColumnIndexOrThrow("matched_handle"))
                )
                if (q.isEmpty() ||
                    entry.displayName.lowercase().contains(q) ||
                    entry.rawPhone.lowercase().contains(q) ||
                    entry.canonicalPhone.contains(q) ||
                    entry.matchedHandle.lowercase().contains(q)
                ) {
                    out.add(entry)
                }
            }
        }
        return out
    }

    fun getContactPublicKey(nodeId: String): String? {
        readableDatabase.rawQuery("SELECT public_key FROM contacts WHERE node_id = ?", arrayOf(nodeId)).use { c ->
            if (c.moveToFirst()) {
                val pk = c.getString(0)
                if (pk.isNotBlank()) return pk
            }
        }
        return null
    }

    // --- Messenger ---
    fun insertMessageIfNew(
        packetId: String,
        conversationId: String,
        senderNodeId: String,
        senderName: String,
        recipientNodeId: String,
        body: String,
        cipherPreview: String,
        timestampMs: Long,
        isOutgoing: Boolean,
        transport: String,
        hopCount: Int,
        viaPath: String,
        deliveryState: String,
        isRead: Boolean = isOutgoing
    ): Boolean {
        val rowId = writableDatabase.insertWithOnConflict("messages", null, ContentValues().apply {
            put("packet_id", packetId)
            put("conversation_id", conversationId)
            put("sender_node_id", senderNodeId)
            put("sender_name", senderName)
            put("recipient_node_id", recipientNodeId)
            put("body", body)
            put("cipher_preview", cipherPreview)
            put("timestamp_ms", timestampMs)
            put("is_outgoing", if (isOutgoing) 1 else 0)
            put("transport", transport)
            put("hop_count", hopCount)
            put("via_path", viaPath)
            put("delivery_state", deliveryState)
            put("is_read", if (isRead) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return rowId != -1L
    }

    fun deleteMessage(id: Long) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id.toString()))
    }

    fun updateMessageDeliveryState(packetId: String, state: String, transport: String? = null) {
        val cv = ContentValues().apply {
            put("delivery_state", state)
            if (transport != null) put("transport", transport)
        }
        writableDatabase.update("messages", cv, "packet_id = ?", arrayOf(packetId))
    }

    fun markConversationRead(conversationId: String) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("is_read", 1) },
            "conversation_id = ? AND is_read = 0",
            arrayOf(conversationId)
        )
    }

    fun getMessages(conversationId: String): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE conversation_id = ? ORDER BY timestamp_ms ASC",
            arrayOf(conversationId)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChatMessage(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        packetId = c.getString(c.getColumnIndexOrThrow("packet_id")),
                        conversationId = c.getString(c.getColumnIndexOrThrow("conversation_id")),
                        senderNodeId = c.getString(c.getColumnIndexOrThrow("sender_node_id")),
                        senderName = c.getString(c.getColumnIndexOrThrow("sender_name")),
                        recipientNodeId = c.getString(c.getColumnIndexOrThrow("recipient_node_id")),
                        body = c.getString(c.getColumnIndexOrThrow("body")),
                        cipherPreview = c.getString(c.getColumnIndexOrThrow("cipher_preview")),
                        timestampMs = c.getLong(c.getColumnIndexOrThrow("timestamp_ms")),
                        isOutgoing = c.getInt(c.getColumnIndexOrThrow("is_outgoing")) == 1,
                        transport = c.getString(c.getColumnIndexOrThrow("transport")),
                        hopCount = c.getInt(c.getColumnIndexOrThrow("hop_count")),
                        viaPath = c.getString(c.getColumnIndexOrThrow("via_path")),
                        deliveryState = c.getString(c.getColumnIndexOrThrow("delivery_state"))
                    )
                )
            }
        }
        return out
    }

    fun getTotalUnreadChats(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE is_read = 0", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun getConversations(livePeers: Map<String, MeshPeer>, searchQuery: String = ""): List<ChatConversationSummary> {
        val result = mutableListOf<ChatConversationSummary>()
        val contactsMap = getContacts().associateBy { it.nodeId }

        fun lastMsgFor(convId: String): Pair<String?, Long> {
            readableDatabase.rawQuery(
                "SELECT body, timestamp_ms FROM messages WHERE conversation_id = ? ORDER BY timestamp_ms DESC LIMIT 1",
                arrayOf(convId)
            ).use { c ->
                if (c.moveToFirst()) return c.getString(0) to c.getLong(1)
            }
            return null to 0L
        }

        fun unreadFor(convId: String): Int {
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND is_read = 0",
                arrayOf(convId)
            ).use { c ->
                return if (c.moveToFirst()) c.getInt(0) else 0
            }
        }

        // Collect all peer conversation IDs first (real peers, contacts, and active DMs)
        val peerIds = linkedSetOf<String>()
        peerIds.addAll(livePeers.keys)
        peerIds.addAll(contactsMap.keys)
        readableDatabase.rawQuery(
            "SELECT DISTINCT conversation_id FROM messages WHERE conversation_id NOT IN ('#airmesh', '#sos', 'notes')",
            null
        ).use { c ->
            while (c.moveToNext()) peerIds.add(c.getString(0))
        }

        val myId = getProfile().nodeId
        peerIds.remove(myId)
        peerIds.remove("*")

        for (pid in peerIds) {
            val live = livePeers[pid]
            val contact = contactsMap[pid]
            val (lastBody, lastTs) = lastMsgFor(pid)
            val title = live?.displayName ?: contact?.displayName ?: pid
            val sub = when {
                live != null -> "${live.transport} • ~${"%.1f".format(live.distanceMeters)} м • ${live.hops} пер."
                contact != null -> "${contact.handle} • ECDH E2EE"
                else -> "$pid • Очередь Store & Forward"
            }
            result.add(
                ChatConversationSummary(
                    conversationId = pid,
                    title = title,
                    subtitle = sub,
                    lastMessage = lastBody ?: "Начните защищённый диалог",
                    lastTimestampMs = if (lastTs > 0L) lastTs else (live?.lastSeenMs ?: contact?.lastSeenMs ?: 0L),
                    unreadCount = unreadFor(pid),
                    accentColor = Theme.avatarPalette[(pid.hashCode() and 0x7FFFFFFF) % Theme.avatarPalette.size],
                    iconRes = 0,
                    initials = Theme.initialsFor(title),
                    isChannel = false,
                    isOnlineNearby = live != null,
                    hops = live?.hops ?: 1
                )
            )
        }

        val (airLast, airTs) = lastMsgFor("#airmesh")
        result.add(
            ChatConversationSummary(
                conversationId = "#airmesh",
                title = "Общий радиоэфир (#airmesh)",
                subtitle = "Локальный канал • ${livePeers.size} узлов в радиусе",
                lastMessage = airLast ?: "Широковещательный канал для устройств рядом",
                lastTimestampMs = airTs,
                unreadCount = unreadFor("#airmesh"),
                accentColor = Theme.brand,
                iconRes = R.drawable.ic_radar,
                initials = "",
                isChannel = true,
                isOnlineNearby = livePeers.isNotEmpty(),
                hops = 1
            )
        )

        val (notesLast, notesTs) = lastMsgFor("notes")
        result.add(
            ChatConversationSummary(
                conversationId = "notes",
                title = "Избранное",
                subtitle = "Личное локальное хранилище AES-256-GCM",
                lastMessage = notesLast ?: "Личные заметки, ключи и координаты",
                lastTimestampMs = notesTs,
                unreadCount = 0,
                accentColor = 0xFF0F766E.toInt(),
                iconRes = R.drawable.ic_lock,
                initials = "",
                isChannel = false,
                isOnlineNearby = true,
                hops = 0
            )
        )

        val (sosLast, sosTs) = lastMsgFor("#sos")
        result.add(
            ChatConversationSummary(
                conversationId = "#sos",
                title = "Экстренный канал (#sos)",
                subtitle = "Приоритетная ретрансляция до 6 переходов",
                lastMessage = sosLast ?: "Экстренные оповещения при отсутствии связи",
                lastTimestampMs = sosTs,
                unreadCount = unreadFor("#sos"),
                accentColor = Theme.accentCoral,
                iconRes = R.drawable.ic_alert,
                initials = "",
                isChannel = true,
                isOnlineNearby = true,
                hops = 0
            )
        )

        val q = searchQuery.trim().lowercase().removePrefix("@")
        if (q.isEmpty()) return result
        return result.filter {
            it.title.lowercase().contains(q) ||
                it.conversationId.lowercase().contains(q) ||
                it.subtitle.lowercase().contains(q) ||
                it.lastMessage.lowercase().contains(q)
        }
    }

    // --- AirMesh Store & Forward Queue ---
    fun hasSeenPacket(packetId: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM mesh_relay_queue WHERE packet_id = ? UNION SELECT 1 FROM messages WHERE packet_id = ? UNION SELECT 1 FROM mails WHERE packet_id = ?",
            arrayOf(packetId, packetId, packetId)
        ).use { c ->
            return c.moveToFirst()
        }
    }

    fun enqueueRelayPacket(packetId: String, packetJson: String, targetNodeId: String, ttl: Int) {
        writableDatabase.insertWithOnConflict("mesh_relay_queue", null, ContentValues().apply {
            put("packet_id", packetId)
            put("packet_json", packetJson)
            put("target_node_id", targetNodeId)
            put("ttl", ttl)
            put("created_at", System.currentTimeMillis())
            put("delivered", 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getPendingRelayPackets(limit: Int = 25): List<String> {
        val cutoff = System.currentTimeMillis() - 48 * 3600_000L
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT packet_json FROM mesh_relay_queue WHERE delivered = 0 AND ttl > 0 AND created_at > ? ORDER BY created_at DESC LIMIT ?",
            arrayOf(cutoff.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun countPendingRelayPackets(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM mesh_relay_queue WHERE delivered = 0 AND ttl > 0", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun markRelayPacketDelivered(packetId: String) {
        writableDatabase.update(
            "mesh_relay_queue",
            ContentValues().apply { put("delivered", 1) },
            "packet_id = ?",
            arrayOf(packetId)
        )
    }

    // --- Teron Mail & IMAP/SMTP Account ---
    fun saveMailAccountConfig(cfg: MailAccountConfig) {
        putKv("mail_email", cfg.email.trim())
        putKv("mail_password", cfg.password)
        putKv("mail_sender_name", cfg.senderName.trim())
        putKv("mail_imap_host", cfg.imapHost.trim())
        putKv("mail_imap_port", cfg.imapPort.toString())
        putKv("mail_smtp_host", cfg.smtpHost.trim())
        putKv("mail_smtp_port", cfg.smtpPort.toString())
    }

    fun getMailAccountConfig(): MailAccountConfig = MailAccountConfig(
        email = getKv("mail_email", ""),
        password = getKv("mail_password", ""),
        senderName = getKv("mail_sender_name", getProfile().displayName),
        imapHost = getKv("mail_imap_host", ""),
        imapPort = getKv("mail_imap_port", "993").toIntOrNull() ?: 993,
        smtpHost = getKv("mail_smtp_host", ""),
        smtpPort = getKv("mail_smtp_port", "465").toIntOrNull() ?: 465
    )

    fun insertMailIfNew(
        packetId: String,
        folder: String,
        fromName: String,
        fromAddress: String,
        toAddress: String,
        subject: String,
        body: String,
        timestampMs: Long,
        isRead: Boolean,
        viaAirMesh: Boolean,
        hopCount: Int,
        priority: String = "NORMAL"
    ): Boolean {
        val id = writableDatabase.insertWithOnConflict("mails", null, ContentValues().apply {
            put("packet_id", packetId)
            put("folder", folder)
            put("from_name", fromName)
            put("from_address", fromAddress)
            put("to_address", toAddress)
            put("subject", subject.ifBlank { "(Без темы)" })
            put("body", body)
            put("timestamp_ms", timestampMs)
            put("is_read", if (isRead) 1 else 0)
            put("is_starred", 0)
            put("via_airmesh", if (viaAirMesh) 1 else 0)
            put("hop_count", hopCount)
            put("priority", priority)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return id != -1L
    }

    fun getMails(folder: String, searchQuery: String = ""): List<MailItem> {
        val out = mutableListOf<MailItem>()
        val sql = when {
            folder == "STARRED" -> "SELECT * FROM mails WHERE is_starred = 1 ORDER BY timestamp_ms DESC"
            else -> "SELECT * FROM mails WHERE folder = ? ORDER BY timestamp_ms DESC"
        }
        val args = if (folder == "STARRED") emptyArray() else arrayOf(folder)
        val q = searchQuery.trim().lowercase()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val item = MailItem(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    packetId = c.getString(c.getColumnIndexOrThrow("packet_id")),
                    folder = c.getString(c.getColumnIndexOrThrow("folder")),
                    fromName = c.getString(c.getColumnIndexOrThrow("from_name")),
                    fromAddress = c.getString(c.getColumnIndexOrThrow("from_address")),
                    toAddress = c.getString(c.getColumnIndexOrThrow("to_address")),
                    subject = c.getString(c.getColumnIndexOrThrow("subject")),
                    body = c.getString(c.getColumnIndexOrThrow("body")),
                    timestampMs = c.getLong(c.getColumnIndexOrThrow("timestamp_ms")),
                    isRead = c.getInt(c.getColumnIndexOrThrow("is_read")) == 1,
                    isStarred = c.getInt(c.getColumnIndexOrThrow("is_starred")) == 1,
                    viaAirMesh = c.getInt(c.getColumnIndexOrThrow("via_airmesh")) == 1,
                    hopCount = c.getInt(c.getColumnIndexOrThrow("hop_count")),
                    priority = c.getString(c.getColumnIndexOrThrow("priority"))
                )
                if (q.isEmpty() ||
                    item.subject.lowercase().contains(q) ||
                    item.body.lowercase().contains(q) ||
                    item.fromName.lowercase().contains(q) ||
                    item.fromAddress.lowercase().contains(q)
                ) {
                    out.add(item)
                }
            }
        }
        return out
    }

    fun markMailRead(id: Long) {
        writableDatabase.update("mails", ContentValues().apply { put("is_read", 1) }, "id = ?", arrayOf(id.toString()))
    }

    fun toggleMailStar(id: Long, starred: Boolean) {
        writableDatabase.update(
            "mails",
            ContentValues().apply { put("is_starred", if (starred) 1 else 0) },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun promoteMailOutboxToSent(packetId: String) {
        writableDatabase.update(
            "mails",
            ContentValues().apply { put("folder", "SENT") },
            "packet_id = ? AND folder = 'OUTBOX_MESH'",
            arrayOf(packetId)
        )
    }

    fun deleteMail(id: Long) {
        writableDatabase.delete("mails", "id = ?", arrayOf(id.toString()))
    }

    fun getUnreadMailCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM mails WHERE folder = 'INBOX' AND is_read = 0", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // --- Music Favorites ---
    fun isTrackFavorite(trackKey: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM music_favorites WHERE track_key = ?", arrayOf(trackKey)).use { c ->
            return c.moveToFirst()
        }
    }

    fun toggleTrackFavorite(trackKey: String): Boolean {
        return if (isTrackFavorite(trackKey)) {
            writableDatabase.delete("music_favorites", "track_key = ?", arrayOf(trackKey))
            false
        } else {
            writableDatabase.insert("music_favorites", null, ContentValues().apply {
                put("track_key", trackKey)
                put("added_at", System.currentTimeMillis())
            })
            true
        }
    }

    // --- Hanzi Progress ---
    fun getHanziMasteryMap(): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        readableDatabase.rawQuery("SELECT card_id, mastery_level FROM hanzi_progress", null).use { c ->
            while (c.moveToNext()) map[c.getInt(0)] = c.getInt(1)
        }
        return map
    }

    fun updateHanziMastery(cardId: Int, delta: Int) {
        val current = getHanziMasteryMap()[cardId] ?: 0
        val next = (current + delta).coerceIn(0, 5)
        writableDatabase.insertWithOnConflict("hanzi_progress", null, ContentValues().apply {
            put("card_id", cardId)
            put("mastery_level", next)
            put("last_reviewed", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // --- Real Multi-Chain Crypto Wallet ---
    private fun iconForAsset(assetKey: String): Int = when (assetKey) {
        "BTC" -> R.drawable.ic_btc
        "ETH" -> R.drawable.ic_eth
        "TRX" -> R.drawable.ic_trx
        else -> R.drawable.ic_usdt
    }

    fun getWalletBalances(): List<WalletBalance> {
        val me = getProfile()
        val out = mutableListOf<WalletBalance>()
        readableDatabase.rawQuery(
            """
            SELECT * FROM wallet_balances
            ORDER CASE asset_key
                WHEN 'USDT_TRC20' THEN 1
                WHEN 'USDT_ERC20' THEN 2
                WHEN 'BTC' THEN 3
                WHEN 'ETH' THEN 4
                WHEN 'TRX' THEN 5
                ELSE 6
            END
            """.trimIndent().replace("ORDER CASE", "ORDER BY CASE"),
            null
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(c.getColumnIndexOrThrow("asset_key"))
                val addr = when (key) {
                    "BTC" -> me.btcAddress
                    "TRX", "USDT_TRC20" -> me.trxAddress
                    else -> me.walletAddress
                }
                out.add(
                    WalletBalance(
                        assetKey = key,
                        symbol = c.getString(c.getColumnIndexOrThrow("symbol")),
                        networkLabel = c.getString(c.getColumnIndexOrThrow("network_label")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        address = addr,
                        balance = c.getDouble(c.getColumnIndexOrThrow("balance")),
                        rateUsd = c.getDouble(c.getColumnIndexOrThrow("rate_usd")),
                        rateRub = c.getDouble(c.getColumnIndexOrThrow("rate_rub")),
                        accentColor = c.getInt(c.getColumnIndexOrThrow("accent_color")),
                        iconRes = iconForAsset(key),
                        decimalsDisplay = c.getInt(c.getColumnIndexOrThrow("decimals_display"))
                    )
                )
            }
        }
        return out
    }

    fun updateChainBalance(assetKey: String, balance: Double) {
        writableDatabase.update(
            "wallet_balances",
            ContentValues().apply { put("balance", balance.coerceAtLeast(0.0)) },
            "asset_key = ?",
            arrayOf(assetKey)
        )
    }

    fun updateMarketRates(ratesUsdBySymbol: Map<String, Double>, usdToRub: Double) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((sym, usd) in ratesUsdBySymbol) {
                if (usd <= 0.0) continue
                val rub = if (usdToRub > 0.0) usd * usdToRub else 0.0
                db.update(
                    "wallet_balances",
                    ContentValues().apply {
                        put("rate_usd", usd)
                        if (rub > 0.0) put("rate_rub", rub)
                    },
                    "symbol = ?",
                    arrayOf(sym)
                )
            }
            putKv("wallet_rates_ts", System.currentTimeMillis().toString())
            if (usdToRub > 0.0) putKv("usd_rub", usdToRub.toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertWalletTransaction(
        txHash: String,
        symbol: String,
        network: String,
        amount: Double,
        counterpartyName: String,
        counterpartyAddress: String,
        isIncoming: Boolean,
        memo: String,
        status: String,
        viaAirMesh: Boolean,
        timestampMs: Long
    ) {
        if (txHash.isBlank() || amount <= 0.0) return
        writableDatabase.insertWithOnConflict("wallet_txs", null, ContentValues().apply {
            put("tx_hash", txHash)
            put("symbol", symbol)
            put("network", network)
            put("amount", amount)
            put("counterparty_name", counterpartyName)
            put("counterparty_address", counterpartyAddress)
            put("is_incoming", if (isIncoming) 1 else 0)
            put("memo", memo)
            put("status", status)
            put("via_airmesh", if (viaAirMesh) 1 else 0)
            put("timestamp_ms", timestampMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getWalletTransactions(): List<WalletTx> {
        val out = mutableListOf<WalletTx>()
        readableDatabase.rawQuery("SELECT * FROM wallet_txs ORDER BY timestamp_ms DESC LIMIT 60", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    WalletTx(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        txHash = c.getString(c.getColumnIndexOrThrow("tx_hash")),
                        symbol = c.getString(c.getColumnIndexOrThrow("symbol")),
                        network = c.getString(c.getColumnIndexOrThrow("network")),
                        amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
                        counterpartyName = c.getString(c.getColumnIndexOrThrow("counterparty_name")),
                        counterpartyAddress = c.getString(c.getColumnIndexOrThrow("counterparty_address")),
                        isIncoming = c.getInt(c.getColumnIndexOrThrow("is_incoming")) == 1,
                        memo = c.getString(c.getColumnIndexOrThrow("memo")),
                        status = c.getString(c.getColumnIndexOrThrow("status")),
                        viaAirMesh = c.getInt(c.getColumnIndexOrThrow("via_airmesh")) == 1,
                        timestampMs = c.getLong(c.getColumnIndexOrThrow("timestamp_ms"))
                    )
                )
            }
        }
        return out
    }

    fun putKv(key: String, value: String) {
        writableDatabase.insertWithOnConflict("kv_store", null, ContentValues().apply {
            put("k", key)
            put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getKv(key: String, default: String = ""): String {
        readableDatabase.rawQuery("SELECT v FROM kv_store WHERE k = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else default
        }
    }
}
