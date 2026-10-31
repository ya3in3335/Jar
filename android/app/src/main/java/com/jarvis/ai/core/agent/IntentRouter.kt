package com.jarvis.ai.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Local intent router — spec section 16B.
 *
 * Unambiguous commands are resolved by deterministic rules and never reach
 * a language model: "افتح يوتيوب" costs nothing and answers instantly. Only
 * ambiguity, reasoning or planning is worth a model call.
 *
 * Mirrors `jarvis_win.core.intent` on the desktop so both platforms route
 * the same utterance the same way.
 */
object IntentRouter {

    /** A resolved local command. */
    data class Intent(
        val tool: String,
        val args: Map<String, Any?> = emptyMap(),
        /**
         * True when the action changes something the user would not want
         * undone silently (spec section 11). Callers must confirm first.
         */
        val sensitive: Boolean = false,
    )

    private val DIACRITICS = Regex("[ً-ْٰـ]")

    private val FOLD = mapOf(
        'أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا', 'ٱ' to 'ا',
        'ى' to 'ي', 'ئ' to 'ي',
        'ة' to 'ه',
        'ؤ' to 'و',
    )

    /**
     * Fold Arabic orthographic variation so one rule matches many spellings:
     * drop diacritics and tatweel, unify alef/ya/ta-marbuta, map Arabic-Indic
     * digits to ASCII, collapse whitespace.
     */
    fun normalize(input: String): String {
        val stripped = DIACRITICS.replace(input, "")
        val folded = buildString(stripped.length) {
            for (ch in stripped) {
                when {
                    FOLD.containsKey(ch) -> append(FOLD[ch])
                    ch in '٠'..'٩' -> append(ch - 0x0660 + '0'.code)
                    ch in '۰'..'۹' -> append(ch - 0x06F0 + '0'.code)
                    else -> append(ch)
                }
            }
        }
        return folded.replace(Regex("\\s+"), " ").trim().lowercase()
    }

    /** Spoken name to canonical app id; normalised at init so keys can't drift. */
    private val RAW_ALIASES = mapOf(
        "يوتيوب" to "youtube", "اليوتيوب" to "youtube", "youtube" to "youtube",
        "انستغرام" to "instagram", "انستقرام" to "instagram", "instagram" to "instagram",
        "فيسبوك" to "facebook", "facebook" to "facebook",
        "واتساب" to "whatsapp", "whatsapp" to "whatsapp",
        "تيليجرام" to "telegram", "تلغرام" to "telegram", "telegram" to "telegram",
        "الاعدادات" to "settings", "اعدادات" to "settings", "settings" to "settings",
        "الكاميرا" to "camera", "كاميرا" to "camera", "camera" to "camera",
        "المتصفح" to "browser", "متصفح" to "browser", "كروم" to "browser",
        "chrome" to "browser", "browser" to "browser",
        "الموسيقى" to "music", "موسيقى" to "music", "music" to "music",
        "الرسائل" to "messages", "messages" to "messages",
        "الهاتف" to "phone",
        "الحاسبة" to "calculator", "calculator" to "calculator",
    )

    val appAliases: Map<String, String> = RAW_ALIASES.entries.associate { normalize(it.key) to it.value }

    // Patterns are written in NORMALISED Arabic, because route() folds the
    // input first: alef variants -> ا, ى -> ي, ة -> ه.
    private const val OPEN_VERBS = "(?:افتح|اضغط|شغل|ادخل|open|launch|start)"
    private const val SEARCH_VERBS = "(?:ابحث|بحث|search|google)"
    private const val MUSIC = "(?:موسيقي|الموسيقي|اغنيه|الاغنيه|music|song)"
    private const val SCREEN_WORDS = "(?:شاشه|الشاشه|screen)"
    private const val LOOK_VERBS = "(?:شوف|انظر|حلل|اقرا|افحص|look|analyze)"

