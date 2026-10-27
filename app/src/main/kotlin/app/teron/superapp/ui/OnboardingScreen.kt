package app.teron.superapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.R
import app.teron.superapp.TeronApp
import app.teron.superapp.core.Theme
import app.teron.superapp.crypto.Secp256k1Wallet

/**
 * First-launch real account & non-custodial BIP-39 multi-chain wallet setup / restoration screen.
 */
class OnboardingScreen(
    private val ctx: Context,
    private val app: TeronApp,
    private val onCompleted: () -> Unit
) {
    private var importMode: Boolean = false
    private var generatedMnemonic: String = app.db.getProfile().seedPhrase.let {
        if (Secp256k1Wallet.validateMnemonic(it)) it else Secp256k1Wallet.generateMnemonic12()
    }
    private var draftName: String = ""
    private var draftHandle: String = ""
    private var draftPhone: String = ""
    private var draftBio: String = ""
    private var draftImportSeed: String = ""

    private var rootContainer: LinearLayout? = null

    fun build(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        rootContainer = root
        render()
        return root
    }

    private fun render() {
        val root = rootContainer ?: return
        root.removeAllViews()

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val px = Theme.dp(ctx, 20)
            setPadding(px, Theme.dp(ctx, 26), px, Theme.dp(ctx, 28))
        }

        // Brand Identity Header
        val brandRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(Components.tonalIconBadge(ctx, R.drawable.ic_shield, Theme.brandText, 44, 22, 12f))
        val brandTextCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 12)
            }
        }
        brandTextCol.addView(TextView(ctx).apply {
            text = "Teron"
            setTextColor(Theme.textPrimary)
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        brandTextCol.addView(TextView(ctx).apply {
            text = "Автономная связь • Почта • Мультисетевой кошелёк"
            setTextColor(Theme.textSecondary)
            textSize = 12.5f
        })
        brandRow.addView(brandTextCol)
        col.addView(brandRow)

        // Segmented Control: Create vs Restore Seed
        val seg = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 12).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 4)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 22)
                bottomMargin = Theme.dp(ctx, 18)
            }
        }

        fun segTab(title: String, active: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
            text = title
            gravity = Gravity.CENTER
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (active) Color.WHITE else Theme.textSecondary)
            background = if (active) Theme.roundedBg(Theme.brand, Theme.dp(ctx, 9).toFloat()) else null
            setPadding(0, Theme.dp(ctx, 10), 0, Theme.dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

        val nameField = Components.labeledInput(ctx, "Ваше имя", "Например: Александр", draftName)
        val handleField = Components.labeledInput(ctx, "Ник в сети Teron (@handle)", "Например: aleksandr", draftHandle)
        val phoneField = Components.labeledInput(
            ctx = ctx,
            label = "Номер телефона (для синхронизации контактов)",
            hintText = "+7 777 000 00 00 (хешируется SHA-256)",
            initialValue = draftPhone,
            inputType = InputType.TYPE_CLASS_PHONE
        )
        val bioField = Components.labeledInput(ctx, "О себе (необязательно)", "Должность, город или статус", draftBio)

        seg.addView(segTab("Новый аккаунт", !importMode) {
            draftName = nameField.input.text.toString()
            draftHandle = handleField.input.text.toString()
            draftPhone = phoneField.input.text.toString()
            draftBio = bioField.input.text.toString()
            importMode = false
            render()
        })
        seg.addView(segTab("Восстановить по Seed", importMode) {
            draftName = nameField.input.text.toString()
            draftHandle = handleField.input.text.toString()
            draftPhone = phoneField.input.text.toString()
            draftBio = bioField.input.text.toString()
            importMode = true
            render()
        })
        col.addView(seg)

        // Profile Form Card
        val profileCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 16)
            setPadding(p, p, p, Theme.dp(ctx, 6))
        }
        profileCard.addView(nameField.container)
        profileCard.addView(handleField.container)
        profileCard.addView(phoneField.container)
        profileCard.addView(bioField.container)
        col.addView(profileCard)

        // Crypto Wallet Section (BIP-39)
        col.addView(Components.sectionHeader(ctx, "Некастодиальный криптокошелёк (BIP-39)"))

        val seedInputField = Components.labeledInput(
            ctx = ctx,
            label = "Секретная фраза (12 или 24 слова на английском)",
            hintText = "Введите слова через пробел (совместимо с Trust Wallet, MetaMask, Electrum)…",
            initialValue = draftImportSeed,
            singleLine = false,
            minLinesCount = 3
        )

        if (!importMode) {
            val walletCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 16)
                setPadding(p, p, p, p)
            }
            walletCard.addView(TextView(ctx).apply {
                text = "Ваша персональная фраза восстановления (12 слов BIP-39)"
                setTextColor(Theme.textPrimary)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            walletCard.addView(TextView(ctx).apply {
                text = "Сохраните её в надёжном месте. Ключи генерируются локально на вашем устройстве и подходят к Bitcoin, Ethereum и TRON."
                setTextColor(Theme.textSecondary)
                textSize = 12f
                setPadding(0, Theme.dp(ctx, 3), 0, Theme.dp(ctx, 12))
            })

            // 12-word 2-column grid
            val words = generatedMnemonic.split(' ')
            val grid = GridLayout(ctx).apply {
                columnCount = 2
                rowCount = 6
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            words.forEachIndexed { idx, w ->
                val pill = TextView(ctx).apply {
                    text = "${idx + 1}.  $w"
                    setTextColor(Theme.textPrimary)
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    background = Theme.roundedBg(Theme.cardAlt, Theme.dp(ctx, 8).toFloat(), Theme.border, Theme.dp(ctx, 1))
                    setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 7), Theme.dp(ctx, 10), Theme.dp(ctx, 7))
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(idx % 2, 1f)
                        val m = Theme.dp(ctx, 3)
                        setMargins(m, m, m, m)
                    }
                }
                grid.addView(pill)
            }
            walletCard.addView(grid)

            // Preview of real derived blockchain addresses
            val derived = Secp256k1Wallet.deriveMultiChain(generatedMnemonic)
            walletCard.addView(TextView(ctx).apply {
                text = "ETH / ERC-20: ${Theme.shortAddress(derived.ethAddress, 10, 8)}\n" +
                    "BTC SegWit:   ${Theme.shortAddress(derived.btcAddress, 10, 8)}\n" +
                    "TRON TRC-20:  ${Theme.shortAddress(derived.trxAddress, 10, 8)}"
                setTextColor(Theme.textSecondary)
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setLineSpacing(Theme.dp(ctx, 2).toFloat(), 1.05f)
                setPadding(0, Theme.dp(ctx, 10), 0, Theme.dp(ctx, 10))
            })

            val seedBtns = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            seedBtns.addView(Components.secondaryButton(ctx, "Скопировать", R.drawable.ic_copy) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("BIP39 Seed", generatedMnemonic))
                Toast.makeText(ctx, "Seed-фраза скопирована в буфер обмена", Toast.LENGTH_SHORT).show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = Theme.dp(ctx, 8)
                }
            })
            seedBtns.addView(Components.secondaryButton(ctx, "Новая фраза", R.drawable.ic_refresh) {
                draftName = nameField.input.text.toString()
                draftHandle = handleField.input.text.toString()
                draftPhone = phoneField.input.text.toString()
                draftBio = bioField.input.text.toString()
                generatedMnemonic = Secp256k1Wallet.generateMnemonic12()
                render()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            walletCard.addView(seedBtns)
            col.addView(walletCard)
        } else {
            val restoreCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 16)
                setPadding(p, p, p, Theme.dp(ctx, 8))
            }
            restoreCard.addView(seedInputField.container)
            col.addView(restoreCard)
        }

        // Complete CTA
        val cta = Components.primaryButton(
            ctx = ctx,
            label = if (importMode) "Восстановить аккаунт и кошелёк" else "Создать аккаунт и продолжить",
            iconRes = R.drawable.ic_check
        ) {
            val enteredName = nameField.input.text.toString().trim()
            if (enteredName.isEmpty()) {
                Toast.makeText(ctx, "Пожалуйста, укажите ваше имя", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            val chosenSeed = if (importMode) {
                val raw = seedInputField.input.text.toString()
                if (!Secp256k1Wallet.validateMnemonic(raw)) {
                    Toast.makeText(ctx, "Проверьте BIP-39 фразу: должно быть 12 или 24 корректных слова с верной контрольной суммой", Toast.LENGTH_LONG).show()
                    return@primaryButton
                }
                Secp256k1Wallet.normalizeMnemonic(raw)
            } else {
                generatedMnemonic
            }

            app.db.completeOnboarding(
                displayName = enteredName,
                handleInput = handleField.input.text.toString(),
                bio = bioField.input.text.toString(),
                mnemonicOrNull = chosenSeed,
                phoneNumber = phoneField.input.text.toString()
            )
            app.cloudSync.syncOwnProfileAsync()
            app.walletEngine.refreshAllChains(false)
            onCompleted()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 20)
            }
        }
        col.addView(cta)

        scroll.addView(col)
        root.addView(scroll)
    }
}
