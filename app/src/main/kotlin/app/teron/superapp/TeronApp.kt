package app.teron.superapp

import android.app.Application
import app.teron.superapp.audio.TeronAudioEngine
import app.teron.superapp.core.TeronDatabase
import app.teron.superapp.core.Theme
import app.teron.superapp.mesh.TeronMeshEngine
import app.teron.superapp.wallet.TeronWalletEngine

class TeronApp : Application() {

    lateinit var db: TeronDatabase
        private set
    lateinit var meshEngine: TeronMeshEngine
        private set
    lateinit var audioEngine: TeronAudioEngine
        private set
    lateinit var walletEngine: TeronWalletEngine
        private set
    var startupError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        initContainer()
    }

    fun reinitializeAfterReset() {
        try { meshEngine.stop() } catch (_: Throwable) {}
        initContainer()
    }

    private fun initContainer() {
        try {
            db = TeronDatabase(this)
            Theme.isDark = db.getProfile().darkTheme
            meshEngine = TeronMeshEngine(this, db)
            audioEngine = TeronAudioEngine(this, db)
            walletEngine = TeronWalletEngine(db)
            try {
                meshEngine.start()
            } catch (_: Throwable) {}
            if (db.isOnboarded()) {
                walletEngine.refreshAllChains(false)
            }
            startupError = null
        } catch (t: Throwable) {
            startupError = t.stackTraceToString()
        }
    }
}
