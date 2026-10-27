package app.teron.superapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.R
import app.teron.superapp.TeronApp
import app.teron.superapp.core.TeronCrypto
import app.teron.superapp.core.Theme
import app.teron.superapp.core.WalletBalance
import app.teron.superapp.crypto.Secp256k1Wallet
import java.util.Locale
import java.util.UUID

class EcosystemScreen(
    private val ctx: Context,
    private val app: TeronApp,
    private val onThemeChanged: () -> Unit,
    private val onOpenSunCode: (scanTab: Boolean) -> Unit,
    private val onResetToOnboarding: () -> Unit
) {
    var activeSubApp = 0 // 0=Wallet, 1=Hanzi, 2=Mahjong, 3=Settings
    private var rootContainer: LinearLayout? = null
    private val settingsScreen = SettingsScreen(
        ctx = ctx,
        app = app,
        onThemeChanged = onThemeChanged,
        onOpenSunCode = { onOpenSunCode(false) },
        onOpenWalletBackup = { showSeedBackupOrImportSheet() },
        onResetToOnboarding = onResetToOnboarding
    )

    // Hanzi Dojo state
    private data class Lesson(
        val id: Int,
        val character: String,
        val pinyin: String,
        val meaningRu: String,
        val visualAssociation: String,
        val strokesHint: Int
    )
    private var currentLessonIdx = 0
    private var quizMode = false

    // Mahjong state (pure CJK tile characters, zero emojis)
    private data class Tile(val id: Int, val glyph: String, val suitColor: Int, var matched: Boolean = false)
    private var mahjongBoard: MutableList<Tile> = mutableListOf()
    private var selectedTileId: Int? = null
    private var mahjongMoves = 0
    private var mahjongMatches = 0

    private val lessons = listOf(
        Lesson(1, "山", "shān", "Гора", "Три горных пика: центральная вершина возвышается над хребтом", 3),
        Lesson(2, "水", "shuǐ", "Вода / Поток", "Стремительное русло реки с боковыми водоворотами", 4),
        Lesson(3, "火", "huǒ", "Огонь", "Языки пламени и разлетающиеся по бокам искры", 4),
        Lesson(4, "木", "mù", "Дерево", "Ствол с кроной ветвей наверху и корнями в земле", 4),
        Lesson(5, "日", "rì", "Солнце / День", "Солнечный диск с точкой света посередине", 4),
        Lesson(6, "月", "yuè", "Луна / Месяц", "Серп растущего полумесяца в ночном небе", 4),
        Lesson(7, "人", "rén", "Человек", "Шагающий вперёд силуэт на двух точках опоры", 2),
        Lesson(8, "心", "xīn", "Сердце / Разум", "Контур сердца и три импульса мысли", 4),
        Lesson(9, "道", "dào", "Путь / Метод", "Движение вперёд с осознанным направлением", 12),
        Lesson(10, "風", "fēng", "Ветер", "Парус, наполненный воздушным потоком", 9),
        Lesson(11, "電", "diàn", "Электричество", "Грозовое облако и разряд молнии под дождём", 13),
        Lesson(12, "龍", "lóng", "Дракон", "Восточный дракон в облаках — символ энергии", 16)
    )

    init {
        resetMahjongBoard()
    }

    fun openSubArg(arg: String?) {
        when (arg) {
            "wallet" -> activeSubApp = 0
            "wallet_receive" -> {
                activeSubApp = 0
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    showReceiveSheet(app.db.getWalletBalances().firstOrNull())
                }
            }
            "hanzi" -> activeSubApp = 1
            "mahjong" -> activeSubApp = 2
            "settings" -> activeSubApp = 3
        }
    }

    fun handleBack(): Boolean {
        if (activeSubApp != 0) {
            activeSubApp = 0
            render()
            return true
        }
        return false
    }

    private fun resetMahjongBoard() {
        data class Spec(val glyph: String, val color: Int)
        val specs = listOf(
            Spec("一", 0xFF3B82F6.toInt()),
            Spec("二", 0xFF3B82F6.toInt()),
            Spec("三", 0xFF3B82F6.toInt()),
            Spec("五", 0xFF10B981.toInt()),
            Spec("七", 0xFF10B981.toInt()),
            Spec("九", 0xFF10B981.toInt()),
            Spec("東", 0xFFF59E0B.toInt()),
            Spec("北", 0xFFF59E0B.toInt()),
            Spec("中", 0xFFEF4444.toInt()),
            Spec("發", 0xFF10B981.toInt()),
            Spec("白", 0xFF8B95A7.toInt()),
            Spec("龍", 0xFF6366F1.toInt())
        )
        val raw = mutableListOf<Tile>()
        var nextId = 1
        for (sp in specs) {
            raw.add(Tile(nextId++, sp.glyph, sp.color))
            raw.add(Tile(nextId++, sp.glyph, sp.color))
        }
        raw.shuffle()
        mahjongBoard = raw
        selectedTileId = null
        mahjongMoves = 0
        mahjongMatches = 0
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

        // Sub-app Navigation Strip
        val tabScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 12), Theme.dp(ctx, 16), Theme.dp(ctx, 6))
        }
        val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tabRow.addView(Components.actionChip(ctx, "Кошелёк", activeSubApp == 0, R.drawable.ic_wallet) { activeSubApp = 0; render() })
        tabRow.addView(Components.actionChip(ctx, "Ханьцзы Додзё", activeSubApp == 1, R.drawable.ic_hanzi) { activeSubApp = 1; render() })
        tabRow.addView(Components.actionChip(ctx, "Маджонг", activeSubApp == 2, R.drawable.ic_mahjong) { activeSubApp = 2; render() })
        tabRow.addView(Components.actionChip(ctx, "Настройки", activeSubApp == 3, R.drawable.ic_settings) { activeSubApp = 3; render() })
        tabScroll.addView(tabRow)
        root.addView(tabScroll)

        if (activeSubApp == 3) {
            root.addView(settingsScreen.build().apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
            return
        }

        // Header Row for Wallet / Hanzi / Mahjong
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 6), Theme.dp(ctx, 16), Theme.dp(ctx, 6))
        }
        top.addView(TextView(ctx).apply {
            text = when (activeSubApp) {
                0 -> "Кошелёк TeronPay"
                1 -> "Каллиграфия и Ханьцзы"
                else -> "Пасьянс Маджонг"
            }
            setTextColor(Theme.textPrimary)
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (activeSubApp == 0) {
            top.addView(Components.solidIconButton(ctx, R.drawable.ic_refresh, Theme.cardAlt, Theme.textPrimary, 38, 17, 10f) {
                Toast.makeText(ctx, "Запрос актуальных балансов и курсов из блокчейна…", Toast.LENGTH_SHORT).show()
                app.walletEngine.refreshAllChains(true)
            })
        }
        root.addView(top)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = when (activeSubApp) {
            0 -> buildWalletView()
            1 -> buildHanziDojoView()
            else -> buildMahjongView()
        }
        scroll.addView(content)
        root.addView(scroll)
    }

    private fun formatCryptoAmount(asset: WalletBalance): String {
        return String.format(Locale.US, "%.${asset.decimalsDisplay}f", asset.balance)
    }

    private fun formatCryptoRaw(symbol: String, amount: Double): String {
        val dec = when (symbol) {
            "BTC" -> 6
            "ETH" -> 5
            else -> 2
        }
        return String.format(Locale.US, "%.${dec}f", amount)
    }

    // 1. REAL NON-CUSTODIAL MULTI-CHAIN CRYPTO WALLET
    private fun buildWalletView(): View {
        val me = app.db.getProfile()
        val assets = app.db.getWalletBalances()
        val txs = app.db.getWalletTransactions()
        val totalRub = assets.sumOf { it.balance * it.rateRub }
        val totalUsd = assets.sumOf { it.balance * it.rateUsd }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 28))
        }

        val portfolioCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 18).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 18)
            setPadding(p, p, p, p)
        }

        val topMeta = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topMeta.addView(TextView(ctx).apply {
            text = "Некастодиальный портфель (BIP-39)"
            setTextColor(Theme.textSecondary)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topMeta.addView(Components.pillBadge(
            ctx,
            if (app.walletEngine.isSyncing) "Синхронизация…" else "Mainnet",
            if (app.walletEngine.isSyncing) Theme.accentAmber else Theme.accentEmerald
        ))
        portfolioCard.addView(topMeta)

        portfolioCard.addView(TextView(ctx).apply {
            text = String.format(Locale.US, "%,.2f ₽", totalRub).replace(',', ' ')
            setTextColor(Theme.textPrimary)
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            setPadding(0, Theme.dp(ctx, 6), 0, Theme.dp(ctx, 2))
        })
        portfolioCard.addView(TextView(ctx).apply {
            text = String.format(Locale.US, "$%,.2f USD • %s", totalUsd, app.walletEngine.lastSyncStatus)
            setTextColor(Theme.textMuted)
            textSize = 11.5f
            fontFeatureSettings = "tnum"
        })

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 16)
            }
        }
        btnRow.addView(Components.primaryButton(ctx, "Пополнить", R.drawable.ic_qr) {
            showReceiveSheet(assets.firstOrNull())
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 6)
            }
        })
        btnRow.addView(Components.secondaryButton(ctx, "Отправить", R.drawable.ic_send) {
            showSendSheet(assets.firstOrNull())
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 6)
            }
        })
        btnRow.addView(Components.solidIconButton(ctx, R.drawable.ic_key, Theme.cardAlt, Theme.textPrimary, 42, 18, 12f) {
            showSeedBackupOrImportSheet()
        })
        portfolioCard.addView(btnRow)
        col.addView(portfolioCard)

        col.addView(Components.sectionHeader(ctx, "Активы и сети"))
        val assetsGroup = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
        }

        assets.forEachIndexed { idx, a ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.rippleSurfaceRow(ctx)
                val p = Theme.dp(ctx, 14)
                setPadding(p, p, p, p)
                setOnClickListener { showAssetDetailSheet(a) }
            }
            row.addView(Components.tonalIconBadge(ctx, a.iconRes, a.accentColor, 42, 20, 12f))

            val leftCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = Theme.dp(ctx, 12)
                }
            }
            val titleLine = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleLine.addView(TextView(ctx).apply {
                text = a.name
                setTextColor(Theme.textPrimary)
                textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            titleLine.addView(TextView(ctx).apply {
                text = a.networkLabel
                setTextColor(Theme.textMuted)
                textSize = 10.5f
                background = Theme.roundedBg(Theme.cardAlt, Theme.dp(ctx, 6).toFloat())
                setPadding(Theme.dp(ctx, 6), Theme.dp(ctx, 2), Theme.dp(ctx, 6), Theme.dp(ctx, 2))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = Theme.dp(ctx, 6)
                }
            })
            leftCol.addView(titleLine)

            leftCol.addView(TextView(ctx).apply {
                text = if (a.rateUsd > 0.0) {
                    String.format(Locale.US, "$%,.2f (%,.2f ₽)", a.rateUsd, a.rateRub).replace(',', ' ')
                } else {
                    Theme.shortAddress(a.address, 8, 6)
                }
                setTextColor(Theme.textSecondary)
                textSize = 12f
                fontFeatureSettings = "tnum"
            })
            row.addView(leftCol)

            val rightCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            rightCol.addView(TextView(ctx).apply {
                text = "${formatCryptoAmount(a)} ${a.symbol}"
                setTextColor(Theme.textPrimary)
                textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                fontFeatureSettings = "tnum"
            })
            rightCol.addView(TextView(ctx).apply {
                text = String.format(Locale.US, "%,.2f ₽", a.balance * a.rateRub).replace(',', ' ')
                setTextColor(Theme.textMuted)
                textSize = 12f
                fontFeatureSettings = "tnum"
            })
            row.addView(rightCol)

            assetsGroup.addView(row)
            if (idx < assets.lastIndex) assetsGroup.addView(Components.divider(ctx, 68))
        }
        col.addView(assetsGroup)

        col.addView(Components.sectionHeader(ctx, "История операций"))
        if (txs.isEmpty()) {
            col.addView(Components.emptyStateCard(
                ctx = ctx,
                iconRes = R.drawable.ic_wallet,
                title = "Транзакций пока нет",
                subtitle = "Пополните ваш адрес BTC (${Theme.shortAddress(me.btcAddress, 6, 4)}), ETH (${Theme.shortAddress(me.walletAddress, 6, 4)}) или TRX (${Theme.shortAddress(me.trxAddress, 6, 4)}) — переводы из блокчейна синхронизируются автоматически.",
                ctaLabel = "Показать QR-код для пополнения",
                onCtaClick = { showReceiveSheet(assets.firstOrNull()) }
            ))
        } else {
            val txGroup = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            }
            txs.take(30).forEachIndexed { idx, tx ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleSurfaceRow(ctx)
                    val p = Theme.dp(ctx, 14)
                    setPadding(p, p, p, p)
                    setOnClickListener {
                        copyText("TX Hash", tx.txHash)
                        Toast.makeText(ctx, "Хеш транзакции скопирован: ${Theme.shortAddress(tx.txHash, 8, 6)}", Toast.LENGTH_SHORT).show()
                    }
                }
                row.addView(Components.tonalIconBadge(
                    ctx,
                    if (tx.isIncoming) R.drawable.ic_arrow_down_left else R.drawable.ic_arrow_up_right,
                    if (tx.isIncoming) Theme.accentEmerald else Theme.brandText,
                    38, 18, 10f
                ))
                val mid = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                    }
                }
                mid.addView(TextView(ctx).apply {
                    text = "${if (tx.isIncoming) "Входящий" else "Исходящий"} • ${tx.network}"
                    setTextColor(Theme.textPrimary)
                    textSize = 13.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                mid.addView(TextView(ctx).apply {
                    text = "${Theme.shortAddress(tx.counterpartyAddress.ifBlank { tx.counterpartyName }, 7, 5)} • ${Theme.formatDateTime(tx.timestampMs)}"
                    setTextColor(Theme.textSecondary)
                    textSize = 11.5f
                })
                row.addView(mid)

                val amtCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                }
                amtCol.addView(TextView(ctx).apply {
                    text = "${if (tx.isIncoming) "+" else "-"}${formatCryptoRaw(tx.symbol, tx.amount)} ${tx.symbol}"
                    setTextColor(if (tx.isIncoming) Theme.accentEmerald else Theme.textPrimary)
                    textSize = 13.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    fontFeatureSettings = "tnum"
                })
                amtCol.addView(TextView(ctx).apply {
                    text = tx.status
                    setTextColor(Theme.textMuted)
                    textSize = 11f
                })
                row.addView(amtCol)
                txGroup.addView(row)
                if (idx < txs.take(30).lastIndex) txGroup.addView(Components.divider(ctx, 64))
            }
            col.addView(txGroup)
        }

        return col
    }

    private fun copyText(label: String, value: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun showAssetDetailSheet(asset: WalletBalance) {
        TeronSheets.showBottomSheet(
            ctx = ctx,
            title = "${asset.name} (${asset.symbol})",
            subtitle = "${asset.networkLabel} • Баланс: ${formatCryptoAmount(asset)} ${asset.symbol}"
        ) { dlg, col ->
            col.addView(QrCodeView(ctx, asset.address).apply {
                val sz = Theme.dp(ctx, 196)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = Theme.dp(ctx, 6)
                    bottomMargin = Theme.dp(ctx, 12)
                }
            })

            col.addView(TextView(ctx).apply {
                text = asset.address
                setTextColor(Theme.textPrimary)
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 10).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 12)
                setPadding(p, p, p, p)
            })

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Theme.dp(ctx, 14)
                }
            }
            row.addView(Components.secondaryButton(ctx, "Копировать", R.drawable.ic_copy) {
                copyText("Address", asset.address)
                Toast.makeText(ctx, "Адрес скопирован", Toast.LENGTH_SHORT).show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = Theme.dp(ctx, 6)
                }
            })
            row.addView(Components.primaryButton(ctx, "Отправить", R.drawable.ic_send) {
                dlg.dismiss()
                showSendSheet(asset)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = Theme.dp(ctx, 6)
                }
            })
            col.addView(row)
        }
    }

    private fun showReceiveSheet(initial: WalletBalance?) {
        val assets = app.db.getWalletBalances()
        if (assets.isEmpty()) return
        var selected = initial ?: assets.first()

        TeronSheets.showBottomSheet(
            ctx = ctx,
            title = "Пополнение кошелька",
            subtitle = "Выберите сеть и отсканируйте QR-код или скопируйте публичный адрес"
        ) { _, col ->
            val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

            fun rebuildReceiveContent() {
                container.removeAllViews()
                val chipScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
                val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (a in assets) {
                    val label = "${a.symbol} (${a.networkLabel.substringBefore(" ")})"
                    chipRow.addView(Components.actionChip(ctx, label, selected.assetKey == a.assetKey) {
                        selected = a
                        rebuildReceiveContent()
                    })
                }
                chipScroll.addView(chipRow)
                container.addView(chipScroll)

                container.addView(QrCodeView(ctx, selected.address).apply {
                    val sz = Theme.dp(ctx, 200)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = Theme.dp(ctx, 14)
                        bottomMargin = Theme.dp(ctx, 12)
                    }
                })

                container.addView(TextView(ctx).apply {
                    text = "Отправляйте только активы сети ${selected.networkLabel} на этот адрес:"
                    setTextColor(Theme.textSecondary)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, Theme.dp(ctx, 6))
                })

                container.addView(TextView(ctx).apply {
                    text = selected.address
                    setTextColor(Theme.textPrimary)
                    textSize = 12.5f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 12).toFloat(), Theme.border, Theme.dp(ctx, 1))
                    val p = Theme.dp(ctx, 12)
                    setPadding(p, p, p, p)
                })

                container.addView(Components.primaryButton(ctx, "Копировать адрес", R.drawable.ic_copy) {
                    copyText(selected.symbol, selected.address)
                    Toast.makeText(ctx, "Адрес ${selected.networkLabel} скопирован", Toast.LENGTH_SHORT).show()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = Theme.dp(ctx, 14)
                    }
                })
            }

            rebuildReceiveContent()
            col.addView(container)
        }
    }

    private fun showSendSheet(initial: WalletBalance?) {
        val assets = app.db.getWalletBalances()
        if (assets.isEmpty()) return
        var selected = initial ?: assets.first()
        var modeOnChain = true

        TeronSheets.showBottomSheet(
            ctx = ctx,
            title = "Перевод средств",
            subtitle = "Подпись транзакции выполняется локально ключом secp256k1 на вашем устройстве"
        ) { dlg, col ->
            val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

            fun rebuildSendForm() {
                container.removeAllViews()
                val modeRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = Theme.dp(ctx, 10)
                    }
                }
                modeRow.addView(Components.actionChip(ctx, "Блокчейн Mainnet", modeOnChain, R.drawable.ic_globe) {
                    modeOnChain = true
                    rebuildSendForm()
                })
                modeRow.addView(Components.actionChip(ctx, "AirMesh (Офлайн чек)", !modeOnChain, R.drawable.ic_mesh) {
                    modeOnChain = false
                    rebuildSendForm()
                })
                container.addView(modeRow)

                val chipScroll = HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = Theme.dp(ctx, 10)
                    }
                }
                val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (a in assets) {
                    val label = "${a.symbol} ${a.networkLabel.substringBefore(" ")} (${formatCryptoAmount(a)})"
                    chipRow.addView(Components.actionChip(ctx, label, selected.assetKey == a.assetKey) {
                        selected = a
                        rebuildSendForm()
                    })
                }
                chipScroll.addView(chipRow)
                container.addView(chipScroll)

                val addrHint = when {
                    !modeOnChain -> "ID узла AirMesh (например TRN-XXXX)"
                    selected.assetKey == "BTC" -> "bc1q..."
                    selected.assetKey == "ETH" || selected.assetKey == "USDT_ERC20" -> "0x..."
                    else -> "T..."
                }
                val toField = Components.labeledInput(ctx, if (modeOnChain) "Адрес получателя (${selected.networkLabel})" else "Получатель в сети AirMesh", addrHint)
                val amtField = Components.labeledInput(ctx, "Сумма (${selected.symbol})", "0.00")
                val memoField = Components.labeledInput(ctx, "Комментарий (необязательно)", "")
                container.addView(toField.container)
                container.addView(amtField.container)
                container.addView(memoField.container)

                if (!modeOnChain) {
                    val peers = app.db.getContacts()
                    if (peers.isNotEmpty()) {
                        val peerScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
                        val peerRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                        for (p in peers) {
                            peerRow.addView(Components.actionChip(ctx, p.displayName, false, R.drawable.ic_user) {
                                toField.input.setText(p.nodeId)
                            })
                        }
                        peerScroll.addView(peerRow)
                        container.addView(peerScroll)
                    }
                }

                container.addView(Components.primaryButton(
                    ctx,
                    if (modeOnChain) "Подписать и отправить в блокчейн" else "Подписать чек и отправить в AirMesh",
                    R.drawable.ic_send
                ) {
                    val target = toField.input.text.toString().trim()
                    val amt = amtField.input.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                    val memo = memoField.input.text.toString().trim()
                    if (target.isEmpty() || amt <= 0.0) {
                        Toast.makeText(ctx, "Проверьте адрес получателя и сумму", Toast.LENGTH_SHORT).show()
                        return@primaryButton
                    }
                    if (modeOnChain) {
                        Toast.makeText(ctx, "Формирование и подпись транзакции…", Toast.LENGTH_SHORT).show()
                        app.walletEngine.sendOnChainAsset(selected.assetKey, target, amt, memo) { ok, msg ->
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            if (ok) {
                                dlg.dismiss()
                                render()
                            }
                        }
                    } else {
                        if (selected.balance < amt) {
                            Toast.makeText(ctx, "Недостаточно средств на балансе ${selected.symbol}", Toast.LENGTH_SHORT).show()
                            return@primaryButton
                        }
                        val me = app.db.getProfile()
                        val txHash = "mesh-" + UUID.randomUUID().toString().replace("-", "").take(16)
                        val sig = TeronCrypto.signData(me.privateKeyB64, "$txHash:${selected.symbol}:$amt:$target")
                        app.db.updateChainBalance(selected.assetKey, (selected.balance - amt).coerceAtLeast(0.0))
                        app.db.upsertWalletTransaction(
                            txHash = txHash,
                            symbol = selected.symbol,
                            network = "AirMesh",
                            amount = amt,
                            counterpartyName = target,
                            counterpartyAddress = target,
                            isIncoming = false,
                            memo = memo.ifBlank { "Крипто-чек AirMesh" },
                            status = "Передано в AirMesh",
                            viaAirMesh = true,
                            timestampMs = System.currentTimeMillis()
                        )
                        app.meshEngine.broadcastSignedCryptoTransfer(txHash, selected.symbol, amt, target, memo, sig)
                        Toast.makeText(ctx, "Подписанный крипто-чек отправлен в AirMesh", Toast.LENGTH_SHORT).show()
                        dlg.dismiss()
                        render()
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = Theme.dp(ctx, 8)
                    }
                })
            }

            rebuildSendForm()
            col.addView(container)
        }
    }

    fun showSeedBackupOrImportSheet() {
        val currentMnemonic = app.db.getProfile().seedPhrase
        TeronSheets.showBottomSheet(
            ctx = ctx,
            title = "Резервная копия кошелька (BIP-39)",
            subtitle = "Секретная фраза хранится исключительно на этом устройстве. Никому не сообщайте эти слова."
        ) { dlg, col ->
            if (currentMnemonic.isNotEmpty()) {
                val words = currentMnemonic.split(" ")
                val grid = GridLayout(ctx).apply {
                    columnCount = 3
                    background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
                    val p = Theme.dp(ctx, 10)
                    setPadding(p, p, p, p)
                }
                words.forEachIndexed { idx, w ->
                    grid.addView(TextView(ctx).apply {
                        text = "${idx + 1}. $w"
                        setTextColor(Theme.textPrimary)
                        textSize = 12.5f
                        typeface = Typeface.MONOSPACE
                        background = Theme.roundedBg(Theme.cardAlt, Theme.dp(ctx, 8).toFloat())
                        setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 6), Theme.dp(ctx, 8), Theme.dp(ctx, 6))
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = 0
                            columnSpec = GridLayout.spec(idx % 3, 1f)
                            setMargins(Theme.dp(ctx, 4), Theme.dp(ctx, 4), Theme.dp(ctx, 4), Theme.dp(ctx, 4))
                        }
                    })
                }
                col.addView(grid)

                col.addView(Components.secondaryButton(ctx, "Скопировать фразу", R.drawable.ic_copy) {
                    copyText("BIP-39 Seed", currentMnemonic)
                    Toast.makeText(ctx, "Фраза скопирована в буфер обмена", Toast.LENGTH_SHORT).show()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = Theme.dp(ctx, 10)
                        bottomMargin = Theme.dp(ctx, 16)
                    }
                })
            }

            val importField = Components.labeledInput(
                ctx = ctx,
                label = "Импортировать другую BIP-39 фразу (12 или 24 слова)",
                hintText = "Введите слова на английском через пробел",
                initialValue = "",
                singleLine = false,
                minLinesCount = 3
            )
            col.addView(importField.container)

            col.addView(Components.primaryButton(ctx, "Восстановить кошелёк из фразы", R.drawable.ic_key) {
                val norm = Secp256k1Wallet.normalizeMnemonic(importField.input.text.toString())
                if (!app.db.importWalletSeed(norm)) {
                    Toast.makeText(ctx, "Некорректная фраза или контрольная сумма BIP-39", Toast.LENGTH_LONG).show()
                    return@primaryButton
                }
                dlg.dismiss()
                Toast.makeText(ctx, "Кошелёк обновлён. Запрос балансов из блокчейна…", Toast.LENGTH_LONG).show()
                app.walletEngine.refreshAllChains(true)
                render()
            })
        }
    }

    // 2. HANZI DOJO (100% EMOJI-FREE)
    private fun buildHanziDojoView(): View {
        val lesson = lessons[currentLessonIdx % lessons.size]
        val masteryMap = app.db.getHanziMasteryMap()
        val masteredCount = masteryMap.values.count { it > 0 }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 24))
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Theme.dp(ctx, 12)
            }
        }
        headerRow.addView(Components.pillBadge(ctx, "Изучено: $masteredCount / ${lessons.size}", Theme.brandText))
        headerRow.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        headerRow.addView(Components.actionChip(ctx, if (quizMode) "К прописям" else "Проверка знаний", quizMode, R.drawable.ic_award) {
            quizMode = !quizMode
            render()
        })
        col.addView(headerRow)

        if (quizMode) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 18).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 20)
                setPadding(p, p, p, p)
            }
            card.addView(TextView(ctx).apply {
                text = "Выберите верное значение иероглифа"
                setTextColor(Theme.textSecondary)
                textSize = 13f
            })
            card.addView(TextView(ctx).apply {
                text = lesson.character
                setTextColor(Theme.textPrimary)
                textSize = 72f
                gravity = Gravity.CENTER
                setPadding(0, Theme.dp(ctx, 8), 0, Theme.dp(ctx, 4))
            })
            card.addView(TextView(ctx).apply {
                text = "[ ${lesson.pinyin} ]"
                setTextColor(Theme.brandText)
                textSize = 15f
                setPadding(0, 0, 0, Theme.dp(ctx, 14))
            })

            val options = (lessons.filter { it.id != lesson.id }.shuffled().take(3) + lesson).shuffled()
            for (opt in options) {
                card.addView(Components.secondaryButton(ctx, opt.meaningRu) {
                    if (opt.id == lesson.id) {
                        app.db.updateHanziMastery(lesson.id, 1)
                        Toast.makeText(ctx, "Верно: ${lesson.character} (${lesson.pinyin}) — ${lesson.meaningRu}", Toast.LENGTH_SHORT).show()
                        currentLessonIdx = (currentLessonIdx + 1) % lessons.size
                        render()
                    } else {
                        Toast.makeText(ctx, "Подсказка: ${lesson.visualAssociation}", Toast.LENGTH_SHORT).show()
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = Theme.dp(ctx, 8)
                    }
                })
            }
            col.addView(card)
        } else {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 18).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 18)
                setPadding(p, p, p, p)
            }

            val topInfo = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topInfo.addView(TextView(ctx).apply {
                text = "${lesson.character}  •  ${lesson.pinyin}"
                setTextColor(Theme.textPrimary)
                textSize = 24f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val done = (masteryMap[lesson.id] ?: 0) > 0
            topInfo.addView(Components.pillBadge(ctx, if (done) "Освоено" else "Черт: ${lesson.strokesHint}", if (done) Theme.accentEmerald else Theme.textSecondary))
            card.addView(topInfo)

            card.addView(TextView(ctx).apply {
                text = lesson.meaningRu
                setTextColor(Theme.brandText)
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, Theme.dp(ctx, 4), 0, Theme.dp(ctx, 4))
            })
            card.addView(TextView(ctx).apply {
                text = lesson.visualAssociation
                setTextColor(Theme.textSecondary)
                textSize = 13f
                setPadding(0, 0, 0, Theme.dp(ctx, 14))
            })

            val pad = CalligraphyPadView(ctx, lesson.character).apply {
                val sz = Theme.dp(ctx, 240)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sz)
            }
            card.addView(pad)

            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Theme.dp(ctx, 14)
                }
            }
            actions.addView(Components.secondaryButton(ctx, "Очистить", R.drawable.ic_refresh) {
                pad.clearPad()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = Theme.dp(ctx, 6)
                }
            })
            actions.addView(Components.primaryButton(ctx, "Засчитать", R.drawable.ic_check) {
                app.db.updateHanziMastery(lesson.id, 1)
                currentLessonIdx = (currentLessonIdx + 1) % lessons.size
                render()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = Theme.dp(ctx, 6)
                }
            })
            card.addView(actions)
            col.addView(card)

            val navRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Theme.dp(ctx, 10)
                }
            }
            navRow.addView(Components.secondaryButton(ctx, "Предыдущий") {
                currentLessonIdx = (currentLessonIdx - 1 + lessons.size) % lessons.size
                render()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = Theme.dp(ctx, 6)
                }
            })
            navRow.addView(Components.secondaryButton(ctx, "Следующий") {
                currentLessonIdx = (currentLessonIdx + 1) % lessons.size
                render()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = Theme.dp(ctx, 6)
                }
            })
            col.addView(navRow)
        }

        return col
    }

    // 3. MAHJONG SOLITAIRE (CLASSIC CJK GLYPHS, ZERO EMOJIS)
    private fun buildMahjongView(): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 24))
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Theme.dp(ctx, 14)
            }
        }
        bar.addView(TextView(ctx).apply {
            text = "Пар открыто: $mahjongMatches / 12   •   Ходов: $mahjongMoves"
            setTextColor(Theme.textPrimary)
            textSize = 13.5f
            fontFeatureSettings = "tnum"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Components.solidIconButton(ctx, R.drawable.ic_refresh, Theme.cardAlt, Theme.textPrimary, 34, 16, 8f) {
            resetMahjongBoard()
            render()
        })
        col.addView(bar)

        val grid = GridLayout(ctx).apply { columnCount = 4 }
        mahjongBoard.forEachIndexed { idx, tile ->
            val selected = selectedTileId == tile.id
            val cell = FrameLayout(ctx).apply {
                val bg = when {
                    tile.matched -> Theme.withAlpha(Theme.card, 70)
                    selected -> Theme.withAlpha(Theme.brand, 42)
                    else -> Theme.card
                }
                val stroke = when {
                    tile.matched -> Color.TRANSPARENT
                    selected -> Theme.brand
                    else -> Theme.border
                }
                background = Theme.roundedBg(bg, Theme.dp(ctx, 12).toFloat(), stroke, Theme.dp(ctx, if (selected) 2 else 1))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = Theme.dp(ctx, 74)
                    columnSpec = GridLayout.spec(idx % 4, 1f)
                    setMargins(Theme.dp(ctx, 4), Theme.dp(ctx, 4), Theme.dp(ctx, 4), Theme.dp(ctx, 4))
                }
                if (!tile.matched) {
                    setOnClickListener { onTileTapped(tile) }
                }
            }
            if (!tile.matched) {
                cell.addView(TextView(ctx).apply {
                    text = tile.glyph
                    setTextColor(tile.suitColor)
                    textSize = 26f
                    typeface = Typeface.SERIF
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                })
            }
            grid.addView(cell)
        }
        col.addView(grid)
        return col
    }

    private fun onTileTapped(tile: Tile) {
        val prevId = selectedTileId
        if (prevId == null) {
            selectedTileId = tile.id
            render()
            return
        }
        if (prevId == tile.id) {
            selectedTileId = null
            render()
            return
        }
        val prevTile = mahjongBoard.find { it.id == prevId }
        mahjongMoves++
        if (prevTile != null && prevTile.glyph == tile.glyph) {
            prevTile.matched = true
            tile.matched = true
            mahjongMatches++
            selectedTileId = null
            if (mahjongMatches >= 12) {
                Toast.makeText(ctx, "Партия завершена за $mahjongMoves ходов", Toast.LENGTH_LONG).show()
            }
        } else {
            selectedTileId = tile.id
        }
        render()
    }
}
