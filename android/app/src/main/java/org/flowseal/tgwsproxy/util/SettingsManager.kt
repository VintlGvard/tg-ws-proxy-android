package org.flowseal.tgwsproxy.util

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

object SettingsManager {

    private const val PREFS_NAME = "tg_ws_proxy_prefs"

    private const val KEY_PORT = "pref_port"
    private const val KEY_DOMAIN = "pref_domain"
    private const val KEY_SECRET = "pref_secret"
    private const val KEY_CF_FALLBACK = "pref_cf_fallback"

    const val DEFAULT_PORT = 1443
    const val DEFAULT_DOMAIN = "google.com"
    const val CANONICAL_KEY_HEX = "d41d8cd98f00b204e9800998ecf8427e"
    const val DEFAULT_SECRET = "eed41d8cd98f00b204e9800998ecf8427e676f6f676c652e636f6d"

    val PRESET_DOMAINS = listOf(
        "google.com" to "Google",
        "icloud.com" to "Apple",
        "cloudflare.com" to "Cloudflare",
        "microsoft.com" to "Microsoft",
        "yandex.ru" to "Яндекс"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPort(context: Context): Int {
        return getPrefs(context).getInt(KEY_PORT, DEFAULT_PORT)
    }

    fun setPort(context: Context, port: Int) {
        getPrefs(context).edit().putInt(KEY_PORT, port).apply()
    }

    fun getDomain(context: Context): String {
        return getPrefs(context).getString(KEY_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN
    }

    fun setDomain(context: Context, domain: String) {
        getPrefs(context).edit().putString(KEY_DOMAIN, domain).apply()
    }

    fun getSecret(context: Context): String {
        val saved = getPrefs(context).getString(KEY_SECRET, null)
        if (!saved.isNullOrBlank() && saved != "ee8401a20ac9071e9c89793b2493d70875676f6f676c652e636f6d") {
            return saved
        }
        val domain = getDomain(context)
        val defaultSecret = getSecretForDomain(domain)
        setSecret(context, defaultSecret)
        return defaultSecret
    }

    fun setSecret(context: Context, secret: String) {
        getPrefs(context).edit().putString(KEY_SECRET, secret).apply()
    }

    fun isCloudflareFallback(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CF_FALLBACK, true)
    }

    fun setCloudflareFallback(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CF_FALLBACK, enabled).apply()
    }

    fun resetToDefaults(context: Context) {
        setPort(context, DEFAULT_PORT)
        setDomain(context, DEFAULT_DOMAIN)
        setSecret(context, DEFAULT_SECRET)
        setCloudflareFallback(context, true)
    }

    /**
     * Generates a deterministic MTProto Fake TLS secret using canonical key:
     * Format: 'ee' + canonical key + domain in hex (ASCII).
     */
    fun getSecretForDomain(domain: String): String {
        val domainHex = domain.trim().toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
        return "ee$CANONICAL_KEY_HEX$domainHex"
    }

    /**
     * Generates a completely new random MTProto Fake TLS secret when requested:
     */
    fun generateRandomSecretForDomain(domain: String): String {
        val randomBytes = ByteArray(16)
        SecureRandom().nextBytes(randomBytes)
        val randomHex = randomBytes.joinToString("") { "%02x".format(it) }
        val domainHex = domain.trim().toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
        return "ee$randomHex$domainHex"
    }

    /**
     * Alias for compatibility
     */
    fun generateSecretForDomain(domain: String): String {
        return getSecretForDomain(domain)
    }

    /**
     * Checks if secret format matches the current domain.
     */
    fun isDomainMatchingSecret(secret: String, domain: String): Boolean {
        val domainHex = domain.trim().toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
        return secret.lowercase().endsWith(domainHex.lowercase())
    }
}
