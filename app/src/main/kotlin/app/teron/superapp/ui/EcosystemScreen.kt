package app.teron.superapp.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.teron.superapp.TeronApp
import app.teron.superapp.core.HanziCard
import app.teron.superapp.core.TeronCrypto
import app.teron.superapp.core.Theme
import java.util.Locale

class EcosystemScreen(
    private val ctx: Context,
    private val app: TeronApp,
    private val onThemeChanged: () -> Unit,
    private val onOpenSunCode: (scanTab: Boolean) -> Unit,
    private val onRequestAllPermissions: () -> Unit
) {
    // null = Hub, "hanzi", "mahjong", "wallet", "settings"
    var activeModule: String? = null
    private var rootContainer: LinearLayout? = null

    // --- Hanzi Dojo state ---
    private var hanziIdx: Int = 0
    private var hanziQuizMode: Boolean = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitialized = false

    // --- Mahjong Blast state ---
    data class MahjongTile(
        val slotId: Int,
        val code: String,
        val symbol: String,
        val nameRu: String,
        val loreRu: String,
        val color: Int,
        var matched: Boolean = false
    )
    private data class TileSpec(val code: String, val sym: String, val nameRu: String, val lore: String, val color: Int)

    private val tileCatalog = listOf(
        TileSpec("D_RED", "中", "Красный Дракон (Хунчжун)", "Символ точного попадания в цель, успеха на экзаменах и жизненной энергии в культуре Китая.", Theme.accentCoral),
        TileSpec("D_GRN", "發", "Зелёный Дракон (Фацай)", "Означает процветание, рост и пожелание достатка («Гунси Фацай»).", Theme.accentEmerald),
        TileSpec("D_WHT", "白", "Белый Дракон (Байбань)", "Олицетворяет чистоту помыслов, искренность и уважение к родителям.", Theme.accentBlue),
        TileSpec("W_EAST", "東", "Восточный Ветер (Дун)", "Главный ветер Маджонга: символ весны, рассвета над Тихим океаном и начала пути.", Theme.accentAmber),
        TileSpec("W_SOUTH", "南", "Южный Ветер (Нань)", "Символ тёплого лета, полуденного солнца и творческого огня.", Theme.accentCoral),
        TileSpec("W_WEST", "西", "Западный Ветер (Си)", "Символ золотой осени, сбора урожая и мудрого спокойствия.", Theme.accentPurple),
        TileSpec("B_1", "🎋", "1 Бамбук (Павлин)", "Первая кость масти Бамбука традиционно изображает птицу счастья — воробья или павлина.", Theme.accentEmerald),
        TileSpec("C_8", "八萬", "8 Тьмы (Восемь тысяч)", "Число 8 (ба) в Китае созвучно богатству и бесконечной гармонии.", Theme.accentAmber),
        TileSpec("P_9", "九筒", "9 Жемчужин (Небесный круг)", "Девятка в китайской традиции — императорское число полноты неба и земли.", Theme.accentCyan)
    )

    private val mahjongBoard = mutableListOf<MahjongTile>()
    private var selectedTileSlot: Int? = null
    private var mahjongScore: Int = 0
    private var mahjongCombo: Int = 0
    private var lastCulturalNote: String = "Выберите две одинаковые открытые кости Маджонга (свободные слева или справа в ряду), чтобы открыть культурную историю символа!"

    private fun ensureTtsInitialized() {
        if (ttsInitialized) return
        ttsInitialized = true
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val res = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                }
            }
        } catch (_: Throwable) {}
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Throwable) {}
    }

    fun openSubArg(arg: String?) {
        activeModule = arg
    }

    fun handleBack(): Boolean {
        if (activeModule != null) {
            activeModule = null
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
        val view = when (activeModule) {
            "hanzi" -> buildHanziDojoView()
            "mahjong" -> buildMahjongView()
            "wallet" -> buildWalletView()
            "settings" -> SettingsScreen.build(
                ctx = ctx,
                app = app,
                onBack = { activeModule = null; render() },
                onThemeChanged = onThemeChanged,
                onOpenSunCode = onOpenSunCode,
                onRequestAllPermissions = onRequestAllPermissions
            )
            else -> buildEcosystemHub()
        }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildEcosystemHub(): View {
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 12), Theme.dp(ctx, 16), Theme.dp(ctx, 24))
        }

        col.addView(TextView(ctx).apply {
            text = "Экосистема Teron"
            setTextColor(Theme.textPrimary)
            textSize = 22f
            paint.isFakeBoldText = true
        })
        col.addView(TextView(ctx).apply {
            text = "Встроенные автономные мини-сервисы: обучение, игры, криптокошелёк и управление узлом"
            setTextColor(Theme.textSecondary)
            textSize = 13f
            setPadding(0, Theme.dp(ctx, 4), 0, Theme.dp(ctx, 14))
        })

        fun moduleCard(glyph: String, color: Int, title: String, tag: String, desc: String, modKey: String): View {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.rippleCard(ctx, Theme.card, 20f)
                val p = Theme.dp(ctx, 16)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 12)
                }
                val top = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(Components.squircleIcon(ctx, glyph, color, 48, 22f))
                val mid = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = Theme.dp(ctx, 12)
                    }
                }
                mid.addView(TextView(ctx).apply {
                    text = title
                    setTextColor(Theme.textPrimary)
                    textSize = 17f
                    paint.isFakeBoldText = true
                })
                mid.addView(TextView(ctx).apply {
                    text = tag
                    setTextColor(color)
                    textSize = 12f
                    paint.isFakeBoldText = true
                })
                top.addView(mid)
                top.addView(Components.pillBadge(ctx, "Открыть →", color))
                addView(top)

                addView(TextView(ctx).apply {
                    text = desc
                    setTextColor(Theme.textSecondary)
                    textSize = 13f
                    setPadding(0, Theme.dp(ctx, 10), 0, 0)
                })
                setOnClickListener {
                    activeModule = modKey
                    render()
                }
            }
        }

        val mastered = app.db.getHanziMasteryMap().values.count { it >= 3 }
        col.addView(moduleCard(
            "🐼", Theme.accentCoral,
            "Ханьцзы Додзё (Red Panda)",
            "Изучено уверенно: $mastered / ${hanziDeck.size} иероглифов",
            "Запоминание китайских иероглифов через яркие визуальные ассоциации, офлайн-озвучка произношения, рисование кистью на сетке 米字格 и умная викторина.",
            "hanzi"
        ))

        col.addView(moduleCard(
            "🀄", Theme.accentEmerald,
            "Маджонг Культура (Mahjong Blast)",
            "Рекорд сессии: $mahjongScore очк.",
            "Классическая логическая игра с подлинными китайскими костями (Бамбук, Драконы, Ветры Востока) и культурными историями каждого символа.",
            "mahjong"
        ))

        val totalRub = app.db.getWalletBalances().sumOf { it.balance * it.rateRub }
        col.addView(moduleCard(
            "💎", Theme.accentCyan,
            "TeronPay Кошелёк",
            "Портфель: ~${"%,.0f".format(Locale("ru"), totalRub)} ₽",
            "Криптокошелёк с ключами ECDSA, seed-фразой из 12 слов и мгновенными офлайн-переводами между смартфонами через сеть AirMesh.",
            "wallet"
        ))

        col.addView(moduleCard(
            "⚙️", Theme.accentPurple,
            "Настройки и Профиль",
            "Узел ${app.db.getProfile().nodeId}",
            "Настройка имени, почтового адреса @teron.mesh, смена тёмной/светлой темы, ключи безопасности и фоновый демон ретрансляции.",
            "settings"
        ))

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // MODULE 1: HANZI DOJO (CHINESE LEARNING BY ASSOCIATIONS + CALLIGRAPHY)
    // =========================================================================
    private val hanziDeck = listOf(
        HanziCard(1, "人", "rén (жэнь)", "Человек", "Две шагающие ноги в профиль — шагающий вперёд человек.", "Ключ #9: Человек", "База", 1, "中国人", "Житель Китая"),
        HanziCard(2, "木", "mù (му)", "Дерево", "Вертикальный ствол, горизонтальные ветви и расходящиеся вниз корни.", "Ключ #75: Дерево", "Природа", 1, "木头", "Древесина"),
        HanziCard(3, "林", "lín (линь)", "Роща / Лес", "Два дерева 木 стоят рядом плечом к плечу — получается светлая роща!", "木 + 木", "Природа", 1, "树林", "Лесная роща"),
        HanziCard(4, "森", "sēn (сэнь)", "Густая тайга", "Целых три дерева 木 пирамидой — непроходимая приморская тайга!", "木 + 木 + 木", "Природа", 2, "森林", "Густой лес / тайга"),
        HanziCard(5, "火", "huǒ (хо)", "Огонь", "Языки пламени в центре и две летящие по бокам искры костра.", "Ключ #86: Огонь", "Стихии", 1, "火车", "Поезд («огненная повозка»)"),
        HanziCard(6, "山", "shān (шань)", "Гора / Сопка", "Три горные вершины на горизонте, центральная сопка самая высокая.", "Ключ #46: Гора", "Природа", 1, "火山", "Вулкан («огненная гора»)"),
        HanziCard(7, "水", "shuǐ (шуй)", "Вода", "Стремительный центральный поток реки и брызги волн по сторонам.", "Ключ #85: Вода", "Стихии", 1, "喝水", "Пить воду"),
        HanziCard(8, "日", "rì (жи)", "Солнце / День", "Окно на восток с солнечным лучом посередине.", "Ключ #72: Солнце", "Космос", 1, "今日", "Сегодняшний день"),
        HanziCard(9, "月", "yuè (юэ)", "Луна / Месяц", "Изогнутый серп растущего месяца с двумя облачками на ночном небе.", "Ключ #74: Луна", "Космос", 1, "月亮", "Яркая луна"),
        HanziCard(10, "明", "míng (мин)", "Ясный / Светлый", "Солнце 日 и Луна 月 светят вместе — максимально светло и ясно!", "日 (солнце) + 月 (луна)", "Мудрость", 1, "明天", "Завтра («светлый день»)"),
        HanziCard(11, "女", "nǚ (нюй)", "Женщина", "Изящный силуэт танцующей девушки в традиционном поклоне.", "Ключ #38: Женщина", "Люди", 1, "女儿", "Дочь"),
        HanziCard(12, "子", "zǐ (цзы)", "Ребёнок / Мудрец", "Малыш в пелёнке, радостно раскинувший ручки в стороны.", "Ключ #39: Дитя", "Люди", 1, "孔子", "Конфуций (Учитель Кун)"),
        HanziCard(13, "好", "hǎo (хао)", "Хорошо / Любить", "Женщина 女 рядом с ребёнком 子 — символ счастья, тепла и добра!", "女 + 子", "Общение", 1, "你好", "Привет! («Тебе добра»)"),
        HanziCard(14, "安", "ān (ань)", "Спокойствие / Мир", "Женщина 女 под надёжной крышей дома 宀 — в доме царит мир и уют.", "宀 (крыша) + 女", "Гармония", 1, "安全", "Безопасность"),
        HanziCard(15, "家", "jiā (цзя)", "Дом / Семья", "Крыша 宀, а под ней кабанчик 豕 — в древности достаток в хозяйстве означал крепкую семью!", "宀 (крыша) + 豕", "Быт", 1, "大家", "Все вместе / Семья"),
        HanziCard(16, "心", "xīn (синь)", "Сердце / Душа", "Чаша сердца и три бьющиеся капли-импульса эмоций.", "Ключ #61: Сердце", "Чувства", 1, "开心", "Радоваться («открыть сердце»)"),
        HanziCard(17, "友", "yǒu (ю)", "Друг", "Две протянутые друг другу руки в крепком рукопожатии.", "Рука + Рука", "Общение", 1, "朋友", "Верный друг"),
        HanziCard(18, "信", "xìn (синь)", "Письмо / Доверие", "Человек 亻 стоит рядом со своим Словом 言 — верность слову и весточка-письмо!", "亻 (человек) + 言 (речь)", "Связь", 2, "写信", "Написать письмо"),
        HanziCard(19, "电", "diàn (дянь)", "Электричество / Связь", "Молния, пробивающая грозовое облако и уходящая хвостом разряда вниз.", "Дождь + Молния", "Технологии", 1, "电话", "Телефон («электрическая речь»)"),
        HanziCard(20, "海", "hǎi (хай)", "Море / Океан", "Три капли воды 氵 слева + «каждый день» 每 — бескрайнее Японское море, куда текут все реки!", "氵 (вода) + 每", "Приморье", 2, "大海", "Бескрайний океан"),
        HanziCard(21, "茶", "chá (ча)", "Чай", "Трава 艹 сверху, человек 人 посередине собирает листья с чайного куста 木 снизу!", "艹 + 人 + 木", "Культура", 1, "绿茶", "Зелёный чай"),
        HanziCard(22, "龙", "lóng (лун)", "Дракон", "Изгибающийся в небесах восточный дракон с гребнем — символ энергии и мудрости.", "Ключ: Дракон", "Культура", 2, "神龙", "Небесный дракон"),
        HanziCard(23, "道", "dào (дао)", "Путь / Дао", "Голова мудреца 首 на дороге движения 辶 — осознанный жизненный путь.", "辶 (идти) + 首", "Философия", 2, "知道", "Знать («ведать путь»)"),
        HanziCard(24, "福", "fú (фу)", "Счастье / Благополучие", "Алтарь благословения 礻 слева + полный кувшин под одной крышей над полем 田 — полный достаток!", "礻 + 一 + 口 + 田", "Культура", 2, "幸福", "Счастье")
    )

    private fun buildHanziDojoView(): View {
        ensureTtsInitialized()
        val masteryMap = app.db.getHanziMasteryMap()
        val card = hanziDeck[hanziIdx.coerceIn(0, hanziDeck.lastIndex)]
        val stars = masteryMap[card.id] ?: 0

        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 12), Theme.dp(ctx, 16), Theme.dp(ctx, 24))
        }

        // Top nav
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(ctx).apply {
            text = "← Экосистема"
            setTextColor(Theme.accentCyan)
            textSize = 14.5f
            paint.isFakeBoldText = true
            setOnClickListener { activeModule = null; render() }
        })
        top.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        top.addView(Components.actionChip(ctx, if (hanziQuizMode) "📖 Режим карточек" else "🧠 Викторина", hanziQuizMode, Theme.accentCoral) {
            hanziQuizMode = !hanziQuizMode
            render()
        })
        col.addView(top)

        col.addView(TextView(ctx).apply {
            text = "🐼 Ханьцзы Додзё"
            setTextColor(Theme.textPrimary)
            textSize = 21f
            paint.isFakeBoldText = true
            setPadding(0, Theme.dp(ctx, 8), 0, Theme.dp(ctx, 2))
        })
        col.addView(TextView(ctx).apply {
            text = "Карточка ${hanziIdx + 1} из ${hanziDeck.size} • Категория: ${card.category} • Уровень: ${"★".repeat(stars)}${"☆".repeat(5 - stars)}"
            setTextColor(Theme.textSecondary)
            textSize = 12f
            setPadding(0, 0, 0, Theme.dp(ctx, 12))
        })

        if (hanziQuizMode) {
            col.addView(buildHanziQuizCard(card))
            scroll.addView(col)
            return scroll
        }

        // Main Associative Flashcard
        val flash = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 22).toFloat(), Theme.accentCoral, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 18)
            setPadding(p, p, p, p)
        }

        flash.addView(TextView(ctx).apply {
            text = card.hanzi
            setTextColor(Theme.textPrimary)
            textSize = 68f
            gravity = Gravity.CENTER
        })
        flash.addView(TextView(ctx).apply {
            text = "${card.pinyin}  —  ${card.meaningRu}"
            setTextColor(Theme.accentAmber)
            textSize = 19f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(ctx, 4), 0, Theme.dp(ctx, 8))
        })

        // Speak button (offline Android TTS)
        flash.addView(TextView(ctx).apply {
            text = "🔊 Произнести по-китайски (${card.hanzi} / ${card.exampleZh})"
            setTextColor(Color.WHITE)
            textSize = 13f
            paint.isFakeBoldText = true
            background = Theme.rippleButton(ctx, Theme.accentCoral, 12f)
            setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 8), Theme.dp(ctx, 14), Theme.dp(ctx, 8))
            setOnClickListener {
                if (ttsReady) {
                    tts?.speak("${card.hanzi}。 ${card.exampleZh}", TextToSpeech.QUEUE_FLUSH, null, "hanzi_${card.id}")
                } else {
                    Toast.makeText(ctx, "Пиньинь: ${card.pinyin} (пакет китайского голоса TTS не установлен в системе)", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Association Box
        val assocBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.cardAlt, Theme.dp(ctx, 14).toFloat())
            val p = Theme.dp(ctx, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 14)
            }
        }
        assocBox.addView(TextView(ctx).apply {
            text = "💡 Образная ассоциация (${card.radicalHint}):"
            setTextColor(Theme.accentCyan)
            textSize = 12.5f
            paint.isFakeBoldText = true
        })
        assocBox.addView(TextView(ctx).apply {
            text = card.associationRu
            setTextColor(Theme.textPrimary)
            textSize = 14f
            setPadding(0, Theme.dp(ctx, 4), 0, Theme.dp(ctx, 6))
        })
        assocBox.addView(TextView(ctx).apply {
            text = "Пример: ${card.exampleZh} — ${card.exampleRu}"
            setTextColor(Theme.textSecondary)
            textSize = 12.5f
        })
        flash.addView(assocBox)
        col.addView(flash)

        // Finger Calligraphy Practice Pad
        col.addView(Components.sectionHeader(ctx, "✍ ПРАКТИКА КАЛЛИГРАФИИ (НАРИСУЙТЕ ПАЛЬЦЕМ)"))
        val padCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 18).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 12)
            setPadding(p, p, p, p)
        }
        val calPad = CalligraphyPadView(ctx, card.hanzi).apply {
            layoutParams = LinearLayout.LayoutParams(Theme.dp(ctx, 210), Theme.dp(ctx, 210))
        }
        padCard.addView(calPad)
        padCard.addView(TextView(ctx).apply {
            text = "Очистить холст"
            setTextColor(Theme.accentCyan)
            textSize = 12.5f
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 8), Theme.dp(ctx, 12), Theme.dp(ctx, 4))
            setOnClickListener { calPad.clearPad() }
        })
        col.addView(padCard)

        // Navigation + Mastery Row
        val navRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 14)
            }
        }
        navRow.addView(TextView(ctx).apply {
            text = "← Пред."
            setTextColor(Theme.textPrimary)
            gravity = Gravity.CENTER
            background = Theme.rippleCard(ctx, Theme.cardAlt, 13f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 12), Theme.dp(ctx, 12), Theme.dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Theme.dp(ctx, 6) }
            setOnClickListener {
                hanziIdx = if (hanziIdx - 1 < 0) hanziDeck.lastIndex else hanziIdx - 1
                render()
            }
        })
        navRow.addView(TextView(ctx).apply {
            text = "✓ Запомнил!"
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(ctx, Theme.accentEmerald, 13f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 12), Theme.dp(ctx, 12), Theme.dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { rightMargin = Theme.dp(ctx, 6) }
            setOnClickListener {
                app.db.updateHanziMastery(card.id, +1)
                hanziIdx = (hanziIdx + 1) % hanziDeck.size
                render()
            }
        })
        navRow.addView(TextView(ctx).apply {
            text = "След. →"
            setTextColor(Theme.textPrimary)
            gravity = Gravity.CENTER
            background = Theme.rippleCard(ctx, Theme.cardAlt, 13f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 12), Theme.dp(ctx, 12), Theme.dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                hanziIdx = (hanziIdx + 1) % hanziDeck.size
                render()
            }
        })
        col.addView(navRow)

        scroll.addView(col)
        return scroll
    }

    private fun buildHanziQuizCard(target: HanziCard): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 22).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 20)
            setPadding(p, p, p, p)
        }
        box.addView(TextView(ctx).apply {
            text = "Что означает этот иероглиф?"
            setTextColor(Theme.textSecondary)
            textSize = 14f
        })
        box.addView(TextView(ctx).apply {
            text = target.hanzi
            setTextColor(Theme.textPrimary)
            textSize = 72f
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(ctx, 10), 0, Theme.dp(ctx, 14))
        })

        val distractors = (hanziDeck.filter { it.id != target.id }.shuffled().take(3) + target).shuffled()
        for (opt in distractors) {
            box.addView(TextView(ctx).apply {
                text = "${opt.meaningRu}  •  ${opt.pinyin}"
                setTextColor(Theme.textPrimary)
                textSize = 15f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                background = Theme.rippleCard(ctx, Theme.cardAlt, 14f)
                setPadding(Theme.dp(ctx, 14), Theme.dp(ctx, 13), Theme.dp(ctx, 14), Theme.dp(ctx, 13))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 9)
                }
                setOnClickListener {
                    if (opt.id == target.id) {
                        app.db.updateHanziMastery(target.id, +1)
                        Toast.makeText(ctx, "Верно! ${target.hanzi} — ${target.meaningRu} ★", Toast.LENGTH_SHORT).show()
                        hanziIdx = (hanziIdx + 1) % hanziDeck.size
                        render()
                    } else {
                        Toast.makeText(ctx, "Подсказка: ${target.associationRu}", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
        return box
    }

    // =========================================================================
    // MODULE 2: MAHJONG BLAST / CULTURAL SOLITAIRE (36 TILES, 6x6 LAYERED ROWS)
    // =========================================================================
    private fun resetMahjongBoard() {
        mahjongBoard.clear()
        selectedTileSlot = null
        mahjongCombo = 0
        // 9 tile types * 4 tiles each = 36 tiles (6x6 board)
        val pool = mutableListOf<TileSpec>()
        for (spec in tileCatalog) {
            repeat(4) { pool.add(spec) }
        }
        pool.shuffle()
        pool.forEachIndexed { idx, s ->
            mahjongBoard.add(MahjongTile(idx, s.code, s.sym, s.nameRu, s.lore, s.color, false))
        }
    }

    private fun isTileFreeInRow(slot: Int): Boolean {
        val t = mahjongBoard.getOrNull(slot) ?: return false
        if (t.matched) return false
        val row = slot / 6
        val col = slot % 6
        val leftEmpty = (col == 0) || mahjongBoard[row * 6 + (col - 1)].matched
        val rightEmpty = (col == 5) || mahjongBoard[row * 6 + (col + 1)].matched
        return leftEmpty || rightEmpty
    }

    private fun buildMahjongView(): View {
        if (mahjongBoard.isEmpty()) resetMahjongBoard()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 12), Theme.dp(ctx, 16), Theme.dp(ctx, 24))
        }

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(ctx).apply {
            text = "← Экосистема"
            setTextColor(Theme.accentCyan)
            textSize = 14.5f
            paint.isFakeBoldText = true
            setOnClickListener { activeModule = null; render() }
        })
        top.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val remaining = mahjongBoard.count { !it.matched }
        top.addView(Components.pillBadge(ctx, "Очки: $mahjongScore • Костей: $remaining", Theme.accentEmerald))
        col.addView(top)

        col.addView(TextView(ctx).apply {
            text = "🀄 Маджонг Культура"
            setTextColor(Theme.textPrimary)
            textSize = 21f
            paint.isFakeBoldText = true
            setPadding(0, Theme.dp(ctx, 8), 0, Theme.dp(ctx, 8))
        })

        // 6x6 Mahjong Solitaire Grid
        val gridCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 20).toFloat(), Theme.border, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 10)
            setPadding(p, p, p, p)
        }

        val grid = GridLayout(ctx).apply {
            columnCount = 6
            rowCount = 6
        }

        val screenW = ctx.resources.displayMetrics.widthPixels
        val tileW = ((screenW - Theme.dp(ctx, 84)) / 6).coerceAtLeast(Theme.dp(ctx, 42))
        val tileH = (tileW * 1.24f).toInt()

        for (slot in 0 until 36) {
            val tile = mahjongBoard[slot]
            val free = isTileFreeInRow(slot)
            val isSelected = selectedTileSlot == slot

            val cell = TextView(ctx).apply {
                if (tile.matched) {
                    text = ""
                    background = Theme.roundedBg(Theme.withAlpha(Theme.border, 28), Theme.dp(ctx, 8).toFloat())
                } else {
                    text = tile.symbol
                    textSize = if (tile.symbol.length > 1) 13.5f else 20f
                    paint.isFakeBoldText = true
                    gravity = Gravity.CENTER
                    setTextColor(if (free) tile.color else Theme.textMuted)
                    val fill = when {
                        isSelected -> Theme.withAlpha(Theme.accentCyan, 65)
                        free -> if (Theme.isDark) 0xFF1F2D4D.toInt() else 0xFFFFFFFF.toInt()
                        else -> if (Theme.isDark) 0xFF0E1526.toInt() else 0xFFE2E8F0.toInt()
                    }
                    val stroke = when {
                        isSelected -> Theme.accentCyan
                        free -> Theme.withAlpha(tile.color, 130)
                        else -> Theme.border
                    }
                    background = Theme.roundedBg(fill, Theme.dp(ctx, 9).toFloat(), stroke, Theme.dp(ctx, if (isSelected) 2f else 1f))
                    setOnClickListener {
                        if (!free) {
                            Toast.makeText(ctx, "Кость зажата соседями слева и справа! Выберите крайнюю кость в ряду.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val prevSlot = selectedTileSlot
                        if (prevSlot == null || prevSlot == slot) {
                            selectedTileSlot = if (prevSlot == slot) null else slot
                            render()
                        } else {
                            val prevTile = mahjongBoard[prevSlot]
                            if (prevTile.code == tile.code) {
                                prevTile.matched = true
                                tile.matched = true
                                selectedTileSlot = null
                                mahjongCombo++
                                mahjongScore += 25 * mahjongCombo
                                lastCulturalNote = "✨ ${tile.nameRu}: ${tile.loreRu}"
                                if (mahjongBoard.all { it.matched }) {
                                    lastCulturalNote = "🏆 Победа! Все 36 костей разобраны! Нажмите «Новая партия» для следующего расклада."
                                }
                                render()
                            } else {
                                mahjongCombo = 0
                                selectedTileSlot = slot
                                render()
                            }
                        }
                    }
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = tileW
                    height = tileH
                    val m = Theme.dp(ctx, 2.5f)
                    setMargins(m, m, m, m)
                }
            }
            grid.addView(cell)
        }
        gridCard.addView(grid)
        col.addView(gridCard)

        // Controls: Hint, Shuffle, New Game
        val ctrlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 12)
            }
        }
        ctrlRow.addView(TextView(ctx).apply {
            text = "💡 Подсказка"
            setTextColor(Theme.textPrimary)
            textSize = 13f
            gravity = Gravity.CENTER
            background = Theme.rippleCard(ctx, Theme.cardAlt, 12f)
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 11), Theme.dp(ctx, 10), Theme.dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Theme.dp(ctx, 6) }
            setOnClickListener {
                val freeTiles = mahjongBoard.filter { isTileFreeInRow(it.slotId) }
                val pair = freeTiles.groupBy { it.code }.values.firstOrNull { it.size >= 2 }
                if (pair != null) {
                    selectedTileSlot = pair[0].slotId
                    Toast.makeText(ctx, "Найдена пара: ${pair[0].nameRu}!", Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    Toast.makeText(ctx, "Свободных пар нет — нажмите «Перемешать»!", Toast.LENGTH_SHORT).show()
                }
            }
        })
        ctrlRow.addView(TextView(ctx).apply {
            text = "🔀 Перемешать"
            setTextColor(Theme.textPrimary)
            textSize = 13f
            gravity = Gravity.CENTER
            background = Theme.rippleCard(ctx, Theme.cardAlt, 12f)
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 11), Theme.dp(ctx, 10), Theme.dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Theme.dp(ctx, 6) }
            setOnClickListener {
                val activeSpecs = mahjongBoard.filter { !it.matched }.shuffled()
                var k = 0
                for (i in mahjongBoard.indices) {
                    if (!mahjongBoard[i].matched) {
                        val s = activeSpecs[k++]
                        mahjongBoard[i] = MahjongTile(i, s.code, s.symbol, s.nameRu, s.loreRu, s.color, false)
                    }
                }
                selectedTileSlot = null
                render()
            }
        })
        ctrlRow.addView(TextView(ctx).apply {
            text = "🔄 Заново"
            setTextColor(Color.WHITE)
            textSize = 13f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(ctx, Theme.accentEmerald, 12f)
            setPadding(Theme.dp(ctx, 10), Theme.dp(ctx, 11), Theme.dp(ctx, 10), Theme.dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                resetMahjongBoard()
                render()
            }
        })
        col.addView(ctrlRow)

        // Cultural Encyclopedia Card
        val loreCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.accentAmber, Theme.dp(ctx, 1))
            val p = Theme.dp(ctx, 14)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 12)
            }
        }
        loreCard.addView(TextView(ctx).apply {
            text = "🏮 Культурная энциклопедия Востока"
            setTextColor(Theme.accentAmber)
            textSize = 12.5f
            paint.isFakeBoldText = true
        })
        loreCard.addView(TextView(ctx).apply {
            text = lastCulturalNote
            setTextColor(Theme.textPrimary)
            textSize = 13.5f
            setPadding(0, Theme.dp(ctx, 4), 0, 0)
        })
        col.addView(loreCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // MODULE 3: TERONPAY CRYPTO WALLET (ECDSA OFFLINE AIRMESH P2P VOUCHERS)
    // =========================================================================
    private fun buildWalletView(): View {
        val me = app.db.getProfile()
        val balances = app.db.getWalletBalances()
        val txs = app.db.getWalletTransactions()
        val totalRub = balances.sumOf { it.balance * it.rateRub }

        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 12), Theme.dp(ctx, 16), Theme.dp(ctx, 24))
        }

        col.addView(TextView(ctx).apply {
            text = "← Экосистема"
            setTextColor(Theme.accentCyan)
            textSize = 14.5f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, Theme.dp(ctx, 10))
            setOnClickListener { activeModule = null; render() }
        })

        // Portfolio Hero Card
        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.gradientBg(
                intArrayOf(0xFF0E293C.toInt(), 0xFF1D174A.toInt(), 0xFF0F172A.toInt()),
                Theme.dp(ctx, 24).toFloat(),
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                Theme.accentCyan,
                Theme.dp(ctx, 1)
            )
            val p = Theme.dp(ctx, 20)
            setPadding(p, p, p, p)
        }
        hero.addView(TextView(ctx).apply {
            text = "TeronPay • Децентрализованный сейф"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 12.5f
        })
        hero.addView(TextView(ctx).apply {
            text = "≈ ${"%,.0f".format(Locale("ru"), totalRub)} ₽"
            setTextColor(Color.WHITE)
            textSize = 30f
            paint.isFakeBoldText = true
            setPadding(0, Theme.dp(ctx, 4), 0, Theme.dp(ctx, 4))
        })
        hero.addView(TextView(ctx).apply {
            text = "Адрес: ${me.walletAddress.take(18)}…${me.walletAddress.takeLast(6)} (нажмите, чтобы скопировать)"
            setTextColor(Theme.accentCyan)
            textSize = 11.5f
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("TeronPay Address", me.walletAddress))
                Toast.makeText(ctx, "Адрес кошелька скопирован!", Toast.LENGTH_SHORT).show()
            }
        })

        val actRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Theme.dp(ctx, 16)
            }
        }
        actRow.addView(TextView(ctx).apply {
            text = "💸 Перевести по AirMesh"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = Theme.rippleButton(ctx, Theme.accentIndigo, 13f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 11), Theme.dp(ctx, 12), Theme.dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { rightMargin = Theme.dp(ctx, 8) }
            setOnClickListener { showSendCryptoDialog() }
        })
        actRow.addView(TextView(ctx).apply {
            text = "🔑 Seed-фраза"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            gravity = Gravity.CENTER
            background = Theme.rippleCard(ctx, 0x33FFFFFF, 13f, Color.TRANSPARENT, 0f)
            setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 11), Theme.dp(ctx, 12), Theme.dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("🔑 Мнемоническая фраза (12 слов)")
                    .setMessage("${me.seedPhrase}\n\nХраните фразу в тайне: она даёт полный доступ к вашим криптоключам ECDSA.")
                    .setPositiveButton("Понятно", null)
                    .show()
            }
        })
        hero.addView(actRow)
        col.addView(hero)

        // Assets List
        col.addView(Components.sectionHeader(ctx, "АКТИВЫ КОШЕЛЬКА"))
        for (b in balances) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 16).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 13)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 8)
                }
            }
            row.addView(Components.squircleIcon(ctx, b.glyph, b.accentColor, 42, 18f))
            val info = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Theme.dp(ctx, 12) }
            }
            info.addView(TextView(ctx).apply {
                text = b.name
                setTextColor(Theme.textPrimary)
                textSize = 15f
                paint.isFakeBoldText = true
            })
            info.addView(TextView(ctx).apply {
                text = b.symbol
                setTextColor(Theme.textSecondary)
                textSize = 12f
            })
            row.addView(info)

            val valCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            val fmtBal = if (b.symbol == "BTC") "%.4f".format(b.balance) else "%.2f".format(b.balance)
            valCol.addView(TextView(ctx).apply {
                text = "$fmtBal ${b.symbol}"
                setTextColor(Theme.textPrimary)
                textSize = 15f
                paint.isFakeBoldText = true
            })
            valCol.addView(TextView(ctx).apply {
                text = "≈ ${"%,.0f".format(Locale("ru"), b.balance * b.rateRub)} ₽"
                setTextColor(Theme.textSecondary)
                textSize = 12f
            })
            row.addView(valCol)
            col.addView(row)
        }

        // Transactions History
        col.addView(Components.sectionHeader(ctx, "ИСТОРИЯ ПОДПИСАННЫХ ОПЕРАЦИЙ"))
        for (tx in txs) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.roundedBg(Theme.card, Theme.dp(ctx, 14).toFloat(), Theme.border, Theme.dp(ctx, 1))
                val p = Theme.dp(ctx, 12)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Theme.dp(ctx, 7)
                }
            }
            val signStr = if (tx.isIncoming) "+" else "−"
            val colSign = if (tx.isIncoming) Theme.accentEmerald else Theme.accentAmber
            card.addView(Components.squircleIcon(ctx, if (tx.isIncoming) "↓" else "↑", colSign, 38, 17f))
            val mid = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Theme.dp(ctx, 10) }
            }
            mid.addView(TextView(ctx).apply {
                text = "${tx.counterpartyName} • ${tx.memo}"
                setTextColor(Theme.textPrimary)
                textSize = 13.5f
                paint.isFakeBoldText = true
                maxLines = 1
            })
            mid.addView(TextView(ctx).apply {
                text = "${tx.txHash} • ECDSA ✓ • ${Theme.formatTime(tx.timestampMs)}"
                setTextColor(Theme.textMuted)
                textSize = 11f
            })
            card.addView(mid)
            card.addView(TextView(ctx).apply {
                text = "$signStr${tx.amount} ${tx.symbol}"
                setTextColor(colSign)
                textSize = 14f
                paint.isFakeBoldText = true
            })
            col.addView(card)
        }

        scroll.addView(col)
        return scroll
    }

    private fun showSendCryptoDialog() {
        val me = app.db.getProfile()
        val peerDefault = app.meshEngine.livePeers.values.firstOrNull()?.nodeId
            ?: app.db.getContacts().firstOrNull()?.nodeId
            ?: "TRN-FRIEND01"

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Theme.dp(ctx, 16)
            setPadding(p, p, p, p)
        }
        val toEdit = EditText(ctx).apply {
            setText(peerDefault)
            hint = "ID узла (TRN-...) или trn1-адрес"
            setSingleLine(true)
        }
        val symEdit = EditText(ctx).apply {
            setText("TRN")
            hint = "Валюта (TRN, TON, USDT)"
            setSingleLine(true)
        }
        val amtEdit = EditText(ctx).apply {
            setText("15.0")
            hint = "Сумма"
            setSingleLine(true)
        }
        val memoEdit = EditText(ctx).apply {
            setText("Офлайн P2P расчёт AirMesh")
            hint = "Комментарий к чеку"
            setSingleLine(true)
        }
        layout.addView(toEdit)
        layout.addView(symEdit)
        layout.addView(amtEdit)
        layout.addView(memoEdit)

        AlertDialog.Builder(ctx)
            .setTitle("💸 P2P крипто-перевод через AirMesh")
            .setView(layout)
            .setPositiveButton("Подписать ECDSA и отправить") { _, _ ->
                val to = toEdit.text.toString().trim()
                val sym = symEdit.text.toString().trim().uppercase()
                val amt = amtEdit.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val memo = memoEdit.text.toString().trim()
                val txHash = "0x" + TeronCrypto.shortSha256Hex("${me.nodeId}:$to:$sym:$amt:${System.currentTimeMillis()}", 10)
                val sig = TeronCrypto.signData(me.privateKeyB64, "$txHash:$sym:$amt:$to")

                val ok = app.db.recordWalletTransfer(
                    txHash = txHash,
                    symbol = sym,
                    amount = amt,
                    counterpartyName = to,
                    counterpartyAddress = to,
                    isIncoming = false,
                    memo = memo,
                    signatureHex = sig.take(28),
                    viaAirMesh = true
                )
                if (ok) {
                    app.meshEngine.broadcastSignedCryptoTransfer(txHash, sym, amt, to, memo, sig.take(28))
                    Toast.makeText(ctx, "Чек $txHash подписан ECDSA и отправлен в AirMesh!", Toast.LENGTH_LONG).show()
                    render()
                } else {
                    Toast.makeText(ctx, "Недостаточно средств на балансе $sym или неверная сумма", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
