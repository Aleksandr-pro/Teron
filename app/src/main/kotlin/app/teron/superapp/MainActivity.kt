package app.teron.superapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.audio.TeronAudioEngine
import app.teron.superapp.core.Theme
import app.teron.superapp.mesh.TeronMeshEngine
import app.teron.superapp.suncode.SunCodeCodec
import app.teron.superapp.suncode.SunCodeDialog
import app.teron.superapp.ui.Components
import app.teron.superapp.ui.EcosystemScreen
import app.teron.superapp.ui.HomeHubScreen
import app.teron.superapp.ui.MailScreen
import app.teron.superapp.ui.MessengerScreen
import app.teron.superapp.ui.MusicScreen
import app.teron.superapp.ui.SettingsScreen

class MainActivity : Activity(), TeronMeshEngine.Listener, TeronAudioEngine.Listener {

    companion object {
        private const val REQ_PERMS_BLE = 301
        private const val REQ_PERMS_CAM = 302
        private const val REQ_PERMS_AUDIO = 303
        private const val REQ_PERMS_ALL = 304

        private const val REQ_PICK_SUNCODE_PNG = 401
        private const val REQ_PICK_AUDIO_FILE = 402
    }

    private lateinit var app: TeronApp
    private lateinit var rootFrame: LinearLayout
    private lateinit var screenContainer: FrameLayout
    private lateinit var miniPlayerSlot: FrameLayout
    private lateinit var bottomNavBar: LinearLayout

    private lateinit var messengerScreen: MessengerScreen
    private lateinit var mailScreen: MailScreen
    private lateinit var musicScreen: MusicScreen
    private lateinit var ecosystemScreen: EcosystemScreen

    private var currentTab: Int = 0
    private val uiTickHandler = Handler(Looper.getMainLooper())

