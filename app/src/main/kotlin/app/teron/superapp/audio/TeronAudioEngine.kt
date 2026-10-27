package app.teron.superapp.audio

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.teron.superapp.core.MusicTrack
import app.teron.superapp.core.TeronDatabase
import app.teron.superapp.core.Theme
import java.util.Collections
import java.util.concurrent.Executors

class TeronAudioEngine(
    private val context: Context,
    private val db: TeronDatabase
) {
    interface Listener {
        fun onAudioStateChanged()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val synthPool = Executors.newSingleThreadExecutor()
    private val listeners = Collections.synchronizedSet(LinkedHashSet<Listener>())

    private var player: MediaPlayer? = null
    val tracks = mutableListOf<MusicTrack>()

    var currentIndex: Int = 0
        private set
    var isPlaying: Boolean = false
        private set
    var isPreparing: Boolean = false
        private set
    var isShuffle: Boolean = false
    var isRepeatOne: Boolean = false

    init {
        reloadLibrary()
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyChanged() {
        mainHandler.post {
            for (l in listeners.toList()) l.onAudioStateChanged()
        }
    }

    val currentTrack: MusicTrack?
        get() = tracks.getOrNull(currentIndex)

    val currentPositionSec: Int
        get() = try { (player?.currentPosition ?: 0) / 1000 } catch (_: Exception) { 0 }

    val durationSec: Int
        get() = try {
            val d = (player?.duration ?: 0) / 1000
            if (d > 0) d else (currentTrack?.durationSec ?: 28)
        } catch (_: Exception) { currentTrack?.durationSec ?: 28 }

    fun hasAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun reloadLibrary() {
        val builtIn = listOf(
            MusicTrack(
                id = -1L,
                title = "Огни Находки",
                artist = "Teron Sound • Офлайн Синтез",
                album = "Приморье 2027",
                durationSec = 28,
                uriOrPath = "synth://0",
                isBuiltInSynth = true,
                synthPresetId = 0,
                accentColor = Theme.accentCyan,
                genre = "Synthwave • Без интернета",
                isFavorite = db.isTrackFavorite("synth://0")
            ),
            MusicTrack(
                id = -2L,
                title = "Ночная Тайга",
                artist = "Teron Sound • Офлайн Синтез",
                album = "AirMesh Автономность",
                durationSec = 28,
                uriOrPath = "synth://1",
                isBuiltInSynth = true,
                synthPresetId = 1,
                accentColor = Theme.accentEmerald,
                genre = "Lo-Fi Chill • Без интернета",
                isFavorite = db.isTrackFavorite("synth://1")
            ),
            MusicTrack(
                id = -3L,
                title = "Шёпот Гучжэна (古筝)",
                artist = "Восточный Ветер • Офлайн",
                album = "Культура Востока",
                durationSec = 28,
                uriOrPath = "synth://2",
                isBuiltInSynth = true,
                synthPresetId = 2,
                accentColor = Theme.accentAmber,
                genre = "Пентатоника • Без интернета",
                isFavorite = db.isTrackFavorite("synth://2")
            ),
            MusicTrack(
                id = -4L,
                title = "Орбита AirMesh",
                artist = "Teron Sound • Офлайн Синтез",
                album = "Эстафета Радиоузлов",
                durationSec = 28,
                uriOrPath = "synth://3",
                isBuiltInSynth = true,
                synthPresetId = 3,
                accentColor = Theme.accentIndigo,
                genre = "Cyber Ambient • Без интернета",
                isFavorite = db.isTrackFavorite("synth://3")
            ),
            MusicTrack(
                id = -5L,
                title = "Тихий Океан",
                artist = "Бухта Золотой Рог",
                album = "Приморье 2027",
                durationSec = 28,
                uriOrPath = "synth://4",
                isBuiltInSynth = true,
                synthPresetId = 4,
                accentColor = Theme.accentPink,
                genre = "Acoustic Wave • Без интернета",
                isFavorite = db.isTrackFavorite("synth://4")
            )
        )

        val localTracks = mutableListOf<MusicTrack>()
        if (hasAudioPermission()) {
            try {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION
                )
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    var count = 0
                    while (c.moveToNext() && count < 150) {
                        val id = c.getLong(0)
                        val title = c.getString(1)?.ifBlank { "Аудиотрек $id" } ?: "Аудиотрек $id"
                        val artist = c.getString(2)?.let { if (it == "<unknown>") "Память телефона" else it } ?: "Память телефона"
                        val album = c.getString(3) ?: "Локальная фонотека"
                        val durMs = c.getLong(4)
                        if (durMs < 3_000L) continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        localTracks.add(
                            MusicTrack(
                                id = id,
                                title = title,
                                artist = artist,
                                album = album,
                                durationSec = (durMs / 1000L).toInt(),
                                uriOrPath = contentUri,
                                isBuiltInSynth = false,
                                synthPresetId = -1,
                                accentColor = Theme.avatarPalette[(id.toInt() and 0x7FFFFFFF) % Theme.avatarPalette.size],
                                genre = "Локальный файл",
                                isFavorite = db.isTrackFavorite(contentUri)
                            )
                        )
                        count++
                    }
                }
            } catch (_: Exception) {}
        }

        val prevKey = currentTrack?.uriOrPath
        tracks.clear()
        tracks.addAll(builtIn)
        tracks.addAll(localTracks)
        if (prevKey != null) {
            val idx = tracks.indexOfFirst { it.uriOrPath == prevKey }
            if (idx >= 0) currentIndex = idx
        }
        notifyChanged()
    }

    fun addPickedDocumentUri(uri: Uri) {
        var displayTitle = "Импортированный трек"
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    displayTitle = c.getString(0)?.substringBeforeLast('.') ?: displayTitle
                }
            }
        } catch (_: Exception) {}

        val key = uri.toString()
        val existingIdx = tracks.indexOfFirst { it.uriOrPath == key }
        if (existingIdx >= 0) {
            playIndex(existingIdx)
            return
        }
        val t = MusicTrack(
            id = System.currentTimeMillis(),
            title = displayTitle,
            artist = "Выбранный файл",
            album = "Офлайн-фонотека",
            durationSec = 180,
            uriOrPath = key,
            isBuiltInSynth = false,
            synthPresetId = -1,
            accentColor = Theme.accentBlue,
            genre = "Аудиофайл устройства",
            isFavorite = db.isTrackFavorite(key)
        )
        tracks.add(0, t)
        playIndex(0)
    }

    fun playIndex(index: Int) {
        if (tracks.isEmpty()) return
        currentIndex = index.coerceIn(0, tracks.lastIndex)
        val track = tracks[currentIndex]
        isPreparing = true
        notifyChanged()

        synthPool.execute {
            try {
                val playTarget: Any = if (track.isBuiltInSynth) {
                    ProceduralSynth.ensurePresetWav(context, track.synthPresetId).absolutePath
                } else {
                    Uri.parse(track.uriOrPath)
                }

                mainHandler.post {
                    try {
                        releasePlayer()
                        val mp = MediaPlayer()
                        mp.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        if (playTarget is String) {
                            mp.setDataSource(playTarget)
                        } else if (playTarget is Uri) {
                            mp.setDataSource(context, playTarget)
                        }
                        mp.setOnCompletionListener {
                            if (isRepeatOne) {
                                playIndex(currentIndex)
                            } else {
                                next()
                            }
                        }
                        mp.prepare()
                        mp.start()
                        player = mp
                        isPreparing = false
                        isPlaying = true
                        notifyChanged()
                    } catch (_: Exception) {
                        isPreparing = false
                        isPlaying = false
                        notifyChanged()
                    }
                }
            } catch (_: Exception) {
                isPreparing = false
                isPlaying = false
                notifyChanged()
            }
        }
    }

    fun togglePlayPause() {
        val mp = player
        if (mp == null) {
            playIndex(currentIndex)
            return
        }
        try {
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
            } else {
                mp.start()
                isPlaying = true
            }
            notifyChanged()
        } catch (_: Exception) {
            playIndex(currentIndex)
        }
    }

    fun next() {
        if (tracks.isEmpty()) return
        val nextIdx = if (isShuffle && tracks.size > 1) {
            (tracks.indices - currentIndex).random()
        } else {
            (currentIndex + 1) % tracks.size
        }
        playIndex(nextIdx)
    }

    fun prev() {
        if (tracks.isEmpty()) return
        if (currentPositionSec > 4) {
            seekToSec(0)
            return
        }
        val prevIdx = if (currentIndex - 1 < 0) tracks.lastIndex else currentIndex - 1
        playIndex(prevIdx)
    }

    fun seekToSec(sec: Int) {
        try {
            player?.seekTo((sec.coerceAtLeast(0)) * 1000)
            notifyChanged()
        } catch (_: Exception) {}
    }

    fun toggleFavorite(track: MusicTrack) {
        val fav = db.toggleTrackFavorite(track.uriOrPath)
        val idx = tracks.indexOfFirst { it.uriOrPath == track.uriOrPath }
        if (idx >= 0) {
            tracks[idx] = tracks[idx].copy(isFavorite = fav)
        }
        notifyChanged()
    }

    fun releasePlayer() {
        try {
            player?.stop()
        } catch (_: Exception) {}
        try {
            player?.release()
        } catch (_: Exception) {}
        player = null
        isPlaying = false
    }
}
