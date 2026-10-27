package app.teron.superapp.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.TeronApp
import app.teron.superapp.core.Theme

object HomeHubScreen {

    fun build(
        ctx: Context,
        app: TeronApp,
        onNavigateTab: (tabIndex: Int, subArg: String?) -> Unit,
        onOpenSunCode: (scanTab: Boolean) -> Unit
    ): View {
        val profile = app.db.getProfile()
        val liveCount = app.meshEngine.livePeers.size
        val unreadChats = app.db.getTotalUnreadChats()
        val unreadMails = app.db.getUnreadMailCount()
        val pendingRelay = app.db.countPendingRelayPackets()

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dp(ctx, 16)
            setPadding(p, Theme.dp(ctx, 12), p, Theme.dp(ctx, 24))
        }

        // 1. Top Profile & SunCode Quick Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val avatar = Components.squircleIcon(ctx, profile.avatarGlyph, profile.avatarColor, 48, 22f).apply {
            setOnClickListener { onNavigateTab(4, "settings") }
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 12)
            }
        }
        titleCol.addView(TextView(ctx).apply {
            text = "Teron"
            setTextColor(Theme.textPrimary)
            textSize = 24f
            paint.isFakeBoldText = true
        })
        titleCol.addView(TextView(ctx).apply {
            text = "${profile.displayName} • ${profile.nodeId}"
            setTextColor(Theme.textSecondary)
            textSize = 12.5f
        })

        val sunBtn = TextView(ctx).apply {
            text = "☀ SunCode"
            setTextColor(Color.WHITE)
            textSize = 13f
            paint.isFakeBoldText = true
            background = Theme.rippleButton(ctx, Theme.accentIndigo, 13f)
            setPadding(Theme.dp(ctx, 13), Theme.dp(ctx, 9), Theme.dp(ctx, 13), Theme.dp(ctx, 9))
            setOnClickListener { onOpenSunCode(false) }
        }
        header.addView(avatar)
        header.addView(titleCol)
        header.addView(sunBtn)
        col.addView(header)

        // 2. Hero AirMesh Offline Radio Card
        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.gradientBg(
                intArrayOf(0xFF141E38.toInt(), 0xFF1B1442.toInt(), 0xFF0E293C.toInt()),
                Theme.dp(ctx, 22).toFloat(),
                GradientDrawable.Orientation.TL_BR,
                Theme.withAlpha(Theme.accentCyan, 95),
                Theme.dp(ctx, 1)
            )
            val pad = Theme.dp(ctx, 18)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 16)
            }
        }

        val heroTop = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroTop.addView(TextView(ctx).apply {
            text = "📡 Протокол AirMesh"
            setTextColor(Color.WHITE)
            textSize = 17f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val radioStateText = if (liveCount > 0) "В эфире: $liveCount узл." else "Поиск узлов…"
        heroTop.addView(Components.pillBadge(ctx, radioStateText, if (liveCount > 0) Theme.accentEmerald else Theme.accentCyan))
        hero.addView(heroTop)

        hero.addView(TextView(ctx).apply {
            text = "Прямая связь между телефонами по Bluetooth LE и Wi-Fi без мобильной сети и серверов. Дальность по эстафете до 800 м (до ${profile.maxRelayHops} переходов)."
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 12.8f
            setPadding(0, Theme.dp(ctx, 8), 0, Theme.dp(ctx, 12))
        })

        // Status chips row
        val chipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        chipsRow.addView(Components.pillBadge(ctx, if (app.meshEngine.isWifiMeshActive) "Wi-Fi P2P ✓" else "Wi-Fi Ожид.", Theme.accentCyan).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Theme.dp(ctx, 6)
            }
        })
        chipsRow.addView(Components.pillBadge(ctx, if (app.meshEngine.isBleScanning) "BLE Радар ✓" else "BLE Разреш.", Theme.accentAmber).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Theme.dp(ctx, 6)
            }
        })
        chipsRow.addView(Components.pillBadge(ctx, "Очередь: $pendingRelay", Theme.accentPurple))
        hero.addView(chipsRow)

        // Hero action buttons
        val heroActions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 14)
            }
        }
        heroActions.addView(TextView(ctx).apply {
            text = "Открыть Радар"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(ctx, Theme.accentIndigo, 12f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 10), Theme.dp(ctx, 12), Theme.dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
            setOnClickListener { onNavigateTab(1, "radar") }
        })
        heroActions.addView(TextView(ctx).apply {
            text = "🆘 SOS Маяк"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(ctx, Theme.accentCoral, 12f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 10), Theme.dp(ctx, 12), Theme.dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                app.meshEngine.sendChatMessage(
                    "#sos",
                    "🆘 SOS-сигнал от ${profile.displayName} (${profile.nodeId})! Требуется связь/координация в радиусе AirMesh (6 переходов)."
                )
                Toast.makeText(ctx, "Экстренный SOS-пакет отправлен в эфир AirMesh!", Toast.LENGTH_LONG).show()
                onNavigateTab(1, "#sos")
            }
        })
        hero.addView(heroActions)
        col.addView(hero)

        // 3. Super-App Services Bento Grid
        col.addView(Components.sectionHeader(ctx, "СЕРВИСЫ СУПЕРПРИЛОЖЕНИЯ TERON"))

        data class BentoItem(
            val glyph: String,
            val title: String,
            val subtitle: String,
            val color: Int,
            val badge: String?,
            val onClick: () -> Unit
        )

        val items = listOf(
            BentoItem(
                "💬", "Мессенджер",
                "E2EE чаты без сети",
                Theme.accentBlue,
                if (unreadChats > 0) "$unreadChats новых" else null
            ) { onNavigateTab(1, null) },
            BentoItem(
                "✉️", "Teron Mail",
                "Почта @teron.mesh",
                Theme.accentIndigo,
                if (unreadMails > 0) "$unreadMails писем" else null
            ) { onNavigateTab(2, null) },
            BentoItem(
                "🎵", "Teron Sound",
                "Офлайн-музыка и синтез",
                Theme.accentPink,
                if (app.audioEngine.isPlaying) "Играет ♪" else null
            ) { onNavigateTab(3, null) },
            BentoItem(
                "📷", "Сканер SunCode",
                "Навёл камеру — контакт",
                Theme.accentAmber,
                null
            ) { onOpenSunCode(true) },
            BentoItem(
                "🐼", "Ханьцзы Додзё",
                "Китайский по ассоциациям",
                Theme.accentCoral,
                "32 иероглифа"
            ) { onNavigateTab(4, "hanzi") },
            BentoItem(
                "🀄", "Маджонг Культура",
                "Пасьянс и символы Китая",
                Theme.accentEmerald,
                "Игра"
            ) { onNavigateTab(4, "mahjong") },
            BentoItem(
                "💎", "TeronPay",
                "Криптокошелёк • Офлайн чеки",
                Theme.accentCyan,
                "ECDSA"
            ) { onNavigateTab(4, "wallet") },
            BentoItem(
                "⚙️", "Настройки",
                "Профиль, тема и ключи",
                Theme.accentPurple,
                null
            ) { onNavigateTab(4, "settings") }
        )

        for (rowIdx in items.indices step 2) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 10)
                }
            }
            for (cIdx in 0..1) {
                val item = items.getOrNull(rowIdx + cIdx) ?: continue
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Theme.rippleCard(ctx, Theme.card, 18f)
                    val p = Theme.dp(ctx, 14)
                    setPadding(p, p, p, p)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (cIdx == 0) rightMargin = Theme.dp(ctx, 5) else leftMargin = Theme.dp(ctx, 5)
                    }
                    setOnClickListener { item.onClick() }
                }
                val topIconRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topIconRow.addView(Components.squircleIcon(ctx, item.glyph, item.color, 40, 18f))
                topIconRow.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
                if (item.badge != null) {
                    topIconRow.addView(Components.pillBadge(ctx, item.badge, item.color))
                }
                card.addView(topIconRow)

                card.addView(TextView(ctx).apply {
                    text = item.title
                    setTextColor(Theme.textPrimary)
                    textSize = 15.5f
                    paint.isFakeBoldText = true
                    setPadding(0, Theme.dp(ctx, 10), 0, Theme.dp(ctx, 2))
                })
                card.addView(TextView(ctx).apply {
                    text = item.subtitle
                    setTextColor(Theme.textSecondary)
                    textSize = 12f
                })
                row.addView(card)
            }
            col.addView(row)
        }

        // 4. Security Architecture Footer Card
        val secCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.cardAlt, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 14)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 6)
            }
        }
        secCard.addView(TextView(ctx).apply {
            text = "🛡 Архитектура нулевого доверия (Zero-Server)"
            setTextColor(Theme.textPrimary)
            textSize = 13.5f
            paint.isFakeBoldText = true
        })
        secCard.addView(TextView(ctx).apply {
            text = "Переписка, почта и ключи хранятся локально в SQLite на вашем смартфоне. Пакеты AirMesh шифруются алгоритмом AES-256-GCM с обменом ключами ECDH P-256 — промежуточные телефоны-ретрансляторы не могут прочитать ваши сообщения."
            setTextColor(Theme.textSecondary)
            textSize = 12f
            setPadding(0, Theme.dp(ctx, 4), 0, 0)
        })
        col.addView(secCard)

        scroll.addView(col)
        return scroll
    }
}
