package app.teron.superapp.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.R
import app.teron.superapp.TeronApp
import app.teron.superapp.core.MailAccountConfig
import app.teron.superapp.core.MailItem
import app.teron.superapp.core.Theme
import app.teron.superapp.mail.ImapSmtpClient
import java.util.concurrent.Executors

class MailScreen(
    private val ctx: Context,
    private val app: TeronApp
) {
    private var currentFolder: String = "INBOX"
    private var searchQuery: String = ""
    private var openedMail: MailItem? = null
    private var isSyncingImap: Boolean = false
    private var rootContainer: LinearLayout? = null
    private val netPool = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun handleBack(): Boolean {
        if (openedMail != null) {
            openedMail = null
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
        val mail = openedMail
        if (mail != null) {
            root.addView(buildMailReader(mail))
        } else {
            root.addView(buildMailboxList())
        }
    }

    private fun buildMailboxList(): View {
        val me = app.db.getProfile()
        val acct = app.db.getMailAccountConfig()
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Top Bar
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 14), Theme.dp(ctx, 16), Theme.dp(ctx, 8))
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = "Почта"
            setTextColor(Theme.textPrimary)
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titleCol.addView(TextView(ctx).apply {
            text = if (acct.isConfigured) "${acct.email} • ${me.handle}" else "${me.handle} • Автономный режим"
            setTextColor(Theme.textSecondary)
            textSize = 12f
            maxLines = 1
        })
        top.addView(titleCol)

        if (acct.isConfigured) {
            top.addView(Components.solidIconButton(ctx, R.drawable.ic_refresh, Theme.cardAlt, Theme.textPrimary, 38, 17, 10f) {
                triggerImapSync()
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = Theme.dp(ctx, 8)
            })
        }

        top.addView(Components.solidIconButton(ctx, R.drawable.ic_server, Theme.cardAlt, if (acct.isConfigured) Theme.accentEmerald else Theme.textPrimary, 38, 17, 10f) {
            showAccountSetupSheet()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = Theme.dp(ctx, 8)
        })

        top.addView(Components.primaryButton(ctx, "Написать", R.drawable.ic_edit) {
            showComposeSheet()
        })
        wrapper.addView(top)

        // Search Bar
        val searchBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 12).toFloat(), Theme.border, Theme.dp(ctx, 1))
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 2), Theme.dp(ctx, 8), Theme.dp(ctx, 2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Theme.dp(ctx, 16)
                rightMargin = Theme.dp(ctx, 16)
                topMargin = Theme.dp(ctx, 4)
                bottomMargin = Theme.dp(ctx, 8)
            }
        }
        searchBox.addView(Components.icon(ctx, R.drawable.ic_search, 16, Theme.textMuted))
        val searchEdit = EditText(ctx).apply {
            setText(searchQuery)
            hint = "Поиск по теме, отправителю или тексту…"
            setHintTextColor(Theme.textMuted)
            setTextColor(Theme.textPrimary)
            textSize = 13.5f
            setSingleLine(true)
            background = null
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 9), Theme.dp(ctx, 8), Theme.dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBox.addView(searchEdit)
        searchBox.addView(TextView(ctx).apply {
            text = if (searchQuery.isNotEmpty()) "Сброс" else "Найти"
            setTextColor(Theme.brandText)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 6), Theme.dp(ctx, 6), Theme.dp(ctx, 6))
            setOnClickListener {
                searchQuery = if (searchQuery.isNotEmpty() && searchEdit.text.toString() == searchQuery) "" else searchEdit.text.toString()
                render()
            }
        })
        wrapper.addView(searchBox)

        // Folder Chips
        val folderScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 2), Theme.dp(ctx, 16), Theme.dp(ctx, 8))
        }
        val folderRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        data class FolderSpec(val code: String, val label: String, val iconRes: Int)
        val folders = listOf(
            FolderSpec("INBOX", "Входящие", R.drawable.ic_inbox),
            FolderSpec("SENT", "Отправленные", R.drawable.ic_send),
            FolderSpec("OUTBOX_MESH", "Очередь AirMesh", R.drawable.ic_clock),
            FolderSpec("STARRED", "Отмеченные", R.drawable.ic_star)
        )
        for (f in folders) {
            folderRow.addView(Components.actionChip(ctx, f.label, currentFolder == f.code, f.iconRes) {
                currentFolder = f.code
                render()
            })
        }
        folderScroll.addView(folderRow)
        wrapper.addView(folderScroll)

        if (isSyncingImap) {
            wrapper.addView(TextView(ctx).apply {
                text = "Синхронизация входящих писем по TLS/SSL…"
                setTextColor(Theme.brandText)
                textSize = 12f
                setPadding(Theme.dp(ctx, 18), 0, Theme.dp(ctx, 18), Theme.dp(ctx, 6))
            })
        }

        // Mail List
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 20))
        }

        val items = app.db.getMails(currentFolder, searchQuery)
        if (items.isEmpty()) {
            if (!acct.isConfigured && currentFolder == "INBOX") {
                listCol.addView(Components.emptyStateCard(
                    ctx = ctx,
                    iconRes = R.drawable.ic_mail,
                    title = "Почтовый ящик готов к работе",
                    subtitle = "Подключите ваш реальный почтовый аккаунт (Gmail, Яндекс, Mail.ru, корпоративный IMAP/SMTP) или отправляйте зашифрованные письма напрямую через AirMesh.",
                    ctaLabel = "Подключить почту IMAP/SMTP",
                    onCtaClick = { showAccountSetupSheet() }
                ))
            } else {
                listCol.addView(Components.emptyStateCard(
                    ctx = ctx,
                    iconRes = R.drawable.ic_inbox,
                    title = "В этой папке нет писем",
                    subtitle = if (acct.isConfigured) "Нажмите кнопку обновления сверху для загрузки новых писем с сервера ${acct.imapHost}."
                    else "Отправленные и входящие письма появятся здесь."
                ))
            }
        } else {
            val cardGroup = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            }
            items.forEachIndexed { idx, item ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    background = Theme.rippleSurfaceRow(ctx)
                    val p = Theme.dp(ctx, 14)
                    setPadding(p, p, p, p)
                    setOnClickListener {
                        app.db.markMailRead(item.id)
                        openedMail = item.copy(isRead = true)
                        render()
                    }
                }

                val senderDisplay = if (item.folder == "SENT" || item.folder == "OUTBOX_MESH") item.toAddress else item.fromName
                row.addView(Components.monogramAvatar(ctx, senderDisplay, Theme.avatarPalette[(senderDisplay.hashCode() and 0x7FFFFFFF) % Theme.avatarPalette.size], 40, 13.5f).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = Theme.dp(ctx, 2)
                })

                val mid = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                    }
                }
                val r1 = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (!item.isRead && item.folder == "INBOX") {
                    r1.addView(View(ctx).apply {
                        background = Theme.circleBg(Theme.brand)
                        val s = Theme.dp(ctx, 7)
                        layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = Theme.dp(ctx, 6) }
                    })
                }
                r1.addView(TextView(ctx).apply {
                    text = if (item.folder == "SENT" || item.folder == "OUTBOX_MESH") "Кому: ${item.toAddress}" else item.fromName
                    setTextColor(Theme.textPrimary)
                    textSize = 14.5f
                    typeface = Typeface.create(if (!item.isRead) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                r1.addView(TextView(ctx).apply {
                    text = Theme.formatTime(item.timestampMs)
                    setTextColor(Theme.textMuted)
                    textSize = 11f
                    fontFeatureSettings = "tnum"
                })
                mid.addView(r1)

                mid.addView(TextView(ctx).apply {
                    text = item.subject
                    setTextColor(Theme.textPrimary)
                    textSize = 13.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                    setPadding(0, Theme.dp(ctx, 2), 0, Theme.dp(ctx, 2))
                })
                mid.addView(TextView(ctx).apply {
                    text = item.body.replace("\n", " ").trim()
                    setTextColor(Theme.textSecondary)
                    textSize = 12.5f
                    maxLines = 2
                })
                row.addView(mid)
                cardGroup.addView(row)
                if (idx < items.lastIndex) cardGroup.addView(Components.divider(ctx, 66))
            }
            listCol.addView(cardGroup)
        }

        scroll.addView(listCol)
        wrapper.addView(scroll)
        return wrapper
    }

    private fun buildMailReader(mail: MailItem): View {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.bgElevated, 0f, Theme.border, Theme.dp(ctx, 1))
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 10), Theme.dp(ctx, 12), Theme.dp(ctx, 10))
        }
        bar.addView(Components.solidIconButton(ctx, R.drawable.ic_arrow_left, Theme.cardAlt, Theme.textPrimary, 38, 18, 10f) {
            openedMail = null
            render()
        })
        bar.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

        bar.addView(Components.solidIconButton(
            ctx,
            if (mail.isStarred) R.drawable.ic_star_filled else R.drawable.ic_star,
            Theme.cardAlt,
            if (mail.isStarred) Theme.accentAmber else Theme.textSecondary,
            38, 18, 10f
        ) {
            val next = !mail.isStarred
            app.db.toggleMailStar(mail.id, next)
            openedMail = mail.copy(isStarred = next)
            render()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = Theme.dp(ctx, 8)
        })

        bar.addView(Components.solidIconButton(ctx, R.drawable.ic_trash, Theme.cardAlt, Theme.accentCoral, 38, 18, 10f) {
            app.db.deleteMail(mail.id)
            openedMail = null
            render()
        })
        wrapper.addView(bar)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dp(ctx, 18)
            setPadding(p, p, p, p)
        }

        col.addView(TextView(ctx).apply {
            text = mail.subject
            setTextColor(Theme.textPrimary)
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        val metaCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 12).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 12)
                bottomMargin = Theme.dp(ctx, 14)
            }
        }
        metaCard.addView(TextView(ctx).apply {
            text = "От: ${mail.fromName} <${mail.fromAddress}>"
            setTextColor(Theme.textPrimary)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        metaCard.addView(TextView(ctx).apply {
            text = "Кому: ${mail.toAddress} • ${Theme.formatDateTime(mail.timestampMs)}"
            setTextColor(Theme.textSecondary)
            textSize = 12f
            setPadding(0, Theme.dp(ctx, 3), 0, Theme.dp(ctx, 3))
        })
        metaCard.addView(TextView(ctx).apply {
            text = if (mail.viaAirMesh) "Канал: Автономная сеть AirMesh (AES-256-GCM, переходов: ${mail.hopCount})"
            else "Канал: Защищённый почтовый сервер TLS/SSL"
            setTextColor(Theme.accentEmerald)
            textSize = 11.5f
        })
        col.addView(metaCard)

        col.addView(TextView(ctx).apply {
            text = mail.body
            setTextColor(Theme.textPrimary)
            textSize = 14.5f
            setLineSpacing(Theme.dp(ctx, 3).toFloat(), 1.08f)
        })

        col.addView(Components.primaryButton(ctx, "Ответить", R.drawable.ic_send) {
            showComposeSheet(prefillTo = mail.fromAddress, prefillSubject = if (mail.subject.startsWith("Re:", true)) mail.subject else "Re: ${mail.subject}")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 22)
            }
        })

        scroll.addView(col)
        wrapper.addView(scroll)
        return wrapper
    }

    private fun triggerImapSync() {
        val cfg = app.db.getMailAccountConfig()
        if (!cfg.isConfigured) {
            showAccountSetupSheet()
            return
        }
        if (isSyncingImap) return
        isSyncingImap = true
        render()
        netPool.execute {
            val (_, msg) = ImapSmtpClient.syncInbox(cfg, app.db)
            mainHandler.post {
                isSyncingImap = false
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun showComposeSheet(prefillTo: String = "", prefillSubject: String = "") {
        val acct = app.db.getMailAccountConfig()
        val subtitle = if (acct.isConfigured) {
            "Адреса @teron.mesh — через AirMesh, внешние email — через SMTP (${acct.email})"
        } else {
            "Автономная отправка AirMesh (подключите IMAP/SMTP для внешних email)"
        }

        TeronSheets.showBottomSheet(ctx, "Новое письмо", subtitle) { dlg, col ->
            val toField = Components.labeledInput(ctx, "Кому", "user@example.com или login@teron.mesh", prefillTo)
            val subjField = Components.labeledInput(ctx, "Тема", "Тема сообщения", prefillSubject)
            val bodyField = Components.labeledInput(ctx, "Сообщение", "Текст письма…", "", singleLine = false, minLinesCount = 5)

            col.addView(toField.container)
            col.addView(subjField.container)
            col.addView(bodyField.container)

            col.addView(Components.primaryButton(ctx, "Отправить", R.drawable.ic_send) {
                val to = toField.input.text.toString().trim()
                val subj = subjField.input.text.toString().trim().ifBlank { "(Без темы)" }
                val body = bodyField.input.text.toString().trim()
                if (to.isEmpty() || body.isEmpty()) {
                    Toast.makeText(ctx, "Заполните получателя и текст письма", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }

                val isMeshTarget = to.endsWith("@teron.mesh", ignoreCase = true) || to.startsWith("TRN-", ignoreCase = true) || !to.contains("@")
                if (isMeshTarget) {
                    app.meshEngine.sendAirMeshMail(to, "", subj, body, "NORMAL")
                    Toast.makeText(ctx, "Письмо зашифровано и передано в AirMesh", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    render()
                } else {
                    if (!acct.isConfigured) {
                        Toast.makeText(ctx, "Для отправки на внешний адрес $to подключите ваш SMTP-аккаунт", Toast.LENGTH_LONG).show()
                        dlg.dismiss()
                        showAccountSetupSheet()
                        return@primaryButton
                    }
                    dlg.dismiss()
                    Toast.makeText(ctx, "Отправка через SMTP (${acct.smtpHost})…", Toast.LENGTH_SHORT).show()
                    netPool.execute {
                        val (_, resMsg) = ImapSmtpClient.sendEmail(acct, to, subj, body, app.db)
                        mainHandler.post {
                            Toast.makeText(ctx, resMsg, Toast.LENGTH_LONG).show()
                            render()
                        }
                    }
                }
            })
        }
    }

    private fun showAccountSetupSheet() {
        val cur = app.db.getMailAccountConfig()
        TeronSheets.showBottomSheet(
            ctx = ctx,
            title = "Почтовый аккаунт IMAP / SMTP",
            subtitle = "Gmail, Яндекс, Mail.ru, iCloud или корпоративный сервер (используйте пароль приложения)"
        ) { dlg, col ->
            val emailField = Components.labeledInput(ctx, "Email адрес", "name@yandex.ru", cur.email)
            val passField = Components.labeledInput(ctx, "Пароль / Пароль приложения", "Пароль для внешних почтовых программ", cur.password, isPassword = true)
            val nameField = Components.labeledInput(ctx, "Имя отправителя", app.db.getProfile().displayName, cur.senderName)
            val imapHostField = Components.labeledInput(ctx, "IMAP сервер (SSL)", "imap.yandex.ru", cur.imapHost)
            val imapPortField = Components.labeledInput(ctx, "IMAP порт", "993", cur.imapPort.toString())
            val smtpHostField = Components.labeledInput(ctx, "SMTP сервер (SSL/STARTTLS)", "smtp.yandex.ru", cur.smtpHost)
            val smtpPortField = Components.labeledInput(ctx, "SMTP порт (465 или 587)", "465", cur.smtpPort.toString())

            // Auto-fill IMAP/SMTP hosts when typing email domain
            emailField.input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val preset = ImapSmtpClient.suggestServers(s?.toString().orEmpty())
                    if (preset.imapHost.isNotEmpty()) {
                        imapHostField.input.setText(preset.imapHost)
                        imapPortField.input.setText(preset.imapPort.toString())
                        smtpHostField.input.setText(preset.smtpHost)
                        smtpPortField.input.setText(preset.smtpPort.toString())
                    }
                }
            })

            col.addView(emailField.container)
            col.addView(passField.container)
            col.addView(nameField.container)
            col.addView(imapHostField.container)
            col.addView(imapPortField.container)
            col.addView(smtpHostField.container)
            col.addView(smtpPortField.container)

            col.addView(Components.primaryButton(ctx, "Сохранить и синхронизировать", R.drawable.ic_check) {
                val cfg = MailAccountConfig(
                    email = emailField.input.text.toString().trim(),
                    password = passField.input.text.toString(),
                    senderName = nameField.input.text.toString().trim(),
                    imapHost = imapHostField.input.text.toString().trim(),
                    imapPort = imapPortField.input.text.toString().trim().toIntOrNull() ?: 993,
                    smtpHost = smtpHostField.input.text.toString().trim(),
                    smtpPort = smtpPortField.input.text.toString().trim().toIntOrNull() ?: 465
                )
                if (!cfg.isConfigured) {
                    Toast.makeText(ctx, "Заполните email, пароль и адреса серверов", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                app.db.saveMailAccountConfig(cfg)
                dlg.dismiss()
                triggerImapSync()
            })

            if (cur.isConfigured) {
                col.addView(Components.secondaryButton(ctx, "Отключить внешний ящик", R.drawable.ic_logout) {
                    app.db.saveMailAccountConfig(MailAccountConfig("", "", "", "", 993, "", 465))
                    dlg.dismiss()
                    render()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = Theme.dp(ctx, 8)
                    }
                })
            }
        }
    }
}
