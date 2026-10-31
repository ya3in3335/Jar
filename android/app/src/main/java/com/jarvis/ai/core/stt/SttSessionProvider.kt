package com.jarvis.ai.core.stt

import com.jarvis.ai.core.data.SecureStore
import com.jarvis.ai.core.net.BackendException
import com.jarvis.ai.core.net.JarvisBackend
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the recogniser's credential comes from.
 *
 * Two modes, because this app runs two ways:
 *
 * - **Direct**: the key lives on this phone, encrypted with an Android
 *   Keystore key, and the device talks to Deepgram itself. No server to
 *   run, which is the point.
 * - **Backend**: a paired backend mints a short-lived token per session and
 *   the account key never reaches the device.
 *
 * Direct is the default. The trade-off is real and worth stating: a key on
 * the device is only as private as the device. That is fine for a personal
 * build where the owner enters their own key, and not fine for an APK
 * handed to other people with a key already in it — which is why no key is
 * ever compiled in.
 */
interface SttSessionProvider {
    val mode: String
    suspend fun session(language: String): SttSession
}

/** Builds the Deepgram streaming URL on the device. */
object DeepgramUrl {

    const val MODEL = "nova-3"

    /**
     * Arabic runs on Nova-3, which covers ar including the Maghreb
     * locales. Deepgram has no Arabic voice, so speech output stays on our
     * own engines — see DECISIONS.md D8.
     */
    fun build(
        language: String = "ar",
        sampleRate: Int = 16_000,
        endpointingMs: Int = 700,
        utteranceEndMs: Int = 1_000,
    ): String {
        val params = listOf(
            "model" to MODEL,
            "language" to language,
            "encoding" to "linear16",
            "sample_rate" to sampleRate.toString(),
            "channels" to "1",
            "interim_results" to "true",
            "smart_format" to "true",
            "endpointing" to endpointingMs.toString(),
            "utterance_end_ms" to utteranceEndMs.toString(),
            // Lets the device tell a silent turn from a barge-in.
            "vad_events" to "true",
        ).joinToString("&") { (key, value) -> "$key=$value" }

        return "wss://api.deepgram.com/v1/listen?$params"
    }
}

/** No server: the device holds the key and connects directly. */
@Singleton
class DirectSttSessionProvider @Inject constructor(
    private val secureStore: SecureStore,
) : SttSessionProvider {

    override val mode = "direct"

    override suspend fun session(language: String): SttSession {
        val key = secureStore.deepgramKey
        if (key.isNullOrBlank()) {
            throw SttConfigException("أضف مفتاح ديبڨرام من الإعدادات")
        }
        // The key is sent as the Authorization header by the engine, never
        // in the URL, where it would end up in logs and crash reports.
        return SttSession(accessToken = key, expiresInSeconds = 0, url = DeepgramUrl.build(language))
    }
}

/** A paired backend mints a short-lived token, so no key is on the device. */
@Singleton
class BackendSttSessionProvider @Inject constructor(
    private val backend: JarvisBackend,
) : SttSessionProvider {

    override val mode = "backend"

    override suspend fun session(language: String): SttSession =
        try {
            backend.sttSession(language)
        } catch (e: BackendException) {
            throw SttConfigException(e.message ?: "تعذّر الحصول على رمز التعرّف")
        }
}

/**
 * Picks the mode automatically: a paired backend is preferred because it
 * keeps the key off the device, and direct is the fallback so the app
 * works with no server at all.
 */
@Singleton
class AutoSttSessionProvider @Inject constructor(
    private val direct: DirectSttSessionProvider,
    private val backend: BackendSttSessionProvider,
    private val jarvisBackend: JarvisBackend,
    private val secureStore: SecureStore,
) : SttSessionProvider {

    override val mode: String
        get() = if (jarvisBackend.isPaired) backend.mode else direct.mode

    /** True when either route can actually run. */
    val isConfigured: Boolean
        get() = jarvisBackend.isPaired || !secureStore.deepgramKey.isNullOrBlank()

    override suspend fun session(language: String): SttSession {
        if (jarvisBackend.isPaired) {
            // Fall back rather than fail: a backend that is paired but
            // unreachable should not stop a device that holds its own key.
            runCatching { return backend.session(language) }
                .onFailure { if (secureStore.deepgramKey.isNullOrBlank()) throw it }
        }
        return direct.session(language)
    }
}

/** Recognition cannot start for a reason the user can fix. */
class SttConfigException(message: String) : RuntimeException(message)

/**
 * How the credential is presented to Deepgram.
 *
 * A raw account key uses the `Token` scheme; a minted short-lived token
 * uses `Bearer`. Sending the wrong one fails the handshake with a bare
 * 401, which is hard to diagnose from a phone.
 */
fun authHeaderFor(session: SttSession): String =
    if (session.expiresInSeconds > 0) "Bearer ${session.accessToken}" else "Token ${session.accessToken}"
