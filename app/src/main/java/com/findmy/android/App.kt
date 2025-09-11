package com.findmy.android

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

        // 域名配置
        const val DOMAIN_CHINA = "https://www.icloud.com.cn/find"
        const val DOMAIN_INTERNATIONAL = "https://www.icloud.com/find"

        lateinit var instance: App
            private set
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 初始化Cookie管理
        CookieManager.getInstance().setAcceptCookie(true)
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
     * 保存Cookie
     */
    fun saveCookies(url: String) {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url)
        if (cookies != null) {
            prefs.edit().putString(KEY_COOKIES, cookies).apply()
        }
    }

    /**
     * 恢复Cookie
     */
    fun restoreCookies(url: String) {
        val cookies = prefs.getString(KEY_COOKIES, null)
        if (cookies != null) {
            val cookieManager = CookieManager.getInstance()
            val cookieList = cookies.split(";")
            for (cookie in cookieList) {
                cookieManager.setCookie(url, cookie.trim())
            }
            cookieManager.flush()
        }
    }

    /**
     * 清除保存的Cookie
     */
    fun clearCookies() {
        prefs.edit().remove(KEY_COOKIES).apply()
        CookieManager.getInstance().removeAllCookies(null)
    }
}
