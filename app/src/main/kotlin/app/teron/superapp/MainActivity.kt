package app.teron.superapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import app.teron.superapp.ui.OnboardingScreen
import app.teron.superapp.wallet.TeronWalletEngine
import java.security.MessageDigest
import java.util.Locale

class MainActivity : Activity(),
    TeronMeshEngine.Listener,
    TeronAudioEngine.Listener,
    TeronWalletEngine.Listener {

    companion object {
        private const val REQ_PERMS_BLE = 301
        private const val REQ_PERMS_CAM = 302
        private const val REQ_PERMS_AUDIO = 303

        private const val REQ_PICK_SUNCODE_PNG = 401
        private const val REQ_PICK_AUDIO_FILE = 402
        private const val REQ_PICK_PHONE_CONTACT = 403
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
            if (currentTab == 3 && ::musicScreen.isInitialized) {
                musicScreen.updatePlaybackTick()
            }
            uiTickHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            app = application as TeronApp
            val bootErr = app.startupError
            if (bootErr != null) {
                showFatalRecoveryView(bootErr)
                return
            }
            Theme.isDark = app.db.getProfile().darkTheme
            initScreens()
            renderRootState()
            handleIncomingDeepLink(intent)
        } catch (t: Throwable) {
            showFatalRecoveryView(t.stackTraceToString())
        }
    }

    private fun initScreens() {
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
            onResetToOnboarding = {
                app.reinitializeAfterReset()
                initScreens()
                renderRootState()
            }
        )
    }

    private fun renderRootState() {
        applySystemBarColors()
        if (!app.db.isOnboarded()) {
            val onboarding = OnboardingScreen(this, app) {
                buildShell()
            }
            val wrap = wrapWithInsets(onboarding.build())
            setContentView(wrap)
        } else {
            buildShell()
        }
    }

    private fun wrapWithInsets(child: View): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Theme.bg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        attachSystemBarInsets(frame)
        return frame
    }

    private fun attachSystemBarInsets(target: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            target.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else if (Build.VERSION.SDK_INT >= 28) {
            target.setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                val topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val bottomInset = insets.systemWindowInsetBottom
                v.setPadding(0, topInset, 0, bottomInset)
                insets
            }
        }
    }

    private fun showFatalRecoveryView(trace: String) {
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF0B0D11.toInt()) }
        val tv = TextView(this).apply {
            text = "Диагностика запуска Teron:\n\n$trace"
            setTextColor(Color.WHITE)
            textSize = 12f
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p * 2, p, p)
        }
        scroll.addView(tv)
        setContentView(scroll)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingDeepLink(intent)
    }

    private fun handleIncomingDeepLink(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        val contact = SunCodeCodec.decodeUri(data) ?: return
        app.db.upsertContact(contact.nodeId, contact.displayName, contact.handle, contact.publicKeyB64)
        Toast.makeText(this, "Контакт сохранён: ${contact.displayName}", Toast.LENGTH_LONG).show()
        if (app.db.isOnboarded()) {
            switchTab(1, contact.nodeId)
        }
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
        attachSystemBarInsets(rootFrame)

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
            setPadding(Theme.dp(this@MainActivity, 6), Theme.dp(this@MainActivity, 6), Theme.dp(this@MainActivity, 6), Theme.dp(this@MainActivity, 8))
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
        if (!::screenContainer.isInitialized) return
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
        if (!::miniPlayerSlot.isInitialized) return
        miniPlayerSlot.removeAllViews()
        val cur = app.audioEngine.currentTrack
        if (currentTab == 3 || cur == null || (!app.audioEngine.isPlaying && app.audioEngine.currentPositionSec == 0)) {
            return
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(this@MainActivity, 14).toFloat(), Theme.border, Theme.dp(this@MainActivity, 1))
            val px = Theme.dp(this@MainActivity, 12)
            val py = Theme.dp(this@MainActivity, 8)
            setPadding(px, py, px, py)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = Theme.dp(this@MainActivity, 10)
                setMargins(m, 0, m, Theme.dp(this@MainActivity, 6))
            }
            setOnClickListener { switchTab(3, null) }
        }

        bar.addView(Components.tonalIconBadge(this, R.drawable.ic_music, Theme.brandText, 34, 17, 10f))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(this@MainActivity, 10)
                rightMargin = Theme.dp(this@MainActivity, 8)
            }
        }
        info.addView(TextView(this).apply {
            text = cur.title
            setTextColor(Theme.textPrimary)
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        })
        info.addView(TextView(this).apply {
            text = cur.artist
            setTextColor(Theme.textSecondary)
            textSize = 11.5f
            maxLines = 1
        })
        bar.addView(info)

        bar.addView(Components.solidIconButton(
            this,
            if (app.audioEngine.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            Theme.brand,
            Color.WHITE,
            34,
            16,
            17f
        ) {
            app.audioEngine.togglePlayPause()
        })

        miniPlayerSlot.addView(bar)
    }

    private fun refreshBottomNav() {
        if (!::bottomNavBar.isInitialized) return
        bottomNavBar.removeAllViews()
        val unreadChats = app.db.getTotalUnreadChats()
        val unreadMails = app.db.getUnreadMailCount()

        data class NavSpec(val idx: Int, val iconRes: Int, val title: String, val badge: Int = 0)
        val tabs = listOf(
            NavSpec(0, R.drawable.ic_home, "Главная"),
            NavSpec(1, R.drawable.ic_chat, "Чаты", unreadChats),
            NavSpec(2, R.drawable.ic_mail, "Почта", unreadMails),
            NavSpec(3, R.drawable.ic_music, "Аудио"),
            NavSpec(4, R.drawable.ic_grid, "Сервисы")
        )

        for (t in tabs) {
            val active = currentTab == t.idx
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, Theme.dp(this@MainActivity, 4), 0, Theme.dp(this@MainActivity, 4))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (currentTab == t.idx) {
                        if (t.idx == 1) { messengerScreen.activeConversationId = null; messengerScreen.render() }
                        if (t.idx == 4) { ecosystemScreen.activeSubApp = 0; ecosystemScreen.render() }
                    } else {
                        switchTab(t.idx, null)
                    }
                }
            }

            val iconPill = FrameLayout(this).apply {
                val w = Theme.dp(this@MainActivity, 52)
                val h = Theme.dp(this@MainActivity, 28)
                layoutParams = LinearLayout.LayoutParams(w, h)
                if (active) {
                    background = Theme.roundedBg(Theme.withAlpha(Theme.brand, 36), Theme.dp(this@MainActivity, 14).toFloat())
                }
            }
            iconPill.addView(
                Components.icon(this, t.iconRes, 20, if (active) Theme.brandText else Theme.textSecondary).apply {
                    layoutParams = FrameLayout.LayoutParams(Theme.dp(this@MainActivity, 20), Theme.dp(this@MainActivity, 20), Gravity.CENTER)
                }
            )
            if (t.badge > 0) {
                iconPill.addView(TextView(this).apply {
                    text = if (t.badge > 99) "99+" else t.badge.toString()
                    setTextColor(Color.WHITE)
                    textSize = 9.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = Theme.roundedBg(Theme.brand, Theme.dp(this@MainActivity, 8).toFloat())
                    setPadding(Theme.dp(this@MainActivity, 5), Theme.dp(this@MainActivity, 1), Theme.dp(this@MainActivity, 5), Theme.dp(this@MainActivity, 1))
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                })
            }
            item.addView(iconPill)

            item.addView(TextView(this).apply {
                text = t.title
                textSize = 11f
                typeface = Typeface.create(if (active) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                setTextColor(if (active) Theme.brandText else Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, Theme.dp(this@MainActivity, 2), 0, 0)
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
            onRequestPhoneContactPicker = { openPhoneContactPicker() },
            onContactAdded = { contact ->
                switchTab(1, contact.nodeId)
            }
        )
        dlg.show()
        if (scanTab) dlg.switchTab(1)
    }

    private fun openPhoneContactPicker() {
        try {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_PHONE_CONTACT)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть телефонную книгу: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
            val localCount = app.audioEngine.tracks.count { !it.isBuiltInSynth }
            Toast.makeText(this, "Медиатека обновлена (аудиофайлов на устройстве: $localCount)", Toast.LENGTH_SHORT).show()
        } else {
            requestPermissions(arrayOf(perm), REQ_PERMS_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!app.db.isOnboarded()) return
        when (requestCode) {
            REQ_PERMS_BLE -> {
                app.meshEngine.refreshBleTransport()
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
                        Toast.makeText(this, "Контакт ${decoded.displayName} импортирован из SunCode", Toast.LENGTH_LONG).show()
                        switchTab(1, decoded.nodeId)
                    } else {
                        Toast.makeText(this, "В выбранном изображении нет метки Teron SunCode", Toast.LENGTH_LONG).show()
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
            REQ_PICK_PHONE_CONTACT -> {
                try {
                    val proj = arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )
                    contentResolver.query(uri, proj, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val name = c.getString(0)?.trim().orEmpty().ifBlank { "Контакт" }
                            val phone = c.getString(1)?.trim().orEmpty()
                            val cleanDigits = phone.replace(Regex("[^0-9+]"), "")
                            val hash = MessageDigest.getInstance("SHA-256")
                                .digest(cleanDigits.ifEmpty { name }.toByteArray(Charsets.UTF_8))
                            val tag = hash.take(3).joinToString("") { "%02X".format(it) }
                            val nodeId = "TRN-$tag"
                            val handle = "${cleanDigits.trimStart('+').ifEmpty { tag.lowercase(Locale.US) }}@teron.mesh"
                            app.db.upsertContact(nodeId, name, handle, "")
                            Toast.makeText(this, "Импортирован контакт: $name ($phone)", Toast.LENGTH_LONG).show()
                            switchTab(1, nodeId)
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка чтения контакта: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Deprecated("Overrides Android Activity back press")
    override fun onBackPressed() {
        if (!app.db.isOnboarded()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
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
        if (::app.isInitialized && app.startupError == null) {
            app.meshEngine.addListener(this)
            app.audioEngine.addListener(this)
            app.walletEngine.addListener(this)
            uiTickHandler.post(playbackTickRunnable)
        }
    }

    override fun onPause() {
        uiTickHandler.removeCallbacks(playbackTickRunnable)
        if (::app.isInitialized && app.startupError == null) {
            app.meshEngine.removeListener(this)
            app.audioEngine.removeListener(this)
            app.walletEngine.removeListener(this)
        }
        super.onPause()
    }

    override fun onMeshStateChanged() {
        if (!app.db.isOnboarded()) return
        when (currentTab) {
            0 -> switchTab(0, null)
            1 -> messengerScreen.render()
            2 -> mailScreen.render()
            4 -> if (ecosystemScreen.activeSubApp == 0) ecosystemScreen.render()
        }
        refreshBottomNav()
    }

    override fun onPacketDelivered(summary: String) {
        Toast.makeText(this, "AirMesh: $summary", Toast.LENGTH_LONG).show()
    }

    override fun onAudioStateChanged() {
        if (!app.db.isOnboarded()) return
        if (currentTab == 3) musicScreen.render()
        refreshMiniPlayer()
    }

    override fun onWalletUpdated(syncMessage: String?) {
        if (!app.db.isOnboarded()) return
        if (syncMessage != null) {
            Toast.makeText(this, syncMessage, Toast.LENGTH_SHORT).show()
        }
        when (currentTab) {
            0 -> switchTab(0, null)
            4 -> if (ecosystemScreen.activeSubApp == 0) ecosystemScreen.render()
        }
    }
}
