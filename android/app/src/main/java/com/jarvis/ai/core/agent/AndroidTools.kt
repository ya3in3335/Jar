package com.jarvis.ai.core.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.core.net.toUri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * The tools that work through ordinary Android intents and system services:
 * no accessibility service, no screen capture, no special grants.
 *
 * Everything here either does the thing or reports why it could not. None of
 * these return success without having acted.
 */

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.content?.toIntOrNull()

private fun encode(query: String): String = URLEncoder.encode(query, "UTF-8")

/** How each known app is reached, with and without a search term. */
private data class AppTarget(
    val label: String,
    val packageName: String?,
    val search: ((String) -> Uri)? = null,
    val open: Uri? = null,
)

private val APP_TARGETS: Map<String, AppTarget> = mapOf(
    "youtube" to AppTarget(
        "يوتيوب",
        "com.google.android.youtube",
        search = { "https://www.youtube.com/results?search_query=${encode(it)}".toUri() },
    ),
    "instagram" to AppTarget("إنستغرام", "com.instagram.android"),
    "facebook" to AppTarget("فيسبوك", "com.facebook.katana"),
    "whatsapp" to AppTarget("واتساب", "com.whatsapp"),
    "telegram" to AppTarget("تيليجرام", "org.telegram.messenger"),
    "messages" to AppTarget("الرسائل", null, open = "sms:".toUri()),
    "phone" to AppTarget("الهاتف", null, open = "tel:".toUri()),
    "browser" to AppTarget(
        "المتصفح",
        null,
        search = { "https://www.google.com/search?q=${encode(it)}".toUri() },
        open = "https://www.google.com".toUri(),
    ),
    "music" to AppTarget("الموسيقى", null),
    "camera" to AppTarget("الكاميرا", null),
    "settings" to AppTarget("الإعدادات", null),
    "calculator" to AppTarget("الحاسبة", null),
)

// ------------------------------------------------------------- open_application

class OpenApplicationTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "open_application",
        description = "يفتح تطبيقًا على الهاتف، ويمكن تمرير عبارة بحث تُفتح داخله مباشرة.",
        parameters = listOf(
            Tool.Param(
                "app", "string",
                "معرّف التطبيق: " + APP_TARGETS.keys.joinToString(", "),
                enum = APP_TARGETS.keys.toList(),
            ),
            Tool.Param("search", "string", "عبارة بحث تُفتح داخل التطبيق", required = false),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.str("app") ?: return ToolResult.failure("لم يُحدَّد التطبيق")
        val target = APP_TARGETS[id] ?: return ToolResult.failure("تطبيق غير معروف: $id")
        val query = args.str("search")

        // A search term resolves to a deep link when the app supports one.
        val searchUri = target.search
        if (query != null && searchUri != null) {
            val intent = Intent(Intent.ACTION_VIEW, searchUri(query))
            target.packageName?.let { pkg ->
                if (packageAvailable(pkg)) intent.setPackage(pkg)
            }
            if (start(intent)) return ToolResult.success("فتحت ${target.label} وبحثت عن: $query")
            // Fall back to the browser rather than reporting failure.
            if (start(Intent(Intent.ACTION_VIEW, searchUri(query)))) {
                return ToolResult.success("فتحت نتائج البحث عن \"$query\" في المتصفح")
            }
            return ToolResult.failure("تعذّر فتح ${target.label}")
        }

        // Known package: use its own launcher entry.
        target.packageName?.let { pkg ->
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                return if (start(launch)) ToolResult.success("فتحت ${target.label}")
                else ToolResult.failure("تعذّر فتح ${target.label}")
            }
            if (target.open == null && target.search == null) {
                return ToolResult.failure("${target.label} غير مثبّت على هذا الهاتف")
            }
        }

        // System surfaces reached by action rather than package.
        val byAction = when (id) {
            "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "music" -> Intent(Intent.ACTION_VIEW).setType("audio/*")
            "calculator" -> Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CALCULATOR")
            else -> target.open?.let { Intent(Intent.ACTION_VIEW, it) }
        }
        if (byAction != null && start(byAction)) return ToolResult.success("فتحت ${target.label}")
        return ToolResult.failure("تعذّر فتح ${target.label} على هذا الهاتف")
    }

    private fun packageAvailable(pkg: String) =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

// -------------------------------------------------------------------- open_url

class OpenUrlTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "open_url",
        description = "يفتح رابطًا في المتصفح.",
        parameters = listOf(Tool.Param("url", "string", "الرابط الكامل، يبدأ بـ http أو https")),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val raw = args.str("url") ?: return ToolResult.failure("لم يُحدَّد الرابط")
        // Only web schemes: an intent:// or file:// URL from a model is not
        // something to hand to startActivity.
        val uri = runCatching { raw.toUri() }.getOrNull()
        if (uri?.scheme?.lowercase() !in setOf("http", "https")) {
            return ToolResult.failure("يُسمح بروابط http و https فقط")
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.success("فتحت الرابط")
        } catch (e: ActivityNotFoundException) {
            ToolResult.failure("لا يوجد متصفح لفتح الرابط")
        }
    }
}

// ------------------------------------------------------------------ search_web

class SearchWebTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "open_web_search",
        description = "يفتح نتائج البحث في المتصفح ليقرأها المستخدم بنفسه. للبحث والحصول على خلاصة استخدم search_web.",
        parameters = listOf(Tool.Param("query", "string", "عبارة البحث")),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.str("query") ?: return ToolResult.failure("لم تُحدَّد عبارة البحث")
        val uri = "https://www.google.com/search?q=${encode(query)}".toUri()
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.success("بحثت عن: $query")
        } catch (e: ActivityNotFoundException) {
            ToolResult.failure("لا يوجد متصفح للبحث")
        }
    }
}

// -------------------------------------------------------------- control_volume

class ControlVolumeTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "control_volume",
        description = "يرفع أو يخفض أو يكتم صوت الوسائط.",
        parameters = listOf(
            Tool.Param("direction", "string", "الاتجاه", enum = listOf("up", "down", "mute", "unmute")),
            Tool.Param("step", "number", "عدد الدرجات، من 1 إلى 10", required = false),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return ToolResult.failure("خدمة الصوت غير متاحة")
        val direction = args.str("direction") ?: return ToolResult.failure("لم يُحدَّد الاتجاه")
        val steps = (args.int("step") ?: 2).coerceIn(1, 10)
        val stream = AudioManager.STREAM_MUSIC

        when (direction) {
            "up", "down" -> {
                val adjust = if (direction == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(steps) { audio.adjustStreamVolume(stream, adjust, 0) }
            }
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            else -> return ToolResult.failure("اتجاه غير معروف: $direction")
        }

        val now = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val percent = now * 100 / max
        return ToolResult.success(
            when (direction) {
                "up" -> "رفعت الصوت إلى $percent٪"
                "down" -> "خفضت الصوت إلى $percent٪"
                "mute" -> "كتمت الصوت"
                else -> "أعدت الصوت"
            },
        )
    }
}

// ------------------------------------------------------------- media transport

class MediaControlTool(private val context: Context, private val play: Boolean) : ToolExecutor {
    override val tool = Tool(
        name = if (play) "play_media" else "pause_media",
        description = if (play) "يشغّل الوسائط المتوقّفة." else "يوقف تشغيل الوسائط.",
        parameters = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return ToolResult.failure("خدمة الصوت غير متاحة")
        val code = if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE

        // Send a media button event; whichever player holds audio focus acts.
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))

        // Honest wording: the event was sent, and no app may be listening.
        return ToolResult.success(
            if (play) "أرسلت أمر التشغيل" else "أرسلت أمر الإيقاف",
            detail = "media key dispatched; no app may be holding audio focus",
        )
    }
}

// ---------------------------------------------------------------- launch_camera

class LaunchCameraTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "launch_camera",
        description = "يفتح تطبيق الكاميرا.",
        parameters = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult = try {
        context.startActivity(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        ToolResult.success("فتحت الكاميرا")
    } catch (e: ActivityNotFoundException) {
        ToolResult.failure("لا يوجد تطبيق كاميرا")
    }
}

// -------------------------------------------------------------------- set_alarm

class SetAlarmTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "set_alarm",
        description = "يضبط منبّهًا في وقت محدّد.",
        parameters = listOf(
            Tool.Param("hour", "number", "الساعة بنظام 24، من 0 إلى 23"),
            Tool.Param("minute", "number", "الدقيقة، من 0 إلى 59"),
            Tool.Param("label", "string", "عنوان المنبّه", required = false),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val hour = args.int("hour") ?: return ToolResult.failure("لم تُحدَّد الساعة")
        val minute = args.int("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) return ToolResult.failure("وقت غير صحيح")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            // Never skip the clock app's own UI: the user sees what was set.
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.str("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }

        return try {
            context.startActivity(intent)
            ToolResult.success("ضبطت منبّهًا على %02d:%02d".format(hour, minute))
        } catch (e: ActivityNotFoundException) {
            ToolResult.failure("لا يوجد تطبيق منبّه")
        }
    }
}

// ------------------------------------------------------------ list_installed_apps

class ListInstalledAppsTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "list_installed_apps",
        description = "يسرد التطبيقات المعروفة المثبّتة على الهاتف.",
        parameters = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        // Only the catalogue, not every package: a full dump would be a long
        // and privacy-heavy payload to send to a model.
        val installed = APP_TARGETS.entries.filter { (_, target) ->
            target.packageName?.let { context.packageManager.getLaunchIntentForPackage(it) != null } ?: true
        }
        val names = installed.joinToString("، ") { it.value.label }
        return ToolResult.success("التطبيقات المتاحة: $names", detail = installed.joinToString(",") { it.key })
    }
}

/** Build the executor set this device can actually offer. */
fun defaultToolExecutors(context: Context): List<ToolExecutor> = listOf(
    OpenApplicationTool(context),
    OpenUrlTool(context),
    SearchWebTool(context),
    *deviceTools(context).toTypedArray(),
    ControlVolumeTool(context),
    MediaControlTool(context, play = true),
    MediaControlTool(context, play = false),
    LaunchCameraTool(context),
    SetAlarmTool(context),
    ListInstalledAppsTool(context),
)
