package app.teron.superapp.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.TeronApp
import app.teron.superapp.core.ChatMessage
import app.teron.superapp.core.Theme

class MessengerScreen(
    private val ctx: Context,
    private val app: TeronApp,
    private val onRequestBlePermissions: () -> Unit,
    private val onOpenSunCode: (scanTab: Boolean) -> Unit
) {
    var activeConversationId: String? = null
    var activeSubTab: Int = 0 // 0 = Chats, 1 = AirMesh Radar, 2 = Contacts

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

        // Top Header
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Theme.dp(ctx, 16)
            setPadding(p, Theme.dp(ctx, 12), p, Theme.dp(ctx, 8))
        }
        top.addView(TextView(ctx).apply {
            text = "Чаты и AirMesh"
            setTextColor(Theme.textPrimary)
            textSize = 22f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(ctx).apply {
            text = "+ SunCode"
            setTextColor(Color.WHITE)
            textSize = 13f
            paint.isFakeBoldText = true
            background = Theme.rippleButton(ctx, Theme.accentIndigo, 12f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 8), Theme.dp(ctx, 12), Theme.dp(ctx, 8))
            setOnClickListener { onOpenSunCode(true) }
        })
        wrapper.addView(top)

        // Sub-tab chips
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 10))
        }
        val liveCount = app.meshEngine.livePeers.size
        chipRow.addView(Components.actionChip(ctx, "💬 Диалоги", activeSubTab == 0) {
            activeSubTab = 0; render()
        })
        chipRow.addView(Components.actionChip(ctx, "📡 Радар ($liveCount)", activeSubTab == 1, Theme.accentEmerald) {
            activeSubTab = 1; render()
        })
        chipRow.addView(Components.actionChip(ctx, "👥 Контакты", activeSubTab == 2) {
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
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 20))
        }

        val conversations = app.db.getConversations(app.meshEngine.livePeers)
        for (conv in conversations) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.rippleCard(ctx, Theme.card, 16f)
                val p = Theme.dp(ctx, 13)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 9)
                }
                setOnClickListener {
                    activeConversationId = conv.conversationId
                    app.db.markConversationRead(conv.conversationId)
                    render()
                }
            }

            row.addView(Components.squircleIcon(ctx, conv.glyph, conv.accentColor, 46, 19f))

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
                textSize = 15.5f
                paint.isFakeBoldText = true
                maxLines = 1
            })
            mid.addView(TextView(ctx).apply {
                text = conv.subtitle
                setTextColor(if (conv.isOnlineNearby) Theme.accentCyan else Theme.textMuted)
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
            right.addView(TextView(ctx).apply {
                text = Theme.formatTime(conv.lastTimestampMs)
                setTextColor(Theme.textMuted)
                textSize = 11.5f
            })
            if (conv.unreadCount > 0) {
                right.addView(TextView(ctx).apply {
                    text = conv.unreadCount.toString()
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    paint.isFakeBoldText = true
                    gravity = Gravity.CENTER
                    background = Theme.circleBg(Theme.accentIndigo)
                    val s = Theme.dp(ctx, 22)
                    layoutParams = LinearLayout.LayoutParams(s, s).apply { topMargin = Theme.dp(ctx, 6) }
                })
            }
            row.addView(right)
            col.addView(row)
        }

        scroll.addView(col)
        return scroll
    }

    private fun buildRadarSubView(): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 24))
        }

        if (!app.meshEngine.hasBlePermissions()) {
            val permBanner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.withAlpha(Theme.accentAmber, 30), Theme.dp(ctx, 16).toFloat(), Theme.accentAmber, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 14)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 12)
                }
            }
            permBanner.addView(TextView(ctx).apply {
                text = "⚡ Включите радиоразрешения Bluetooth LE («Устройства рядом»)"
                setTextColor(Theme.textPrimary)
                textSize = 14f
                paint.isFakeBoldText = true
            })
            permBanner.addView(TextView(ctx).apply {
                text = "Необходимо для прямой передачи сообщений между смартфонами без интернета на дистанции до 200–800 метров."
                setTextColor(Theme.textSecondary)
                textSize = 12.5f
                setPadding(0, Theme.dp(ctx, 4), 0, Theme.dp(ctx, 10))
            })
            permBanner.addView(TextView(ctx).apply {
                text = "Разрешить радиосвязь AirMesh"
                setTextColor(Color.WHITE)
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                background = Theme.rippleButton(ctx, Theme.accentAmber, 12f)
                setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 10), Theme.dp(ctx, 14), Theme.dp(ctx, 10))
                setOnClickListener { onRequestBlePermissions() }
            })
            col.addView(permBanner)
        }

        // Animated Radar Canvas Card
        val me = app.db.getProfile()
        val peersList = app.meshEngine.livePeers.values.sortedBy { it.distanceMeters }
        val radarCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 22).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 14)
            setPadding(p, p, p, p)
        }
        val radarView = MeshRadarView(ctx).apply {
            selfLabel = me.nodeId
            peers = peersList
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 250))
        }
        radarCard.addView(radarView)

        val statsLine = TextView(ctx).apply {
            text = "Узлов рядом: ${peersList.size}  •  Ретранслировано: ${app.meshEngine.relayedCountSession}  •  В очереди Store&Forward: ${app.db.countPendingRelayPackets()}"
            setTextColor(Theme.textSecondary)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(ctx, 6), 0, Theme.dp(ctx, 10))
        }
        radarCard.addView(statsLine)

        val simBtn = TextView(ctx).apply {
            text = "🧪 Тест эстафеты AirMesh (4 смартфона • 640 м • ECDH)"
            setTextColor(Color.WHITE)
            textSize = 13f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(ctx, Theme.accentIndigo, 12f)
            setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 11), Theme.dp(ctx, 14), Theme.dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                app.meshEngine.runMultiHopFieldSimulation()
                Toast.makeText(ctx, "Запущена криптографическая эстафета через 3 промежуточных узла!", Toast.LENGTH_SHORT).show()
            }
        }
        radarCard.addView(simBtn)
        col.addView(radarCard)

        // Nearby Peers List
        col.addView(Components.sectionHeader(ctx, "УСТРОЙСТВА В РАДИУСЕ ДЕЙСТВИЯ (${peersList.size})"))
        if (peersList.isEmpty()) {
            col.addView(TextView(ctx).apply {
                text = "Пока нет других смартфонов с Teron поблизости. Откройте Teron на втором телефоне (Bluetooth или общий Wi-Fi/Хот-спот без интернета) или запустите тест эстафеты выше."
                setTextColor(Theme.textSecondary)
                textSize = 13f
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 14)
                setPadding(p, p, p, p)
            })
        } else {
            for (p in peersList) {
                val pCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleCard(ctx, Theme.card, 15f)
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
                pCard.addView(Components.squircleIcon(ctx, "📶", Theme.accentEmerald, 42, 18f))
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
                    paint.isFakeBoldText = true
                })
                info.addView(TextView(ctx).apply {
                    text = "${p.transport} • RSSI ${p.rssi} дБм • ~${p.distanceMeters} м • Переходов: ${p.hops}"
                    setTextColor(Theme.accentCyan)
                    textSize = 12f
                })
                pCard.addView(info)
                pCard.addView(Components.pillBadge(ctx, "Чат →", Theme.accentIndigo))
                col.addView(pCard)
            }
        }

        // Live Telemetry Log
        col.addView(Components.sectionHeader(ctx, "ЖУРНАЛ РАДИОПРОТОКОЛА AIRMESH"))
        val logText = synchronized(app.meshEngine.telemetryLogs) {
            app.meshEngine.telemetryLogs.take(12).joinToString("\n").ifBlank { "Инициализация радиомодулей…" }
        }
        col.addView(TextView(ctx).apply {
            text = logText
            setTextColor(Theme.accentCyan)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            background = Theme.roundedBg(0xFF070B14.toInt(), Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 12)
            setPadding(p, p, p, p)
        })

        scroll.addView(col)
        return scroll
    }

    private fun buildContactsSubView(): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 20))
        }

        val addCard = TextView(ctx).apply {
            text = "☀ Отсканировать или поделиться SunCode контактом"
            setTextColor(Color.WHITE)
            textSize = 14f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(ctx, Theme.accentIndigo, 14f)
            setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 13), Theme.dp(ctx, 14), Theme.dp(ctx, 13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Theme.dp(ctx, 12)
            }
            setOnClickListener { onOpenSunCode(false) }
        }
        col.addView(addCard)

        val contacts = app.db.getContacts()
        if (contacts.isEmpty()) {
            col.addView(TextView(ctx).apply {
                text = "Список доверенных контактов пуст. Обменяйтесь лучевыми SunCode-кодами или найдите устройства рядом через Радар AirMesh."
                setTextColor(Theme.textSecondary)
                textSize = 13.5f
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 16)
                setPadding(p, p, p, p)
            })
        } else {
            for (c in contacts) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleCard(ctx, Theme.card, 16f)
                    val p = Theme.dp(ctx, 12)
                    setPadding(p, p, p, p)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = Theme.dp(ctx, 8)
                    }
                    setOnClickListener {
                        activeConversationId = c.nodeId
                        render()
                    }
                }
                row.addView(Components.squircleIcon(ctx, c.displayName.take(1).uppercase(), Theme.accentCyan, 44, 18f))
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
                    paint.isFakeBoldText = true
                })
                info.addView(TextView(ctx).apply {
                    text = "${c.handle} • ${c.nodeId}"
                    setTextColor(Theme.textSecondary)
                    textSize = 12f
                })
                row.addView(info)
                row.addView(Components.pillBadge(ctx, "Написать", Theme.accentIndigo))
                col.addView(row)
            }
        }
        scroll.addView(col)
        return scroll
    }

    private fun buildConversationDetail(convId: String): View {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val livePeer = app.meshEngine.livePeers[convId]
        val contact = app.db.getContacts().firstOrNull { it.nodeId == convId }
        val titleStr = when (convId) {
            "#airmesh" -> "📡 Эфир Поблизости"
            "#sos" -> "🆘 Экстренный маяк"
            "notes" -> "🔐 Избранное (Сейф)"
            else -> livePeer?.displayName ?: contact?.displayName ?: convId
        }
        val subStr = when {
            convId == "#airmesh" -> "Шифрование канала AES-256-GCM • ${app.meshEngine.livePeers.size} узлов"
            convId == "#sos" -> "Приоритетная эстафета до 6 переходов без сети"
            convId == "notes" -> "Хранится локально на устройстве"
            livePeer != null -> "${livePeer.transport} • ~${livePeer.distanceMeters} м • E2EE ECDH"
            else -> "Store & Forward очередь • E2EE ECDH P-256"
        }

        // Chat Top Bar
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.bgElevated, 0f, Theme.border, Theme.dp(ctx, 1))
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 10), Theme.dp(ctx, 12), Theme.dp(ctx, 10))
        }
        val backBtn = TextView(ctx).apply {
            text = "← Назад"
            setTextColor(Theme.accentCyan)
            textSize = 14.5f
            paint.isFakeBoldText = true
            setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 6), Theme.dp(ctx, 12), Theme.dp(ctx, 6))
            setOnClickListener {
                activeConversationId = null
                render()
            }
        }
        val titleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBox.addView(TextView(ctx).apply {
            text = titleStr
            setTextColor(Theme.textPrimary)
            textSize = 16.5f
            paint.isFakeBoldText = true
            maxLines = 1
        })
        titleBox.addView(TextView(ctx).apply {
            text = subStr
            setTextColor(Theme.accentEmerald)
            textSize = 11.5f
            maxLines = 1
        })
        topBar.addView(backBtn)
        topBar.addView(titleBox)
        wrapper.addView(topBar)

        // Messages ScrollView
        val msgScroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val msgCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dp(ctx, 14)
            setPadding(p, p, p, p)
        }

        val messages = app.db.getMessages(convId)
        for (m in messages) {
            msgCol.addView(buildMessageBubble(m))
        }
        msgScroll.addView(msgCol)
        msgScroll.post { msgScroll.fullScroll(View.FOCUS_DOWN) }
        wrapper.addView(msgScroll)

        // Quick-reply chips for field / hiking communication
        val quickScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 4), Theme.dp(ctx, 12), Theme.dp(ctx, 4))
        }
        val quickRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val presets = listOf(
            "📡 Проверка связи AirMesh!",
            "📍 Мы на точке, сети нет",
            "🔋 Держим связь по цепочке",
            "👍 Принято!"
        )
        for (preset in presets) {
            quickRow.addView(TextView(ctx).apply {
                text = preset
                setTextColor(Theme.textSecondary)
                textSize = 12f
                background = Theme.rippleCard(ctx, Theme.cardAlt, 10f)
                setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 6), Theme.dp(ctx, 10), Theme.dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = Theme.dp(ctx, 6)
                }
                setOnClickListener {
                    app.meshEngine.sendChatMessage(convId, preset)
                    render()
                }
            })
        }
        quickScroll.addView(quickRow)
        wrapper.addView(quickScroll)

        // Composer Row
        val composer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.bgElevated, 0f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 8), Theme.dp(ctx, 12), Theme.dp(ctx, 10))
        }
        val edit = EditText(ctx).apply {
            hint = "Зашифрованное сообщение…"
            setHintTextColor(Theme.textMuted)
            setTextColor(Theme.textPrimary)
            textSize = 14.5f
            maxLines = 4
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 18).toFloat(), Theme.border, Theme.dp(ctx, 1))
            setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 10), Theme.dp(ctx, 14), Theme.dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
        }
        val sendBtn = TextView(ctx).apply {
            text = "➤"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = Theme.rippleButton(ctx, Theme.accentIndigo, 22f)
            val s = Theme.dp(ctx, 44)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener {
                val txt = edit.text.toString().trim()
                if (txt.isNotEmpty()) {
                    edit.setText("")
                    app.meshEngine.sendChatMessage(convId, txt)
                    render()
                }
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
                bottomMargin = Theme.dp(ctx, 10)
            }
        }

        val bubbleColor = if (m.isOutgoing) Theme.accentIndigo else Theme.card
        val bubble = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(
                bubbleColor,
                Theme.dp(ctx, 18).toFloat(),
                if (m.isOutgoing) Color.TRANSPARENT else Theme.border,
                if (m.isOutgoing) 0 else Theme.dp(ctx, 1)
            )
            val px = Theme.dp(ctx, 14)
            val py = Theme.dp(ctx, 10)
            setPadding(px, py, px, py)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (m.isOutgoing) leftMargin = Theme.dp(ctx, 44) else rightMargin = Theme.dp(ctx, 44)
            }
            setOnClickListener { showPacketInspectorDialog(m) }
        }

        if (!m.isOutgoing) {
            bubble.addView(TextView(ctx).apply {
                text = "${m.senderName} (${m.senderNodeId})"
                setTextColor(Theme.accentCyan)
                textSize = 11.5f
                paint.isFakeBoldText = true
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
            setTextColor(if (m.isOutgoing) 0xFFD8D4FF.toInt() else Theme.textMuted)
            textSize = 10.5f
        })

        if (m.hopCount > 0 && m.viaPath.isNotBlank()) {
            bubble.addView(TextView(ctx).apply {
                text = "Маршрут эстафеты: ${m.viaPath}"
                setTextColor(if (m.isOutgoing) 0xFFE2E8F0.toInt() else Theme.accentAmber)
                textSize = 10.5f
            })
        }

        outer.addView(bubble)
        return outer
    }

    private fun showPacketInspectorDialog(m: ChatMessage) {
        val details = buildString {
            appendLine("ID пакета: ${m.packetId}")
            appendLine("Отправитель: ${m.senderName} (${m.senderNodeId})")
            appendLine("Транспорт: ${m.transport}")
            appendLine("Переходов (Hops): ${m.hopCount}")
            appendLine("Цепочка ретрансляции: ${m.viaPath.ifBlank { "Прямой канал" }}")
            appendLine("Статус: ${m.deliveryState}")
            appendLine("Шифротекст AES-256-GCM: ${m.cipherPreview}")
            appendLine("Время: ${Theme.formatDateTime(m.timestampMs)}")
        }
        AlertDialog.Builder(ctx)
            .setTitle("🔐 Криптопаспорт пакета AirMesh")
            .setMessage(details)
            .setPositiveButton("ОК", null)
            .show()
    }
}