    private val playbackTickRunnable = object : Runnable {
        override fun run() {
            if (currentTab == 3) {
                musicScreen.updatePlaybackTick()
            }
            uiTickHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TeronApp
        Theme.isDark = app.db.getProfile().darkTheme

        messengerScreen = MessengerScreen(
            ctx = this,
            app = app,
            onRequestBlePermissions = { requestBlePermissions() },
            onOpenSunCode = { scan -> openSunCodeModal(scan) }
        )
        mailScreen = MailScreen(this, app)
        musicScreen = MusicScreen(
            ctx = this,
            app = app,
            onRequestAudioPermissionAndScan = { requestAudioPermissionsAndScan() },
            onOpenAudioFilePicker = { openAudioDocumentPicker() }
        )
        ecosystemScreen = EcosystemScreen(
            ctx = this,
            app = app,
            onThemeChanged = { rebuildShell() },
            onOpenSunCode = { scan -> openSunCodeModal(scan) },
            onRequestAllPermissions = { requestAllPermissions() }
        )

        buildShell()
        handleIncomingDeepLink(intent)

        // Offer friendly one-time callsign setup on very first launch
        if (!app.db.isOnboarded()) {
            app.db.markOnboarded()
            uiTickHandler.postDelayed({
                if (!isFinishing) {
                    SettingsScreen.showEditProfileDialog(this, app) {
                        rebuildShell()
                    }
                }
            }, 350L)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingDeepLink(intent)
    }

    private fun handleIncomingDeepLink(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        val contact = SunCodeCodec.decodeUri(data) ?: return
        app.db.upsertContact(contact.nodeId, contact.displayName, contact.handle, contact.publicKeyB64)
        Toast.makeText(this, "Контакт SunCode добавлен: ${contact.displayName}", Toast.LENGTH_LONG).show()
        switchTab(1, contact.nodeId)
    }

    private fun applySystemBarColors() {
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bgElevated
        if (Build.VERSION.SDK_INT >= 23) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (Theme.isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun buildShell() {
        applySystemBarColors()

        rootFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        if (Build.VERSION.SDK_INT >= 28) {
            rootFrame.setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                val topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val bottomInset = insets.systemWindowInsetBottom
                v.setPadding(0, topInset, 0, bottomInset)
                insets
            }
        }

        screenContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        miniPlayerSlot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        bottomNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.bgElevated, 0f, Theme.border, Theme.dp(this@MainActivity, 1))
            setPadding(Theme.dp(this@MainActivity, 4), Theme.dp(this@MainActivity, 6), Theme.dp(this@MainActivity, 4), Theme.dp(this@MainActivity, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        rootFrame.addView(screenContainer)
        rootFrame.addView(miniPlayerSlot)
        rootFrame.addView(bottomNavBar)
        setContentView(rootFrame)

        switchTab(currentTab, null)
    }

    private fun rebuildShell() {
        Theme.isDark = app.db.getProfile().darkTheme
        buildShell()
    }

    fun switchTab(tabIndex: Int, subArg: String?) {
        currentTab = tabIndex.coerceIn(0, 4)
        screenContainer.removeAllViews()

        val view = when (currentTab) {
            0 -> HomeHubScreen.build(
                ctx = this,
                app = app,
                onNavigateTab = { t, arg -> switchTab(t, arg) },
                onOpenSunCode = { scan -> openSunCodeModal(scan) }
            )
            1 -> {
                messengerScreen.openSubArg(subArg)
                messengerScreen.build()
            }
            2 -> mailScreen.build()
            3 -> musicScreen.build()
            else -> {
                ecosystemScreen.openSubArg(subArg)
                ecosystemScreen.build()
            }
        }

        screenContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        refreshMiniPlayer()
        refreshBottomNav()
    }

    private fun refreshMiniPlayer() {
        miniPlayerSlot.removeAllViews()
        val cur = app.audioEngine.currentTrack
        if (currentTab == 3 || cur == null || (!app.audioEngine.isPlaying && app.audioEngine.currentPositionSec == 0)) {
            return
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.cardAlt, Theme.dp(this@MainActivity, 14).toFloat(), cur.accentColor, Theme.dp(this@MainActivity, 1))
            val px = Theme.dp(this@MainActivity, 12)
            val py = Theme.dp(this@MainActivity, 8)
            setPadding(px, py, px, py)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = Theme.dp(this@MainActivity, 8)
                setMargins(m, 0, m, Theme.dp(this@MainActivity, 6))
            }
            setOnClickListener { switchTab(3, null) }
        }

        bar.addView(Components.squircleIcon(this, "♪", cur.accentColor, 34, 15f))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(this@MainActivity, 10)
            }
        }
        info.addView(TextView(this).apply {
            text = cur.title
            setTextColor(Theme.textPrimary)
            textSize = 13.5f
            paint.isFakeBoldText = true
            maxLines = 1
        })
        info.addView(TextView(this).apply {
            text = cur.artist
            setTextColor(Theme.textSecondary)
            textSize = 11f
            maxLines = 1
        })
        bar.addView(info)

        bar.addView(TextView(this).apply {
            text = if (app.audioEngine.isPlaying) "⏸" else "▶"
            setTextColor(Theme.textPrimary)
            textSize = 18f
            setPadding(Theme.dp(this@MainActivity, 12), Theme.dp(this@MainActivity, 4), Theme.dp(this@MainActivity, 8), Theme.dp(this@MainActivity, 4))
            setOnClickListener { app.audioEngine.togglePlayPause() }
        })

        miniPlayerSlot.addView(bar)
    }

    private fun refreshBottomNav() {
        bottomNavBar.removeAllViews()
        val unreadChats = app.db.getTotalUnreadChats()
        val unreadMails = app.db.getUnreadMailCount()

        data class NavSpec(val idx: Int, val icon: String, val title: String, val badge: Int = 0)
        val tabs = listOf(
            NavSpec(0, "🏠", "Главная"),
            NavSpec(1, "💬", "Чаты", unreadChats),
            NavSpec(2, "✉️", "Почта", unreadMails),
            NavSpec(3, "🎵", "Музыка"),
            NavSpec(4, "🧩", "Сервисы")
        )

        for (t in tabs) {
            val active = currentTab == t.idx
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = if (active) {
                    Theme.roundedBg(Theme.withAlpha(Theme.accentIndigo, 38), Theme.dp(this@MainActivity, 14).toFloat())
                } else null
                setPadding(0, Theme.dp(this@MainActivity, 5), 0, Theme.dp(this@MainActivity, 5))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (currentTab == t.idx) {
                        // Reset nested sub-screen if tapping active tab
                        if (t.idx == 1) { messengerScreen.activeConversationId = null; messengerScreen.render() }
                        if (t.idx == 4) { ecosystemScreen.activeModule = null; ecosystemScreen.render() }
                    } else {
                        switchTab(t.idx, null)
                    }
                }
            }

