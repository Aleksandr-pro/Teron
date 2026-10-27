package app.teron.superapp

import android.app.Application
import app.teron.superapp.audio.TeronAudioEngine
import app.teron.superapp.core.TeronDatabase
import app.teron.superapp.core.Theme
import app.teron.superapp.mesh.TeronMeshEngine

class TeronApp : Application() {

    lateinit var db: TeronDatabase
        private set
    lateinit var meshEngine: TeronMeshEngine
        private set
    lateinit var audioEngine: TeronAudioEngine
        private set

    override fun onCreate() {
        super.onCreate()
        db = TeronDatabase(this)
        Theme.isDark = db.getProfile().darkTheme
        meshEngine = TeronMeshEngine(this, db)
        audioEngine = TeronAudioEngine(this, db)
        meshEngine.start()
    }
}
