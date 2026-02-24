package com.findmydevice.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.findmydevice.android.App
import com.findmydevice.android.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.Toast

class WebFindActivity : ComponentActivity() {

    companion object {
        // iCloud 在部分兼容层 WebView 中会返回不兼容页，降级到 iOS Safari UA 可提升成功率。
        private const val IOS_SAFARI_COMPAT_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Version/16.6 Mobile/15E148 Safari/604.1"
    }

    private lateinit var webView: WebView
    private lateinit var app: App
    private lateinit var clearCacheFab: FloatingActionButton
    private lateinit var findMyUrl: String

    private var pendingRedirectToFindMy = false
    private var lastRedirectAtMs: Long = 0
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var compatibilityUserAgentApplied = false

    // 拖拽相关变量
    private var dX = 0f
    private var dY = 0f
    private var lastAction = 0

    // JavaScript接口类
    inner class WebAppInterface {
        @JavascriptInterface
        fun saveLoginState() {
            runOnUiThread {
                webView.url?.let {
                    app.markLoggedIn()
                    app.saveCookies(it)
                    app.saveLastUrl(it)
                    println("Login state saved from JavaScript")
                }
                pendingRedirectToFindMy = true
                navigateToFindMyIfNeeded(webView.url)
            }
        }

        @JavascriptInterface
        fun onFindMyPageLoaded() {
            runOnUiThread {
                println("Find My page loaded successfully")
                app.markLoggedIn()
            }
        }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val callback = filePathCallback
        filePathCallback = null
        callback?.onReceiveValue(uri?.let { arrayOf(it) })
    }

    private val openMultipleDocumentsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val callback = filePathCallback
        filePathCallback = null
        callback?.onReceiveValue(
            uris.takeIf { it.isNotEmpty() }?.toTypedArray()
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webfind)

        app = App.instance
        findMyUrl = app.getFindMyUrl()
        webView = findViewById(R.id.webView)
        val progress = findViewById<View>(R.id.progress)
        clearCacheFab = findViewById(R.id.clearCacheFab)

