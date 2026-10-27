package app.teron.superapp.suncode

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.R
import app.teron.superapp.TeronApp
import app.teron.superapp.core.TeronFileProvider
import app.teron.superapp.core.Theme
import app.teron.superapp.core.UserProfile
import app.teron.superapp.ui.Components
import app.teron.superapp.ui.QrCodeView
import java.io.File

class SunCodeDialog(
    private val activity: Activity,
    private val onRequestGalleryImport: () -> Unit,
    private val onRequestCameraPermission: () -> Unit,
    private val onRequestPhoneContactPicker: () -> Unit,
    private val onContactAdded: (SunCodeCodec.DecodedContact) -> Unit
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val app = activity.application as TeronApp
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mode = 0 // 0 = My Code, 1 = Scanner & Import
    private var showQrInsteadOfSun = false
    private lateinit var bodyContainer: FrameLayout
    private lateinit var tabRow: LinearLayout

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        buildLayout()
        setOnDismissListener { closeCamera() }
    }

    private fun buildLayout() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
            setPadding(Theme.dp(activity, 16), Theme.dp(activity, 20), Theme.dp(activity, 16), Theme.dp(activity, 16))
        }

        // Top Bar
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(TextView(activity).apply {
            text = "Обмен контактами SunCode"
            setTextColor(Theme.textPrimary)
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topBar.addView(Components.solidIconButton(activity, R.drawable.ic_close, Theme.cardAlt, Theme.textPrimary, 38, 17, 10f) {
            dismiss()
        })
        root.addView(topBar)

        tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(activity, 14)
                bottomMargin = Theme.dp(activity, 12)
            }
        }
        root.addView(tabRow)

        bodyContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(bodyContainer)

        setContentView(root)
        switchTab(0)
    }

    fun switchTab(newMode: Int) {
        mode = newMode
        closeCamera()
        tabRow.removeAllViews()
        tabRow.addView(Components.actionChip(activity, "Моя карточка", mode == 0, R.drawable.ic_suncode) { switchTab(0) })
        tabRow.addView(Components.actionChip(activity, "Сканер и импорт", mode == 1, R.drawable.ic_camera) { switchTab(1) })

        bodyContainer.removeAllViews()
        if (mode == 0) {
            bodyContainer.addView(buildMyCodeView())
        } else {
            bodyContainer.addView(buildScannerView())
        }
    }

    private fun buildMyCodeView(): View {
        val profile = app.db.getProfile()
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, Theme.dp(activity, 4), 0, Theme.dp(activity, 20))
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(activity, 20).toFloat(), Theme.border, Theme.dp(activity, 1))
            val pad = Theme.dp(activity, 20)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val modeSwitchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Theme.dp(activity, 12)
            }
        }
        modeSwitchRow.addView(Components.actionChip(activity, "SunCode", !showQrInsteadOfSun, R.drawable.ic_suncode) {
            showQrInsteadOfSun = false
            switchTab(0)
        })
        modeSwitchRow.addView(Components.actionChip(activity, "QR-код", showQrInsteadOfSun, R.drawable.ic_qr) {
            showQrInsteadOfSun = true
            switchTab(0)
        })
        card.addView(modeSwitchRow)

        if (showQrInsteadOfSun) {
            card.addView(QrCodeView(activity, SunCodeCodec.encodeUri(profile)).apply {
                val sz = Theme.dp(activity, 220)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
            })
        } else {
            card.addView(SunCodeView(activity, profile).apply {
                val sz = Theme.dp(activity, 236)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
            })
        }

        card.addView(TextView(activity).apply {
            text = profile.displayName
            setTextColor(Theme.textPrimary)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(activity, 12), 0, Theme.dp(activity, 2))
        })
        card.addView(TextView(activity).apply {
            text = "${profile.handle} • ${profile.nodeId}"
            setTextColor(Theme.brandText)
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        })
        card.addView(TextView(activity).apply {
            text = "Код содержит ваш публичный ключ ECDH P-256 для сквозного шифрования сообщений и почты AirMesh."
            setTextColor(Theme.textSecondary)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(Theme.dp(activity, 8), Theme.dp(activity, 10), Theme.dp(activity, 8), 0)
        })
        col.addView(card)

        col.addView(Components.primaryButton(activity, "Поделиться PNG-карточкой", R.drawable.ic_share) {
            shareSunCodePng(profile)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(activity, 14)
            }
        })

        col.addView(Components.secondaryButton(activity, "Скопировать URI контакта (teron://)", R.drawable.ic_copy) {
            val uri = SunCodeCodec.encodeUri(profile)
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Teron Contact", uri))
            Toast.makeText(activity, "Ссылка контакта скопирована", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(activity, 10)
            }
        })

        scroll.addView(col)
        return scroll
    }

    private fun shareSunCodePng(profile: UserProfile) {
        try {
            val pngBytes = SunCodeCodec.renderShareCardPng(profile)
            val file = File(TeronFileProvider.sharedDir(activity), "Teron_SunCode_${profile.nodeId}.png")
            file.writeBytes(pngBytes)
            val uri = TeronFileProvider.getUriForSharedFile(activity, file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Мой контакт в Teron: ${SunCodeCodec.encodeUri(profile)}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(sendIntent, "Поделиться карточкой SunCode"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildScannerView(): View {
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, Theme.dp(activity, 2), 0, Theme.dp(activity, 20))
        }

        val hasCamPerm = activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val camBox = FrameLayout(activity).apply {
            background = Theme.roundedBg(0xFF07090D.toInt(), Theme.dp(activity, 18).toFloat(), Theme.border, Theme.dp(activity, 1))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(activity, 250))
        }

        if (hasCamPerm) {
            val textureView = TextureView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    openCameraPreview(st, textureView)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            camBox.addView(textureView)

            val overlay = object : View(activity) {
                private var sweep = 0f
                private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = Theme.dp(activity, 1.5f).toFloat()
                    color = Theme.brandText
                }
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val r = (width.coerceAtMost(height)) * 0.34f
                    pRing.alpha = 180
                    canvas.drawCircle(cx, cy, r, pRing)
                    canvas.drawArc(cx - r * 1.1f, cy - r * 1.1f, cx + r * 1.1f, cy + r * 1.1f, sweep, 60f, false, pRing)
                    if (isAttachedToWindow) {
                        sweep = (sweep + 3.5f) % 360f
                        postInvalidateDelayed(36L)
                    }
                }
            }
            camBox.addView(overlay)

            val pairBtn = Components.primaryButton(activity, "Подключить обнаруженный узел", R.drawable.ic_radar) {
                val live = app.meshEngine.livePeers.values.firstOrNull()
                if (live != null) {
                    finishDecoded(SunCodeCodec.DecodedContact(live.nodeId, live.displayName, live.handle, live.publicKeyB64))
                } else {
                    Toast.makeText(activity, "Убедитесь, что у собеседника открыт Teron в одной сети, либо импортируйте PNG-карточку", Toast.LENGTH_LONG).show()
                }
            }.apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = Theme.dp(activity, 12)
                }
            }
            camBox.addView(pairBtn)
        } else {
            val permCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                val p = Theme.dp(activity, 20)
                setPadding(p, p, p, p)
            }
            permCol.addView(Components.tonalIconBadge(activity, R.drawable.ic_camera, Theme.brandText, 48, 22, 14f))
            permCol.addView(TextView(activity).apply {
                text = "Доступ к камере для оптического сканера SunCode"
                setTextColor(Color.WHITE)
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(0, Theme.dp(activity, 10), 0, Theme.dp(activity, 12))
            })
            permCol.addView(Components.primaryButton(activity, "Разрешить камеру", R.drawable.ic_camera) {
                dismiss()
                onRequestCameraPermission()
            })
            camBox.addView(permCol)
        }
        col.addView(camBox)

        // Import from PNG or Android Contacts Book
        val importRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(activity, 12)
            }
        }
        importRow.addView(Components.secondaryButton(activity, "Из PNG-файла", R.drawable.ic_image) {
            dismiss()
            onRequestGalleryImport()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(activity, 6)
            }
        })
        importRow.addView(Components.secondaryButton(activity, "Телефонная книга", R.drawable.ic_contacts) {
            dismiss()
            onRequestPhoneContactPicker()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(activity, 6)
            }
        })
        col.addView(importRow)

        val input = EditText(activity).apply {
            hint = "Вставьте ссылку teron://contact?id=TRN-..."
            setHintTextColor(Theme.textMuted)
            setTextColor(Theme.textPrimary)
            textSize = 13.5f
            background = Theme.roundedBg(Theme.card, Theme.dp(activity, 12).toFloat(), Theme.border, Theme.dp(activity, 1))
            val p = Theme.dp(activity, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(activity, 12)
            }
        }
        col.addView(input)

        val rowBtns = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(activity, 10)
            }
        }
        rowBtns.addView(Components.secondaryButton(activity, "Из буфера", R.drawable.ic_copy) {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
            input.setText(txt)
            val dec = SunCodeCodec.decodeUri(txt)
            if (dec != null) finishDecoded(dec)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(activity, 6)
            }
        })
        rowBtns.addView(Components.primaryButton(activity, "Добавить", R.drawable.ic_check) {
            val dec = SunCodeCodec.decodeUri(input.text.toString())
            if (dec != null) {
                finishDecoded(dec)
            } else {
                Toast.makeText(activity, "Некорректная ссылка контакта teron://contact", Toast.LENGTH_SHORT).show()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(activity, 6)
            }
        })
        col.addView(rowBtns)

        scroll.addView(col)
        return scroll
    }

    private fun finishDecoded(c: SunCodeCodec.DecodedContact) {
        app.db.upsertContact(c.nodeId, c.displayName, c.handle, c.publicKeyB64)
        Toast.makeText(activity, "Контакт ${c.displayName} сохранён", Toast.LENGTH_LONG).show()
        onContactAdded(c)
        dismiss()
    }

    private fun openCameraPreview(st: SurfaceTexture, tv: TextureView) {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return

            st.setDefaultBufferSize(tv.width.coerceAtLeast(640), tv.height.coerceAtLeast(480))
            val surface = Surface(st)
            cm.openCamera(backId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        req.addTarget(surface)
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    session.setRepeatingRequest(req.build(), null, Handler(Looper.getMainLooper()))
                                } catch (_: Exception) {}
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {}
                        }, Handler(Looper.getMainLooper()))
                    } catch (_: Exception) {}
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {}
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
    }
}
