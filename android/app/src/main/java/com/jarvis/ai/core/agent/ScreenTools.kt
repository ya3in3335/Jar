package com.jarvis.ai.core.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.jarvis.ai.core.agent.JarvisAccessibilityService.GlobalAction
import com.jarvis.ai.core.agent.JarvisAccessibilityService.ScrollDirection
import com.jarvis.ai.core.agent.JarvisAccessibilityService.TapOutcome
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acting on the screen, not just launching things at it.
 *
 * An intent gets the assistant to an app's front door. These tools are
 * what let it go further: read what is actually displayed, tap a control
 * by the words on it, scroll, type, go back. That is the difference
 * between "I opened YouTube" and "I opened YouTube, searched, and played
 * the first result".
 *
 * All of it runs through [JarvisAccessibilityService], which the user
 * enables by hand in system settings. When it is off, every tool here says
 * so plainly and tells the user how to turn it on — none of them pretend
 * to have acted.
 */

private fun JsonObject.text(key: String): String? =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.flag(key: String): Boolean =
    this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

private fun JsonObject.number(key: String): Long? =
    this[key]?.jsonPrimitive?.content?.toLongOrNull()

/** The one message every screen tool gives when the service is off. */
internal const val SERVICE_OFF =
    "خدمة التحكّم بالشاشة غير مفعّلة. فعّلها من: الإعدادات ← إمكانية الوصول ← JARVIS، " +
        "أو اطلب منّي فتح تلك الصفحة."

private fun service(): JarvisAccessibilityService? = JarvisAccessibilityService.instance

/**
 * After any action that changes the screen, the next tool call needs the
 * new screen — not the old one. A short settle beats an immediate read
 * that catches the outgoing frame.
 */
private const val SETTLE_MS = 450L

// ------------------------------------------------------------------ reading

class ReadScreenTool : ToolExecutor {

    override val tool = Tool(
        name = "read_screen",
        description =
            "يقرأ ما هو معروض على الشاشة الآن: النصوص والأزرار وحقول الإدخال. " +
                "استخدمه قبل النقر لتعرف ما المتاح فعلًا، وبعد كل خطوة لتتأكّد أنها نجحت.",
        parameters = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val svc = service() ?: return ToolResult.failure(SERVICE_OFF)
        val elements = svc.describeScreen()
        if (elements.isEmpty()) {
            return ToolResult.success(
                "الشاشة فارغة أو لا تحتوي نصًّا يمكن قراءته",
                "لم يُعثر على أي عنصر. قد تكون الشاشة قيد التحميل.",
            )
        }

        val app = JarvisAccessibilityService.foregroundPackage ?: "غير معروف"
        val listing = elements.joinToString("\n") { "- " + it.describe() }
        return ToolResult.success(
            "قرأت الشاشة (${elements.size} عنصرًا)",
            "التطبيق في المقدّمة: $app\nالعناصر الظاهرة:\n$listing",
        )
    }
}

class FindOnScreenTool : ToolExecutor {

    override val tool = Tool(
        name = "find_on_screen",
        description = "يتحقّق ممّا إذا كان نصّ أو زرّ معيّن موجودًا على الشاشة الآن.",
        parameters = listOf(
            Tool.Param("text", "string", "النصّ المراد البحث عنه كما يظهر على الشاشة"),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val svc = service() ?: return ToolResult.failure(SERVICE_OFF)
        val query = args.text("text") ?: return ToolResult.failure("حدّد النصّ")
        val hits = svc.findByText(query)
        return if (hits.isEmpty()) {
            ToolResult.success("لا يوجد '$query' على الشاشة", "غير موجود. اقرأ الشاشة لترى المتاح.")
        } else {
            ToolResult.success("'$query' موجود (${hits.size})", "موجود على الشاشة.")
        }
    }
}

/**
 * Wait for a screen to arrive.
 *
 * The step that makes chained instructions work. Tapping immediately after
 * launching an app races the app's own startup, and loses often enough to
 * make the whole chain look broken.
 */
class WaitForScreenTool : ToolExecutor {

