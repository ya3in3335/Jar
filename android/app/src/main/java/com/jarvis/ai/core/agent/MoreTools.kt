package com.jarvis.ai.core.agent

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.net.toUri
import com.jarvis.ai.core.net.ChatMessage
import com.jarvis.ai.core.net.OpenRouterClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The wider tool set: research, device state, and the everyday actions a
 * multi-step instruction needs.
 *
 * Everything here works through ordinary intents and system services. Each
 * tool either does the thing or says why it could not — none report success
 * without having acted, which is what lets the agent chain them honestly.
 */

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.content?.toIntOrNull()

private fun JsonObject.bool(key: String): Boolean =
    this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun Context.launch(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
}

// ------------------------------------------------------------- web research

/**
 * Web research, through the OpenRouter key the user already has.
 *
 * OpenRouter runs a search plugin for any model when ":online" is appended
 * to the model id, so this needs no second account and no second key. The
 * answer comes back already summarised, which is what the agent needs to
 * chain into the next step — a list of blue links would not be.
 */
class WebSearchTool(
    private val client: OpenRouterClient,
    private val apiKeyProvider: () -> String?,
    private val model: String = "openai/gpt-4o-mini",
) : ToolExecutor {

    override val tool = Tool(
        name = "search_web",
        description = "يبحث في الويب ويعيد خلاصة مع المصادر. استخدمه لأي معلومة حديثة أو لا تعرفها.",
        parameters = listOf(
            Tool.Param("query", "string", "ما تريد البحث عنه، بصياغة واضحة"),
            Tool.Param("detail", "string", "short للخلاصة أو full للتفصيل", required = false,
                enum = listOf("short", "full")),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.str("query") ?: return ToolResult.failure("لم تُحدَّد عبارة البحث")
        val key = apiKeyProvider() ?: return ToolResult.failure("البحث يحتاج مفتاح OpenRouter")
        val wantsFull = args.str("detail") == "full"

        val instruction = buildString {
            append("ابحث في الويب وأجب عن: ")
            append(query)
            append(if (wantsFull) "\nأعطِ تفصيلًا منظّمًا." else "\nأعطِ خلاصة قصيرة.")
            append("\nاذكر المصادر. وإن لم تجد معلومة موثوقة، قل ذلك صراحة ولا تخمّن.")
        }

        return try {
            // ":online" is what turns on the search plugin.
            val turn = client.complete(
                apiKey = key,
                model = "$model:online",
                messages = listOf(ChatMessage.User(instruction)),
                maxTokens = if (wantsFull) 900 else 400,
            )
            val text = turn.text?.trim()
            if (text.isNullOrEmpty()) {
                ToolResult.failure("لم يرجع البحث نتيجة")
            } else {
                ToolResult.success(text, detail = "web search via OpenRouter online plugin")
            }
        } catch (e: Exception) {
            ToolResult.failure("فشل البحث: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

// ----------------------------------------------------------- device state

class BatteryStatusTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "get_battery_status",
        description = "يقرأ نسبة شحن البطارية وحالة الشحن.",
        parameters = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val manager = context.getSystemService(BatteryManager::class.java)
            ?: return ToolResult.failure("خدمة البطارية غير متاحة")
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0) return ToolResult.failure("تعذّرت قراءة البطارية")
        val charging = manager.isCharging
        return ToolResult.success(
            "البطارية $level٪" + if (charging) " وقيد الشحن" else "",
            detail = "level=$level charging=$charging",
        )
    }
}

class DeviceInfoTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "get_device_info",
        description = "يعطي طراز الهاتف وإصدار أندرويد ولغته.",
        parameters = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val locale = context.resources.configuration.locales[0]
        return ToolResult.success(
            "${Build.MANUFACTURER} ${Build.MODEL} · أندرويد ${Build.VERSION.RELEASE} · ${locale.displayName}",
        )
    }
}

class FlashlightTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "control_flashlight",
        description = "يشغّل أو يطفئ مصباح الهاتف.",
        parameters = listOf(
            Tool.Param("on", "boolean", "true للتشغيل، false للإطفاء"),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return ToolResult.failure("خدمة الكاميرا غير متاحة")
        val on = args.bool("on")
        return try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.failure("لا يوجد مصباح في هذا الهاتف")

            manager.setTorchMode(cameraId, on)
            ToolResult.success(if (on) "أشعلت المصباح" else "أطفأت المصباح")
        } catch (e: Exception) {
            ToolResult.failure("تعذّر التحكّم بالمصباح: ${e.javaClass.simpleName}")
        }
    }
}

