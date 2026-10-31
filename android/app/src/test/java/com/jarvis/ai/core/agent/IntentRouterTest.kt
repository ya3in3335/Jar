package com.jarvis.ai.core.agent

import com.jarvis.ai.core.agent.IntentRouter.route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These mirror the desktop suite in windows/tests/test_intent.py — both
 * platforms must route the same utterance the same way.
 */
class IntentRouterTest {

    // ---------------------------------------------------------- normalising

    @Test
    fun `strips diacritics`() {
        assertEquals("افتح", IntentRouter.normalize("اِفْتَحْ"))
    }

    @Test
    fun `folds alef variants`() {
        assertEquals(IntentRouter.normalize("احمد"), IntentRouter.normalize("أحمد"))
    }

    @Test
    fun `converts arabic indic digits`() {
        assertEquals("123456", IntentRouter.normalize("١٢٣٤٥٦"))
    }

    @Test
    fun `collapses whitespace`() {
        assertEquals("افتح يوتيوب", IntentRouter.normalize("  افتح    يوتيوب  "))
    }

    @Test
    fun `alias keys are all normalised`() {
        for (key in IntentRouter.appAliases.keys) {
            assertEquals("alias key not normalised: $key", key, IntentRouter.normalize(key))
        }
    }

    // ------------------------------------------------------------- open app

    @Test
    fun `opens known apps`() {
        val cases = mapOf(
            "افتح يوتيوب" to "youtube",
            "افتح اليوتيوب" to "youtube",
            "افتح الإعدادات" to "settings",
            "ادخل لإنستغرام" to "instagram",
            "افتح الكاميرا" to "camera",
            "open chrome" to "browser",
        )
        for ((utterance, app) in cases) {
            val intent = route(utterance)
            assertNotNull("no intent for: $utterance", intent)
            assertEquals(utterance, "open_application", intent!!.tool)
            assertEquals(utterance, app, intent.args["app"])
        }
    }

    @Test
    fun `unknown app defers to the model`() {
        assertNull(route("افتح تطبيق المحاسبة الخاص بالشركة"))
    }

    @Test
    fun `bare url opens directly`() {
        val intent = route("افتح https://example.com")
        assertEquals("open_url", intent?.tool)
        assertEquals("https://example.com", intent?.args?.get("url"))
    }

    // -------------------------------------------------------------- volume

    @Test
    fun `volume up`() {
        val intent = route("ارفع صوت الهاتف")
        assertEquals("control_volume", intent?.tool)
        assertEquals("up", intent?.args?.get("direction"))
    }

    @Test
    fun `volume down`() {
        assertEquals("down", route("خفض الصوت")?.args?.get("direction"))
    }

    @Test
    fun `volume mute`() {
        assertEquals("mute", route("اكتم الصوت")?.args?.get("direction"))
    }

    // --------------------------------------------------------------- media

    @Test
    fun `pauses music`() {
        assertEquals("pause_media", route("أوقف الموسيقى")?.tool)
    }

    @Test
    fun `plays music`() {
        assertEquals("play_media", route("شغل الموسيقى")?.tool)
    }

    // -------------------------------------------------------------- screen

    @Test
    fun `analyses the screen however it is phrased`() {
        for (utterance in listOf("شوف واش ظاهر في الشاشة", "حلل الشاشة", "انظر الى الشاشة")) {
            assertEquals(utterance, "analyze_screen", route(utterance)?.tool)
        }
    }

    // -------------------------------------------------------------- search

    @Test
    fun `web search extracts the query`() {
        val intent = route("ابحث عن سعر الذهب")
        assertEquals("search_web", intent?.tool)
        assertEquals("سعر الذهب", intent?.args?.get("query"))
    }

    @Test
    fun `compound command opens the app and searches inside it`() {
        val intent = route("افتح يوتيوب وابحث عن فيديو لتعلم بايثون")
        assertEquals("open_application", intent?.tool)
        assertEquals("youtube", intent?.args?.get("app"))
        assertTrue(intent?.args?.get("search").toString().contains("بايثون"))
    }

    // ------------------------------------------------------------ deferral

    @Test
    fun `ambiguous utterances go to the model`() {
        for (utterance in listOf(
            "",
            "   ",
            "لماذا السماء زرقاء",
            "قارن بين هاتين الصفقتين واخبرني أيهما أفضل",
            "اكتب لي رسالة لصاحب المتجر",
        )) {
            assertNull("should defer: $utterance", route(utterance))
        }
    }
}