        if (!ensureWebViewAvailable()) {
            Toast.makeText(this, "系统WebView不可用，请升级卓易通或系统后重试", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Basic secure WebView setup
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = buildPrimaryUserAgent(userAgentString.orEmpty())
        }

        // Cookie设置
        try {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        } catch (e: Throwable) {
            println("CookieManager setup failed: ${e.message}")
        }

        // 添加JavaScript接口
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@WebFindActivity.filePathCallback?.onReceiveValue(null)
                this@WebFindActivity.filePathCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes.orEmpty()
                    .filter { !it.isNullOrBlank() }
                    .distinct()
                    .toTypedArray()
                    .ifEmpty { arrayOf("*/*") }

                val allowMultiple =
                    fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                return try {
                    if (allowMultiple) {
                        openMultipleDocumentsLauncher.launch(acceptTypes)
                    } else {
                        openDocumentLauncher.launch(acceptTypes)
                    }
                    true
                } catch (_: Exception) {
                    this@WebFindActivity.filePathCallback?.onReceiveValue(null)
                    this@WebFindActivity.filePathCallback = null
                    Toast.makeText(
                        this@WebFindActivity,
                        "无法打开文件选择器，请检查系统文件管理器/权限",
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // 兼容部分页面通过 window.open 打开登录/验证页，统一在当前 WebView 内处理。
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = webView
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE

                // 处理iCloud登录页面
                url?.let { currentUrl ->
                    if (isICloudUrl(currentUrl)) {
                        // 注入JavaScript来自动处理登录选项（包括 /find 登录页）
                        injectLoginHelpers()
                    }

                    // 登录后（或已存在登录态）自动跳转到 Find My
                    navigateToFindMyIfNeeded(currentUrl)

                    // 若命中 iCloud 的“不兼容浏览器”页面，自动降级到兼容 UA 后重试一次。
                    if (!compatibilityUserAgentApplied && isICloudUrl(currentUrl)) {
                        detectUnsupportedBrowserPageAndRetry(currentUrl)
                    }
                }

                // 页面加载完成后保存Cookie和URL
                url?.let {
                    app.saveCookies(it)
                    app.saveLastUrl(it)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return

                progress.visibility = View.GONE
                val failingUrl = request.url?.toString().orEmpty()
                println(
                    "WebView main-frame error: code=${error?.errorCode}, " +
                        "desc=${error?.description}, url=$failingUrl"
                )

                val shouldRetryCompat = error?.errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ||
                    error?.errorCode == WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME
                if (shouldRetryCompat && maybeSwitchToCompatibilityUserAgent("net_${error?.errorCode}", failingUrl)) {
                    return
                }

                Toast.makeText(
                    this@WebFindActivity,
                    "页面加载失败，请检查网络后重试",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return

                val statusCode = errorResponse?.statusCode ?: -1
                val failingUrl = request.url?.toString().orEmpty()
                println("WebView main-frame HTTP error: status=$statusCode, url=$failingUrl")

                if ((statusCode == 403 || statusCode == 406 || statusCode == 451) &&
                    maybeSwitchToCompatibilityUserAgent("http_$statusCode", failingUrl)
                ) {
                    return
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                progress.visibility = View.GONE
                println("WebView SSL error: primary=${error?.primaryError}, url=${error?.url}")
                handler?.cancel()
                Toast.makeText(
                    this@WebFindActivity,
                    "SSL连接失败，请检查设备时间和网络环境",
                    Toast.LENGTH_SHORT
                ).show()
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }
        }

        // 每次启动都打开 iCloud 首页；Cookie 恢复尽量用同 host 的 URL，避免 host/path 不匹配导致“掉登录”。
        val domain = app.getCurrentDomain()
        val restoreUrl = run {
            val lastUrl = app.getLastUrl()
            if (lastUrl.isNullOrBlank()) {
                domain
            } else {
                val domainHost = Uri.parse(domain).host
                val lastHost = Uri.parse(lastUrl).host
                if (!domainHost.isNullOrBlank() && domainHost == lastHost) lastUrl else domain
            }
        }

        if (app.hasLoginState()) {
            app.restoreCookies(restoreUrl)
        }
        val initialUrl = if (app.hasLoginState()) findMyUrl else domain
        webView.loadUrl(initialUrl)
        println("Loaded initial url: $initialUrl (domain=$domain, findMyUrl=$findMyUrl, restoreUrl=$restoreUrl)")

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

        // 设置清除缓存FAB的点击监听器
        clearCacheFab.setOnClickListener {
            clearWebViewCache()
        }

        // 设置FAB的拖拽功能
        setupFabDrag()
    }

    private fun setupFabDrag() {
        clearCacheFab.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    lastAction = MotionEvent.ACTION_DOWN
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = event.rawX + dX
                    val newY = event.rawY + dY

                    // 获取父布局的尺寸
                    val parent = view.parent as View
                    val parentWidth = parent.width
                    val parentHeight = parent.height

                    // 限制拖拽范围在父布局内
                    val fabWidth = view.width
                    val fabHeight = view.height

                    val constrainedX = newX.coerceIn(0f, (parentWidth - fabWidth).toFloat())
                    val constrainedY = newY.coerceIn(0f, (parentHeight - fabHeight).toFloat())

                    view.animate()
                        .x(constrainedX)
                        .y(constrainedY)
                        .setDuration(0)
                        .start()

                    lastAction = MotionEvent.ACTION_MOVE
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (lastAction == MotionEvent.ACTION_DOWN) {
                        // 如果只是点击而不是拖拽，则执行点击事件
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun injectLoginHelpers() {
        val jsCode = """
            (function() {
                'use strict';

                // 防重复注入（单页应用场景下 onPageFinished 可能多次触发）
                if (window.__FMD_LOGIN_HELPER_INSTALLED__) {
                    return;
                }
                window.__FMD_LOGIN_HELPER_INSTALLED__ = true;

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

                    // 尝试在登录流程结束后自动进入 Find My（如果已经在 /find 则不会重复跳转）
                    try {
                        if (!window.location.href.includes('/find') && !window.location.hash.includes('find')) {
                            // 这里不强制立即跳转，避免影响验证码/二次验证；交给原生侧 onPageFinished 做兜底跳转。
                            console.log('iCloud Login Helper: Not on Find My yet, native will handle redirect if needed');
                        }
                    } catch (e) {}

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

    private fun ensureWebViewAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pkg = WebView.getCurrentWebViewPackage()
                if (pkg == null) {
                    println("WebView provider not found")
                    false
                } else {
                    println("WebView provider: ${pkg.packageName} ${pkg.versionName}")
                    true
                }
            } else {
                true
            }
        } catch (e: Throwable) {
            println("WebView provider check failed: ${e.message}")
            false
        }
    }

    private fun buildPrimaryUserAgent(base: String): String {
        val compatSuffix = "Version/16.0 Mobile Safari/605.1.15"
        if (base.contains(compatSuffix)) return base
        return "$base AppleWebKit/605.1.15 (KHTML, like Gecko) $compatSuffix"
    }

    private fun maybeSwitchToCompatibilityUserAgent(reason: String, failingUrl: String?): Boolean {
        if (compatibilityUserAgentApplied) return false
        if (failingUrl.isNullOrBlank() || !isICloudUrl(failingUrl)) return false

        compatibilityUserAgentApplied = true
        webView.settings.userAgentString = IOS_SAFARI_COMPAT_UA
        val retryUrl = webView.url ?: app.getCurrentDomain()
        println("Switch UA to iOS Safari compatibility mode: reason=$reason, retryUrl=$retryUrl")

        Toast.makeText(this, "检测到内核兼容问题，已切换兼容模式重试", Toast.LENGTH_SHORT).show()
        webView.post { webView.loadUrl(retryUrl) }
        return true
    }

    private fun detectUnsupportedBrowserPageAndRetry(currentUrl: String) {
        webView.evaluateJavascript(
            """
            (function() {
                var text = ((document.body && document.body.innerText) || '').toLowerCase();
                var unsupported = text.includes('browser') && text.includes('support');
                return unsupported ? '1' : '0';
            })();
            """.trimIndent()
        ) { result ->
            if (result == "\"1\"") {
                maybeSwitchToCompatibilityUserAgent("unsupported_page", currentUrl)
            }
        }
    }

    private fun isICloudUrl(url: String): Boolean {
        return try {
            val host = Uri.parse(url).host ?: return false
            host.endsWith("icloud.com") || host.endsWith("icloud.com.cn")
        } catch (_: Exception) {
            false
        }
    }

    private fun isFindMyUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val path = uri.path.orEmpty()
            val frag = uri.fragment.orEmpty()
            path.startsWith("/find") || frag.contains("find", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun navigateToFindMyIfNeeded(currentUrl: String?) {
        if (currentUrl.isNullOrBlank()) return
        if (!isICloudUrl(currentUrl)) return
        if (isFindMyUrl(currentUrl)) return
        // 仅在明确需要时（例如登录流程结束）才自动跳转到 Find My。
        // 避免在用户访问 iCloud 其他页面（如上传/浏览文件）时被强制打断。
        if (!pendingRedirectToFindMy) return

        val now = System.currentTimeMillis()
        if (now - lastRedirectAtMs < 2_000) return
        lastRedirectAtMs = now
        pendingRedirectToFindMy = false

        webView.post {
            if (webView.url != null && !isFindMyUrl(webView.url!!)) {
                webView.loadUrl(findMyUrl)
            }
        }
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

    private fun clearWebViewCache() {
        // 显示确认对话框或直接清除
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()

        // 清除Cookie
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Throwable) {
            println("CookieManager clear failed: ${e.message}")
        }

        // 清除应用保存的登录状态
        app.clearAllState()
        pendingRedirectToFindMy = false

        // 显示提示信息
        Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show()

        // 重新加载默认页面
        loadDefaultDomain()
    }
}