// -------------------------------------------------------------- clipboard

class ClipboardTool(private val context: Context, private val write: Boolean) : ToolExecutor {
    override val tool = Tool(
        name = if (write) "write_clipboard" else "read_clipboard",
        description = if (write) "ينسخ نصًّا إلى الحافظة." else "يقرأ ما في حافظة النسخ.",
        parameters = if (write) listOf(Tool.Param("text", "string", "النصّ المراد نسخه")) else emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return ToolResult.failure("الحافظة غير متاحة")

        if (write) {
            val text = args.str("text") ?: return ToolResult.failure("لم يُحدَّد النصّ")
            clipboard.setPrimaryClip(ClipData.newPlainText("jarvis", text))
            return ToolResult.success("نسخت النصّ")
        }

        // Android 10+ only hands the clipboard to the focused app, so an
        // empty read is normal rather than a failure.
        val clip = clipboard.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        return if (text.isNullOrBlank()) {
            ToolResult.failure("الحافظة فارغة أو غير متاحة للتطبيق الآن")
        } else {
            ToolResult.success(text)
        }
    }
}

// ----------------------------------------------------------- communication

class ComposeMessageTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "compose_message",
        description = "يفتح رسالة جاهزة في واتساب أو الرسائل القصيرة. المستخدم هو من يضغط إرسال.",
        parameters = listOf(
            Tool.Param("app", "string", "التطبيق", enum = listOf("whatsapp", "sms")),
            Tool.Param("text", "string", "نصّ الرسالة"),
            Tool.Param("phone", "string", "الرقم بصيغة دولية، اختياري", required = false),
        ),
        // Opens a prefilled draft and never sends: the send button stays
        // the user's. Still flagged, because the text is about to leave.
        sensitive = true,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args.str("text") ?: return ToolResult.failure("لم يُحدَّد نصّ الرسالة")
        val phone = args.str("phone")?.filter { it.isDigit() || it == '+' }

        val intent = when (args.str("app")) {
            "whatsapp" -> {
                val target = if (phone != null) "https://wa.me/${phone.trimStart('+')}?text=${encode(text)}"
                else "https://wa.me/?text=${encode(text)}"
                Intent(Intent.ACTION_VIEW, target.toUri())
            }
            else -> Intent(Intent.ACTION_SENDTO, "smsto:${phone.orEmpty()}".toUri())
                .putExtra("sms_body", text)
        }

        return if (context.launch(intent)) {
            ToolResult.success("جهّزت الرسالة — اضغط إرسال بنفسك")
        } else {
            ToolResult.failure("التطبيق غير مثبّت")
        }
    }
}

class DialTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "dial_number",
        description = "يفتح لوحة الاتصال برقم جاهز. المستخدم هو من يضغط اتصال.",
        parameters = listOf(Tool.Param("phone", "string", "الرقم")),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val phone = args.str("phone") ?: return ToolResult.failure("لم يُحدَّد الرقم")
        // ACTION_DIAL, never ACTION_CALL: placing a call without the user
        // pressing the button needs a permission this app should not hold.
        val intent = Intent(Intent.ACTION_DIAL, "tel:$phone".toUri())
        return if (context.launch(intent)) ToolResult.success("فتحت الاتصال بـ $phone")
        else ToolResult.failure("تعذّر فتح تطبيق الهاتف")
    }
}

class FindContactTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "find_contact",
        description = "يفتح جهة اتصال بالاسم.",
        parameters = listOf(Tool.Param("name", "string", "اسم جهة الاتصال")),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args.str("name") ?: return ToolResult.failure("لم يُحدَّد الاسم")
        // Opens the picker filtered by name rather than reading contacts,
        // so no contacts permission is needed at all.
        val intent = Intent(Intent.ACTION_SEARCH)
            .setPackage("com.android.contacts")
            .putExtra(android.app.SearchManager.QUERY, name)
        if (context.launch(intent)) return ToolResult.success("فتحت جهات الاتصال على $name")

        val fallback = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        return if (context.launch(fallback)) ToolResult.success("فتحت جهات الاتصال")
        else ToolResult.failure("تعذّر فتح جهات الاتصال")
    }
}

// -------------------------------------------------------------- calendar

class CreateEventTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "create_calendar_event",
        description = "ينشئ موعدًا في التقويم.",
        parameters = listOf(
            Tool.Param("title", "string", "عنوان الموعد"),
            Tool.Param("hour", "number", "الساعة بنظام 24"),
            Tool.Param("minute", "number", "الدقيقة", required = false),
            Tool.Param("days_from_now", "number", "بعد كم يوم، 0 لليوم", required = false),
            Tool.Param("duration_minutes", "number", "المدّة بالدقائق", required = false),
        ),
        sensitive = true,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = args.str("title") ?: return ToolResult.failure("لم يُحدَّد العنوان")
        val hour = args.int("hour") ?: return ToolResult.failure("لم تُحدَّد الساعة")
        if (hour !in 0..23) return ToolResult.failure("ساعة غير صحيحة")

        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, args.int("days_from_now") ?: 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, (args.int("minute") ?: 0).coerceIn(0, 59))
            set(Calendar.SECOND, 0)
        }
        val minutes = (args.int("duration_minutes") ?: 60).coerceIn(5, 24 * 60)

        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.timeInMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.timeInMillis + minutes * 60_000L)

        val when_ = SimpleDateFormat("EEEE d MMMM, HH:mm", Locale("ar")).format(start.time)
        return if (context.launch(intent)) ToolResult.success("جهّزت موعد \"$title\" يوم $when_")
        else ToolResult.failure("لا يوجد تطبيق تقويم")
    }
}

// --------------------------------------------------------------- navigation

class NavigateTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "navigate_to",
        description = "يفتح الخرائط ويبدأ التوجيه إلى مكان.",
        parameters = listOf(
            Tool.Param("destination", "string", "اسم المكان أو عنوانه"),
            Tool.Param("mode", "string", "وسيلة التنقّل", required = false,
                enum = listOf("driving", "walking", "transit")),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val destination = args.str("destination") ?: return ToolResult.failure("لم تُحدَّد الوجهة")
        val mode = when (args.str("mode")) {
            "walking" -> "w"
            "transit" -> "r"
            else -> "d"
        }
        val intent = Intent(Intent.ACTION_VIEW, "google.navigation:q=${encode(destination)}&mode=$mode".toUri())
        if (context.launch(intent)) return ToolResult.success("بدأت التوجيه إلى $destination")

        val web = Intent(Intent.ACTION_VIEW, "https://www.google.com/maps/dir/?api=1&destination=${encode(destination)}".toUri())
        return if (context.launch(web)) ToolResult.success("فتحت الخرائط على $destination")
        else ToolResult.failure("لا يوجد تطبيق خرائط")
    }
}

// ----------------------------------------------------------- system screens

class OpenSettingsTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "open_settings_page",
        description = "يفتح صفحة إعدادات محدّدة في النظام.",
        parameters = listOf(
            Tool.Param(
                "page", "string", "الصفحة المطلوبة",
                enum = listOf("wifi", "bluetooth", "sound", "display", "battery", "apps", "location", "storage", "about"),
            ),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        // Modern Android forbids toggling radios programmatically, so the
        // honest action is to open the switch rather than claim to flip it.
        val action = when (args.str("page")) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            else -> Settings.ACTION_DEVICE_INFO_SETTINGS
        }
        return if (context.launch(Intent(action))) ToolResult.success("فتحت الإعدادات المطلوبة")
        else ToolResult.failure("تعذّر فتح هذه الصفحة")
    }
}

class ShareTextTool(private val context: Context) : ToolExecutor {
    override val tool = Tool(
        name = "share_text",
        description = "يفتح قائمة المشاركة بنصّ جاهز.",
        parameters = listOf(Tool.Param("text", "string", "النصّ المراد مشاركته")),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args.str("text") ?: return ToolResult.failure("لم يُحدَّد النصّ")
        val intent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            null,
        )
        return if (context.launch(intent)) ToolResult.success("فتحت قائمة المشاركة")
        else ToolResult.failure("تعذّر فتح المشاركة")
    }
}

/** Everything that needs only a context. */
fun deviceTools(context: Context): List<ToolExecutor> = listOf(
    BatteryStatusTool(context),
    DeviceInfoTool(context),
    FlashlightTool(context),
    ClipboardTool(context, write = false),
    ClipboardTool(context, write = true),
    ComposeMessageTool(context),
    DialTool(context),
    FindContactTool(context),
    CreateEventTool(context),
    NavigateTool(context),
    OpenSettingsTool(context),
    ShareTextTool(context),
)
