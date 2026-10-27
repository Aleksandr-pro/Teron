package app.teron.superapp.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
import app.teron.superapp.core.MailItem
import app.teron.superapp.core.Theme
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocketFactory

class MailScreen(
    private val ctx: Context,
    private val app: TeronApp
) {
    private var currentFolder: String = "INBOX"
    private var searchQuery: String = ""
    private var openedMail: MailItem? = null
    private var rootContainer: LinearLayout? = null
    private val netPool = Executors.newSingleThreadExecutor()

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
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Top Bar
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 12), Theme.dp(ctx, 16), Theme.dp(ctx, 6))
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = "Teron Mail"
            setTextColor(Theme.textPrimary)
            textSize = 22f
            paint.isFakeBoldText = true
        })
        titleCol.addView(TextView(ctx).apply {
            text = me.handle
            setTextColor(Theme.accentCyan)
            textSize = 12f
        })
        top.addView(titleCol)

        val imapBtn = TextView(ctx).apply {
            text = "🌐 SSL"
            setTextColor(Theme.textPrimary)
            textSize = 12.5f
            background = Theme.rippleCard(ctx, Theme.cardAlt, 12f)
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 8), Theme.dp(ctx, 10), Theme.dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
            setOnClickListener { showExternalImapDialog() }
        }
        val composeBtn = TextView(ctx).apply {
            text = "✉ Написать"
            setTextColor(Color.WHITE)
            textSize = 13f
            paint.isFakeBoldText = true
            background = Theme.rippleButton(ctx, Theme.accentIndigo, 12f)
            setPadding(Theme.dp(ctx, 13), Theme.dp(ctx, 8), Theme.dp(ctx, 13), Theme.dp(ctx, 8))
            setOnClickListener { showComposeDialog() }
        }
        top.addView(imapBtn)
        top.addView(composeBtn)
        wrapper.addView(top)

        // Search input
        val searchBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 6))
        }
        val searchEdit = EditText(ctx).apply {
            setText(searchQuery)
            hint = "🔍 Поиск писем по теме или адресу…"
            setHintTextColor(Theme.textMuted)
            setTextColor(Theme.textPrimary)
            textSize = 13.5f
            setSingleLine(true)
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
            setPadding(Theme.dp(ctx, 13), Theme.dp(ctx, 9), Theme.dp(ctx, 13), Theme.dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 8)
            }
        }
        val findBtn = TextView(ctx).apply {
            text = "Найти"
            setTextColor(Theme.accentCyan)
            textSize = 13f
            paint.isFakeBoldText = true
            background = Theme.rippleCard(ctx, Theme.cardAlt, 12f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 9), Theme.dp(ctx, 12), Theme.dp(ctx, 9))
            setOnClickListener {
                searchQuery = searchEdit.text.toString()
                render()
            }
        }
        searchBox.addView(searchEdit)
        searchBox.addView(findBtn)
        wrapper.addView(searchBox)

        // Folder tabs
        val folderScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 8))
        }
        val folderRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val folders = listOf(
            "INBOX" to "📥 Входящие",
            "SENT" to "📤 Отправленные",
            "OUTBOX_MESH" to "📡 Очередь AirMesh",
            "STARRED" to "⭐ Избранное"
        )
        for ((fCode, fLabel) in folders) {
            folderRow.addView(Components.actionChip(ctx, fLabel, currentFolder == fCode) {
                currentFolder = fCode; render()
            })
        }
        folderScroll.addView(folderRow)
        wrapper.addView(folderScroll)

        // Letters list
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 4), Theme.dp(ctx, 16), Theme.dp(ctx, 20))
        }

        val items = app.db.getMails(currentFolder, searchQuery)
        if (items.isEmpty()) {
            listCol.addView(TextView(ctx).apply {
                text = "В этой папке пока нет писем. Нажмите «✉ Написать», чтобы отправить офлайн-письмо по протоколу AirMesh на любой адрес @teron.mesh."
                setTextColor(Theme.textSecondary)
                textSize = 13.5f
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 16)
                setPadding(p, p, p, p)
            })
        } else {
            for (item in items) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Theme.rippleCard(
                        ctx,
                        if (!item.isRead && item.folder == "INBOX") Theme.cardAlt else Theme.card,
                        16f
                    )
                    val p = Theme.dp(ctx, 14)
                    setPadding(p, p, p, p)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = Theme.dp(ctx, 9)
                    }
                    setOnClickListener {
                        app.db.markMailRead(item.id)
                        openedMail = item.copy(isRead = true)
                        render()
                    }
                }

                val row1 = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (!item.isRead && item.folder == "INBOX") {
                    row1.addView(View(ctx).apply {
                        background = Theme.circleBg(Theme.accentCyan)
                        val s = Theme.dp(ctx, 8)
                        layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = Theme.dp(ctx, 8) }
                    })
                }
                row1.addView(TextView(ctx).apply {
                    text = if (item.folder == "SENT" || item.folder == "OUTBOX_MESH") "Кому: ${item.toAddress}" else item.fromName
                    setTextColor(Theme.textPrimary)
                    textSize = 14.5f
                    paint.isFakeBoldText = !item.isRead
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (item.priority != "NORMAL") {
                    val pCol = if (item.priority == "SOS") Theme.accentCoral else Theme.accentAmber
                    row1.addView(Components.pillBadge(ctx, item.priority, pCol).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            rightMargin = Theme.dp(ctx, 6)
                        }
                    })
                }
                row1.addView(TextView(ctx).apply {
                    text = Theme.formatTime(item.timestampMs)
                    setTextColor(Theme.textMuted)
                    textSize = 11.5f
                })
                card.addView(row1)

                card.addView(TextView(ctx).apply {
                    text = (if (item.isStarred) "⭐ " else "") + item.subject
                    setTextColor(Theme.textPrimary)
                    textSize = 15f
                    paint.isFakeBoldText = true
                    maxLines = 1
                    setPadding(0, Theme.dp(ctx, 4), 0, Theme.dp(ctx, 2))
                })

                card.addView(TextView(ctx).apply {
                    text = item.body.replace("\n", " ")
                    setTextColor(Theme.textSecondary)
                    textSize = 12.8f
                    maxLines = 2
                })
                listCol.addView(card)
            }
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
            background = Theme.roundedBg(Theme.bgElevated, 0f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 10), Theme.dp(ctx, 12), Theme.dp(ctx, 10))
        }
        bar.addView(TextView(ctx).apply {
            text = "← В ящик"
            setTextColor(Theme.accentCyan)
            textSize = 14.5f
            paint.isFakeBoldText = true
            setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 6), Theme.dp(ctx, 12), Theme.dp(ctx, 6))
            setOnClickListener { openedMail = null; render() }
        })
        bar.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

        bar.addView(TextView(ctx).apply {
            text = if (mail.isStarred) "⭐ В избранном" else "☆ В избранное"
            setTextColor(Theme.accentAmber)
            textSize = 13f
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 6), Theme.dp(ctx, 10), Theme.dp(ctx, 6))
            setOnClickListener {
                val next = !mail.isStarred
                app.db.toggleMailStar(mail.id, next)
                openedMail = mail.copy(isStarred = next)
                render()
            }
        })
        bar.addView(TextView(ctx).apply {
            text = "🗑"
            textSize = 16f
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 6), Theme.dp(ctx, 8), Theme.dp(ctx, 6))
            setOnClickListener {
                app.db.deleteMail(mail.id)
                openedMail = null
                render()
            }
        })
        wrapper.addView(bar)

        val scroll = ScrollView(ctx).apply {
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
            textSize = 20f
            paint.isFakeBoldText = true
        })

        val metaCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
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
            paint.isFakeBoldText = true
        })
        metaCard.addView(TextView(ctx).apply {
            text = "Кому: ${mail.toAddress}  •  ${Theme.formatDateTime(mail.timestampMs)}"
            setTextColor(Theme.textSecondary)
            textSize = 12f
            setPadding(0, Theme.dp(ctx, 3), 0, Theme.dp(ctx, 3))
        })
        metaCard.addView(TextView(ctx).apply {
            text = if (mail.viaAirMesh) "📡 Протокол доставки: AirMesh E2EE (переходов: ${mail.hopCount})" else "🌐 Протокол доставки: SSL Почта"
            setTextColor(Theme.accentEmerald)
            textSize = 11.5f
        })
        col.addView(metaCard)

        col.addView(TextView(ctx).apply {
            text = mail.body
            setTextColor(Theme.textPrimary)
            textSize = 15f
            setLineSpacing(Theme.dp(ctx, 3).toFloat(), 1.08f)
        })

        col.addView(TextView(ctx).apply {
            text = "↩ Ответить через AirMesh"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(ctx, Theme.accentIndigo, 14f)
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 13), Theme.dp(ctx, 16), Theme.dp(ctx, 13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 22)
            }
            setOnClickListener {
                showComposeDialog(prefillTo = mail.fromAddress, prefillSubject = "Re: ${mail.subject}")
            }
        })

        scroll.addView(col)
        wrapper.addView(scroll)
        return wrapper
    }

    private fun showComposeDialog(prefillTo: String = "", prefillSubject: String = "") {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dp(ctx, 16)
            setPadding(p, p, p, p)
        }

        val contacts = app.db.getContacts()
        val toEdit = EditText(ctx).apply {
            setText(prefillTo.ifBlank { contacts.firstOrNull()?.handle ?: "friend@teron.mesh" })
            hint = "Кому (user@teron.mesh или ID узла)"
            setSingleLine(true)
        }
        val subjEdit = EditText(ctx).apply {
            setText(prefillSubject)
            hint = "Тема письма"
            setSingleLine(true)
        }
        val bodyEdit = EditText(ctx).apply {
            hint = "Текст письма (зашифровано AES-256-GCM для передачи без интернета)…"
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
        }

        var priority = "NORMAL"
        val prioRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Theme.dp(ctx, 6), 0, Theme.dp(ctx, 6))
        }
        val prioLabel = TextView(ctx).apply {
            text = "Приоритет: Обычный (нажмите для смены)"
            setTextColor(Theme.accentCyan)
            textSize = 13f
            setOnClickListener {
                priority = when (priority) {
                    "NORMAL" -> "HIGH"
                    "HIGH" -> "SOS"
                    else -> "NORMAL"
                }
                text = "Приоритет: $priority"
            }
        }
        prioRow.addView(prioLabel)

        layout.addView(toEdit)
        layout.addView(subjEdit)
        layout.addView(prioRow)
        layout.addView(bodyEdit)

        AlertDialog.Builder(ctx)
            .setTitle("✉ Новое письмо Teron Mail")
            .setView(layout)
            .setPositiveButton("Отправить в AirMesh") { _, _ ->
                val to = toEdit.text.toString().trim()
                val subj = subjEdit.text.toString().trim().ifBlank { "Без темы" }
                val body = bodyEdit.text.toString().trim()
                if (to.isNotEmpty() && body.isNotEmpty()) {
                    app.meshEngine.sendAirMeshMail(to, "", subj, body, priority)
                    Toast.makeText(ctx, "Письмо зашифровано и отправлено в AirMesh!", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showExternalImapDialog() {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dp(ctx, 16)
            setPadding(p, p, p, p)
        }
        val hostEdit = EditText(ctx).apply {
            setText(app.db.getKv("imap_host", "imap.mail.ru"))
            hint = "IMAP SSL хост (напр. imap.mail.ru)"
            setSingleLine(true)
        }
        val portEdit = EditText(ctx).apply {
            setText(app.db.getKv("imap_port", "993"))
            hint = "Порт SSL (993)"
            setSingleLine(true)
        }
        layout.addView(TextView(ctx).apply {
            text = "Когда смартфон подключён к интернету, Teron Mail умеет соединяться с классическими почтовыми серверами по TLS/SSL сокету."
            textSize = 12.5f
            setPadding(0, 0, 0, Theme.dp(ctx, 8))
        })
        layout.addView(hostEdit)
        layout.addView(portEdit)

        AlertDialog.Builder(ctx)
            .setTitle("🌐 Мост IMAP/SMTP (SSL)")
            .setView(layout)
            .setPositiveButton("Проверить SSL-соединение") { _, _ ->
                val host = hostEdit.text.toString().trim()
                val port = portEdit.text.toString().trim().toIntOrNull() ?: 993
                app.db.putKv("imap_host", host)
                app.db.putKv("imap_port", port.toString())
                netPool.execute {
                    val resultMsg = try {
                        SSLSocketFactory.getDefault().createSocket(host, port).use { sock ->
                            sock.soTimeout = 4000
                            val greet = BufferedReader(InputStreamReader(sock.getInputStream())).readLine() ?: "OK"
                            val w = OutputStreamWriter(sock.getOutputStream())
                            w.write("a1 LOGOUT\r\n"); w.flush()
                            "SSL мост активен: ${greet.take(65)}"
                        }
                    } catch (e: Exception) {
                        "Интернет недоступен (работает автономный режим AirMesh): ${e.javaClass.simpleName}"
                    }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(ctx, resultMsg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
