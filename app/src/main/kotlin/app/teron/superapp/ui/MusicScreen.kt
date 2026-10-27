package app.teron.superapp.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.teron.superapp.R
import app.teron.superapp.TeronApp
import app.teron.superapp.core.Theme

class MusicScreen(
    private val ctx: Context,
    private val app: TeronApp,
    private val onRequestAudioPermissionAndScan: () -> Unit,
    private val onOpenAudioFilePicker: () -> Unit
) {
    private var filterMode = 0 // 0=All, 1=Device Audio, 2=Favorites, 3=Ambient DSP
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
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 14), Theme.dp(ctx, 16), Theme.dp(ctx, 24))
        }

        // Top Bar
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(ctx).apply {
            text = "Аудиоплеер"
            setTextColor(Theme.textPrimary)
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val localCount = engine.tracks.count { !it.isBuiltInSynth }
        top.addView(Components.pillBadge(ctx, "На устройстве: $localCount", Theme.textSecondary))
        col.addView(top)

        // Player Surface Card (clean Graphite / Pure Slate card — zero neon gradient)
        val playerCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 18).toFloat(), Theme.border, Theme.dp(ctx, 1))
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
        trackRow.addView(Components.tonalIconBadge(ctx, R.drawable.ic_music, Theme.brandText, 50, 24, 14f))
        val metaCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 14)
                rightMargin = Theme.dp(ctx, 8)
            }
        }
        metaCol.addView(TextView(ctx).apply {
            text = cur?.title ?: "Выберите композицию"
            setTextColor(Theme.textPrimary)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        })
        metaCol.addView(TextView(ctx).apply {
            text = cur?.artist ?: "Локальная фонотека"
            setTextColor(Theme.textSecondary)
            textSize = 13f
            maxLines = 1
        })
        metaCol.addView(TextView(ctx).apply {
            text = cur?.album ?: ""
            setTextColor(Theme.textMuted)
            textSize = 11.5f
            maxLines = 1
        })
        trackRow.addView(metaCol)

        if (cur != null) {
            trackRow.addView(Components.solidIconButton(
                ctx = ctx,
                resId = if (cur.isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart,
                bgColor = Theme.cardAlt,
                iconTint = if (cur.isFavorite) Theme.accentCoral else Theme.textSecondary,
                boxSizeDp = 38,
                iconSizeDp = 18,
                radiusDp = 10f
            ) {
                engine.toggleFavorite(cur)
                render()
            })
        }
        playerCard.addView(trackRow)

        // Spectrum Visualizer
        val spec = AudioSpectrumView(ctx).apply {
            activePlaying = engine.isPlaying
            barColor = Theme.brandText
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 52)).apply {
                topMargin = Theme.dp(ctx, 14)
                bottomMargin = Theme.dp(ctx, 6)
            }
        }
        spectrumRef = spec
        playerCard.addView(spec)

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
        playerCard.addView(seek)

        val timeTv = TextView(ctx).apply {
            text = "${fmtSec(pos)} / ${fmtSec(dur)}"
            setTextColor(Theme.textMuted)
            textSize = 11.5f
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
            setPadding(0, 0, Theme.dp(ctx, 6), Theme.dp(ctx, 10))
        }
        timeLabelRef = timeTv
        playerCard.addView(timeTv)

        // Vector Transport Controls Row
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun transportBtn(iconRes: Int, primary: Boolean = false, active: Boolean = false, action: () -> Unit): FrameLayout {
            val sz = if (primary) 54 else 42
            val icSz = if (primary) 22 else 18
            val bg = when {
                primary -> Theme.brand
                active -> Theme.withAlpha(Theme.brand, 38)
                else -> Theme.cardAlt
            }
            val tint = when {
                primary -> Color.WHITE
                active -> Theme.brandText
                else -> Theme.textPrimary
            }
            return Components.solidIconButton(ctx, iconRes, bg, tint, sz, icSz, (sz / 2f)) { action() }.apply {
                (layoutParams as LinearLayout.LayoutParams).apply {
                    leftMargin = Theme.dp(ctx, 6)
                    rightMargin = Theme.dp(ctx, 6)
                }
            }
        }

        controls.addView(transportBtn(R.drawable.ic_shuffle, active = engine.isShuffle) {
            engine.isShuffle = !engine.isShuffle
            render()
        })
        controls.addView(transportBtn(R.drawable.ic_skip_prev) { engine.prev() })
        controls.addView(transportBtn(if (engine.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, primary = true) {
            engine.togglePlayPause()
        })
        controls.addView(transportBtn(R.drawable.ic_skip_next) { engine.next() })
        controls.addView(transportBtn(R.drawable.ic_repeat, active = engine.isRepeatOne) {
            engine.isRepeatOne = !engine.isRepeatOne
            render()
        })
        playerCard.addView(controls)
        col.addView(playerCard)

        // Library Scan & File Import Row
        val importRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 12)
                bottomMargin = Theme.dp(ctx, 10)
            }
        }
        importRow.addView(Components.secondaryButton(ctx, "Сканировать память", R.drawable.ic_refresh) {
            onRequestAudioPermissionAndScan()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Theme.dp(ctx, 6)
            }
        })
        importRow.addView(Components.secondaryButton(ctx, "Открыть аудиофайл", R.drawable.ic_folder) {
            onOpenAudioFilePicker()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = Theme.dp(ctx, 6)
            }
        })
        col.addView(importRow)

        // Category Filter Chips
        val fScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, Theme.dp(ctx, 2), 0, Theme.dp(ctx, 10))
        }
        val fRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fRow.addView(Components.actionChip(ctx, "Все (${engine.tracks.size})", filterMode == 0) { filterMode = 0; render() })
        fRow.addView(Components.actionChip(ctx, "На устройстве ($localCount)", filterMode == 1) { filterMode = 1; render() })
        fRow.addView(Components.actionChip(ctx, "Избранное", filterMode == 2, R.drawable.ic_heart) { filterMode = 2; render() })
        fRow.addView(Components.actionChip(ctx, "Ambient DSP", filterMode == 3, R.drawable.ic_waveform) { filterMode = 3; render() })
        fScroll.addView(fRow)
        col.addView(fScroll)

        val filtered = engine.tracks.mapIndexed { idx, t -> idx to t }.filter { (_, t) ->
            when (filterMode) {
                1 -> !t.isBuiltInSynth
                2 -> t.isFavorite
                3 -> t.isBuiltInSynth
                else -> true
            }
        }

        if (filtered.isEmpty()) {
            col.addView(Components.emptyStateCard(
                ctx = ctx,
                iconRes = R.drawable.ic_music,
                title = "Список треков пуст",
                subtitle = "Предоставьте доступ к медиатеке смартфона или выберите аудиофайл MP3, FLAC, WAV или M4A через проводник.",
                ctaLabel = "Сканировать медиатеку",
                onCtaClick = { onRequestAudioPermissionAndScan() }
            ))
        } else {
            val listGroup = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
            }
            filtered.forEachIndexed { idx, (realIdx, t) ->
                val active = realIdx == engine.currentIndex
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.rippleSurfaceRow(ctx)
                    val p = Theme.dp(ctx, 12)
                    setPadding(p, p, p, p)
                    setOnClickListener {
                        if (active) engine.togglePlayPause() else engine.playIndex(realIdx)
                    }
                }
                row.addView(
                    Components.tonalIconBadge(
                        ctx,
                        if (active && engine.isPlaying) R.drawable.ic_waveform else R.drawable.ic_music,
                        if (active) Theme.brandText else Theme.textSecondary,
                        40, 18, 10f
                    )
                )
                val tCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                        rightMargin = Theme.dp(ctx, 8)
                    }
                }
                tCol.addView(TextView(ctx).apply {
                    text = t.title
                    setTextColor(if (active) Theme.brandText else Theme.textPrimary)
                    textSize = 14.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                })
                tCol.addView(TextView(ctx).apply {
                    text = "${t.artist} • ${fmtSec(t.durationSec)}"
                    setTextColor(Theme.textSecondary)
                    textSize = 12f
                    fontFeatureSettings = "tnum"
                    maxLines = 1
                })
                row.addView(tCol)

                row.addView(Components.icon(
                    ctx,
                    if (t.isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart,
                    18,
                    if (t.isFavorite) Theme.accentCoral else Theme.textMuted
                ).apply {
                    setOnClickListener {
                        engine.toggleFavorite(t)
                        render()
                    }
                })
                listGroup.addView(row)
                if (idx < filtered.lastIndex) listGroup.addView(Components.divider(ctx, 64))
            }
            col.addView(listGroup)
        }

        scroll.addView(col)
        root.addView(scroll)
    }
}