            val iconLabel = if (t.badge > 0) "${t.icon}•${t.badge}" else t.icon
            item.addView(TextView(this).apply {
                text = iconLabel
                textSize = if (t.badge > 0) 14f else 18f
                setTextColor(if (t.badge > 0) Theme.accentCyan else Theme.textPrimary)
                gravity = Gravity.CENTER
            })
            item.addView(TextView(this).apply {
                text = t.title
                textSize = 11f
                paint.isFakeBoldText = active
                setTextColor(if (active) Theme.accentCyan else Theme.textSecondary)
                gravity = Gravity.CENTER
            })
            bottomNavBar.addView(item)
        }
    }

    private fun openSunCodeModal(scanTab: Boolean) {
        val dlg = SunCodeDialog(
            activity = this,
            onRequestGalleryImport = { openSunCodeGalleryPicker() },
            onRequestCameraPermission = {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_PERMS_CAM)
            },
            onContactAdded = { contact ->
                switchTab(1, contact.nodeId)
            }
        )
        dlg.show()
        if (scanTab) dlg.switchTab(1)
    }

    private fun openSunCodeGalleryPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_SUNCODE_PNG)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть галерею: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAudioDocumentPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_AUDIO_FILE)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть проводник: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBlePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), REQ_PERMS_BLE)
    }

    private fun requestAudioPermissionsAndScan() {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            app.audioEngine.reloadLibrary()
            musicScreen.render()
            Toast.makeText(this, "Фонотека обновлена (всего треков: ${app.audioEngine.tracks.size})", Toast.LENGTH_SHORT).show()
        } else {
            requestPermissions(arrayOf(perm), REQ_PERMS_AUDIO)
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms.toTypedArray(), REQ_PERMS_ALL)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_PERMS_BLE, REQ_PERMS_ALL -> {
                app.meshEngine.refreshBleTransport()
                if (requestCode == REQ_PERMS_ALL) app.audioEngine.reloadLibrary()
                if (currentTab == 1) messengerScreen.render()
                if (currentTab == 0) switchTab(0, null)
            }
            REQ_PERMS_CAM -> {
                openSunCodeModal(true)
            }
            REQ_PERMS_AUDIO -> {
                app.audioEngine.reloadLibrary()
                if (currentTab == 3) musicScreen.render()
            }
        }
    }

    @Deprecated("Overrides Android Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_PICK_SUNCODE_PNG -> {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                    val decoded = SunCodeCodec.extractUriFromPngBytes(bytes)
                    if (decoded != null) {
                        app.db.upsertContact(decoded.nodeId, decoded.displayName, decoded.handle, decoded.publicKeyB64)
                        Toast.makeText(this, "Контакт ${decoded.displayName} импортирован из SunCode!", Toast.LENGTH_LONG).show()
                        switchTab(1, decoded.nodeId)
                    } else {
                        Toast.makeText(this, "В этом изображении не найдена метка Teron SunCode", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка чтения файла: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_PICK_AUDIO_FILE -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                app.audioEngine.addPickedDocumentUri(uri)
                if (currentTab == 3) musicScreen.render()
            }
        }
    }

    @Deprecated("Overrides Android Activity back press")
    override fun onBackPressed() {
        when (currentTab) {
            1 -> if (messengerScreen.handleBack()) return
            2 -> if (mailScreen.handleBack()) return
            4 -> if (ecosystemScreen.handleBack()) return
        }
        if (currentTab != 0) {
            switchTab(0, null)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        app.meshEngine.addListener(this)
        app.audioEngine.addListener(this)
        uiTickHandler.post(playbackTickRunnable)
    }

    override fun onPause() {
        uiTickHandler.removeCallbacks(playbackTickRunnable)
        app.meshEngine.removeListener(this)
        app.audioEngine.removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        ecosystemScreen.shutdown()
        super.onDestroy()
    }

    override fun onMeshStateChanged() {
        when (currentTab) {
            0 -> switchTab(0, null)
            1 -> messengerScreen.render()
            2 -> mailScreen.render()
            4 -> if (ecosystemScreen.activeModule == "wallet") ecosystemScreen.render()
        }
        refreshBottomNav()
    }

    override fun onPacketDelivered(summary: String) {
        Toast.makeText(this, "📡 AirMesh: $summary", Toast.LENGTH_LONG).show()
    }

    override fun onAudioStateChanged() {
        if (currentTab == 3) musicScreen.render()
        refreshMiniPlayer()
    }
}
