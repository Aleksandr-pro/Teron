package app.teron.superapp.core

data class UserProfile(
    val nodeId: String,
    val displayName: String,
    val handle: String,
    val bio: String,
    val avatarColor: Int,
    val avatarGlyph: String,
    val publicKeyB64: String,
    val privateKeyB64: String,
    val seedPhrase: String,
    val walletAddress: String,
    val maxRelayHops: Int = 4,
    val darkTheme: Boolean = true,
    val backgroundDaemon: Boolean = false
)

data class MeshPeer(
    val nodeId: String,
    val displayName: String,
    val handle: String,
    val publicKeyB64: String,
    val transport: String,
    val rssi: Int,
    val distanceMeters: Double,
    val hops: Int,
    val ipAddress: String?,
    val bleAddress: String?,
    val lastSeenMs: Long,
    val isTrustedContact: Boolean = false
)

data class ChatConversationSummary(
    val conversationId: String,
    val title: String,
    val subtitle: String,
    val lastMessage: String,
    val lastTimestampMs: Long,
    val unreadCount: Int,
    val accentColor: Int,
    val glyph: String,
    val isChannel: Boolean,
    val isOnlineNearby: Boolean,
    val hops: Int
)

data class ChatMessage(
    val id: Long,
    val packetId: String,
    val conversationId: String,
    val senderNodeId: String,
    val senderName: String,
    val recipientNodeId: String,
    val body: String,
    val cipherPreview: String,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val transport: String,
    val hopCount: Int,
    val viaPath: String,
    val deliveryState: String
)

data class MeshRelayPacket(
    val packetId: String,
    val kind: String, // BROADCAST, CHAT, SOS, MAIL, CRYPTO_TX, ACK
    val senderNodeId: String,
    val senderName: String,
    val senderHandle: String,
    val senderPubKey: String,
    val targetNodeId: String, // "*" for broadcast or specific nodeId
    val ttl: Int,
    val hopCount: Int,
    val viaNodes: String,
    val cipherTextB64: String,
    val ivB64: String,
    val signatureB64: String,
    val metaSubject: String = "",
    val createdAtMs: Long
)

data class MailItem(
    val id: Long,
    val packetId: String,
    val folder: String, // INBOX, SENT, DRAFTS, OUTBOX_MESH
    val fromName: String,
    val fromAddress: String,
    val toAddress: String,
    val subject: String,
    val body: String,
    val timestampMs: Long,
    val isRead: Boolean,
    val isStarred: Boolean,
    val viaAirMesh: Boolean,
    val hopCount: Int,
    val priority: String // NORMAL, HIGH, SOS
)

data class MusicTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val uriOrPath: String,
    val isBuiltInSynth: Boolean,
    val synthPresetId: Int,
    val accentColor: Int,
    val genre: String,
    val isFavorite: Boolean
)

data class HanziCard(
    val id: Int,
    val hanzi: String,
    val pinyin: String,
    val meaningRu: String,
    val associationRu: String,
    val radicalHint: String,
    val category: String,
    val hskLevel: Int,
    val exampleZh: String,
    val exampleRu: String,
    val masteryLevel: Int = 0
)

data class WalletBalance(
    val symbol: String,
    val name: String,
    val balance: Double,
    val rateRub: Double,
    val accentColor: Int,
    val glyph: String
)

data class WalletTx(
    val id: Long,
    val txHash: String,
    val symbol: String,
    val amount: Double,
    val counterpartyName: String,
    val counterpartyAddress: String,
    val isIncoming: Boolean,
    val memo: String,
    val signatureHex: String,
    val viaAirMesh: Boolean,
    val timestampMs: Long
)
