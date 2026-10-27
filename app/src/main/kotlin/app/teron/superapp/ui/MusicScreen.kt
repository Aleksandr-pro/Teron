package app.teron.superapp.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.teron.superapp.TeronApp
import app.teron.superapp.core.Theme

class MusicScreen(
    private val ctx: Context,
    private val app: TeronApp,
    private val onRequestAudioPermissionAndScan: () -> Unit,
    private val onOpenAudioFilePicker: () -> Unit
) {
    private var filterMode = 0 // 0=All, 1=Offline Synth, 2=Phone Files, 3=Favorites
    private var rootContainer: LinearLayout? = null
    private var seekBarRef: SeekBar? = null
    private var timeLabelRef: TextView? = null
    private var spectrumRef: AudioSpectrumView? = null

    fun build(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        rootContainer = root
        render()
        return root
    }

    fun updatePlaybackTick() {
        val engine = app.audioEngine
        val pos = engine.currentPositionSec
        val dur = engine.durationSec.coerceAtLeast(1)
        seekBarRef?.max = dur
        seekBarRef?.progress = pos.coerceIn(0, dur)
        timeLabelRef?.text = "${fmtSec(pos)} / ${fmtSec(dur)}"
        spectrumRef?.activePlaying = engine.isPlaying
        spectrumRef?.invalidate()
    }

    private fun fmtSec(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)

    fun render() {
        val root = rootContainer ?: return
        root.removeAllViews()
        val engine = app.audioEngine
        val cur = engine.currentTrack

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 12), Theme.dp(ctx, 16), Theme.dp(ctx, 24))
        }

        // Header
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(ctx).apply {
            text = "Teron Sound"
            setTextColor(Theme.textPrimary)
            textSize = 22f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(Components.pillBadge(ctx, "Офлайн Hi-Fi", Theme.accentPink))
        col.addView(top)

        // Hero Player Card
        val accent = cur?.accentColor ?: Theme.accentIndigo
        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.gradientBg(
                intArrayOf(Theme.withAlpha(accent, 110), 0xFF141B30.toInt(), 0xFF0B1020.toInt()),
                Theme.dp(ctx, 24).toFloat(),
                GradientDrawable.Orientation.TL_BR,
                Theme.withAlpha(accent, 130),
                Theme.dp(ctx, 1)
            )
            val p = Theme.dp(ctx, 18)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 14)
            }
        }

        val trackRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        trackRow.addView(Components.squircleIcon(ctx, "🎵", accent, 52, 24f))
        val metaCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 14)
            }
        }
        metaCol.addView(TextView(ctx).apply {
            text = cur?.title ?: "Выберите трек"
            setTextColor(Color.WHITE)
            textSize = 18f
            paint.isFakeBoldText = true
            maxLines = 1
        })
        metaCol.addView(TextView(ctx).apply {
            text = cur?.artist ?: "Офлайн-фонотека"
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 13f
            maxLines = 1
        })
        metaCol.addView(TextView(ctx).apply {
            text = cur?.genre ?: ""
            setTextColor(Theme.accentCyan)
            textSize = 11.5f
        })
        trackRow.addView(metaCol)

        if (cur != null) {
            trackRow.addView(TextView(ctx).apply {
                text = if (cur.isFavorite) "❤️" else "🤍"
                textSize = 22f
                setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 8), Theme.dp(ctx, 4), Theme.dp(ctx, 8))
                setOnClickListener {
                    engine.toggleFavorite(cur)
                    render()
                }
            })
        }
        hero.addView(trackRow)

        // Spectrum Visualizer
        val spec = AudioSpectrumView(ctx).apply {
            activePlaying = engine.isPlaying
            barColor = accent
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 64)).apply {
                topMargin = Theme.dp(ctx, 14)
                bottomMargin = Theme.dp(ctx, 8)
            }
        }
        spectrumRef = spec
        hero.addView(spec)

        // SeekBar + Time
        val dur = engine.durationSec.coerceAtLeast(1)
        val pos = engine.currentPositionSec.coerceIn(0, dur)
        val seek = SeekBar(ctx).apply {
            max = dur
            progress = pos
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) engine.seekToSec(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        seekBarRef = seek
        hero.addView(seek)

        val timeTv = TextView(ctx).apply {
            text = "${fmtSec(pos)} / ${fmtSec(dur)}"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 12f
            gravity = Gravity.END
            setPadding(0, 0, Theme.dp(ctx, 6), Theme.dp(ctx, 8))
        }
        timeLabelRef = timeTv
        hero.addView(timeTv)

        // Transport Controls Row
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun ctrlBtn(lbl: String, big: Boolean = false, active: Boolean = false, action: () -> Unit): TextView =
            TextView(ctx).apply {
                text = lbl
                setTextColor(if (active) Theme.accentCyan else Color.WHITE)
                textSize = if (big) 22f else 16f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                val sz = Theme.dp(ctx, if (big) 56 else 42)
                background = if (big) Theme.rippleButton(ctx, accent, 28f) else Theme.rippleCard(ctx, 0x33FFFFFF, 21f, Color.TRANSPARENT, 0f)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    leftMargin = Theme.dp(ctx, 6)
                    rightMargin = Theme.dp(ctx, 6)
                }
                setOnClickListener { action() }
            }

        controls.addView(ctrlBtn("🔀", active = engine.isShuffle) {
            engine.isShuffle = !engine.isShuffle
            render()
        })
        controls.addView(ctrlBtn("⏮") { engine.prev() })
        controls.addView(ctrlBtn(if (engine.isPlaying) "⏸" else "▶", big = true) { engine.togglePlayPause() })
        controls.addView(ctrlBtn("⏭") { engine.next() })
        controls.addView(ctrlBtn("🔁", active = engine.isRepeatOne) {
            engine.isRepeatOne = !engine.isRepeatOne
            render()
        })
        hero.addView(controls)
        col.addView(hero)

        // Import / Scan Local Phone Music Actions
        val importRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 12)
                bottomMargin = Theme.dp(ctx, 10)
            }
        }
        importRow.addView(TextView(ctx).apply {
            text = "🔍 Музыка телефона"
            setTextColor(Theme.textPrimary)
            textSize = 12.5f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleCard(ctx, Theme.card, 13f)
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 11), Theme.dp(ctx, 10), Theme.dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 6)
            }
            setOnClickListener { onRequestAudioPermissionAndScan() }
        })
        importRow.addView(TextView(ctx).apply {
            text = "📂 Открыть MP3/WAV"
            setTextColor(Theme.textPrimary)
            textSize = 12.5f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleCard(ctx, Theme.card, 13f)
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 11), Theme.dp(ctx, 10), Theme.dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 6)
            }
            setOnClickListener { onOpenAudioFilePicker() }
        })
        col.addView(importRow)

        // Filter chips
        val fScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, Theme.dp(ctx, 2), 0, Theme.dp(ctx, 10))
        }
        val fRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fRow.addView(Components.actionChip(ctx, "Все (${engine.tracks.size})", filterMode == 0) { filterMode = 0; render() })
        fRow.addView(Components.actionChip(ctx, "Офлайн-синтез", filterMode == 1, Theme.accentPink) { filterMode = 1; render() })
        fRow.addView(Components.actionChip(ctx, "На устройстве", filterMode == 2, Theme.accentBlue) { filterMode = 2; render() })
        fRow.addView(Components.actionChip(ctx, "Избранное ❤️", filterMode == 3, Theme.accentCoral) { filterMode = 3; render() })
        fScroll.addView(fRow)
        col.addView(fScroll)

        // Track list
        val filtered = engine.tracks.mapIndexed { idx, t -> idx to t }.filter { (_, t) ->
            when (filterMode) {
                1 -> t.isBuiltInSynth
                2 -> !t.isBuiltInSynth
                3 -> t.isFavorite
                else -> true
            }
        }

        if (filtered.isEmpty()) {
            col.addView(TextView(ctx).apply {
                text = "Треки в этой категории не найдены. Нажмите «🔍 Музыка телефона» или «📂 Открыть MP3/WAV», чтобы добавить композиции с вашего устройства."
                setTextColor(Theme.textSecondary)
                textSize = 13f
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 14)
                setPadding(p, p, p, p)
            })
        } else {
            for ((realIdx, t) in filtered) {
                val active = realIdx == engine.currentIndex
                val itemRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleCard(
                        ctx,
                        if (active) Theme.cardAlt else Theme.card,
                        15f,
                        if (active) t.accentColor else Theme.border,
                        1f
                    )
                    val p = Theme.dp(ctx, 12)
                    setPadding(p, p, p, p)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = Theme.dp(ctx, 8)
                    }
                    setOnClickListener {
                        if (active) engine.togglePlayPause() else engine.playIndex(realIdx)
                    }
                }
                itemRow.addView(Components.squircleIcon(ctx, if (active && engine.isPlaying) "🔊" else "♪", t.accentColor, 42, 18f))
                val tCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                    }
                }
                tCol.addView(TextView(ctx).apply {
                    text = t.title
                    setTextColor(Theme.textPrimary)
                    textSize = 14.5f
                    paint.isFakeBoldText = true
                    maxLines = 1
                })
                tCol.addView(TextView(ctx).apply {
                    text = "${t.artist} • ${fmtSec(t.durationSec)}"
                    setTextColor(Theme.textSecondary)
                    textSize = 12f
                    maxLines = 1
                })
                itemRow.addView(tCol)
                itemRow.addView(TextView(ctx).apply {
                    text = if (t.isFavorite) "❤️" else "🤍"
                    textSize = 17f
                    setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 4), Theme.dp(ctx, 4), Theme.dp(ctx, 4))
                    setOnClickListener {
                        engine.toggleFavorite(t)
                        render()
                    }
                })
                col.addView(itemRow)
            }
        }

        scroll.addView(col)
        root.addView(scroll)
    }
}
