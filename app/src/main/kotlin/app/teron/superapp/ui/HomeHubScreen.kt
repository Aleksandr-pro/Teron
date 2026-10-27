package app.teron.superapp.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.R
import app.teron.superapp.TeronApp
import app.teron.superapp.core.Theme
import java.util.Locale

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
        val balances = app.db.getWalletBalances()
        val totalUsd = balances.sumOf { it.balance * it.rateUsd }
        val totalRub = balances.sumOf { it.balance * it.rateRub }

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dp(ctx, 16)
            setPadding(p, Theme.dp(ctx, 14), p, Theme.dp(ctx, 24))
        }

        // 1. Top User Identity & SunCode Bar
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val avatar = Components.monogramAvatar(ctx, profile.displayName, profile.avatarColor, 44, 15f).apply {
            setOnClickListener { onNavigateTab(4, "settings") }
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 12)
                rightMargin = Theme.dp(ctx, 8)
            }
            setOnClickListener { onNavigateTab(4, "settings") }
        }
        titleCol.addView(TextView(ctx).apply {
            text = profile.displayName
            setTextColor(Theme.textPrimary)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        })
        titleCol.addView(TextView(ctx).apply {
            text = "${profile.handle} • ${profile.nodeId}"
            setTextColor(Theme.textSecondary)
            textSize = 12f
            maxLines = 1
        })

        val sunBtn = Components.secondaryButton(ctx, "SunCode", R.drawable.ic_suncode) {
            onOpenSunCode(false)
        }
        header.addView(avatar)
        header.addView(titleCol)
        header.addView(sunBtn)
        col.addView(header)

        // 2. Non-Custodial Wallet Overview Card
        val walletCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rippleCard(ctx, Theme.card, 16f, Theme.border, 1f)
            val pad = Theme.dp(ctx, 18)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 16)
            }
            setOnClickListener { onNavigateTab(4, "wallet") }
        }
        val wTop = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wTop.addView(Components.icon(ctx, R.drawable.ic_wallet, 16, Theme.textSecondary).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = Theme.dp(ctx, 6)
        })
        wTop.addView(TextView(ctx).apply {
            text = "Некастодиальный кошелёк"
            setTextColor(Theme.textSecondary)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        wTop.addView(TextView(ctx).apply {
            text = Theme.shortAddress(profile.walletAddress, 6, 4)
            setTextColor(Theme.textMuted)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
        })
        walletCard.addView(wTop)

        val mainBalanceText = if (totalRub > 0.0 || balances.any { it.rateRub > 0.0 }) {
            "${"%,.2f".format(Locale("ru"), totalRub)} ₽"
        } else {
            "${"%,.2f".format(Locale.US, totalUsd)} USD"
        }
        walletCard.addView(TextView(ctx).apply {
            text = mainBalanceText
            setTextColor(Theme.textPrimary)
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            setPadding(0, Theme.dp(ctx, 6), 0, Theme.dp(ctx, 2))
        })
        walletCard.addView(TextView(ctx).apply {
            text = "BTC • ETH • USDT (TRC-20 / ERC-20) • TRX"
            setTextColor(Theme.textMuted)
            textSize = 12f
        })

        val wActions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 14)
            }
        }
        wActions.addView(Components.primaryButton(ctx, "Кошелёк", R.drawable.ic_arrow_up_right) {
            onNavigateTab(4, "wallet")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
        })
        wActions.addView(Components.secondaryButton(ctx, "Получить QR", R.drawable.ic_qr) {
            onNavigateTab(4, "wallet_receive")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        walletCard.addView(wActions)
        col.addView(walletCard)

        // 3. AirMesh Autonomous Radio Status Card
        val meshCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val pad = Theme.dp(ctx, 16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 12)
            }
        }
        val mTop = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mTop.addView(Components.tonalIconBadge(ctx, R.drawable.ic_radar, if (liveCount > 0) Theme.accentEmerald else Theme.brandText, 36, 18, 10f))
        val mTitleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 10)
            }
        }
        mTitleCol.addView(TextView(ctx).apply {
            text = "Автономная сеть AirMesh"
            setTextColor(Theme.textPrimary)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        mTitleCol.addView(TextView(ctx).apply {
            text = "P2P Bluetooth LE и локальный Wi-Fi • до ${profile.maxRelayHops} переходов"
            setTextColor(Theme.textSecondary)
            textSize = 12f
        })
        mTop.addView(mTitleCol)
        mTop.addView(
            Components.pillBadge(
                ctx,
                if (liveCount > 0) "Узлов: $liveCount" else "Поиск эфира",
                if (liveCount > 0) Theme.accentEmerald else Theme.textSecondary
            )
        )
        meshCard.addView(mTop)

        val statusStrip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 12)
            }
        }
        statusStrip.addView(Components.pillBadge(
            ctx,
            if (app.cloudSync.isOnlineConnected) "Сеть Teron онлайн" else "Каталог офлайн",
            if (app.cloudSync.isOnlineConnected) Theme.accentEmerald else Theme.textMuted
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Theme.dp(ctx, 6)
            }
        })
        statusStrip.addView(Components.pillBadge(
            ctx,
            if (app.meshEngine.isBleScanning) "BLE активен" else "BLE доступ",
            if (app.meshEngine.isBleScanning) Theme.brandText else Theme.accentAmber
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Theme.dp(ctx, 6)
            }
        })
        statusStrip.addView(Components.pillBadge(ctx, "Очередь: $pendingRelay", Theme.textSecondary))
        meshCard.addView(statusStrip)

        val meshBtns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 12)
            }
        }
        meshBtns.addView(Components.primaryButton(ctx, "Люди и контакты", R.drawable.ic_users) {
            onNavigateTab(1, "contacts")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
        })
        meshBtns.addView(Components.secondaryButton(ctx, "Радар эфира", R.drawable.ic_radar) {
            onNavigateTab(1, "radar")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        meshCard.addView(meshBtns)
        col.addView(meshCard)

        // 4. Modular Super-App Services Grid
        col.addView(Components.sectionHeader(ctx, "Разделы и сервисы"))

        data class BentoItem(
            val iconRes: Int,
            val title: String,
            val subtitle: String,
            val color: Int,
            val badge: String?,
            val onClick: () -> Unit
        )

        val items = listOf(
            BentoItem(
                R.drawable.ic_chat, "Мессенджер",
                "Сквозное шифрование E2EE",
                Theme.brandText,
                if (unreadChats > 0) "$unreadChats" else null
            ) { onNavigateTab(1, null) },
            BentoItem(
                R.drawable.ic_mail, "Почта",
                "IMAP/SMTP и AirMesh",
                Theme.accentBlue,
                if (unreadMails > 0) "$unreadMails" else null
            ) { onNavigateTab(2, null) },
            BentoItem(
                R.drawable.ic_wallet, "TeronPay",
                "BTC, ETH, USDT, TRX",
                Theme.accentEmerald,
                null
            ) { onNavigateTab(4, "wallet") },
            BentoItem(
                R.drawable.ic_music, "Аудиоплеер",
                "Локальная фонотека",
                Theme.accentPurple,
                if (app.audioEngine.isPlaying) "В эфире" else null
            ) { onNavigateTab(3, null) },
            BentoItem(
                R.drawable.ic_camera, "Сканер SunCode",
                "Оптический обмен ключами",
                Theme.accentAmber,
                null
            ) { onOpenSunCode(true) },
            BentoItem(
                R.drawable.ic_book, "Ханьцзы Додзё",
                "Иероглифы и каллиграфия",
                Theme.brandText,
                null
            ) { onNavigateTab(4, "hanzi") },
            BentoItem(
                R.drawable.ic_tiles, "Маджонг",
                "Традиционный пасьянс",
                Theme.accentEmerald,
                null
            ) { onNavigateTab(4, "mahjong") },
            BentoItem(
                R.drawable.ic_settings, "Настройки",
                "Профиль, ключи, тема",
                Theme.textSecondary,
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
                    background = Theme.rippleCard(ctx, Theme.card, 14f, Theme.border, 1f)
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
                topIconRow.addView(Components.tonalIconBadge(ctx, item.iconRes, item.color, 38, 19, 10f))
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
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

        // 5. Security Architecture Footer
        val secCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 14)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 4)
            }
        }
        secCard.addView(Components.tonalIconBadge(ctx, R.drawable.ic_shield, Theme.accentEmerald, 36, 18, 10f))
        val secCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 12)
            }
        }
        secCol.addView(TextView(ctx).apply {
            text = "Локальное хранение и сквозное шифрование"
            setTextColor(Theme.textPrimary)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        secCol.addView(TextView(ctx).apply {
            text = "Приватные ключи BIP-39 и переписка хранятся только в памяти вашего устройства. Пакеты AirMesh защищены ECDH P-256 и AES-256-GCM."
            setTextColor(Theme.textSecondary)
            textSize = 11.5f
            setPadding(0, Theme.dp(ctx, 2), 0, 0)
        })
        secCard.addView(secCol)
        col.addView(secCard)

        scroll.addView(col)
        return scroll
    }
}
