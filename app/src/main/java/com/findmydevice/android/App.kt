package com.findmydevice.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import java.util.*

class App : Application() {

    companion object {
        private const val PREFS_NAME = "FindMyPrefs"
        private const val KEY_REGION = "region"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_LOGGED_IN = "logged_in"

        // 域名配置
        const val DOMAIN_CHINA = "https://www.icloud.com.cn/"
        const val DOMAIN_INTERNATIONAL = "https://www.icloud.com/"

        lateinit var instance: App
            private set
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 初始化Cookie管理
        try {
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (e: Throwable) {
            println("CookieManager init failed: ${e.message}")
        }
    }

    /**
     * 检测用户地区
     * @return true为中国大陆，false为国际
     */
    fun isChinaRegion(): Boolean {
        val savedRegion = prefs.getString(KEY_REGION, null)
        if (savedRegion != null) {
            return savedRegion == "china"
        }

        // 自动检测地区
        val locale = Locale.getDefault()
        val country = locale.country.uppercase()

        // 中国大陆地区码：CN, HK, MO, TW等
        val isChina = country in listOf("CN", "HK", "MO", "TW")

        // 保存检测结果
        prefs.edit().putString(KEY_REGION, if (isChina) "china" else "international").apply()

        return isChina
    }

    /**
     * 获取当前域名
     */
    fun getCurrentDomain(): String {
        return if (isChinaRegion()) DOMAIN_CHINA else DOMAIN_INTERNATIONAL
    }

    /**
     * 获取 Find My 页面 URL
     */
    fun getFindMyUrl(): String {
        val base = getCurrentDomain()
        return if (base.endsWith("/")) "${base}find" else "$base/find"
    }

    /**
     * 保存Cookie - 使用更可靠的方式
     */
    fun saveCookies(url: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)

            if (!cookies.isNullOrEmpty()) {
                // 保存到SharedPreferences
                prefs.edit().putString(KEY_COOKIES, cookies).apply()

                // 同时保存到CookieManager的持久化存储
                cookieManager.flush()

                println("Cookies saved successfully: ${cookies.length} chars")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("Error saving cookies: ${e.message}")
        }
    }

    /**
     * 恢复Cookie - 使用更可靠的方式
     */
    fun restoreCookies(url: String) {
        try {
            val savedCookies = prefs.getString(KEY_COOKIES, null)

            if (!savedCookies.isNullOrEmpty()) {
                val cookieManager = CookieManager.getInstance()

                // 解析并设置每个Cookie
                val cookieList = savedCookies.split(";")
                var restoredCount = 0

                for (cookie in cookieList) {
                    val trimmedCookie = cookie.trim()
                    if (trimmedCookie.isNotEmpty()) {
                        cookieManager.setCookie(url, trimmedCookie)
                        restoredCount++
                    }
                }

                // 确保Cookie被持久化
                cookieManager.flush()

                println("Cookies restored successfully: $restoredCount cookies")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("Error restoring cookies: ${e.message}")
        }
    }

    /**
     * 保存最后访问的URL
     */
    fun saveLastUrl(url: String) {
        prefs.edit().putString(KEY_LAST_URL, url).apply()
        println("Last URL saved: $url")
    }

    /**
     * 获取最后访问的URL
     */
    fun getLastUrl(): String? {
        return prefs.getString(KEY_LAST_URL, null)
    }

    /**
     * 检查是否有保存的登录状态
     */
    fun hasLoginState(): Boolean {
        val hasCookies = prefs.contains(KEY_COOKIES)
        val hasUrl = prefs.contains(KEY_LAST_URL)
        if (!hasCookies || !hasUrl) return false

        // 兼容旧版本：之前只要有 cookies+lastUrl 就认为有登录态
        if (!prefs.contains(KEY_LOGGED_IN)) return true

        return prefs.getBoolean(KEY_LOGGED_IN, false)
    }

    fun markLoggedIn() {
        prefs.edit().putBoolean(KEY_LOGGED_IN, true).apply()
    }

    /**
     * 清除所有保存的状态
     */
    fun clearAllState() {
        prefs.edit()
            .remove(KEY_COOKIES)
            .remove(KEY_LAST_URL)
            .remove(KEY_LOGGED_IN)
            .apply()

        // 清除CookieManager的Cookie
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Throwable) {
            println("CookieManager clear failed: ${e.message}")
        }

        println("All saved state cleared")
    }
}
