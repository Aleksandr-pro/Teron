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
import app.teron.superapp.TeronApp
import app.teron.superapp.core.TeronFileProvider
import app.teron.superapp.core.Theme
import java.io.File

class SunCodeDialog(
    private val activity: Activity,
    private val onRequestGalleryImport: () -> Unit,
    private val onRequestCameraPermission: () -> Unit,
    private val onContactAdded: (SunCodeCodec.DecodedContact) -> Unit
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val app = activity.application as TeronApp
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mode = 0 // 0 = My Code, 1 = Live Scanner
    private lateinit var bodyContainer: FrameLayout
    private lateinit var tabMyBtn: TextView
    private lateinit var tabScanBtn: TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        buildLayout()
        setOnDismissListener { closeCamera() }
    }

    private fun buildLayout() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
            setPadding(Theme.dp(activity, 18), Theme.dp(activity, 28), Theme.dp(activity, 18), Theme.dp(activity, 20))
        }

        // Top Bar
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(activity).apply {
            text = "Лучевой SunCode"
            setTextColor(Theme.textPrimary)
            textSize = 20f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(activity).apply {
            text = "Закрыть"
            setTextColor(Theme.accentCyan)
            textSize = 15f
            setPadding(Theme.dp(activity, 14), Theme.dp(activity, 8), Theme.dp(activity, 14), Theme.dp(activity, 8))
            background = Theme.rippleCard(activity, Theme.cardAlt, 12f)
            setOnClickListener { dismiss() }
        }
        topBar.addView(title)
        topBar.addView(closeBtn)
        root.addView(topBar)

        // Mode switcher
        val switcher = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.roundedBg(Theme.cardAlt, Theme.dp(activity, 14).toFloat())
            val p = Theme.dp(activity, 4)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(activity, 16)
                bottomMargin = Theme.dp(activity, 14)
            }
        }
        tabMyBtn = TextView(activity).apply {
            text = "☀ Мой SunCode"
            gravity = Gravity.CENTER
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(0, Theme.dp(activity, 10), 0, Theme.dp(activity, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(0) }
        }
        tabScanBtn = TextView(activity).apply {
            text = "📷 Сканер камеры"
            gravity = Gravity.CENTER
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(0, Theme.dp(activity, 10), 0, Theme.dp(activity, 10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(1) }
        }
        switcher.addView(tabMyBtn)
        switcher.addView(tabScanBtn)
        root.addView(switcher)

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
        tabMyBtn.background = if (mode == 0) Theme.roundedBg(Theme.accentIndigo, Theme.dp(activity, 11).toFloat()) else null
        tabMyBtn.setTextColor(if (mode == 0) Color.WHITE else Theme.textSecondary)
        tabScanBtn.background = if (mode == 1) Theme.roundedBg(Theme.accentIndigo, Theme.dp(activity, 11).toFloat()) else null
        tabScanBtn.setTextColor(if (mode == 1) Color.WHITE else Theme.textSecondary)

        bodyContainer.removeAllViews()
        if (mode == 0) {
            bodyContainer.addView(buildMyCodeView())
        } else {
            bodyContainer.addView(buildScannerView())
        }
    }

    private fun buildMyCodeView(): View {
        val profile = app.db.getProfile()
        val scroll = ScrollView(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Theme.dp(activity, 8), Theme.dp(activity, 6), Theme.dp(activity, 8), Theme.dp(activity, 20))
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(activity, 24).toFloat(), Theme.border, Theme.dp(activity, 1))
            val pad = Theme.dp(activity, 20)
            setPadding(pad, pad, pad, pad)
        }

        val sunView = SunCodeView(activity, profile).apply {
            layoutParams = LinearLayout.LayoutParams(Theme.dp(activity, 250), Theme.dp(activity, 250))
        }
        card.addView(sunView)

        val nameTv = TextView(activity).apply {
            text = profile.displayName
            setTextColor(Theme.textPrimary)
            textSize = 21f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(activity, 8), 0, Theme.dp(activity, 4))
        }
        card.addView(nameTv)

        val handleTv = TextView(activity).apply {
            text = "${profile.handle} • ${profile.nodeId}"
            setTextColor(Theme.accentCyan)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        card.addView(handleTv)

        val descTv = TextView(activity).apply {
            text = "64 гармонических луча кодируют ваш узел AirMesh и отпечаток публичного ключа ECDH P-256."
            setTextColor(Theme.textSecondary)
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(Theme.dp(activity, 8), Theme.dp(activity, 10), Theme.dp(activity, 8), 0)
        }
        card.addView(descTv)
        col.addView(card)

        // Share PNG Button
        val shareBtn = TextView(activity).apply {
            text = "📤 Поделиться карточкой SunCode (PNG)"
            setTextColor(Color.WHITE)
            textSize = 15f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(activity, Theme.accentIndigo, 15f)
            setPadding(Theme.dp(activity, 16), Theme.dp(activity, 14), Theme.dp(activity, 16), Theme.dp(activity, 14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(activity, 16)
            }
            setOnClickListener { shareSunCodePng(profile) }
        }
        col.addView(shareBtn)

        val copyBtn = TextView(activity).apply {
            text = "📋 Скопировать ссылку контакта (teron://)"
            setTextColor(Theme.textPrimary)
            textSize = 14f
            gravity = Gravity.CENTER
            background = Theme.rippleCard(activity, Theme.cardAlt, 14f)
            setPadding(Theme.dp(activity, 16), Theme.dp(activity, 13), Theme.dp(activity, 16), Theme.dp(activity, 13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(activity, 10)
            }
            setOnClickListener {
                val uri = SunCodeCodec.encodeUri(profile)
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Teron SunCode", uri))
                Toast.makeText(activity, "SunCode ссылка скопирована!", Toast.LENGTH_SHORT).show()
            }
        }
        col.addView(copyBtn)

        scroll.addView(col)
        return scroll
    }

    private fun shareSunCodePng(profile: app.teron.superapp.core.UserProfile) {
        try {
            val pngBytes = SunCodeCodec.renderShareCardPng(profile)
            val file = File(TeronFileProvider.sharedDir(activity), "Teron_SunCode_${profile.nodeId}.png")
            file.writeBytes(pngBytes)
            val uri = TeronFileProvider.getUriForSharedFile(activity, file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Мой контакт в автономном мессенджере Teron: ${SunCodeCodec.encodeUri(profile)}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(sendIntent, "Поделиться SunCode"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildScannerView(): View {
        val scroll = ScrollView(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val hasCamPerm = activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val camBox = FrameLayout(activity).apply {
            background = Theme.roundedBg(0xFF050811.toInt(), Theme.dp(activity, 22).toFloat(), Theme.accentCyan, Theme.dp(activity, 1.5f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(activity, 270))
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

            // Animated radial targeting reticle overlay
            val overlay = object : View(activity) {
                private var sweep = 0f
                private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = Theme.dp(activity, 2f).toFloat()
                    color = Theme.accentCyan
                }
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val r = (width.coerceAtMost(height)) * 0.34f
                    pRing.alpha = 180
                    canvas.drawCircle(cx, cy, r, pRing)
                    pRing.color = Theme.accentAmber
                    canvas.drawArc(cx - r * 1.1f, cy - r * 1.1f, cx + r * 1.1f, cy + r * 1.1f, sweep, 55f, false, pRing)
                    pRing.color = Theme.accentCyan
                    if (isAttachedToWindow) {
                        sweep = (sweep + 4f) % 360f
                        postInvalidateDelayed(35L)
                    }
                }
            }
            camBox.addView(overlay)

            // Pair nearby AirMesh peer when aligned in reticle
            val pairBtn = TextView(activity).apply {
                text = "⚡ Захватить узел в прицеле / рядом"
                setTextColor(Color.WHITE)
                textSize = 12.5f
                paint.isFakeBoldText = true
                background = Theme.roundedBg(0xCC111827.toInt(), Theme.dp(activity, 12).toFloat(), Theme.accentCyan, Theme.dp(activity, 1))
                setPadding(Theme.dp(activity, 14), Theme.dp(activity, 8), Theme.dp(activity, 14), Theme.dp(activity, 8))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = Theme.dp(activity, 12)
                }
                setOnClickListener {
                    val live = app.meshEngine.livePeers.values.firstOrNull()
                    if (live != null) {
                        val c = SunCodeCodec.DecodedContact(live.nodeId, live.displayName, live.handle, live.publicKeyB64)
                        finishDecoded(c)
                    } else {
                        Toast.makeText(activity, "Наведите на экран с SunCode (убедитесь, что у собеседника включён AirMesh) или загрузите PNG из галереи", Toast.LENGTH_LONG).show()
                    }
                }
            }
            camBox.addView(pairBtn)
        } else {
            val permCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding(Theme.dp(activity, 20), Theme.dp(activity, 20), Theme.dp(activity, 20), Theme.dp(activity, 20))
            }
            permCol.addView(TextView(activity).apply {
                text = "📷 Разрешите доступ к камере для живого оптического видоискателя SunCode"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
            })
            permCol.addView(TextView(activity).apply {
                text = "Включить камеру"
                setTextColor(Color.WHITE)
                paint.isFakeBoldText = true
                background = Theme.rippleButton(activity, Theme.accentIndigo, 12f)
                setPadding(Theme.dp(activity, 18), Theme.dp(activity, 10), Theme.dp(activity, 18), Theme.dp(activity, 10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Theme.dp(activity, 12)
                }
                setOnClickListener {
                    dismiss()
                    onRequestCameraPermission()
                }
            })
            camBox.addView(permCol)
        }
        col.addView(camBox)

        // Gallery PNG Import button
        val galleryBtn = TextView(activity).apply {
            text = "🖼 Сканировать карточку SunCode из галереи (PNG)"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(activity, Theme.accentEmerald, 14f)
            setPadding(Theme.dp(activity, 16), Theme.dp(activity, 13), Theme.dp(activity, 16), Theme.dp(activity, 13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(activity, 14)
            }
            setOnClickListener {
                dismiss()
                onRequestGalleryImport()
            }
        }
        col.addView(galleryBtn)

        // Paste teron:// code manually or from clipboard
        val input = EditText(activity).apply {
            hint = "Или вставьте ссылку teron://contact?id=TRN-..."
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
        val clipBtn = TextView(activity).apply {
            text = "Из буфера"
            setTextColor(Theme.textPrimary)
            gravity = Gravity.CENTER
            background = Theme.rippleCard(activity, Theme.cardAlt, 12f)
            setPadding(Theme.dp(activity, 12), Theme.dp(activity, 12), Theme.dp(activity, 12), Theme.dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(activity, 8)
            }
            setOnClickListener {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
                input.setText(txt)
                val dec = SunCodeCodec.decodeUri(txt)
                if (dec != null) finishDecoded(dec)
            }
        }
        val addBtn = TextView(activity).apply {
            text = "Добавить контакт"
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(activity, Theme.accentIndigo, 12f)
            setPadding(Theme.dp(activity, 12), Theme.dp(activity, 12), Theme.dp(activity, 12), Theme.dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
            setOnClickListener {
                val dec = SunCodeCodec.decodeUri(input.text.toString())
                if (dec != null) {
                    finishDecoded(dec)
                } else {
                    Toast.makeText(activity, "Неверный формат кода teron://contact", Toast.LENGTH_SHORT).show()
                }
            }
        }
        rowBtns.addView(clipBtn)
        rowBtns.addView(addBtn)
        col.addView(rowBtns)

        scroll.addView(col)
        return scroll
    }

    private fun finishDecoded(c: SunCodeCodec.DecodedContact) {
        app.db.upsertContact(c.nodeId, c.displayName, c.handle, c.publicKeyB64)
        Toast.makeText(activity, "Контакт ${c.displayName} (${c.nodeId}) добавлен!", Toast.LENGTH_LONG).show()
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
