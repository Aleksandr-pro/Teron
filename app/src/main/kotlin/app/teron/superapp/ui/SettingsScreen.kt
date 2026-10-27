package app.teron.superapp.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.TeronApp
import app.teron.superapp.core.TeronCrypto
import app.teron.superapp.core.Theme
import app.teron.superapp.mesh.MeshForegroundService

object SettingsScreen {

    fun build(
        ctx: Context,
        app: TeronApp,
        onBack: (() -> Unit)?,
        onThemeChanged: () -> Unit,
        onOpenSunCode: (scanTab: Boolean) -> Unit,
        onRequestAllPermissions: () -> Unit
    ): View {
        val profile = app.db.getProfile()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dp(ctx, 16)
            setPadding(p, Theme.dp(ctx, 12), p, Theme.dp(ctx, 28))
        }

        if (onBack != null) {
            col.addView(TextView(ctx).apply {
                text = "← Назад в Экосистему"
                setTextColor(Theme.accentCyan)
                textSize = 14.5f
                paint.isFakeBoldText = true
                setPadding(0, 0, 0, Theme.dp(ctx, 10))
                setOnClickListener { onBack() }
            })
        }

        // Profile Center Card (inspired by the article's Settings screenshot)
        val centerCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 22).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 20)
            setPadding(p, p, p, p)
        }
        centerCard.addView(Components.squircleIcon(ctx, profile.avatarGlyph, profile.avatarColor, 72, 32f))
        centerCard.addView(TextView(ctx).apply {
            text = profile.displayName
            setTextColor(Theme.textPrimary)
            textSize = 22f
            paint.isFakeBoldText = true
            setPadding(0, Theme.dp(ctx, 10), 0, Theme.dp(ctx, 2))
        })
        centerCard.addView(TextView(ctx).apply {
            text = "${profile.handle}  •  ${profile.nodeId}"
            setTextColor(Theme.accentCyan)
            textSize = 12.8f
        })
        if (profile.bio.isNotBlank()) {
            centerCard.addView(TextView(ctx).apply {
                text = profile.bio
                setTextColor(Theme.textSecondary)
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 6), Theme.dp(ctx, 8), 0)
            })
        }

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 14)
            }
        }
        btnRow.addView(TextView(ctx).apply {
            text = "👤 Профиль"
            setTextColor(Theme.textPrimary)
            textSize = 13.5f
            paint.isFakeBoldText = true
            background = Theme.rippleCard(ctx, Theme.cardAlt, 14f)
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 9), Theme.dp(ctx, 16), Theme.dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
            setOnClickListener {
                showEditProfileDialog(ctx, app, onThemeChanged)
            }
        })
        btnRow.addView(TextView(ctx).apply {
            text = "☀ Мой SunCode"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            paint.isFakeBoldText = true
            background = Theme.rippleButton(ctx, Theme.accentIndigo, 14f)
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 9), Theme.dp(ctx, 16), Theme.dp(ctx, 9))
            setOnClickListener { onOpenSunCode(false) }
        })
        centerCard.addView(btnRow)
        col.addView(centerCard)

        // Settings Group Card
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 20).toFloat(), Theme.border, Theme.dp(ctx, 1))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 16)
            }
        }

        fun settingRow(glyph: String, iconBg: Int, title: String, subtitle: String, onClick: () -> Unit): View {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.rippleCard(ctx, Color.TRANSPARENT, 0f, Color.TRANSPARENT, 0f)
                val px = Theme.dp(ctx, 14)
                val py = Theme.dp(ctx, 13)
                setPadding(px, py, px, py)
                addView(Components.squircleIcon(ctx, glyph, iconBg, 38, 17f))
                val mid = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                    }
                }
                mid.addView(TextView(ctx).apply {
                    text = title
                    setTextColor(Theme.textPrimary)
                    textSize = 15f
                    paint.isFakeBoldText = true
                })
                mid.addView(TextView(ctx).apply {
                    text = subtitle
                    setTextColor(Theme.textSecondary)
                    textSize = 12f
                })
                addView(mid)
                addView(TextView(ctx).apply {
                    text = "›"
                    setTextColor(Theme.textMuted)
                    textSize = 22f
                })
                setOnClickListener { onClick() }
            }
        }

        group.addView(settingRow(
            "🎨", Theme.accentPurple,
            "Внешний вид",
            if (Theme.isDark) "Тёмная тема Teron Obsidian (нажмите для смены)" else "Светлая тема Teron Pearl (нажмите для смены)"
        ) {
            val nextDark = !profile.darkTheme
            Theme.isDark = nextDark
            app.db.updateProfile(
                profile.displayName, profile.handle, profile.bio,
                profile.avatarColor, profile.avatarGlyph, profile.maxRelayHops,
                nextDark, profile.backgroundDaemon
            )
            onThemeChanged()
        })

        group.addView(settingRow(
            "📡", Theme.accentEmerald,
            "Дальность эстафеты AirMesh",
            "До ${profile.maxRelayHops} переходов (~${profile.maxRelayHops * 200} м) • Фоновый узел: ${if (profile.backgroundDaemon) "Вкл" else "Выкл"}"
        ) {
            val nextHops = if (profile.maxRelayHops >= 6) 2 else profile.maxRelayHops + 1
            val nextDaemon = !profile.backgroundDaemon
            app.db.updateProfile(
                profile.displayName, profile.handle, profile.bio,
                profile.avatarColor, profile.avatarGlyph, nextHops,
                profile.darkTheme, nextDaemon
            )
            try {
                val svcIntent = Intent(ctx, MeshForegroundService::class.java)
                if (nextDaemon) {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svcIntent) else ctx.startService(svcIntent)
                } else {
                    ctx.stopService(svcIntent)
                }
            } catch (_: Exception) {}
            Toast.makeText(ctx, "Эстафета: до $nextHops переходов • Фоновый режим: ${if (nextDaemon) "Вкл" else "Выкл"}", Toast.LENGTH_SHORT).show()
            onThemeChanged()
        })

        group.addView(settingRow(
            "🔐", Theme.accentBlue,
            "Конфиденциальность и Ключи",
            "Отпечаток ECDH: ${TeronCrypto.fingerprintHex(profile.publicKeyB64).take(23)}…"
        ) {
            val info = buildString {
                appendLine("Узел AirMesh: ${profile.nodeId}")
                appendLine("Кошелёк TeronPay: ${profile.walletAddress}")
                appendLine("Отпечаток ключа ECDH P-256:")
                appendLine(TeronCrypto.fingerprintHex(profile.publicKeyB64))
                appendLine()
                appendLine("Секретная фраза восстановления (12 слов):")
                appendLine(profile.seedPhrase)
                appendLine()
                appendLine("Все данные хранятся исключительно на устройстве без передачи на сторонние серверы.")
            }
            AlertDialog.Builder(ctx)
                .setTitle("🔐 Ключи шифрования устройства")
                .setMessage(info)
                .setPositiveButton("Скопировать ID") { _, _ ->
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Teron ID", profile.nodeId))
                    Toast.makeText(ctx, "ID узла скопирован", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Закрыть", null)
                .show()
        })

        group.addView(settingRow(
            "🛡", Theme.accentAmber,
            "Системные разрешения Android",
            "Bluetooth LE рядом, Камера SunCode, Локальная музыка"
        ) {
            onRequestAllPermissions()
        })

        group.addView(settingRow(
            "ℹ️", Theme.accentCyan,
            "О суперприложении Teron",
            "Версия 1.0.0 (Build 1) • 100% Native Kotlin APK"
        ) {
            AlertDialog.Builder(ctx)
                .setTitle("Teron SuperApp v1.0.0")
                .setMessage(
                    "Нативное Android-приложение (Kotlin + Android SDK, без WebView).\n\n" +
                        "Включает:\n" +
                        "• AirMesh — автономный мессенджер по Bluetooth LE и Wi-Fi P2P с эстафетной передачей Store & Forward и сквозным шифрованием ECDH + AES-256-GCM\n" +
                        "• SunCode — лучевые контактные коды с живым оптическим сканером Camera2\n" +
                        "• Teron Mail — автономная P2P-почта и SSL IMAP мост\n" +
                        "• Teron Sound — офлайн-плеер и полифонический синтезатор\n" +
                        "• Ханьцзы Додзё — изучение китайского по ассоциациям и каллиграфии\n" +
                        "• Маджонг Культура — пасьянс с энциклопедией символов\n" +
                        "• TeronPay — криптокошелёк с офлайн-чеками ECDSA"
                )
                .setPositiveButton("Отлично", null)
                .show()
        })

        col.addView(group)
        scroll.addView(col)
        return scroll
    }

    fun showEditProfileDialog(ctx: Context, app: TeronApp, onSaved: () -> Unit) {
        val cur = app.db.getProfile()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dp(ctx, 16)
            setPadding(p, p, p, p)
        }
        val nameEdit = EditText(ctx).apply {
            setText(cur.displayName)
            hint = "Ваше имя или позывной"
            setSingleLine(true)
        }
        val handleEdit = EditText(ctx).apply {
            setText(cur.handle)
            hint = "Почтовый адрес (напр. alex@teron.mesh)"
            setSingleLine(true)
        }
        val bioEdit = EditText(ctx).apply {
            setText(cur.bio)
            hint = "О себе / статус узла"
            setSingleLine(true)
        }
        val glyphEdit = EditText(ctx).apply {
            setText(cur.avatarGlyph)
            hint = "Символ аватара (напр. ⚡, 🐉, 🌊, 🚀)"
            setSingleLine(true)
        }
        layout.addView(nameEdit)
        layout.addView(handleEdit)
        layout.addView(bioEdit)
        layout.addView(glyphEdit)

        AlertDialog.Builder(ctx)
            .setTitle("👤 Настройка профиля Teron")
            .setView(layout)
            .setPositiveButton("Сохранить") { _, _ ->
                val rawHandle = handleEdit.text.toString().trim().let {
                    if (it.contains("@")) it else "$it@teron.mesh"
                }
                val nextColor = Theme.avatarPalette[(nameEdit.text.toString().hashCode() and 0x7FFFFFFF) % Theme.avatarPalette.size]
                app.db.updateProfile(
                    displayName = nameEdit.text.toString(),
                    handle = rawHandle,
                    bio = bioEdit.text.toString(),
                    avatarColor = nextColor,
                    avatarGlyph = glyphEdit.text.toString().trim().take(2).ifBlank { "⚡" },
                    maxRelayHops = cur.maxRelayHops,
                    darkTheme = cur.darkTheme,
                    backgroundDaemon = cur.backgroundDaemon
                )
                onSaved()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
