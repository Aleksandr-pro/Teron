package app.teron.superapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
import app.teron.superapp.core.TeronCrypto
import app.teron.superapp.core.Theme
import app.teron.superapp.suncode.SunCodeCodec

class MessengerScreen(
    private val ctx: Context,
    private val app: TeronApp,
    private val onRequestBlePermissions: () -> Unit,
    private val onOpenSunCode: (scanTab: Boolean) -> Unit
) {
    var activeConversationId: String? = null
    var activeSubTab: Int = 0 // 0 = Dialogs, 1 = AirMesh Radar, 2 = Contacts

    private var rootContainer: LinearLayout? = null

    fun openSubArg(arg: String?) {
        when (arg) {
            "radar" -> { activeConversationId = null; activeSubTab = 1 }
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

    fun render() {
        val root = rootContainer ?: return
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Theme.dp(ctx, 16)
            setPadding(p, Theme.dp(ctx, 14), p, Theme.dp(ctx, 8))
        }
        top.addView(TextView(ctx).apply {
            text = "Сообщения"
            setTextColor(Theme.textPrimary)
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(Components.secondaryButton(ctx, "Контакт", R.drawable.ic_plus) {
            showAddContactSheet()
        })
        wrapper.addView(top)

        // Sub-tab filter bar
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 10))
        }
        val liveCount = app.meshEngine.livePeers.size
        chipRow.addView(Components.actionChip(ctx, "Диалоги", activeSubTab == 0, R.drawable.ic_chat) {
            activeSubTab = 0; render()
        })
        chipRow.addView(Components.actionChip(ctx, "Радар ($liveCount)", activeSubTab == 1, R.drawable.ic_radar, Theme.accentEmerald) {
            activeSubTab = 1; render()
        })
        chipRow.addView(Components.actionChip(ctx, "Контакты", activeSubTab == 2, R.drawable.ic_users) {
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

    private fun buildChatsListSubView(): View {
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 20))
        }

        val conversations = app.db.getConversations(app.meshEngine.livePeers)
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
        scroll.addView(col)
        return scroll
    }

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
                text = "Разрешение необходимо для прямого поиска смартфонов Teron в радиусе 200–800 метров без сотовой сети."
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

        val radarCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 14)
            setPadding(p, p, p, p)
        }
        radarCard.addView(MeshRadarView(ctx).apply {
            selfLabel = me.nodeId
            peers = peersList
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 240))
        })

        val metaGrid = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 8)
            }
        }
        fun statBox(label: String, value: String): View = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Theme.roundedBg(Theme.cardAlt, Theme.dp(ctx, 10).toFloat())
            setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 8), Theme.dp(ctx, 8), Theme.dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 3)
                rightMargin = Theme.dp(ctx, 3)
            }
            addView(TextView(ctx).apply {
                text = value
                setTextColor(Theme.textPrimary)
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                fontFeatureSettings = "tnum"
            })
            addView(TextView(ctx).apply {
                text = label
                setTextColor(Theme.textMuted)
                textSize = 10.5f
            })
        }
        metaGrid.addView(statBox("Локальный IP", app.meshEngine.getLocalIpv4()))
        metaGrid.addView(statBox("Ретрансляций", app.meshEngine.relayedCountSession.toString()))
        metaGrid.addView(statBox("В очереди", app.db.countPendingRelayPackets().toString()))
        radarCard.addView(metaGrid)

        radarCard.addView(Components.secondaryButton(ctx, "Обновить сканирование эфира", R.drawable.ic_refresh) {
            app.meshEngine.refreshBleTransport()
            Toast.makeText(ctx, "Сканирование Bluetooth LE и Wi-Fi LAN обновлено", Toast.LENGTH_SHORT).show()
            render()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 10)
            }
        })
        col.addView(radarCard)

        col.addView(Components.sectionHeader(ctx, "Обнаруженные узлы (${peersList.size})"))
        if (peersList.isEmpty()) {
            col.addView(Components.emptyStateCard(
                ctx = ctx,
                iconRes = R.drawable.ic_wifi,
                title = "Устройств поблизости не обнаружено",
                subtitle = "Запустите Teron на втором смартфоне с включённым Bluetooth или в одной локальной сети Wi-Fi / точки доступа.",
                ctaLabel = "Обменяться SunCode",
                onCtaClick = { onOpenSunCode(false) }
            ))
        } else {
            for (p in peersList) {
                val pCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleCard(ctx, Theme.card, 14f, Theme.border, 1f)
                    val pad = Theme.dp(ctx, 12)
                    setPadding(pad, pad, pad, pad)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = Theme.dp(ctx, 8)
                    }
                    setOnClickListener {
                        activeConversationId = p.nodeId
                        render()
                    }
                }
                pCard.addView(Components.tonalIconBadge(ctx, R.drawable.ic_radar, Theme.accentEmerald, 40, 18, 10f))
                val info = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                    }
                }
                info.addView(TextView(ctx).apply {
                    text = "${p.displayName} (${p.nodeId})"
                    setTextColor(Theme.textPrimary)
                    textSize = 14.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                info.addView(TextView(ctx).apply {
                    text = "${p.transport} • ${p.rssi} дБм • ~${p.distanceMeters} м • Переходов: ${p.hops}"
                    setTextColor(Theme.textSecondary)
                    textSize = 12f
                })
                pCard.addView(info)
                pCard.addView(Components.icon(ctx, R.drawable.ic_chevron_right, 18, Theme.textMuted))
                col.addView(pCard)
            }
        }

        col.addView(Components.sectionHeader(ctx, "Системный журнал радиомодуля"))
        val logText = synchronized(app.meshEngine.telemetryLogs) {
            app.meshEngine.telemetryLogs.take(12).joinToString("\n").ifBlank { "Инициализация сетевых интерфейсов…" }
        }
        col.addView(TextView(ctx).apply {
            text = logText
            setTextColor(Theme.textSecondary)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 12).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 12)
            setPadding(p, p, p, p)
        })

        scroll.addView(col)
        return scroll
    }

    private fun buildContactsSubView(): View {
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 20))
        }

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Theme.dp(ctx, 12)
            }
        }
        btnRow.addView(Components.primaryButton(ctx, "Сканировать SunCode", R.drawable.ic_camera) {
            onOpenSunCode(true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
        })
        btnRow.addView(Components.secondaryButton(ctx, "По ID узла", R.drawable.ic_plus) {
            showAddContactSheet()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f)
        })
        col.addView(btnRow)

        val contacts = app.db.getContacts()
        if (contacts.isEmpty()) {
            col.addView(Components.emptyStateCard(
                ctx = ctx,
                iconRes = R.drawable.ic_users,
                title = "Нет сохранённых контактов",
                subtitle = "Отсканируйте SunCode собеседника камерой, импортируйте карточку или добавьте ID узла вручную."
            ))
        } else {
            val group = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            }
            contacts.forEachIndexed { idx, c ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleSurfaceRow(ctx)
                    val p = Theme.dp(ctx, 13)
                    setPadding(p, p, p, p)
                    setOnClickListener {
                        activeConversationId = c.nodeId
                        render()
                    }
                    setOnLongClickListener {
                        TeronSheets.showBottomSheet(ctx, c.displayName, "${c.handle} • ${c.nodeId}") { dlg, sheet ->
                            sheet.addView(Components.primaryButton(ctx, "Удалить из контактов", R.drawable.ic_trash, Theme.accentCoral) {
                                app.db.deleteContact(c.nodeId)
                                dlg.dismiss()
                                render()
                            })
                        }
                        true
                    }
                }
                row.addView(Components.monogramAvatar(ctx, c.displayName, Theme.avatarPalette[(c.nodeId.hashCode() and 0x7FFFFFFF) % Theme.avatarPalette.size], 42, 14f))
                val info = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                    }
                }
                info.addView(TextView(ctx).apply {
                    text = c.displayName
                    setTextColor(Theme.textPrimary)
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                info.addView(TextView(ctx).apply {
                    text = "${c.handle} • ${c.nodeId}"
                    setTextColor(Theme.textSecondary)
                    textSize = 12f
                })
                row.addView(info)
                row.addView(Components.icon(ctx, R.drawable.ic_chevron_right, 18, Theme.textMuted))
                group.addView(row)
                if (idx < contacts.lastIndex) group.addView(Components.divider(ctx, 67))
            }
            col.addView(group)
        }
        scroll.addView(col)
        return scroll
    }

    private fun showAddContactSheet() {
        TeronSheets.showBottomSheet(ctx, "Добавить контакт", "По ссылке teron:// или идентификатору узла AirMesh") { dlg, col ->
            val idField = Components.labeledInput(ctx, "ID узла или ссылка teron://", "TRN-A1B2C3D4 или teron://contact?...")
            val nameField = Components.labeledInput(ctx, "Имя контакта", "Например: Дмитрий")
            val handleField = Components.labeledInput(ctx, "Почтовый адрес (необязательно)", "dmitry@teron.mesh")

            col.addView(idField.container)
            col.addView(nameField.container)
            col.addView(handleField.container)

            col.addView(Components.primaryButton(ctx, "Сохранить контакт", R.drawable.ic_check) {
                val raw = idField.input.text.toString().trim()
                val decoded = SunCodeCodec.decodeUri(raw)
                if (decoded != null) {
                    app.db.upsertContact(decoded.nodeId, nameField.input.text.toString().ifBlank { decoded.displayName }, decoded.handle, decoded.publicKeyB64)
                    dlg.dismiss()
                    activeConversationId = decoded.nodeId
                    render()
                    return@primaryButton
                }
                val normId = raw.uppercase().let { if (it.startsWith("TRN-")) it else if (it.isNotEmpty()) "TRN-$it" else "" }
                if (normId.length < 6) {
                    Toast.makeText(ctx, "Введите корректный ID узла (например TRN-8F20A1B4)", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                val nm = nameField.input.text.toString().trim().ifBlank { normId }
                app.db.upsertContact(normId, nm, handleField.input.text.toString().trim(), "")
                dlg.dismiss()
                activeConversationId = normId
                render()
            })

            col.addView(Components.secondaryButton(ctx, "Открыть сканер SunCode", R.drawable.ic_camera) {
                dlg.dismiss()
                onOpenSunCode(true)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Theme.dp(ctx, 8)
                }
            })
        }
    }

    private fun buildConversationDetail(convId: String): View {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val livePeer = app.meshEngine.livePeers[convId]
        val contact = app.db.getContacts().firstOrNull { it.nodeId == convId }
        val titleStr = when (convId) {
            "#airmesh" -> "Общий радиоэфир (#airmesh)"
            "#sos" -> "Экстренный канал (#sos)"
            "notes" -> "Избранное"
            else -> livePeer?.displayName ?: contact?.displayName ?: convId
        }
        val subStr = when {
            convId == "#airmesh" -> "AES-256-GCM • ${app.meshEngine.livePeers.size} узлов в радиусе"
            convId == "#sos" -> "Приоритетная ретрансляция до 6 переходов"
            convId == "notes" -> "Зашифровано на устройстве"
            livePeer != null -> "${livePeer.transport} • ~${livePeer.distanceMeters} м • ECDH P-256"
            else -> "Очередь Store & Forward • ECDH P-256"
        }

        // Top Bar
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.bgElevated, 0f, Theme.border, Theme.dp(ctx, 1))
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 10), Theme.dp(ctx, 12), Theme.dp(ctx, 10))
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
            setTextColor(if (livePeer != null || convId == "notes") Theme.accentEmerald else Theme.textSecondary)
            textSize = 11.5f
            maxLines = 1
        })
        topBar.addView(backBtn)
        topBar.addView(titleBox)

        val peerKey = livePeer?.publicKeyB64?.takeIf { it.isNotBlank() } ?: contact?.publicKeyB64?.takeIf { it.isNotBlank() }
        if (peerKey != null) {
            topBar.addView(Components.solidIconButton(ctx, R.drawable.ic_shield, Theme.cardAlt, Theme.accentEmerald, 36, 17, 10f) {
                TeronSheets.showInfoSheet(
                    ctx = ctx,
                    title = "Верификация ключей E2EE",
                    bodyText = "Собеседник: $titleStr ($convId)\n\nОтпечаток публичного ключа ECDH P-256:\n${TeronCrypto.fingerprintHex(peerKey)}\n\nСверьте этот код с экраном собеседника при личной встрече."
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
                title = "Сообщений пока нет",
                subtitle = "Все сообщения шифруются алгоритмом AES-256-GCM перед отправкой в эфир или сохранением в очередь Store & Forward."
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
