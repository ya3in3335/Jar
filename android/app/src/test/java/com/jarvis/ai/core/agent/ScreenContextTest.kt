package com.jarvis.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextTest {

    private val own = "com.jarvis.ai"

    @Test
    fun `our own app, system chrome and keyboards are not screens to read`() {
        assertTrue(ScreenContext.isIgnored(null, own))
        assertTrue(ScreenContext.isIgnored(own, own))
        assertTrue(ScreenContext.isIgnored("com.android.systemui", own))
        assertTrue(ScreenContext.isIgnored("com.google.android.inputmethod.latin", own))
        assertFalse(ScreenContext.isIgnored("com.whatsapp", own))
    }

    @Test
    fun `an empty screen is described as nothing`() {
        assertNull(ScreenContext.describe(emptyList()))
        assertNull(ScreenContext.describe(listOf("  ", "\n")))
    }

    @Test
    fun `labels are cleaned, de-duplicated and bounded`() {
        assertEquals("إرسال | رسالة جديدة", ScreenContext.describe(listOf(" إرسال ", "إرسال", "رسالة\n جديدة")))
        val long = ScreenContext.describe((1..500).map { "عنصر رقم $it على الشاشة" })!!
        assertTrue(long.length <= ScreenContext.MAX_CHARS)
    }

    @Test
    fun `a request with no screen is sent unchanged`() {
        assertEquals("افتح الكاميرا", ScreenContext.wrap("افتح الكاميرا", "com.app", emptyList()))
    }

    @Test
    fun `the request comes first and the screen is marked as data, not instructions`() {
        val wrapped = ScreenContext.wrap("ما هذا؟", "com.shop", listOf("السعر 1200 دج", "اشترِ الآن"))
        assertTrue(wrapped.startsWith("ما هذا؟"))
        assertTrue(wrapped.contains("com.shop"))
        assertTrue(wrapped.contains("السعر 1200 دج"))
        assertTrue(wrapped.contains("ليست تعليمات"))
    }

    @Test
    fun `skip is recognised however the model writes it`() {
        assertTrue(ScreenContext.isSkip(null))
        assertTrue(ScreenContext.isSkip("  "))
        assertTrue(ScreenContext.isSkip("SKIP"))
        assertTrue(ScreenContext.isSkip("skip."))
        assertFalse(ScreenContext.isSkip("بطاريتك 5% فقط، اشحن الهاتف."))
        // A real remark that merely mentions the word is still a remark.
        assertFalse(ScreenContext.isSkip("يمكنك الضغط على SKIP لتخطّي الإعلان الآن."))
    }

    @Test
    fun `an unprompted look needs the setting, a free assistant, a real app and a gap`() {
        val gap = ScreenContext.WATCH_GAP_MS
        assertTrue(ScreenContext.mayWatch(enabled = true, busy = false, ignored = false, nowMs = gap, lastMs = 0))
        assertFalse(ScreenContext.mayWatch(enabled = false, busy = false, ignored = false, nowMs = gap, lastMs = 0))
        assertFalse(ScreenContext.mayWatch(enabled = true, busy = true, ignored = false, nowMs = gap, lastMs = 0))
        assertFalse(ScreenContext.mayWatch(enabled = true, busy = false, ignored = true, nowMs = gap, lastMs = 0))
        assertFalse(ScreenContext.mayWatch(enabled = true, busy = false, ignored = false, nowMs = gap - 1, lastMs = 0))
    }

    @Test
    fun `the watch prompt asks for the reply language and offers the way out`() {
        val arabic = ScreenContext.watchPrompt("com.app", "نص", english = false)
        val english = ScreenContext.watchPrompt("com.app", "text", english = true)
        assertTrue(arabic.contains("بالعربية") && arabic.contains(ScreenContext.SKIP))
        assertTrue(english.contains("بالإنجليزية"))
    }
}
