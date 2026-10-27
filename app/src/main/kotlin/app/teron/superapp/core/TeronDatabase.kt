package app.teron.superapp.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

class TeronDatabase(context: Context) : SQLiteOpenHelper(context, "teron_superapp.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE profile (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                node_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                handle TEXT NOT NULL,
                bio TEXT NOT NULL,
                avatar_color INTEGER NOT NULL,
                avatar_glyph TEXT NOT NULL,
                public_key TEXT NOT NULL,
                private_key TEXT NOT NULL,
                seed_phrase TEXT NOT NULL,
                wallet_address TEXT NOT NULL,
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
                public_key TEXT NOT NULL,
                avatar_color INTEGER NOT NULL,
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

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

        db.execSQL(
            """
            CREATE TABLE wallet_balances (
                symbol TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                balance REAL NOT NULL,
                rate_rub REAL NOT NULL,
                accent_color INTEGER NOT NULL,
                glyph TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE wallet_txs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tx_hash TEXT UNIQUE NOT NULL,
                symbol TEXT NOT NULL,
                amount REAL NOT NULL,
                counterparty_name TEXT NOT NULL,
                counterparty_address TEXT NOT NULL,
                is_incoming INTEGER NOT NULL,
                memo TEXT NOT NULL,
                signature_hex TEXT NOT NULL,
                via_airmesh INTEGER NOT NULL,
                timestamp_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE kv_store (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            )
            """.trimIndent()
        )

        seedInitialData(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations preserve user data across APK updates
    }

    private fun seedInitialData(db: SQLiteDatabase) {
        val id = TeronCrypto.generateIdentity()
        val shortTag = id.nodeId.substringAfter("TRN-").lowercase()
        val now = System.currentTimeMillis()

        val profileCv = ContentValues().apply {
            put("id", 1)
            put("node_id", id.nodeId)
            put("display_name", "Пилот $shortTag")
            put("handle", "user_$shortTag@teron.mesh")
            put("bio", "На связи через Teron AirMesh • Автономная сеть")
            put("avatar_color", Theme.accentIndigo)
            put("avatar_glyph", "⚡")
            put("public_key", id.publicKeyB64)
            put("private_key", id.privateKeyB64)
            put("seed_phrase", id.seedPhrase)
            put("wallet_address", id.walletAddress)
            put("max_relay_hops", 4)
            put("dark_theme", 1)
            put("bg_daemon", 0)
            put("onboarded", 0)
        }
        db.insert("profile", null, profileCv)

        // Seed starter messages in #airmesh & saved notes
        val chKey = TeronCrypto.deriveChannelKey("#airmesh")
        val encWelcome = TeronCrypto.encryptAesGcm(
            "Эфир AirMesh активен. Все устройства Teron поблизости (по Bluetooth LE и локальному Wi-Fi / Хот-споту) автоматически образуют ячеистую mesh-сеть с эстафетной передачей до 4 переходов.",
            chKey
        )
        db.insert("messages", null, ContentValues().apply {
            put("packet_id", "welcome-airmesh-1")
            put("conversation_id", "#airmesh")
            put("sender_node_id", "TRN-SYSTEM")
            put("sender_name", "Ядро AirMesh")
            put("recipient_node_id", "*")
            put("body", "Эфир AirMesh активен. Устройства Teron поблизости автоматически находят друг друга по Bluetooth LE и Wi-Fi без интернета и передают зашифрованные сообщения по цепочке (Store & Forward).")
            put("cipher_preview", encWelcome.cipherTextB64.take(36) + "…")
            put("timestamp_ms", now - 120_000L)
            put("is_outgoing", 0)
            put("transport", "AirMesh Core")
            put("hop_count", 0)
            put("via_path", "Локальный узел")
            put("delivery_state", "AES-256-GCM")
            put("is_read", 1)
        })

        db.insert("messages", null, ContentValues().apply {
            put("packet_id", "welcome-notes-1")
            put("conversation_id", "notes")
            put("sender_node_id", id.nodeId)
            put("sender_name", "Избранное")
            put("recipient_node_id", id.nodeId)
            put("body", "Личное офлайн-хранилище Teron. Заметки, координаты маршрута и ключи шифруются и хранятся исключительно в памяти вашего телефона.")
            put("cipher_preview", "LOCAL-AES256-GCM")
            put("timestamp_ms", now - 300_000L)
            put("is_outgoing", 1)
            put("transport", "Локально")
            put("hop_count", 0)
            put("via_path", id.nodeId)
            put("delivery_state", "Сохранено на устройстве")
            put("is_read", 1)
        })

        // Seed Teron Mail welcome letters
        db.insert("mails", null, ContentValues().apply {
            put("packet_id", "mail-welcome-1")
            put("folder", "INBOX")
            put("from_name", "Команда Teron")
            put("from_address", "core@teron.mesh")
            put("to_address", "user_$shortTag@teron.mesh")
            put("subject", "Добро пожаловать в Teron — автономную экосистему")
            put(
                "body",
                "Приветствуем в Teron!\n\n" +
                    "Ваш персональный почтовый адрес в mesh-сети: user_$shortTag@teron.mesh\n" +
                    "Ваш узел AirMesh: ${id.nodeId}\n\n" +
                    "Что умеет Teron Mail:\n" +
                    "1. Отправка писем напрямую между смартфонами без интернета по цепочке AirMesh (BLE + Wi-Fi P2P).\n" +
                    "2. Режим Store & Forward: если адресат далеко, письмо сохраняется в очереди и автоматически уходит при встрече с любым промежуточным узлом Teron.\n" +
                    "3. Подключение внешнего почтового ящика по защищённому SSL SMTP/IMAP, когда появляется интернет.\n\n" +
                    "Обменяйтесь лучевыми SunCode-кодами с друзьями, чтобы мгновенно добавить их ключи шифрования!"
            )
            put("timestamp_ms", now - 60_000L)
            put("is_read", 0)
            put("is_starred", 1)
            put("via_airmesh", 1)
            put("hop_count", 1)
            put("priority", "HIGH")
        })

        db.insert("mails", null, ContentValues().apply {
            put("packet_id", "mail-welcome-2")
            put("folder", "INBOX")
            put("from_name", "Протокол безопасности AirMesh")
            put("from_address", "security@teron.mesh")
            put("to_address", "user_$shortTag@teron.mesh")
            put("subject", "Как проверить связь без интернета на двух смартфонах")
            put(
                "body",
                "Инструкция для полевого теста:\n\n" +
                    "• Установите Teron APK на два или более Android-смартфона.\n" +
                    "• Разрешите доступ к Bluetooth / Устройствам рядом (или подключите телефоны к одной точке доступа Wi-Fi даже без SIM-карты и интернета).\n" +
                    "• Откройте вкладку «Чаты → Радар AirMesh»: смартфоны автоматически обнаружат друг друга, покажут уровень сигнала RSSI (дБм) и дистанцию в метрах.\n" +
                    "• Отправьте личное сообщение, письмо или крипто-перевод TeronPay — пакет пройдёт с шифрованием ECDH + AES-256-GCM и цифровой подписью ECDSA!"
            )
            put("timestamp_ms", now - 180_000L)
            put("is_read", 0)
            put("is_starred", 0)
            put("via_airmesh", 1)
            put("hop_count", 0)
            put("priority", "NORMAL")
        })

        // Seed TeronPay Wallet assets
        val assets = listOf(
            WalletBalance("TRN", "Teron Mesh Token", 250.0, 42.5, Theme.accentCyan, "⚡"),
            WalletBalance("TON", "Toncoin", 18.4, 495.0, Theme.accentBlue, "💎"),
            WalletBalance("USDT", "Tether USD", 125.0, 96.2, Theme.accentEmerald, "₮"),
            WalletBalance("BTC", "Bitcoin", 0.0042, 8_950_000.0, Theme.accentAmber, "₿")
        )
        for (a in assets) {
            db.insert("wallet_balances", null, ContentValues().apply {
                put("symbol", a.symbol)
                put("name", a.name)
                put("balance", a.balance)
                put("rate_rub", a.rateRub)
                put("accent_color", a.accentColor)
                put("glyph", a.glyph)
            })
        }

        val genesisSig = TeronCrypto.signData(id.privateKeyB64, "GENESIS:${id.walletAddress}:250.0:TRN")
        db.insert("wallet_txs", null, ContentValues().apply {
            put("tx_hash", "0x" + TeronCrypto.shortSha256Hex("genesis-${id.nodeId}", 12))
            put("symbol", "TRN")
            put("amount", 250.0)
            put("counterparty_name", "Teron Genesis Pool")
            put("counterparty_address", "trn10000000000000000genesis")
            put("is_incoming", 1)
            put("memo", "Стартовый грант узла AirMesh")
            put("signature_hex", genesisSig.take(32))
            put("via_airmesh", 1)
            put("timestamp_ms", now - 240_000L)
        })
    }

    fun getProfile(): UserProfile {
        val db = readableDatabase
        db.rawQuery("SELECT * FROM profile WHERE id = 1", null).use { c ->
            if (c.moveToFirst()) {
                return UserProfile(
                    nodeId = c.getString(c.getColumnIndexOrThrow("node_id")),
                    displayName = c.getString(c.getColumnIndexOrThrow("display_name")),
                    handle = c.getString(c.getColumnIndexOrThrow("handle")),
                    bio = c.getString(c.getColumnIndexOrThrow("bio")),
                    avatarColor = c.getInt(c.getColumnIndexOrThrow("avatar_color")),
                    avatarGlyph = c.getString(c.getColumnIndexOrThrow("avatar_glyph")),
                    publicKeyB64 = c.getString(c.getColumnIndexOrThrow("public_key")),
                    privateKeyB64 = c.getString(c.getColumnIndexOrThrow("private_key")),
                    seedPhrase = c.getString(c.getColumnIndexOrThrow("seed_phrase")),
                    walletAddress = c.getString(c.getColumnIndexOrThrow("wallet_address")),
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

    fun markOnboarded() {
        writableDatabase.update("profile", ContentValues().apply { put("onboarded", 1) }, "id = 1", null)
    }

    fun updateProfile(
        displayName: String,
        handle: String,
        bio: String,
        avatarColor: Int,
        avatarGlyph: String,
        maxRelayHops: Int,
        darkTheme: Boolean,
        backgroundDaemon: Boolean
    ) {
        writableDatabase.update("profile", ContentValues().apply {
            put("display_name", displayName.trim().ifBlank { "Пилот Teron" })
            put("handle", handle.trim().ifBlank { "pilot@teron.mesh" })
            put("bio", bio.trim())
            put("avatar_color", avatarColor)
            put("avatar_glyph", avatarGlyph)
            put("max_relay_hops", maxRelayHops.coerceIn(1, 6))
            put("dark_theme", if (darkTheme) 1 else 0)
            put("bg_daemon", if (backgroundDaemon) 1 else 0)
            put("onboarded", 1)
        }, "id = 1", null)
    }

    fun upsertContact(nodeId: String, displayName: String, handle: String, publicKeyB64: String, avatarColor: Int = Theme.accentCyan) {
        if (nodeId.isBlank() || nodeId == getProfile().nodeId) return
        writableDatabase.insertWithOnConflict("contacts", null, ContentValues().apply {
            put("node_id", nodeId)
            put("display_name", displayName.ifBlank { nodeId })
            put("handle", handle.ifBlank { "${nodeId.lowercase()}@teron.mesh" })
            put("public_key", publicKeyB64)
            put("avatar_color", avatarColor)
            put("added_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getContacts(): List<MeshPeer> {
        val list = mutableListOf<MeshPeer>()
        readableDatabase.rawQuery("SELECT * FROM contacts ORDER BY display_name ASC", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    MeshPeer(
                        nodeId = c.getString(c.getColumnIndexOrThrow("node_id")),
                        displayName = c.getString(c.getColumnIndexOrThrow("display_name")),
                        handle = c.getString(c.getColumnIndexOrThrow("handle")),
                        publicKeyB64 = c.getString(c.getColumnIndexOrThrow("public_key")),
                        transport = "Контакт SunCode",
                        rssi = -65,
                        distanceMeters = 0.0,
                        hops = 1,
                        ipAddress = null,
                        bleAddress = null,
                        lastSeenMs = c.getLong(c.getColumnIndexOrThrow("added_at")),
                        isTrustedContact = true
                    )
                )
            }
        }
        return list
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

    fun getConversations(livePeers: Map<String, MeshPeer>): List<ChatConversationSummary> {
        val result = mutableListOf<ChatConversationSummary>()
        val contactsMap = getContacts().associateBy { it.nodeId }

        fun lastMsgFor(convId: String): Pair<String, Long> {
            readableDatabase.rawQuery(
                "SELECT body, timestamp_ms FROM messages WHERE conversation_id = ? ORDER BY timestamp_ms DESC LIMIT 1",
                arrayOf(convId)
            ).use { c ->
                if (c.moveToFirst()) return c.getString(0) to c.getLong(1)
            }
            return "Нет сообщений" to 0L
        }

        fun unreadFor(convId: String): Int {
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND is_read = 0",
                arrayOf(convId)
            ).use { c ->
                return if (c.moveToFirst()) c.getInt(0) else 0
            }
        }

        val (airLast, airTs) = lastMsgFor("#airmesh")
        result.add(
            ChatConversationSummary(
                conversationId = "#airmesh",
                title = "Эфир Поблизости (#airmesh)",
                subtitle = "Открытый радиоканал • ${livePeers.size} узлов рядом",
                lastMessage = airLast,
                lastTimestampMs = airTs,
                unreadCount = unreadFor("#airmesh"),
                accentColor = Theme.accentCyan,
                glyph = "📡",
                isChannel = true,
                isOnlineNearby = livePeers.isNotEmpty(),
                hops = 1
            )
        )

        val (sosLast, sosTs) = lastMsgFor("#sos")
        result.add(
            ChatConversationSummary(
                conversationId = "#sos",
                title = "Экстренный маяк (#sos)",
                subtitle = "Приоритетная ретрансляция до 6 переходов",
                lastMessage = if (sosTs == 0L) "Канал экстренных оповещений без связи" else sosLast,
                lastTimestampMs = sosTs,
                unreadCount = unreadFor("#sos"),
                accentColor = Theme.accentCoral,
                glyph = "🆘",
                isChannel = true,
                isOnlineNearby = true,
                hops = 0
            )
        )

        val (notesLast, notesTs) = lastMsgFor("notes")
        result.add(
            ChatConversationSummary(
                conversationId = "notes",
                title = "Избранное (Локальный сейф)",
                subtitle = "Зашифрованные офлайн-заметки",
                lastMessage = notesLast,
                lastTimestampMs = notesTs,
                unreadCount = 0,
                accentColor = Theme.accentAmber,
                glyph = "🔐",
                isChannel = false,
                isOnlineNearby = true,
                hops = 0
            )
        )

        // Collect all peer conversation IDs from contacts, livePeers, and message history
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
                contact != null -> "${contact.handle} • Store & Forward"
                else -> "$pid • Офлайн-очередь AirMesh"
            }
            result.add(
                ChatConversationSummary(
                    conversationId = pid,
                    title = title,
                    subtitle = sub,
                    lastMessage = lastBody,
                    lastTimestampMs = if (lastTs > 0L) lastTs else (live?.lastSeenMs ?: contact?.lastSeenMs ?: 0L),
                    unreadCount = unreadFor(pid),
                    accentColor = Theme.avatarPalette[(pid.hashCode() and 0x7FFFFFFF) % Theme.avatarPalette.size],
                    glyph = title.trim().take(1).uppercase().ifBlank { "У" },
                    isChannel = false,
                    isOnlineNearby = live != null,
                    hops = live?.hops ?: 1
                )
            )
        }
        return result
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

    // --- Teron Mail ---
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

    // --- Hanzi Dojo (Associative Chinese Learning) ---
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

    // --- TeronPay Crypto Wallet ---
    fun getWalletBalances(): List<WalletBalance> {
        val out = mutableListOf<WalletBalance>()
        readableDatabase.rawQuery("SELECT * FROM wallet_balances", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    WalletBalance(
                        symbol = c.getString(c.getColumnIndexOrThrow("symbol")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        balance = c.getDouble(c.getColumnIndexOrThrow("balance")),
                        rateRub = c.getDouble(c.getColumnIndexOrThrow("rate_rub")),
                        accentColor = c.getInt(c.getColumnIndexOrThrow("accent_color")),
                        glyph = c.getString(c.getColumnIndexOrThrow("glyph"))
                    )
                )
            }
        }
        return out
    }

    fun getWalletTransactions(): List<WalletTx> {
        val out = mutableListOf<WalletTx>()
        readableDatabase.rawQuery("SELECT * FROM wallet_txs ORDER BY timestamp_ms DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    WalletTx(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        txHash = c.getString(c.getColumnIndexOrThrow("tx_hash")),
                        symbol = c.getString(c.getColumnIndexOrThrow("symbol")),
                        amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
                        counterpartyName = c.getString(c.getColumnIndexOrThrow("counterparty_name")),
                        counterpartyAddress = c.getString(c.getColumnIndexOrThrow("counterparty_address")),
                        isIncoming = c.getInt(c.getColumnIndexOrThrow("is_incoming")) == 1,
                        memo = c.getString(c.getColumnIndexOrThrow("memo")),
                        signatureHex = c.getString(c.getColumnIndexOrThrow("signature_hex")),
                        viaAirMesh = c.getInt(c.getColumnIndexOrThrow("via_airmesh")) == 1,
                        timestampMs = c.getLong(c.getColumnIndexOrThrow("timestamp_ms"))
                    )
                )
            }
        }
        return out
    }

    fun recordWalletTransfer(
        txHash: String,
        symbol: String,
        amount: Double,
        counterpartyName: String,
        counterpartyAddress: String,
        isIncoming: Boolean,
        memo: String,
        signatureHex: String,
        viaAirMesh: Boolean
    ): Boolean {
        if (amount <= 0.0) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT 1 FROM wallet_txs WHERE tx_hash = ?", arrayOf(txHash)).use { it.moveToFirst() }
            if (existing) {
                db.endTransaction()
                return false
            }
            val curBal = db.rawQuery("SELECT balance FROM wallet_balances WHERE symbol = ?", arrayOf(symbol)).use {
                if (it.moveToFirst()) it.getDouble(0) else 0.0
            }
            if (!isIncoming && curBal < amount) {
                db.endTransaction()
                return false
            }
            val newBal = if (isIncoming) curBal + amount else curBal - amount
            db.update("wallet_balances", ContentValues().apply { put("balance", newBal) }, "symbol = ?", arrayOf(symbol))
            db.insert("wallet_txs", null, ContentValues().apply {
                put("tx_hash", txHash)
                put("symbol", symbol)
                put("amount", amount)
                put("counterparty_name", counterpartyName)
                put("counterparty_address", counterpartyAddress)
                put("is_incoming", if (isIncoming) 1 else 0)
                put("memo", memo)
                put("signature_hex", signatureHex)
                put("via_airmesh", if (viaAirMesh) 1 else 0)
                put("timestamp_ms", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
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
