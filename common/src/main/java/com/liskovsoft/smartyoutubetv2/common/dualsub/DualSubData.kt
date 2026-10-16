package com.liskovsoft.smartyoutubetv2.common.dualsub

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.liskovsoft.smartyoutubetv2.common.BuildConfig

/**
 * Dual subtitle settings. Stored in a private prefs file so the API key never leaves the device
 * (and isn't part of SmartTube's settings backup).
 */
class DualSubData private constructor(context: Context) {
    companion object {
        private const val PREFS_NAME = "dualsub_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_SHOW_ORIGINAL = "show_original"
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_PREFER_HUMAN = "prefer_human"
        private const val KEY_USAGE_INPUT = "usage_input_tokens"
        private const val KEY_USAGE_OUTPUT = "usage_output_tokens"

        val TARGET_LANGS = listOf("zh-Hant", "zh-Hans", "en", "ja", "ko")
        val SOURCE_LANGS = listOf("en", "ja", "ko", "zh-Hant", "zh-Hans", "es", "fr", "de")
        val MODELS = listOf(ClaudeTranslator.MODEL_HAIKU, ClaudeTranslator.MODEL_SONNET)

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var sInstance: DualSubData? = null

        @JvmStatic
        fun instance(context: Context): DualSubData {
            return sInstance ?: synchronized(this) {
                sInstance ?: DualSubData(context.applicationContext).also { sInstance = it }
            }
        }

        @JvmStatic
        fun langTitle(code: String): String = when (code) {
            "zh-Hant" -> "繁體中文"
            "zh-Hans" -> "简体中文"
            "en" -> "English"
            "ja" -> "日本語"
            "ko" -> "한국어"
            "es" -> "Español"
            "fr" -> "Français"
            "de" -> "Deutsch"
            else -> code
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val listeners = LinkedHashSet<Runnable>()

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = put { putBoolean(KEY_ENABLED, value) }

    var targetLang: String
        get() = prefs.getString(KEY_TARGET_LANG, null) ?: TARGET_LANGS[0]
        set(value) = put { putString(KEY_TARGET_LANG, value) }

    var isShowOriginal: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ORIGINAL, true)
        set(value) = put { putBoolean(KEY_SHOW_ORIGINAL, value) }

    /** null = auto */
    var sourceLang: String?
        get() = prefs.getString(KEY_SOURCE_LANG, null)
        set(value) = put { putString(KEY_SOURCE_LANG, value) }

    /** Use YouTube's human translation (when the video has one) instead of AI. Off by default: quality varies a lot. */
    var isPreferHumanTranslation: Boolean
        get() = prefs.getBoolean(KEY_PREFER_HUMAN, false)
        set(value) = put { putBoolean(KEY_PREFER_HUMAN, value) }

    var model: String
        get() = prefs.getString(KEY_MODEL, null) ?: BuildConfig.DUALSUB_MODEL
        set(value) = put { putString(KEY_MODEL, value) }

    /** The key typed in the settings wins over the one from .env (build time) */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, null)?.ifEmpty { null } ?: BuildConfig.DUALSUB_API_KEY
        set(value) = put { putString(KEY_API_KEY, value.trim()) }

    /** Only the last 4 chars are ever shown on screen */
    val maskedApiKey: String
        get() = apiKey.let { if (it.length < 4) "" else "••••" + it.takeLast(4) }

    /** Claude tokens used since the last reset: input to output */
    val usage: Pair<Long, Long>
        get() = prefs.getLong(KEY_USAGE_INPUT, 0) to prefs.getLong(KEY_USAGE_OUTPUT, 0)

    /** Not a setting: doesn't notify listeners (that would reload the video) */
    fun addUsage(inputTokens: Long, outputTokens: Long) {
        if (inputTokens == 0L && outputTokens == 0L) {
            return
        }
        val (input, output) = usage
        prefs.edit().putLong(KEY_USAGE_INPUT, input + inputTokens).putLong(KEY_USAGE_OUTPUT, output + outputTokens).apply()
    }

    fun resetUsage() {
        prefs.edit().putLong(KEY_USAGE_INPUT, 0).putLong(KEY_USAGE_OUTPUT, 0).apply()
    }

    fun addOnChange(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeOnChange(listener: Runnable) {
        listeners.remove(listener)
    }

    private inline fun put(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        listeners.toList().forEach { it.run() }
    }
}
