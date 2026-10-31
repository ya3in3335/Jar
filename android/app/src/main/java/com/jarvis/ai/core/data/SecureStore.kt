package com.jarvis.ai.core.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secrets at rest: the OpenRouter key and this device's hub token.
 *
 * Backed by EncryptedSharedPreferences with an AES-256-GCM master key held
 * in the Android Keystore, so the bytes on disk are useless off-device.
 *
 * If the keystore is unavailable the store reports itself unavailable rather
 * than quietly falling back to plaintext — the UI must then tell the user
 * that nothing will persist.
 */
@Singleton
class SecureStore @Inject constructor(context: Context) {

    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // A corrupt keystore entry or an unsupported device: do not crash,
        // and do not silently degrade to plaintext.
        Log.e(TAG, "secure storage unavailable: ${e.javaClass.simpleName}")
        null
    }

    val isAvailable: Boolean get() = prefs != null

    var openRouterKey: String?
        get() = prefs?.getString(KEY_OPENROUTER, null)
        set(value) = put(KEY_OPENROUTER, value)

    var deviceToken: String?
        get() = prefs?.getString(KEY_DEVICE_TOKEN, null)
        set(value) = put(KEY_DEVICE_TOKEN, value)

    var backendUrl: String?
        get() = prefs?.getString(KEY_BACKEND_URL, null)
        set(value) = put(KEY_BACKEND_URL, value)

    /**
     * Keys for running with no backend at all. They are encrypted at rest
     * with an Android Keystore key, so the bytes on disk are useless off
     * this device — but a key on a phone is only as private as the phone,
     * so none of these is ever compiled into the APK.
     */
    var deepgramKey: String?
        get() = prefs?.getString(KEY_DEEPGRAM, null)
        set(value) = put(KEY_DEEPGRAM, value)

    var azureSpeechKey: String?
        get() = prefs?.getString(KEY_AZURE_KEY, null)
        set(value) = put(KEY_AZURE_KEY, value)

    var azureRegion: String?
        get() = prefs?.getString(KEY_AZURE_REGION, null)
        set(value) = put(KEY_AZURE_REGION, value)

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    private fun put(key: String, value: String?) {
        val editor = prefs?.edit() ?: return
        if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    private companion object {
        const val TAG = "SecureStore"
        const val FILE_NAME = "jarvis_secure_prefs"
        const val KEY_OPENROUTER = "openrouter_api_key"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_DEEPGRAM = "deepgram_api_key"
        const val KEY_AZURE_KEY = "azure_speech_key"
        const val KEY_AZURE_REGION = "azure_speech_region"
    }
}

/** Mask anything token-shaped before it reaches a log or the screen. */
fun redact(text: String): String = text
    .replace(Regex("jv1\\.[a-z2-9]{12}\\.[a-z2-9]{10}\\.[A-Za-z0-9_-]+"), "jv1.***")
    .replace(Regex("sk-or-[A-Za-z0-9_-]{6,}"), "sk-or-***")
    // Deepgram and Azure keys are bare hex, so they are matched by shape.
    .replace(Regex("\\b[0-9a-f]{32,40}\\b"), "***")
