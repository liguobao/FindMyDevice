package com.findmydevice.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.findmydevice.android.App
import com.findmydevice.android.R

class WebFindActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var app: App

    // JavaScript接口类
    inner class WebAppInterface {
        @JavascriptInterface
        fun saveLoginState() {
            runOnUiThread {
                webView.url?.let {
                    app.saveCookies(it)
                    app.saveLastUrl(it)
                    println("Login state saved from JavaScript")
                }
            }
        }

        @JavascriptInterface
        fun onFindMyPageLoaded() {
            runOnUiThread {
                println("Find My page loaded successfully")
                // 可以在这里添加导航到Find My的具体逻辑
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webfind)

        app = App.instance
        webView = findViewById(R.id.webView)
        val progress = findViewById<View>(R.id.progress)

        // Basic secure WebView setup
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webView.settings.userAgentString = webView.settings.userAgentString +
            " AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile Safari/605.1.15"

        // Cookie设置
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // 添加JavaScript接口
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.webChromeClient = object : WebChromeClient() {}

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE

                // 处理iCloud登录页面
                url?.let { currentUrl ->
                    if (currentUrl.contains("icloud.com") && !currentUrl.contains("find")) {
                        // 在iCloud首页，注入JavaScript来自动处理登录选项
                        injectLoginHelpers()
                    }
                }

                // 页面加载完成后保存Cookie和URL
                url?.let {
                    app.saveCookies(it)
                    app.saveLastUrl(it)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }
        }

        // 检查是否有保存的登录状态
        if (app.hasLoginState()) {
            // 有保存的状态，先恢复Cookie
            val lastUrl = app.getLastUrl()
            if (lastUrl != null) {
                app.restoreCookies(lastUrl)
                webView.loadUrl(lastUrl)
                println("Restored to last URL: $lastUrl")
            } else {
                loadDefaultDomain()
            }
        } else {
            // 没有保存的状态，加载默认域名
            loadDefaultDomain()
        }

        // 设置返回按钮处理
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun injectLoginHelpers() {
        val jsCode = """
            (function() {
                'use strict';

                // 等待页面加载完成后执行
                function initLoginHelpers() {
                    console.log('iCloud Login Helper: Initializing...');

                    // 自动勾选"记住密码"选项
                    const rememberPasswordCheckbox = document.querySelector('input[type="checkbox"][name*="remember"], input[type="checkbox"][id*="remember"], input[type="checkbox"][data-testid*="remember"]');
                    if (rememberPasswordCheckbox && !rememberPasswordCheckbox.checked) {
                        rememberPasswordCheckbox.checked = true;
                        rememberPasswordCheckbox.dispatchEvent(new Event('change', { bubbles: true }));
                        console.log('iCloud Login Helper: Remember password checkbox checked');
                    }

                    // 自动勾选"信任浏览器"选项
                    const trustBrowserCheckbox = document.querySelector('input[type="checkbox"][name*="trust"], input[type="checkbox"][id*="trust"], input[type="checkbox"][data-testid*="trust"]');
                    if (trustBrowserCheckbox && !trustBrowserCheckbox.checked) {
                        trustBrowserCheckbox.checked = true;
                        trustBrowserCheckbox.dispatchEvent(new Event('change', { bubbles: true }));
                        console.log('iCloud Login Helper: Trust browser checkbox checked');
                    }

                    // 监听登录表单提交，确保Cookie被保存
                    const loginForm = document.querySelector('form[action*="signin"], form[action*="login"], form[id*="signin"], form[id*="login"]');
                    if (loginForm) {
                        loginForm.addEventListener('submit', function(e) {
                            console.log('iCloud Login Helper: Login form submitted');
                            // 延迟保存，确保登录完成
                            setTimeout(function() {
                                try {
                                    if (window.Android && window.Android.saveLoginState) {
                                        window.Android.saveLoginState();
                                    }
                                } catch (error) {
                                    console.log('iCloud Login Helper: Error saving login state:', error);
                                }
                            }, 2000);
                        });
                    }

                    // 监听页面变化，如果跳转到Find My页面，通知原生代码
                    const observer = new MutationObserver(function(mutations) {
                        mutations.forEach(function(mutation) {
                            if (mutation.type === 'childList' && mutation.addedNodes.length > 0) {
                                const addedNodes = Array.from(mutation.addedNodes);
                                const hasFindMyContent = addedNodes.some(node =>
                                    node.textContent && (
                                        node.textContent.includes('Find My') ||
                                        node.textContent.includes('查找') ||
                                        node.nodeType === Node.ELEMENT_NODE &&
                                        (node.matches && node.matches('[data-testid*="find"]'))
                                    )
                                );

                                if (hasFindMyContent || window.location.href.includes('/find')) {
                                    console.log('iCloud Login Helper: Find My page detected');
                                    try {
                                        if (window.Android && window.Android.onFindMyPageLoaded) {
                                            window.Android.onFindMyPageLoaded();
                                        }
                                    } catch (error) {
                                        console.log('iCloud Login Helper: Error calling onFindMyPageLoaded:', error);
                                    }
                                }
                            }
                        });
                    });

                    observer.observe(document.body, {
                        childList: true,
                        subtree: true
                    });

                    console.log('iCloud Login Helper: Initialized successfully');
                }

                // 页面加载完成后初始化
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', initLoginHelpers);
                } else {
                    initLoginHelpers();
                }

                // 也监听页面完全加载
                window.addEventListener('load', function() {
                    setTimeout(initLoginHelpers, 1000);
                });

            })();
        """

        webView.evaluateJavascript(jsCode, null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 应用退出时保存Cookie和URL
        if (::webView.isInitialized) {
            webView.url?.let {
                app.saveCookies(it)
                app.saveLastUrl(it)
            }
        }
    }

    private fun loadDefaultDomain() {
        val domain = app.getCurrentDomain()
        webView.loadUrl(domain)
    }
}