    private val volumeUp = Regex("(?:ارفع|زيد|علي)\\s+(?:ال)?صوت|volume up")
    private val volumeDown = Regex("(?:خفض|نقص|نزل)\\s+(?:ال)?صوت|volume down")
    private val volumeMute = Regex("(?:اكتم|اسكت|كتم)\\s*(?:ال)?صوت|^mute$")
    private val pauseMedia = Regex("(?:اوقف|وقف|stop|pause)\\s*$MUSIC?\\s*$")
    private val playMedia = Regex("$OPEN_VERBS\\s+$MUSIC\\s*$")
    private val lookVerb = Regex(LOOK_VERBS)
    private val screenWord = Regex(SCREEN_WORDS)
    private val openCommand = Regex("^$OPEN_VERBS\\s+(.+)$")
    private val searchCommand = Regex("$SEARCH_VERBS\\s+(?:عن|علي|في|for)?\\s*(.+)$")
    private val trailingApp = Regex("\\s(?:في|علي)\\s+(\\S+)$")
    private val conjunction = Regex("\\s+و\\s*(?=ابحث|بحث|search)")
    private val bareUrl = Regex("^https?://\\S+$")

    /** Resolve a spoken app name, tolerating attached ال / ل prefixes. */
    private fun appId(raw: String): String? {
        val key = normalize(raw)
        for (candidate in listOf(key, key.removePrefix("ال"), key.removePrefix("لل"), key.removePrefix("ل"))) {
            appAliases[candidate]?.let { return it }
        }
        return null
    }

    /** Convert routed args into the JSON shape a [ToolExecutor] expects. */
    fun argsToJson(args: Map<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in args) {
            when (value) {
                null -> Unit // omit rather than send a null the schema rejects
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    /** Resolve an utterance locally, or return null to defer to the model. */
    fun route(utterance: String): Intent? {
        if (utterance.isBlank()) return null
        val text = normalize(utterance)

        volumeUp.find(text)?.let { return Intent("control_volume", mapOf("direction" to "up", "step" to 2)) }
        volumeDown.find(text)?.let { return Intent("control_volume", mapOf("direction" to "down", "step" to 2)) }
        volumeMute.find(text)?.let { return Intent("control_volume", mapOf("direction" to "mute")) }

        if (pauseMedia.containsMatchIn(text)) return Intent("pause_media")
        if (playMedia.containsMatchIn(text)) return Intent("play_media")

        // Keyword co-occurrence rather than word order: Algerian phrasing
        // varies ("شوف واش ظاهر في الشاشة", "حلل الشاشة").
        if (lookVerb.containsMatchIn(text) && screenWord.containsMatchIn(text)) {
            return Intent("analyze_screen")
        }

        // Checked before the generic search rule, so "افتح يوتيوب وابحث عن X"
        // stays one app action instead of becoming a bare web search.
        openCommand.find(text)?.let { match ->
            val rest = match.groupValues[1].trim()

            appId(rest)?.let { return Intent("open_application", mapOf("app" to it)) }
            if (bareUrl.matches(rest)) return Intent("open_url", mapOf("url" to rest))

            val parts = conjunction.split(rest, limit = 2)
            if (parts.size == 2) {
                val app = appId(parts[0].trim())
                val inner = route(parts[1].trim())
                if (app != null && inner?.tool == "search_web") {
                    return Intent("open_application", mapOf("app" to app, "search" to inner.args["query"]))
                }
            }
            return null // an app we do not know: let the model decide
        }

        searchCommand.find(text)?.let { match ->
            val query = match.groupValues[1].trim()
            trailingApp.find(query)?.let { inApp ->
                appId(inApp.groupValues[1])?.let { app ->
                    return Intent(
                        "open_application",
                        mapOf("app" to app, "search" to query.substring(0, inApp.range.first).trim()),
                    )
                }
            }
            if (query.isNotEmpty()) return Intent("search_web", mapOf("query" to query))
        }

        return null
    }
}
