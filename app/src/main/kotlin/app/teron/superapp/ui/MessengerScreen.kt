package app.teron.superapp.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.R
import app.teron.superapp.TeronApp
import app.teron.superapp.core.ChatMessage
import app.teron.superapp.core.DirectoryUser
import app.teron.superapp.core.PhoneBookEntry
import app.teron.superapp.core.TeronCrypto
import app.teron.superapp.core.Theme
import app.teron.superapp.suncode.SunCodeCodec
import java.util.Locale

class MessengerScreen(
    private val ctx: Context,
    private val app: TeronApp,
    private val onRequestBlePermissions: () -> Unit,
    private val onRequestContactsPermissionAndSync: () -> Unit,
    private val onOpenSunCode: (scanTab: Boolean) -> Unit
) {
    companion object {
        const val DIRECT_APK_DOWNLOAD_URL =
            "https://github.com/Aleksandr-pro/Teron/raw/arena/ee038e90-teron/releases/Teron-v2.1.0.apk"
    }

    var activeConversationId: String? = null
    var activeSubTab: Int = 0 // 0 = Dialogs, 1 = AirMesh Radar, 2 = Contacts & People

    private var rootContainer: LinearLayout? = null

    // Live search state (preserved across dynamic refreshes so keyboard never drops)
    private var dialogsFilterQuery: String = ""
    private var contactsSearchQuery: String = ""
    private var directoryResults: List<DirectoryUser> = emptyList()
    private var isSearchingDirectory: Boolean = false
    private var directoryErrorText: String? = null
    private var lastDirectoryFetchMs: Long = 0L
    private var showAllPhoneBookInvitees: Boolean = false

    private var contactsSearchInput: EditText? = null
    private var contactsResultsContainer: LinearLayout? = null
    private var dialogsResultsContainer: LinearLayout? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null

    fun openSubArg(arg: String?) {
        when (arg) {
            "radar" -> { activeConversationId = null; activeSubTab = 1 }
            "contacts" -> { activeConversationId = null; activeSubTab = 2 }
            "#sos" -> { activeConversationId = "#sos" }
            "#airmesh" -> { activeConversationId = "#airmesh" }
            null -> {}
            else -> { activeConversationId = arg }
        }
    }

    fun handleBack(): Boolean {
        if (activeConversationId != null) {
            activeConversationId = null
            render()
            return true
        }
        return false
    }

    fun build(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        rootContainer = root
        render()
        return root
    }

    /**
     * Updates live lists without stealing EditText focus or closing soft keyboard when user is searching.
     */
    fun refreshDynamicData() {
        if (activeConversationId == null && activeSubTab == 2 && contactsResultsContainer != null && contactsSearchInput?.hasFocus() == true) {
            populateContactsResults(contactsResultsContainer!!)
            return
        }
        if (activeConversationId == null && activeSubTab == 0 && dialogsResultsContainer != null) {
            populateDialogsList(dialogsResultsContainer!!)
            return
        }
        render()
    }

    fun render() {
        val root = rootContainer ?: return
        contactsSearchInput = null
        contactsResultsContainer = null
        dialogsResultsContainer = null
        root.removeAllViews()
        val convId = activeConversationId
        if (convId != null) {
            root.addView(buildConversationDetail(convId))
        } else {
            root.addView(buildMainTabsView())
        }
    }

    private fun buildMainTabsView(): View {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Theme.dp(ctx, 16)
            setPadding(p, Theme.dp(ctx, 14), p, Theme.dp(ctx, 8))
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = "Сообщения и люди"
            setTextColor(Theme.textPrimary)
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titleCol.addView(TextView(ctx).apply {
            val netTxt = if (app.cloudSync.isOnlineConnected) "Сеть Teron E2EE онлайн + AirMesh" else "Автономный эфир AirMesh"
            text = netTxt
            setTextColor(if (app.cloudSync.isOnlineConnected) Theme.accentEmerald else Theme.textMuted)
            textSize = 11.5f
        })
        top.addView(titleCol)
        top.addView(Components.secondaryButton(ctx, "Найти / +", R.drawable.ic_user_plus) {
            showAddContactSheet()
        })
        wrapper.addView(top)

        // Sub-tab filter bar
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 10))
        }
        val liveCount = app.meshEngine.livePeers.size
        val contactsCount = app.db.getContacts().size
        chipRow.addView(Components.actionChip(ctx, "Диалоги", activeSubTab == 0, R.drawable.ic_chat) {
            activeSubTab = 0; render()
        })
        chipRow.addView(Components.actionChip(ctx, "Радар ($liveCount)", activeSubTab == 1, R.drawable.ic_radar, Theme.accentEmerald) {
            activeSubTab = 1; render()
        })
        val contactsLabel = if (contactsCount > 0) "Контакты ($contactsCount)" else "Контакты и поиск"
        chipRow.addView(Components.actionChip(ctx, contactsLabel, activeSubTab == 2, R.drawable.ic_users) {
            activeSubTab = 2; render()
        })
        wrapper.addView(chipRow)

        val content = when (activeSubTab) {
            1 -> buildRadarSubView()
            2 -> buildContactsSubView()
            else -> buildChatsListSubView()
        }
        wrapper.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return wrapper
    }

    // =========================================================================
    // TAB 0: DIALOGS + QUICK SEARCH
    // =========================================================================

    private fun buildChatsListSubView(): View {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Search bar across dialogs + people in Teron
        val searchBox = buildSearchBarView(
            initialText = dialogsFilterQuery,
            hint = "Поиск чатов или людей в Teron (@ник, телефон)...",
            onClear = {
                dialogsFilterQuery = ""
                render()
            },
            onQueryChanged = { q ->
                dialogsFilterQuery = q
                dialogsResultsContainer?.let { populateDialogsList(it) }
                if (q.trim().length >= 2) {
                    scheduleDirectorySearch(q) {
                        dialogsResultsContainer?.let { populateDialogsList(it) }
                    }
                }
            }
        )
        outer.addView(searchBox.first)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 20))
        }
        dialogsResultsContainer = col
        populateDialogsList(col)
        scroll.addView(col)
        outer.addView(scroll)
        return outer
    }

    private fun populateDialogsList(col: LinearLayout) {
        col.removeAllViews()
        val q = dialogsFilterQuery.trim()
        val conversations = app.db.getConversations(app.meshEngine.livePeers, q)

        if (conversations.isNotEmpty()) {
            val listCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            }

            conversations.forEachIndexed { idx, conv ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleSurfaceRow(ctx)
                    val px = Theme.dp(ctx, 14)
                    val py = Theme.dp(ctx, 12)
                    setPadding(px, py, px, py)
                    setOnClickListener {
                        activeConversationId = conv.conversationId
                        app.db.markConversationRead(conv.conversationId)
                        render()
                    }
                }

                if (conv.iconRes != 0) {
                    row.addView(Components.tonalIconBadge(ctx, conv.iconRes, conv.accentColor, 44, 20, 12f))
                } else {
                    row.addView(Components.monogramAvatar(ctx, conv.initials, conv.accentColor, 44, 14.5f))
                }

                val mid = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                        rightMargin = Theme.dp(ctx, 8)
                    }
                }
                mid.addView(TextView(ctx).apply {
                    text = conv.title
                    setTextColor(Theme.textPrimary)
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                })
                mid.addView(TextView(ctx).apply {
                    text = conv.subtitle
                    setTextColor(if (conv.isOnlineNearby && !conv.isChannel) Theme.accentEmerald else Theme.textMuted)
                    textSize = 11.5f
                    maxLines = 1
                })
                mid.addView(TextView(ctx).apply {
                    text = conv.lastMessage
                    setTextColor(Theme.textSecondary)
                    textSize = 13f
                    maxLines = 1
                    setPadding(0, Theme.dp(ctx, 2), 0, 0)
                })
                row.addView(mid)

                val right = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                }
                if (conv.lastTimestampMs > 0L) {
                    right.addView(TextView(ctx).apply {
                        text = Theme.formatTime(conv.lastTimestampMs)
                        setTextColor(Theme.textMuted)
                        textSize = 11f
                        fontFeatureSettings = "tnum"
                    })
                }
                if (conv.unreadCount > 0) {
                    right.addView(TextView(ctx).apply {
                        text = conv.unreadCount.toString()
                        setTextColor(Color.WHITE)
                        textSize = 11f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER
                        background = Theme.circleBg(Theme.brand)
                        val s = Theme.dp(ctx, 20)
                        layoutParams = LinearLayout.LayoutParams(s, s).apply { topMargin = Theme.dp(ctx, 6) }
                    })
                }
                row.addView(right)
                listCard.addView(row)
                if (idx < conversations.lastIndex) {
                    listCard.addView(Components.divider(ctx, 70))
                }
            }
            col.addView(listCard)
        }

        // If user typed a search query in Dialogs, also surface matching Global Teron users right here!
        if (q.length >= 2) {
            val myId = app.db.getProfile().nodeId
            val globalHits = directoryResults.filter { it.nodeId != myId }
            col.addView(Components.sectionHeader(ctx, "Глобальный поиск людей в Teron"))
            if (isSearchingDirectory && globalHits.isEmpty()) {
                col.addView(infoHintCard("Поиск «$q» в каталоге Teron..."))
            } else if (globalHits.isNotEmpty()) {
                col.addView(buildDirectoryUsersCard(globalHits))
            } else {
                col.addView(infoHintCard("В глобальном каталоге Teron совпадений по «$q» пока нет. Проверьте вкладку «Контакты» для синхронизации телефонной книги."))
            }
        }
    }

    // =========================================================================
    // TAB 1: AIRMESH RADAR (BLE + WI-FI MULTICAST + mDNS)
    // =========================================================================

    private fun buildRadarSubView(): View {
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 24))
        }

        if (!app.meshEngine.hasBlePermissions()) {
            val permBanner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.accentAmber, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 14)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 12)
                }
            }
            permBanner.addView(TextView(ctx).apply {
                text = "Требуется доступ к Bluetooth LE («Устройства рядом»)"
                setTextColor(Theme.textPrimary)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            permBanner.addView(TextView(ctx).apply {
                text = "Разрешение необходимо для прямого поиска смартфонов Teron в радиусе 200–800 метров без интернета и сотовой сети."
                setTextColor(Theme.textSecondary)
                textSize = 12.5f
                setPadding(0, Theme.dp(ctx, 4), 0, Theme.dp(ctx, 10))
            })
            permBanner.addView(Components.primaryButton(ctx, "Предоставить разрешение", R.drawable.ic_bluetooth) {
                onRequestBlePermissions()
            })
            col.addView(permBanner)
        }

        val me = app.db.getProfile()
        val peersList = app.meshEngine.livePeers.values.sortedBy { it.distanceMeters }

        val radarBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 18).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 16)
            setPadding(p, p, p, p)
        }
        radarBox.addView(MeshRadarView(ctx).apply {
            selfLabel = me.nodeId
            peers = peersList
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 226))
        })
        radarBox.addView(TextView(ctx).apply {
            text = "Локальный IP: ${app.meshEngine.getLocalIpv4()} • BLE + Wi-Fi + mDNS (_teron._tcp) • Очередь: ${app.db.countPendingRelayPackets()}"
            setTextColor(Theme.textSecondary)
            textSize = 11.5f
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(ctx, 8), 0, Theme.dp(ctx, 10))
        })
        radarBox.addView(Components.secondaryButton(ctx, "Обновить радиоэфир сейчас", R.drawable.ic_refresh) {
            app.meshEngine.triggerDiscoverySweep()
            Toast.makeText(ctx, "Запущено активное сканирование BLE, Wi-Fi и mDNS", Toast.LENGTH_SHORT).show()
        })
        col.addView(radarBox)

        col.addView(Components.sectionHeader(ctx, "Смартфоны с Teron в прямом эфире (${peersList.size})"))
        if (peersList.isEmpty()) {
            col.addView(Components.emptyStateCard(
                ctx = ctx,
                iconRes = R.drawable.ic_wifi,
                title = "Поблизости пока нет активных радиоузлов",
                subtitle = "Радар сканирует Bluetooth LE и локальный Wi-Fi / точку доступа. Для поиска людей в других городах перейдите во вкладку «Контакты».",
                ctaLabel = "Найти людей в сети Teron",
                onCtaClick = {
                    activeSubTab = 2
                    render()
                }
            ))
        } else {
            val peersCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            }
            peersList.forEachIndexed { i, p ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleSurfaceRow(ctx)
                    val pad = Theme.dp(ctx, 14)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener {
                        app.db.upsertContact(p.nodeId, p.displayName, p.handle, p.publicKeyB64)
                        activeConversationId = p.nodeId
                        render()
                    }
                }
                row.addView(Components.monogramAvatar(ctx, p.displayName, Theme.brand, 42, 14f))
                val info = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                    }
                }
                info.addView(TextView(ctx).apply {
                    text = p.displayName
                    setTextColor(Theme.textPrimary)
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                info.addView(TextView(ctx).apply {
                    text = "${p.transport} • ~${"%.0f".format(p.distanceMeters)} м • ${p.rssi} дБм • ${p.nodeId}"
                    setTextColor(Theme.accentEmerald)
                    textSize = 12f
                })
                row.addView(info)
                row.addView(Components.icon(ctx, R.drawable.ic_chevron_right, 18, Theme.textMuted))
                peersCard.addView(row)
                if (i < peersList.lastIndex) peersCard.addView(Components.divider(ctx, 68))
            }
            col.addView(peersCard)
        }

        val logs = app.meshEngine.telemetryLogs.toList()
        if (logs.isNotEmpty()) {
            col.addView(Components.sectionHeader(ctx, "Журнал радиоподсистемы"))
            val logCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 12)
                setPadding(p, p, p, p)
            }
            for (line in logs.take(8)) {
                logCard.addView(TextView(ctx).apply {
                    text = line
                    setTextColor(Theme.textSecondary)
                    textSize = 11.5f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, Theme.dp(ctx, 2), 0, Theme.dp(ctx, 2))
                })
            }
            col.addView(logCard)
        }

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 2: CONTACTS, PHONEBOOK SYNC & GLOBAL USER SEARCH ("ЛЮДИ С ПРИЛОЖЕНИЕМ")
    // =========================================================================

    private fun buildContactsSubView(): View {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Persistent Search Input (never recreated while typing!)
        val searchPair = buildSearchBarView(
            initialText = contactsSearchQuery,
            hint = "Имя, @ник, +7 телефон или TRN-ID...",
            onClear = {
                contactsSearchQuery = ""
                render()
            },
            onQueryChanged = { q ->
                contactsSearchQuery = q
                contactsResultsContainer?.let { populateContactsResults(it) }
                scheduleDirectorySearch(q) {
                    contactsResultsContainer?.let { populateContactsResults(it) }
                }
            }
        )
        contactsSearchInput = searchPair.second
        outer.addView(searchPair.first)

        // Trigger initial global user listing so "Люди в Teron" is populated immediately on open
        val now = System.currentTimeMillis()
        if (now - lastDirectoryFetchMs > 10_000L && !isSearchingDirectory) {
            scheduleDirectorySearch(contactsSearchQuery, immediate = true) {
                contactsResultsContainer?.let { populateContactsResults(it) }
            }
        }

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val resultsCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 28))
        }
        contactsResultsContainer = resultsCol
        populateContactsResults(resultsCol)

        scroll.addView(resultsCol)
        outer.addView(scroll)
        return outer
    }

    private fun populateContactsResults(col: LinearLayout) {
        col.removeAllViews()
        val me = app.db.getProfile()
        val q = contactsSearchQuery.trim()
        val hasContactsPerm = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val savedContacts = app.db.getContacts(q)
        val savedNodeIds = app.db.getContacts().map { it.nodeId }.toSet()
        val allPhoneEntries = app.db.getAllPhoneBookEntries(q)

        // 1. Top Quick-Action Row: Sync Phonebook + SunCode Scanner
        val topActions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Theme.dp(ctx, 10)
            }
        }
        val syncBtnLabel = when {
            app.cloudSync.isSyncingContacts -> "Синхронизация..."
            !hasContactsPerm -> "Синхр. контакты"
            allPhoneEntries.isEmpty() && q.isEmpty() -> "Синхр. контакты"
            else -> "Обновить контакты"
        }
        topActions.addView(Components.primaryButton(ctx, syncBtnLabel, R.drawable.ic_cloud_sync) {
            onRequestContactsPermissionAndSync()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
        })
        topActions.addView(Components.secondaryButton(ctx, "SunCode / QR", R.drawable.ic_suncode) {
            onOpenSunCode(false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f)
        })
        col.addView(topActions)

        // 2. Subtle Phone Linking Prompt if user hasn't linked their own phone number yet
        if (me.phoneNumber.isBlank() && q.isEmpty()) {
            val linkBanner = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.rippleCard(ctx, Theme.card, 14f, Theme.border, 1f)
                val p = Theme.dp(ctx, 12)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 8)
                }
                setOnClickListener { showLinkOwnPhoneSheet() }
            }
            linkBanner.addView(Components.tonalIconBadge(ctx, R.drawable.ic_phone, Theme.brandText, 36, 18, 10f))
            val lbText = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = Theme.dp(ctx, 10)
                    rightMargin = Theme.dp(ctx, 6)
                }
            }
            lbText.addView(TextView(ctx).apply {
                text = "Привяжите свой номер телефона"
                setTextColor(Theme.textPrimary)
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            lbText.addView(TextView(ctx).apply {
                text = "Чтобы знакомые из вашей телефонной книги находили вас в Teron автоматически (SHA-256)"
                setTextColor(Theme.textSecondary)
                textSize = 11.5f
            })
            linkBanner.addView(lbText)
            linkBanner.addView(Components.icon(ctx, R.drawable.ic_chevron_right, 16, Theme.brandText))
            col.addView(linkBanner)
        }

        // 3. Nearby Live Radio Peers (not yet in saved contacts or currently in range)
        val liveNearby = app.meshEngine.livePeers.values.filter { peer ->
            peer.nodeId != me.nodeId && (
                q.isEmpty() ||
                    peer.displayName.contains(q, ignoreCase = true) ||
                    peer.handle.contains(q, ignoreCase = true) ||
                    peer.nodeId.contains(q, ignoreCase = true)
                )
        }
        if (liveNearby.isNotEmpty()) {
            col.addView(Components.sectionHeader(ctx, "Рядом по радиоэфиру AirMesh (${liveNearby.size})"))
            val nearCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.accentEmerald, Theme.dp(ctx, 1))
            }
            liveNearby.forEachIndexed { idx, p ->
                val isSaved = savedNodeIds.contains(p.nodeId)
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleSurfaceRow(ctx)
                    val pad = Theme.dp(ctx, 13)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener {
                        app.db.upsertContact(p.nodeId, p.displayName, p.handle, p.publicKeyB64)
                        activeConversationId = p.nodeId
                        render()
                    }
                }
                row.addView(Components.monogramAvatar(ctx, p.displayName, Theme.accentEmerald, 42, 14f))
                val info = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                        rightMargin = Theme.dp(ctx, 8)
                    }
                }
                info.addView(TextView(ctx).apply {
                    text = p.displayName
                    setTextColor(Theme.textPrimary)
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                info.addView(TextView(ctx).apply {
                    text = "${p.transport} • ~${"%.0f".format(p.distanceMeters)} м • ${p.handle.ifBlank { p.nodeId }}"
                    setTextColor(Theme.accentEmerald)
                    textSize = 11.5f
                })
                row.addView(info)
                row.addView(Components.pillBadge(ctx, if (isSaved) "Чат" else "+ Добавить", Theme.accentEmerald))
                nearCard.addView(row)
                if (idx < liveNearby.lastIndex) nearCard.addView(Components.divider(ctx, 66))
            }
            col.addView(nearCard)
        }

        // 4. Saved Teron Contacts ("Мои контакты в Teron")
        val myContactsHeader = if (q.isEmpty()) {
            "Мои контакты в Teron (${savedContacts.size})"
        } else {
            "Сохранённые контакты (${savedContacts.size})"
        }
        col.addView(Components.sectionHeader(ctx, myContactsHeader))
        if (savedContacts.isEmpty()) {
            if (q.isEmpty()) {
                col.addView(infoHintCard("Сохранённых контактов пока нет. Нажмите «Синхр. контакты» выше, введите имя/@ник в поиске или отсканируйте SunCode собеседника."))
            } else {
                col.addView(infoHintCard("Среди сохранённых контактов совпадений по «$q» нет."))
            }
        } else {
            val savedCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            }
            savedContacts.forEachIndexed { idx, c ->
                val isOnlineRadio = app.meshEngine.livePeers.containsKey(c.nodeId)
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleSurfaceRow(ctx)
                    val pad = Theme.dp(ctx, 13)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener {
                        activeConversationId = c.nodeId
                        app.db.markConversationRead(c.nodeId)
                        render()
                    }
                    setOnLongClickListener {
                        showContactOptionsSheet(c.nodeId, c.displayName, c.handle)
                        true
                    }
                }
                val cColor = Theme.avatarPalette[(c.nodeId.hashCode() and 0x7FFFFFFF) % Theme.avatarPalette.size]
                row.addView(Components.monogramAvatar(ctx, c.displayName, cColor, 42, 14f))
                val info = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                        rightMargin = Theme.dp(ctx, 8)
                    }
                }
                val nameLine = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                nameLine.addView(TextView(ctx).apply {
                    text = c.displayName
                    setTextColor(Theme.textPrimary)
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                })
                if (c.publicKeyB64.isNotBlank()) {
                    nameLine.addView(Components.icon(ctx, R.drawable.ic_verified, 14, Theme.brandText).apply {
                        layoutParams = LinearLayout.LayoutParams(Theme.dp(ctx, 14), Theme.dp(ctx, 14)).apply {
                            leftMargin = Theme.dp(ctx, 5)
                        }
                    })
                }
                info.addView(nameLine)

                val subLine = when {
                    isOnlineRadio -> "В прямом эфире AirMesh • ${c.handle}"
                    c.publicKeyB64.isNotBlank() -> "${c.handle} • E2EE ключ активен"
                    else -> "${c.handle} • ${c.nodeId}"
                }
                info.addView(TextView(ctx).apply {
                    text = subLine
                    setTextColor(if (isOnlineRadio) Theme.accentEmerald else Theme.textSecondary)
                    textSize = 11.5f
                    maxLines = 1
                })
                row.addView(info)
                row.addView(Components.solidIconButton(ctx, R.drawable.ic_send, Theme.cardAlt, Theme.brandText, 34, 15, 10f) {
                    activeConversationId = c.nodeId
                    app.db.markConversationRead(c.nodeId)
                    render()
                })
                savedCard.addView(row)
                if (idx < savedContacts.lastIndex) savedCard.addView(Components.divider(ctx, 66))
            }
            col.addView(savedCard)
        }

        // 5. Global Teron Directory ("Люди с приложением Teron")
        val directoryFiltered = directoryResults.filter { it.nodeId != me.nodeId }
        val dirHeaderTitle = if (q.isEmpty()) {
            "Люди с приложением Teron (${directoryFiltered.size})"
        } else {
            "Поиск в глобальной сети Teron (${directoryFiltered.size})"
        }
        col.addView(Components.sectionHeader(ctx, dirHeaderTitle))
        when {
            isSearchingDirectory && directoryFiltered.isEmpty() -> {
                col.addView(infoHintCard("Загрузка каталога пользователей Teron..."))
            }
            directoryFiltered.isNotEmpty() -> {
                col.addView(buildDirectoryUsersCard(directoryFiltered))
            }
            directoryErrorText != null -> {
                col.addView(infoHintCard("Глобальный каталог временно недоступен (${directoryErrorText}). Локальный поиск AirMesh и SunCode работают штатно."))
            }
            q.isNotEmpty() -> {
                col.addView(infoHintCard("Пользователь «$q» пока не найден в сети Teron. Вы можете отправить ему приглашение ниже."))
            }
            else -> {
                col.addView(infoHintCard("Вы первый активный участник или другие пользователи ещё не опубликовали профиль. Поделитесь ссылкой на Teron с друзьями!"))
            }
        }

        // 6. Phonebook Synchronization Section ("Телефонная книга смартфона")
        val matchedInPhoneBook = allPhoneEntries.filter { it.matchedNodeId.isNotBlank() && it.matchedNodeId != me.nodeId }
        val unmatchedInPhoneBook = allPhoneEntries.filter { it.matchedNodeId.isBlank() }

        if (matchedInPhoneBook.isNotEmpty()) {
            col.addView(Components.sectionHeader(ctx, "Из вашей телефонной книги — уже в Teron (${matchedInPhoneBook.size})"))
            val pbMatchedCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.accentEmerald, Theme.dp(ctx, 1))
            }
            matchedInPhoneBook.forEachIndexed { idx, entry ->
                pbMatchedCard.addView(buildPhoneBookMatchedRow(entry))
                if (idx < matchedInPhoneBook.lastIndex) pbMatchedCard.addView(Components.divider(ctx, 66))
            }
            col.addView(pbMatchedCard)
        }

        col.addView(Components.sectionHeader(ctx, "Телефонная книга — пригласить (${unmatchedInPhoneBook.size})"))
        if (!hasContactsPerm) {
            col.addView(Components.emptyStateCard(
                ctx = ctx,
                iconRes = R.drawable.ic_phone,
                title = "Найдите друзей из телефонной книги",
                subtitle = "Teron проверит номера по защищённым хешам SHA-256 и автоматически покажет всех ваших знакомых, у которых уже установлено приложение.",
                ctaLabel = "Разрешить и синхронизировать",
                onCtaClick = { onRequestContactsPermissionAndSync() }
            ))
        } else if (allPhoneEntries.isEmpty() && q.isEmpty()) {
            col.addView(Components.emptyStateCard(
                ctx = ctx,
                iconRes = R.drawable.ic_cloud_sync,
                title = "Телефонная книга ещё не синхронизирована",
                subtitle = "Нажмите кнопку ниже, чтобы сверить контакты смартфона с глобальным каталогом Teron.",
                ctaLabel = "Синхронизировать сейчас",
                onCtaClick = { onRequestContactsPermissionAndSync() }
            ))
        } else if (unmatchedInPhoneBook.isEmpty()) {
            col.addView(infoHintCard(if (q.isEmpty()) "Все номера из телефонной книги уже обработаны." else "В телефонной книге нет номеров по запросу «$q»."))
        } else {
            val limit = if (showAllPhoneBookInvitees || q.isNotEmpty()) unmatchedInPhoneBook.size else 25
            val visibleInvitees = unmatchedInPhoneBook.take(limit)
            val invCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            }
            visibleInvitees.forEachIndexed { idx, entry ->
                invCard.addView(buildPhoneBookInviteRow(entry))
                if (idx < visibleInvitees.lastIndex) invCard.addView(Components.divider(ctx, 64))
            }
            col.addView(invCard)

            if (!showAllPhoneBookInvitees && q.isEmpty() && unmatchedInPhoneBook.size > limit) {
                col.addView(Components.secondaryButton(
                    ctx,
                    "Показать все контакты (${unmatchedInPhoneBook.size})",
                    R.drawable.ic_users
                ) {
                    showAllPhoneBookInvitees = true
                    contactsResultsContainer?.let { populateContactsResults(it) }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = Theme.dp(ctx, 10)
                    }
                })
            }
        }
    }

    private fun buildDirectoryUsersCard(users: List<DirectoryUser>): View {
        val savedIds = app.db.getContacts().map { it.nodeId }.toSet()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
        }
        users.forEachIndexed { idx, u ->
            val alreadySaved = savedIds.contains(u.nodeId)
            val isOnlineRecently = (System.currentTimeMillis() - u.lastSeenEpochMs) < 90_000L
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.rippleSurfaceRow(ctx)
                val pad = Theme.dp(ctx, 13)
                setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    app.db.upsertContact(u.nodeId, u.displayName, u.handle, u.publicKeyB64, u.avatarColor, u.bio, u.lastSeenEpochMs)
                    activeConversationId = u.nodeId
                    render()
                }
            }
            row.addView(Components.monogramAvatar(ctx, u.displayName, u.avatarColor, 42, 14f))

            val mid = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = Theme.dp(ctx, 12)
                    rightMargin = Theme.dp(ctx, 8)
                }
            }
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(ctx).apply {
                text = u.displayName
                setTextColor(Theme.textPrimary)
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
            })
            titleRow.addView(Components.icon(ctx, R.drawable.ic_verified, 14, Theme.brandText).apply {
                layoutParams = LinearLayout.LayoutParams(Theme.dp(ctx, 14), Theme.dp(ctx, 14)).apply {
                    leftMargin = Theme.dp(ctx, 5)
                }
            })
            mid.addView(titleRow)

            val presence = if (isOnlineRecently) "в сети Teron" else u.nodeId
            mid.addView(TextView(ctx).apply {
                text = "${u.handle} • $presence"
                setTextColor(if (isOnlineRecently) Theme.accentEmerald else Theme.brandText)
                textSize = 11.5f
                maxLines = 1
            })
            if (u.bio.isNotBlank()) {
                mid.addView(TextView(ctx).apply {
                    text = u.bio
                    setTextColor(Theme.textSecondary)
                    textSize = 11.5f
                    maxLines = 1
                })
            }
            row.addView(mid)

            val actionChip = if (alreadySaved) {
                Components.pillBadge(ctx, "Написать", Theme.accentEmerald)
            } else {
                TextView(ctx).apply {
                    text = "+ В контакты"
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = Theme.roundedBg(Theme.brand, Theme.dp(ctx, 10).toFloat())
                    setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 6), Theme.dp(ctx, 10), Theme.dp(ctx, 6))
                    setOnClickListener {
                        app.db.upsertContact(u.nodeId, u.displayName, u.handle, u.publicKeyB64, u.avatarColor, u.bio, u.lastSeenEpochMs)
                        Toast.makeText(ctx, "${u.displayName} добавлен(а) в контакты Teron", Toast.LENGTH_SHORT).show()
                        contactsResultsContainer?.let { populateContactsResults(it) }
                        dialogsResultsContainer?.let { populateDialogsList(it) }
                    }
                }
            }
            row.addView(actionChip)

            card.addView(row)
            if (idx < users.lastIndex) card.addView(Components.divider(ctx, 66))
        }
        return card
    }

    private fun buildPhoneBookMatchedRow(entry: PhoneBookEntry): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rippleSurfaceRow(ctx)
            val pad = Theme.dp(ctx, 13)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                activeConversationId = entry.matchedNodeId
                render()
            }
        }
        row.addView(Components.monogramAvatar(ctx, entry.displayName, Theme.accentEmerald, 40, 13.5f))
        val mid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 12)
                rightMargin = Theme.dp(ctx, 8)
            }
        }
        mid.addView(TextView(ctx).apply {
            text = entry.displayName
            setTextColor(Theme.textPrimary)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        })
        mid.addView(TextView(ctx).apply {
            text = "${entry.rawPhone} • Установлен Teron (${entry.matchedHandle.ifBlank { entry.matchedNodeId }})"
            setTextColor(Theme.accentEmerald)
            textSize = 11.5f
            maxLines = 1
        })
        row.addView(mid)
        row.addView(Components.pillBadge(ctx, "В Teron", Theme.accentEmerald))
        return row
    }

    private fun buildPhoneBookInviteRow(entry: PhoneBookEntry): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rippleSurfaceRow(ctx)
            val pad = Theme.dp(ctx, 12)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { shareAppInviteForContact(entry.displayName) }
        }
        val color = Theme.avatarPalette[(entry.displayName.hashCode() and 0x7FFFFFFF) % Theme.avatarPalette.size]
        row.addView(Components.monogramAvatar(ctx, entry.displayName, color, 38, 13f))
        val mid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 12)
                rightMargin = Theme.dp(ctx, 8)
            }
        }
        mid.addView(TextView(ctx).apply {
            text = entry.displayName
            setTextColor(Theme.textPrimary)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        })
        mid.addView(TextView(ctx).apply {
            text = entry.rawPhone
            setTextColor(Theme.textMuted)
            textSize = 11.5f
            maxLines = 1
        })
        row.addView(mid)
        row.addView(Components.pillBadge(ctx, "Пригласить", Theme.brandText))
        return row
    }

    private fun buildSearchBarView(
        initialText: String,
        hint: String,
        onClear: () -> Unit,
        onQueryChanged: (String) -> Unit
    ): Pair<View, EditText> {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 4), Theme.dp(ctx, 8), Theme.dp(ctx, 4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Theme.dp(ctx, 16)
                rightMargin = Theme.dp(ctx, 16)
                bottomMargin = Theme.dp(ctx, 8)
            }
        }
        wrap.addView(Components.icon(ctx, R.drawable.ic_search, 18, Theme.textMuted))

        val clearBtn = Components.solidIconButton(ctx, R.drawable.ic_close, Theme.cardAlt, Theme.textSecondary, 28, 13, 8f) {
            onClear()
        }.apply {
            visibility = if (initialText.isNotEmpty()) View.VISIBLE else View.GONE
        }

        val input = EditText(ctx).apply {
            setText(initialText)
            setSelection(initialText.length)
            this.hint = hint
            setHintTextColor(Theme.textMuted)
            setTextColor(Theme.textPrimary)
            textSize = 14f
            setSingleLine(true)
            background = null
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 8), Theme.dp(ctx, 8), Theme.dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val str = s?.toString().orEmpty()
                    clearBtn.visibility = if (str.isNotEmpty()) View.VISIBLE else View.GONE
                    onQueryChanged(str)
                }
            })
        }

        wrap.addView(input)
        wrap.addView(clearBtn)
        return Pair(wrap, input)
    }

    private fun scheduleDirectorySearch(query: String, immediate: Boolean = false, onUpdated: () -> Unit) {
        searchDebounceRunnable?.let { uiHandler.removeCallbacks(it) }
        val task = Runnable {
            isSearchingDirectory = true
            directoryErrorText = null
            onUpdated()
            app.cloudSync.searchUsersAsync(query.trim()) { ok, users ->
                isSearchingDirectory = false
                lastDirectoryFetchMs = System.currentTimeMillis()
                if (ok) {
                    directoryErrorText = null
                    directoryResults = users
                } else {
                    directoryErrorText = "офлайн"
                }
                onUpdated()
            }
        }
        searchDebounceRunnable = task
        uiHandler.postDelayed(task, if (immediate) 10L else 280L)
    }

    private fun infoHintCard(message: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
        val p = Theme.dp(ctx, 14)
        setPadding(p, p, p, p)
        addView(TextView(ctx).apply {
            text = message
            setTextColor(Theme.textSecondary)
            textSize = 12.5f
            setLineSpacing(Theme.dp(ctx, 2).toFloat(), 1.05f)
        })
    }

    private fun shareAppInviteForContact(contactName: String = "") {
        val me = app.db.getProfile()
        val greet = if (contactName.isNotBlank()) "$contactName, привет! " else "Привет! "
        val shareText = "${greet}Установи Teron — мессенджер со сквозным шифрованием E2EE, автономной связью AirMesh и криптокошельком.\n\n" +
            "Прямая ссылка на APK: $DIRECT_APK_DOWNLOAD_URL\n\n" +
            "Мой профиль в Teron: ${me.handle} (${me.nodeId})\n" +
            "Быстрое добавление: ${SunCodeCodec.encodeUri(me)}"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        ctx.startActivity(Intent.createChooser(sendIntent, "Пригласить в Teron"))
    }

    private fun showLinkOwnPhoneSheet() {
        val me = app.db.getProfile()
        TeronSheets.showBottomSheet(
            ctx,
            "Привязать номер телефона",
            "Номер хешируется алгоритмом SHA-256 — в открытом виде он нигде не публикуется, но знакомые с вашим номером сразу найдут вас в Teron."
        ) { dlg, col ->
            val phField = Components.labeledInput(
                ctx = ctx,
                label = "Ваш номер телефона",
                hintText = "+7 777 000 00 00",
                initialValue = me.phoneNumber,
                inputType = InputType.TYPE_CLASS_PHONE
            )
            col.addView(phField.container)
            col.addView(Components.primaryButton(ctx, "Сохранить и синхронизировать", R.drawable.ic_cloud_sync) {
                val ph = phField.input.text.toString().trim()
                if (ph.length < 7) {
                    Toast.makeText(ctx, "Введите корректный номер телефона", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                app.db.updateProfile(
                    me.displayName, me.handle, me.bio, me.avatarColor, me.avatarGlyph,
                    me.maxRelayHops, me.darkTheme, me.backgroundDaemon, ph
                )
                dlg.dismiss()
                app.cloudSync.syncOwnProfileAsync { ok, _ ->
                    if (ok) {
                        Toast.makeText(ctx, "Номер привязан! Друзья теперь видят вас в Teron.", Toast.LENGTH_LONG).show()
                    }
                    render()
                }
                render()
            })
        }
    }

    private fun showContactOptionsSheet(nodeId: String, name: String, handle: String) {
        TeronSheets.showBottomSheet(ctx, name, "$handle • $nodeId") { dlg, col ->
            col.addView(Components.primaryButton(ctx, "Открыть диалог E2EE", R.drawable.ic_send) {
                dlg.dismiss()
                activeConversationId = nodeId
                render()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 8)
                }
            })
            col.addView(Components.secondaryButton(ctx, "Скопировать идентификатор $nodeId", R.drawable.ic_copy) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Teron Node ID", nodeId))
                Toast.makeText(ctx, "Скопировано: $nodeId", Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 8)
                }
            })
            col.addView(Components.primaryButton(ctx, "Удалить из контактов", R.drawable.ic_trash, Theme.accentCoral) {
                app.db.deleteContact(nodeId)
                dlg.dismiss()
                render()
            })
        }
    }

    private fun showAddContactSheet() {
        TeronSheets.showBottomSheet(
            ctx = ctx,
            title = "Найти или добавить контакт",
            subtitle = "Синхронизируйте телефонную книгу, найдите человека по @нику или отсканируйте SunCode"
        ) { dlg, col ->
            col.addView(Components.primaryButton(ctx, "Синхронизировать телефонную книгу", R.drawable.ic_cloud_sync) {
                dlg.dismiss()
                activeSubTab = 2
                render()
                onRequestContactsPermissionAndSync()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 8)
                }
            })

            col.addView(Components.secondaryButton(ctx, "Сканировать SunCode / импорт фото", R.drawable.ic_camera) {
                dlg.dismiss()
                onOpenSunCode(true)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 14)
                }
            })

            val lookupField = Components.labeledInput(
                ctx,
                "Быстрый поиск человека в Teron",
                "@ник, +7 номер телефона, имя или TRN-ID"
            )
            col.addView(lookupField.container)

            col.addView(Components.primaryButton(ctx, "Найти человека в сети Teron", R.drawable.ic_search) {
                val raw = lookupField.input.text.toString().trim()
                if (raw.isEmpty()) {
                    dlg.dismiss()
                    activeSubTab = 2
                    render()
                    return@primaryButton
                }
                val uriParsed = SunCodeCodec.decodeUri(raw)
                if (uriParsed != null) {
                    app.db.upsertContact(uriParsed.nodeId, uriParsed.displayName, uriParsed.handle, uriParsed.publicKeyB64)
                    dlg.dismiss()
                    activeConversationId = uriParsed.nodeId
                    render()
                    return@primaryButton
                }
                dlg.dismiss()
                contactsSearchQuery = raw
                activeSubTab = 2
                render()
                scheduleDirectorySearch(raw, immediate = true) {
                    contactsResultsContainer?.let { populateContactsResults(it) }
                }
            })
        }
    }

    // =========================================================================
    // CONVERSATION DETAIL VIEW
    // =========================================================================

    private fun buildConversationDetail(convId: String): View {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val contact = app.db.getContacts().firstOrNull { it.nodeId == convId }
        val livePeer = app.meshEngine.livePeers[convId]

        // If we opened a chat with a TRN-ID whose public key isn't cached yet, resolve silently in background!
        if (!convId.startsWith("#") && convId != "notes" && contact?.publicKeyB64.isNullOrBlank() && livePeer?.publicKeyB64.isNullOrBlank()) {
            app.cloudSync.resolveNodeAsync(convId) { resolved ->
                if (resolved != null && activeConversationId == convId) {
                    render()
                }
            }
        }

        val titleStr = when (convId) {
            "#airmesh" -> "Общий эфир AirMesh"
            "#sos" -> "Экстренный канал SOS"
            "notes" -> "Зашифрованные заметки"
            else -> contact?.displayName ?: livePeer?.displayName ?: convId
        }
        val peerKey = livePeer?.publicKeyB64?.takeIf { it.isNotBlank() } ?: contact?.publicKeyB64?.takeIf { it.isNotBlank() }
        val subStr = when {
            convId == "#airmesh" -> "Широковещательный радиоканал • до ${app.db.getProfile().maxRelayHops} переходов"
            convId == "#sos" -> "Приоритетная ретрансляция • TTL 6"
            convId == "notes" -> "Локальное шифрование AES-256-GCM"
            livePeer != null -> "Прямой радиоканал ${livePeer.transport} • ~${"%.0f".format(livePeer.distanceMeters)} м"
            peerKey != null && app.cloudSync.isOnlineConnected -> "Сквозное шифрование E2EE • Сеть Teron + AirMesh"
            else -> "Store & Forward E2EE • Доставка при появлении в сети"
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.bgElevated, 0f, Theme.border, Theme.dp(ctx, 1))
            val px = Theme.dp(ctx, 12)
            val py = Theme.dp(ctx, 10)
            setPadding(px, py, px, py)
        }

        val backBtn = Components.solidIconButton(ctx, R.drawable.ic_arrow_left, Theme.cardAlt, Theme.textPrimary, 38, 18, 10f) {
            activeConversationId = null
            render()
        }
        val titleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 10)
            }
        }
        titleBox.addView(TextView(ctx).apply {
            text = titleStr
            setTextColor(Theme.textPrimary)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        })
        titleBox.addView(TextView(ctx).apply {
            text = subStr
            setTextColor(if (livePeer != null || convId == "notes" || (peerKey != null && app.cloudSync.isOnlineConnected)) Theme.accentEmerald else Theme.textSecondary)
            textSize = 11.5f
            maxLines = 1
        })
        topBar.addView(backBtn)
        topBar.addView(titleBox)

        if (peerKey != null) {
            topBar.addView(Components.solidIconButton(ctx, R.drawable.ic_shield, Theme.cardAlt, Theme.accentEmerald, 36, 17, 10f) {
                TeronSheets.showInfoSheet(
                    ctx = ctx,
                    title = "Верификация ключей E2EE",
                    bodyText = "Собеседник: $titleStr ($convId)\n\nОтпечаток публичного ключа ECDH P-256:\n${TeronCrypto.fingerprintHex(peerKey)}\n\nВсе сообщения шифруются на вашем устройстве ключом ECDH P-256 + AES-256-GCM. Сервер ретрансляции и промежуточные узлы не имеют доступа к содержимому."
                )
            })
        }
        wrapper.addView(topBar)

        // Messages List
        val msgScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val msgCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dp(ctx, 14)
            setPadding(p, p, p, p)
        }

        val messages = app.db.getMessages(convId)
        if (messages.isEmpty()) {
            msgCol.addView(Components.emptyStateCard(
                ctx = ctx,
                iconRes = R.drawable.ic_lock,
                title = "Начните защищённый диалог",
                subtitle = "Сообщения шифруются сквозным ключом ECDH P-256 + AES-256-GCM и доставляются мгновенно через интернет-релей Teron либо напрямую по радиосети AirMesh."
            ))
        } else {
            for (m in messages) {
                msgCol.addView(buildMessageBubble(m))
            }
        }
        msgScroll.addView(msgCol)
        msgScroll.post { msgScroll.fullScroll(View.FOCUS_DOWN) }
        wrapper.addView(msgScroll)

        // Composer Row
        val composer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.bgElevated, 0f, Theme.border, Theme.dp(ctx, 1))
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 8), Theme.dp(ctx, 12), Theme.dp(ctx, 10))
        }
        val edit = EditText(ctx).apply {
            hint = "Сообщение…"
            setHintTextColor(Theme.textMuted)
            setTextColor(Theme.textPrimary)
            textSize = 14.5f
            maxLines = 4
            background = Theme.roundedBg(Theme.cardAlt, Theme.dp(ctx, 12).toFloat(), Theme.border, Theme.dp(ctx, 1))
            setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 10), Theme.dp(ctx, 14), Theme.dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
        }
        val sendBtn = Components.solidIconButton(ctx, R.drawable.ic_send, Theme.brand, Color.WHITE, 42, 18, 12f) {
            val txt = edit.text.toString().trim()
            if (txt.isNotEmpty()) {
                edit.setText("")
                app.meshEngine.sendChatMessage(convId, txt)
                render()
            }
        }
        composer.addView(edit)
        composer.addView(sendBtn)
        wrapper.addView(composer)

        return wrapper
    }

    private fun buildMessageBubble(m: ChatMessage): View {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (m.isOutgoing) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Theme.dp(ctx, 8)
            }
        }

        val bubbleColor = if (m.isOutgoing) Theme.brand else Theme.card
        val bubble = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(
                bubbleColor,
                Theme.dp(ctx, 14).toFloat(),
                if (m.isOutgoing) Color.TRANSPARENT else Theme.border,
                if (m.isOutgoing) 0 else Theme.dp(ctx, 1)
            )
            val px = Theme.dp(ctx, 13)
            val py = Theme.dp(ctx, 9)
            setPadding(px, py, px, py)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (m.isOutgoing) leftMargin = Theme.dp(ctx, 48) else rightMargin = Theme.dp(ctx, 48)
            }
            setOnClickListener { showPacketInspectorSheet(m) }
        }

        if (!m.isOutgoing) {
            bubble.addView(TextView(ctx).apply {
                text = "${m.senderName} • ${m.senderNodeId}"
                setTextColor(Theme.brandText)
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }

        bubble.addView(TextView(ctx).apply {
            text = m.body
            setTextColor(if (m.isOutgoing) Color.WHITE else Theme.textPrimary)
            textSize = 14.5f
            setPadding(0, Theme.dp(ctx, 2), 0, Theme.dp(ctx, 4))
        })

        val metaText = "${Theme.formatTime(m.timestampMs)} • ${m.deliveryState}"
        bubble.addView(TextView(ctx).apply {
            text = metaText
            setTextColor(if (m.isOutgoing) 0xFFDBEAFE.toInt() else Theme.textMuted)
            textSize = 10.5f
        })

        if (m.hopCount > 0 && m.viaPath.isNotBlank()) {
            bubble.addView(TextView(ctx).apply {
                text = "Маршрут: ${m.viaPath}"
                setTextColor(if (m.isOutgoing) 0xFFBFDBFE.toInt() else Theme.accentAmber)
                textSize = 10.5f
            })
        }

        outer.addView(bubble)
        return outer
    }

    private fun showPacketInspectorSheet(m: ChatMessage) {
        val details = buildString {
            appendLine("Пакет: ${m.packetId}")
            appendLine("Отправитель: ${m.senderName} (${m.senderNodeId})")
            appendLine("Транспорт: ${m.transport}")
            appendLine("Переходов (Hops): ${m.hopCount}")
            appendLine("Цепочка узлов: ${m.viaPath.ifBlank { "Прямой канал" }}")
            appendLine("Статус: ${m.deliveryState}")
            appendLine("Шифротекст: ${m.cipherPreview}")
            append("Время: ${Theme.formatDateTime(m.timestampMs)}")
        }
        TeronSheets.showBottomSheet(ctx, "Паспорт пакета", "Детали шифрования и доставки") { dlg, col ->
            col.addView(TextView(ctx).apply {
                text = details
                setTextColor(Theme.textSecondary)
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setLineSpacing(Theme.dp(ctx, 2).toFloat(), 1.05f)
                setPadding(0, 0, 0, Theme.dp(ctx, 14))
            })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Components.secondaryButton(ctx, "Копировать текст", R.drawable.ic_copy) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Message", m.body))
                Toast.makeText(ctx, "Текст скопирован", Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = Theme.dp(ctx, 8)
                }
            })
            row.addView(Components.primaryButton(ctx, "Удалить", R.drawable.ic_trash, Theme.accentCoral) {
                app.db.deleteMessage(m.id)
                dlg.dismiss()
                render()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
            })
            col.addView(row)
        }
    }
}