    override val tool = Tool(
        name = "wait_for_screen",
        description =
            "ينتظر ظهور نصّ معيّن على الشاشة قبل المتابعة. " +
                "استخدمه بعد فتح تطبيق أو بعد نقرة تُحمّل صفحة جديدة.",
        parameters = listOf(
            Tool.Param("text", "string", "النصّ الذي ينتظر ظهوره"),
            Tool.Param("timeout_seconds", "number", "المهلة بالثواني (1 إلى 20)", required = false),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val svc = service() ?: return ToolResult.failure(SERVICE_OFF)
        val query = args.text("text") ?: return ToolResult.failure("حدّد النصّ")
        val seconds = (args.number("timeout_seconds") ?: 8L).coerceIn(1L, 20L)

        return if (svc.waitForText(query, seconds * 1000)) {
            ToolResult.success("ظهر '$query'", "الشاشة جاهزة.")
        } else {
            ToolResult.failure(
                "لم يظهر '$query' خلال $seconds ثوانٍ",
                "لم يظهر. اقرأ الشاشة لترى ما هو معروض فعلًا بدل الانتظار أكثر.",
            )
        }
    }
}

// ------------------------------------------------------------------- acting

class TapTool : ToolExecutor {

    override val tool = Tool(
        name = "tap",
        description =
            "ينقر على عنصر في الشاشة بالنصّ الظاهر عليه. " +
                "اقرأ الشاشة أوّلًا إن لم تكن متأكّدًا من النصّ الصحيح.",
        parameters = listOf(
            Tool.Param("text", "string", "النصّ الظاهر على الزرّ أو العنصر المراد نقره"),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val svc = service() ?: return ToolResult.failure(SERVICE_OFF)
        val query = args.text("text") ?: return ToolResult.failure("حدّد النصّ")

        return when (svc.tapText(query)) {
            TapOutcome.Clicked, TapOutcome.Tapped -> {
                delay(SETTLE_MS)
                ToolResult.success("نقرت على '$query'", "تمّ النقر. اقرأ الشاشة لترى النتيجة.")
            }
            TapOutcome.NotFound -> {
                val visible = svc.describeScreen(limit = 25)
                    .filter { it.clickable }
                    .joinToString("، ") { it.text }
                ToolResult.failure(
                    "لم أجد '$query' على الشاشة",
                    if (visible.isBlank()) {
                        "غير موجود، ولا توجد عناصر قابلة للنقر ظاهرة."
                    } else {
                        "غير موجود. العناصر القابلة للنقر الآن: $visible"
                    },
                )
            }
            TapOutcome.NotActionable -> ToolResult.failure(
                "وجدت '$query' لكن تعذّر النقر عليه",
                "العنصر ظاهر لكنه غير قابل للنقر.",
            )
        }
    }
}

class ScrollScreenTool : ToolExecutor {

    override val tool = Tool(
        name = "scroll_screen",
        description = "يمرّر الشاشة لرؤية المزيد من المحتوى.",
        parameters = listOf(
            Tool.Param(
                "direction", "string", "اتجاه التمرير",
                enum = listOf("down", "up", "left", "right"),
            ),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val svc = service() ?: return ToolResult.failure(SERVICE_OFF)
        val direction = when (args.text("direction")?.lowercase()) {
            "up" -> ScrollDirection.UP
            "left" -> ScrollDirection.LEFT
            "right" -> ScrollDirection.RIGHT
            else -> ScrollDirection.DOWN
        }

        val size = screenSize(svc)
        return if (svc.scroll(direction, size.first, size.second)) {
            delay(SETTLE_MS)
            ToolResult.success("مرّرت الشاشة", "تمّ التمرير. اقرأ الشاشة لترى المحتوى الجديد.")
        } else {
            ToolResult.failure("تعذّر التمرير", "لا يوجد محتوى قابل للتمرير على هذه الشاشة.")
        }
    }

    /**
     * Screen size for the swipe fallback.
     *
     * currentWindowMetrics is API 30, and this app supports 29, so the
     * older path is a real branch rather than a formality.
     */
    private fun screenSize(context: Context): Pair<Int, Int> {
        val manager = context.getSystemService(WindowManager::class.java)
            ?: return DEFAULT_SIZE
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = manager.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                manager.defaultDisplay.getMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
        }.getOrDefault(DEFAULT_SIZE)
    }

