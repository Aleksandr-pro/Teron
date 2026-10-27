package app.teron.superapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.R
import app.teron.superapp.TeronApp
import app.teron.superapp.core.TeronCrypto
import app.teron.superapp.core.Theme
import app.teron.superapp.mesh.MeshForegroundService
import java.io.File
import java.util.Locale

class SettingsScreen(
    private val ctx: Context,
    private val app: TeronApp,
    private val onThemeChanged: () -> Unit,
    private val onOpenSunCode: () -> Unit,
    private val onOpenWalletBackup: () -> Unit,
    private val onResetToOnboarding: () -> Unit
) {
    private var rootContainer: LinearLayout? = null

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
        val me = app.db.getProfile()
        val mailCfg = app.db.getMailAccountConfig()

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 6), Theme.dp(ctx, 16), Theme.dp(ctx, 28))
        }

        // Profile Summary Card
        val profileCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rippleCard(ctx, Theme.card, 16f, Theme.border, 1f)
            val p = Theme.dp(ctx, 16)
            setPadding(p, p, p, p)
            setOnClickListener { showEditProfileSheet() }
        }
        profileCard.addView(Components.monogramAvatar(ctx, me.displayName, me.avatarColor, 52, 18f))

        val pInfo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 14)
                rightMargin = Theme.dp(ctx, 8)
            }
        }
        pInfo.addView(TextView(ctx).apply {
            text = me.displayName
            setTextColor(Theme.textPrimary)
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        pInfo.addView(TextView(ctx).apply {
            text = "${me.handle} • ${me.nodeId}"
            setTextColor(Theme.brandText)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, Theme.dp(ctx, 2), 0, Theme.dp(ctx, 2))
        })
        pInfo.addView(TextView(ctx).apply {
            text = me.bio.ifBlank { "Нажмите для редактирования профиля" }
            setTextColor(Theme.textSecondary)
            textSize = 12f
            maxLines = 1
        })
        profileCard.addView(pInfo)
        profileCard.addView(Components.solidIconButton(ctx, R.drawable.ic_suncode, Theme.cardAlt, Theme.brandText, 38, 18, 10f) {
            onOpenSunCode()
        })
        col.addView(profileCard)

        // SECTION 1: Identity & Cryptography
        col.addView(Components.sectionHeader(ctx, "Безопасность и ключи"))
        val secGroup = groupCard()
        secGroup.addView(settingRow(
            iconRes = R.drawable.ic_key,
            iconColor = Theme.accentAmber,
            title = "Секретная фраза кошелька (BIP-39)",
            subtitle = "BTC: ${Theme.shortAddress(me.btcAddress, 6, 4)} • ETH: ${Theme.shortAddress(me.walletAddress, 6, 4)}",
            onClick = { onOpenWalletBackup() }
        ))
        secGroup.addView(Components.divider(ctx, 58))
        secGroup.addView(settingRow(
            iconRes = R.drawable.ic_shield,
            iconColor = Theme.accentEmerald,
            title = "Ключ шифрования ECDH P-256",
            subtitle = TeronCrypto.fingerprintHex(me.publicKeyB64),
            onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ECDH Public Key", me.publicKeyB64))
                Toast.makeText(ctx, "Публичный ключ скопирован", Toast.LENGTH_SHORT).show()
            }
        ))
        secGroup.addView(Components.divider(ctx, 58))
        secGroup.addView(settingRow(
            iconRes = R.drawable.ic_suncode,
            iconColor = Theme.brandText,
            title = "Карточка контакта SunCode и QR",
            subtitle = "Поделиться профилем или отсканировать собеседника",
            onClick = { onOpenSunCode() }
        ))
        col.addView(secGroup)

        // SECTION 2: Network & Mail
        col.addView(Components.sectionHeader(ctx, "Сеть AirMesh и Почта"))
        val netGroup = groupCard()
        val localIp = app.meshEngine.getLocalIpv4()
        netGroup.addView(settingRow(
            iconRes = R.drawable.ic_mesh,
            iconColor = Theme.brandText,
            title = "Ретрансляция пакетов AirMesh",
            subtitle = "До ${me.maxRelayHops} переходов • Узел: ${localIp.ifBlank { "офлайн" }}",
            onClick = {
                val next = if (me.maxRelayHops >= 6) 2 else me.maxRelayHops + 1
                app.db.updateProfile(
                    me.displayName, me.handle, me.bio, me.avatarColor, me.avatarGlyph,
                    next, me.darkTheme, me.backgroundDaemon
                )
                render()
            }
        ))
        netGroup.addView(Components.divider(ctx, 58))

        // Background daemon toggle
        val daemonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Theme.dp(ctx, 14)
            setPadding(p, p, p, p)
        }
        daemonRow.addView(Components.tonalIconBadge(ctx, R.drawable.ic_radar, Theme.accentEmerald, 36, 18, 10f))
        val dCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 12)
            }
        }
        dCol.addView(TextView(ctx).apply {
            text = "Фоновая служба ретрансляции"
            setTextColor(Theme.textPrimary)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        dCol.addView(TextView(ctx).apply {
            text = "Поддерживать приём сообщений при выключенном экране"
            setTextColor(Theme.textSecondary)
            textSize = 12f
        })
        daemonRow.addView(dCol)
        daemonRow.addView(Switch(ctx).apply {
            isChecked = me.backgroundDaemon
            setOnCheckedChangeListener { _, checked ->
                app.db.updateProfile(
                    me.displayName, me.handle, me.bio, me.avatarColor, me.avatarGlyph,
                    me.maxRelayHops, me.darkTheme, checked
                )
                val svc = Intent(ctx, MeshForegroundService::class.java)
                try {
                    if (checked) {
                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
                    } else {
                        ctx.stopService(svc)
                    }
                } catch (_: Throwable) {}
            }
        })
        netGroup.addView(daemonRow)
        netGroup.addView(Components.divider(ctx, 58))

        netGroup.addView(settingRow(
            iconRes = R.drawable.ic_server,
            iconColor = if (mailCfg.isConfigured) Theme.accentEmerald else Theme.textSecondary,
            title = "Внешняя почта IMAP / SMTP",
            subtitle = if (mailCfg.isConfigured) "${mailCfg.email} (${mailCfg.imapHost})" else "Не подключена (настраивается во вкладке Почта)",
            onClick = {
                Toast.makeText(ctx, "Откройте вкладку «Почта» → значок сервера для настройки IMAP/SMTP", Toast.LENGTH_LONG).show()
            }
        ))
        col.addView(netGroup)

        // SECTION 3: Appearance & Storage
        col.addView(Components.sectionHeader(ctx, "Интерфейс и хранилище"))
        val sysGroup = groupCard()

        val themeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Theme.dp(ctx, 14)
            setPadding(p, p, p, p)
        }
        themeRow.addView(Components.tonalIconBadge(ctx, if (Theme.isDark) R.drawable.ic_moon else R.drawable.ic_sun, Theme.brandText, 36, 18, 10f))
        val thText = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 12)
            }
        }
        thText.addView(TextView(ctx).apply {
            text = "Тёмная тема оформления"
            setTextColor(Theme.textPrimary)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        thText.addView(TextView(ctx).apply {
            text = if (Theme.isDark) "Graphite Carbon (#0B0D11)" else "Pure Slate (#F7F8FA)"
            setTextColor(Theme.textSecondary)
            textSize = 12f
        })
        themeRow.addView(thText)
        themeRow.addView(Switch(ctx).apply {
            isChecked = Theme.isDark
            setOnCheckedChangeListener { _, checked ->
                Theme.isDark = checked
                app.db.updateProfile(
                    me.displayName, me.handle, me.bio, me.avatarColor, me.avatarGlyph,
                    me.maxRelayHops, checked, me.backgroundDaemon
                )
                onThemeChanged()
            }
        })
        sysGroup.addView(themeRow)
        sysGroup.addView(Components.divider(ctx, 58))

        val dbFile = ctx.getDatabasePath("teron_superapp.db")
        val dbKb = if (dbFile.exists()) dbFile.length() / 1024.0 else 0.0
        val convCount = app.db.getConversations(app.meshEngine.livePeers).size
        sysGroup.addView(settingRow(
            iconRes = R.drawable.ic_database,
            iconColor = Theme.textSecondary,
            title = "Локальная база данных SQLite",
            subtitle = String.format(Locale.US, "%.1f КБ • Чатов: %d • Контактов: %d", dbKb, convCount, app.db.getContacts().size),
            onClick = {
                app.db.writableDatabase.delete("mesh_relay_queue", null, null)
                Toast.makeText(ctx, "Очередь ретрансляции очищена", Toast.LENGTH_SHORT).show()
                render()
            }
        ))
        sysGroup.addView(Components.divider(ctx, 58))
        sysGroup.addView(settingRow(
            iconRes = R.drawable.ic_trash,
            iconColor = Theme.accentCoral,
            title = "Сбросить профиль и данные на устройстве",
            subtitle = "Удалить локальную базу и перейти к начальной настройке",
            onClick = { showResetConfirmSheet() }
        ))
        col.addView(sysGroup)

        col.addView(TextView(ctx).apply {
            text = "Teron v2.0.0 • Native Kotlin • Non-Custodial BIP-39 & AirMesh"
            setTextColor(Theme.textMuted)
            textSize = 11.5f
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(ctx, 20), 0, Theme.dp(ctx, 6))
        })

        scroll.addView(col)
        root.addView(scroll)
    }

    private fun groupCard(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
    }

    private fun settingRow(
        iconRes: Int,
        iconColor: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rippleSurfaceRow(ctx)
            val p = Theme.dp(ctx, 14)
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }
        row.addView(Components.tonalIconBadge(ctx, iconRes, iconColor, 36, 18, 10f))
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 12)
                rightMargin = Theme.dp(ctx, 8)
            }
        }
        textCol.addView(TextView(ctx).apply {
            text = title
            setTextColor(Theme.textPrimary)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        textCol.addView(TextView(ctx).apply {
            text = subtitle
            setTextColor(Theme.textSecondary)
            textSize = 12f
            maxLines = 1
        })
        row.addView(textCol)
        row.addView(Components.icon(ctx, R.drawable.ic_chevron_right, 16, Theme.textMuted))
        return row
    }

    private fun showEditProfileSheet() {
        val me = app.db.getProfile()
        var chosenColor = me.avatarColor
        TeronSheets.showBottomSheet(ctx, "Редактирование профиля", "Имя, адрес @teron.mesh и цвет монограммы") { dlg, col ->
            val nameField = Components.labeledInput(ctx, "Отображаемое имя", "Ваше имя", me.displayName)
            val handlePrefix = me.handle.substringBefore("@")
            val handleField = Components.labeledInput(ctx, "Логин в сети Teron", "username", handlePrefix)
            val bioField = Components.labeledInput(ctx, "О себе / статус", "Краткая подпись", me.bio)

            col.addView(nameField.container)
            col.addView(handleField.container)
            col.addView(bioField.container)

            val paletteRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 16)
                }
            }
            for (c in Theme.avatarPalette) {
                val dot = View(ctx).apply {
                    val sz = Theme.dp(ctx, 30)
                    background = Theme.circleBg(c, if (c == chosenColor) Color.WHITE else 0, Theme.dp(ctx, 2))
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply { rightMargin = Theme.dp(ctx, 10) }
                    setOnClickListener { chosenColor = c }
                }
                paletteRow.addView(dot)
            }
            col.addView(paletteRow)

            col.addView(Components.primaryButton(ctx, "Сохранить изменения", R.drawable.ic_check) {
                val n = nameField.input.text.toString().trim().ifBlank { me.displayName }
                val cleanH = handleField.input.text.toString().trim().lowercase(Locale.ROOT)
                    .replace(Regex("[^a-z0-9._-]"), "").ifBlank { "user" }
                val b = bioField.input.text.toString().trim()
                val mono = Theme.initialsFor(n)
                app.db.updateProfile(
                    n, "$cleanH@teron.mesh", b, chosenColor, mono,
                    me.maxRelayHops, me.darkTheme, me.backgroundDaemon
                )
                dlg.dismiss()
                render()
            })
        }
    }

    private fun showResetConfirmSheet() {
        TeronSheets.showBottomSheet(
            ctx,
            "Сбросить локальные данные?",
            "Убедитесь, что вы сохранили секретную BIP-39 фразу из 12 слов — без неё доступ к кошельку будет утрачен."
        ) { dlg, col ->
            col.addView(Components.primaryButton(ctx, "Подтвердить полный сброс", R.drawable.ic_trash, Theme.accentCoral) {
                val dbPath = File(ctx.getDatabasePath("teron_superapp.db").absolutePath)
                app.db.close()
                dbPath.delete()
                File("${dbPath.absolutePath}-journal").delete()
                File("${dbPath.absolutePath}-wal").delete()
                dlg.dismiss()
                onResetToOnboarding()
            })
        }
    }
}