    private companion object {
        val DEFAULT_SIZE = 1080 to 2340
    }
}

class TypeTextTool : ToolExecutor {

    override val tool = Tool(
        name = "type_text",
        description =
            "يكتب نصًّا في حقل الإدخال النشط على الشاشة. " +
                "انقر على الحقل أوّلًا إن لم يكن نشطًا.",
        parameters = listOf(
            Tool.Param("text", "string", "النصّ المراد كتابته"),
            Tool.Param(
                "replace", "boolean",
                "استبدال ما في الحقل بدل الإضافة إليه (الافتراضي: استبدال)",
                required = false,
            ),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val svc = service() ?: return ToolResult.failure(SERVICE_OFF)
        val value = args.text("text") ?: return ToolResult.failure("حدّد النصّ")
        val replace = if (args.containsKey("replace")) args.flag("replace") else true

        return if (svc.typeText(value, replace)) {
            delay(SETTLE_MS)
            ToolResult.success("كتبت النصّ", "تمّت الكتابة في الحقل.")
        } else {
            ToolResult.failure(
                "لم أجد حقل إدخال نشطًا",
                "لا يوجد حقل إدخال قابل للكتابة. انقر على الحقل أوّلًا.",
            )
        }
    }
}

class PressKeyTool : ToolExecutor {

    override val tool = Tool(
        name = "press_key",
        description = "يضغط أزرار النظام: رجوع، الشاشة الرئيسية، التطبيقات الأخيرة، شريط الإشعارات.",
        parameters = listOf(
            Tool.Param(
                "key", "string", "الزرّ المراد ضغطه",
                enum = listOf("back", "home", "recents", "notifications"),
            ),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val svc = service() ?: return ToolResult.failure(SERVICE_OFF)
        val key = args.text("key")?.lowercase() ?: return ToolResult.failure("حدّد الزرّ")
        val action = when (key) {
            "back" -> GlobalAction.BACK
            "home" -> GlobalAction.HOME
            "recents" -> GlobalAction.RECENTS
            "notifications" -> GlobalAction.NOTIFICATIONS
            else -> return ToolResult.failure("زرّ غير معروف: $key")
        }

        return if (svc.globalAction(action)) {
            delay(SETTLE_MS)
            ToolResult.success("ضغطت $key", "تمّ.")
        } else {
            ToolResult.failure("تعذّر تنفيذ $key")
        }
    }
}

/**
 * Opening the settings page the user needs, when the service is off.
 *
 * Always available, precisely because it is the way out of the one failure
 * every other tool here reports.
 */
class EnableScreenControlTool(private val context: Context) : ToolExecutor {

    override val tool = Tool(
        name = "open_accessibility_settings",
        description =
            "يفتح صفحة إعدادات إمكانية الوصول ليفعّل المستخدم التحكّم بالشاشة. " +
                "استخدمه عندما تفشل أدوات الشاشة بسبب أن الخدمة غير مفعّلة.",
        parameters = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        if (JarvisAccessibilityService.isEnabled) {
            return ToolResult.success("الخدمة مفعّلة أصلًا", "لا حاجة لفتح الإعدادات.")
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult.success(
                "فتحت إعدادات إمكانية الوصول",
                "اختر JARVIS وفعّله، ثم أعد طلبك.",
            )
        }.getOrElse {
            ToolResult.failure("تعذّر فتح الإعدادات", it.message)
        }
    }
}

/**
 * The screen set.
 *
 * Ordered the way they are used: look, then wait, then act.
 */
fun screenTools(context: Context): List<ToolExecutor> = listOf(
    ReadScreenTool(),
    FindOnScreenTool(),
    WaitForScreenTool(),
    TapTool(),
    ScrollScreenTool(),
    TypeTextTool(),
    PressKeyTool(),
    EnableScreenControlTool(context),
)
