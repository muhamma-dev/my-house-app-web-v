package com.myhouse.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * My House, WebView edition. The app is only a frame around the device's own web page:
 *  - at home it finds the device on the Wi-Fi and shows the page the device serves;
 *  - away from home it shows its own copy of the same page and sends every request through the
 *    hidden Firebase link (Remote.kt), so the screens are identical.
 * Text selection and the long-press menu are switched off everywhere.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var loader: WebViewAssetLoader
    private lateinit var remote: Remote
    private val io = Executors.newCachedThreadPool()
    private val prefs by lazy { getSharedPreferences("mhp", 0) }

    @Volatile private var base = ""        // device address while the page comes from the device
    @Volatile private var mode = 0         // 1 = device page (home), 2 = app copy + hidden link (away), 3 = offline page
    @Volatile private var alive = true
    @Volatile private var finding = false
    @Volatile private var lastLocalFail = 0L
    private var docStart = false
    private var chooser: ValueCallback<Array<Uri>>? = null

    private val host = "https://appassets.androidplatform.net"
    private val keys = listOf("tok", "nm", "lang", "theme")

    // Runs at the start of every page: shares the saved login/language/theme between pages,
    // switches off text selection and the long-press menu, and makes file downloads work.
    private val startJs = """
(function(){
  if(window.__mhInit)return; window.__mhInit=true;
  var K=['tok','nm','lang','theme'];
  var si=Storage.prototype.setItem, ri=Storage.prototype.removeItem;
  try{
    K.forEach(function(k){
      var v=MHNative.load(k);
      if(v){ si.call(window.localStorage,k,v); }
      else { var p=window.localStorage.getItem(k); if(p) MHNative.save(k,p); }
    });
  }catch(e){}
  Storage.prototype.setItem=function(k,v){ si.call(this,k,v); try{ if(this===window.localStorage && K.indexOf(k)>=0) MHNative.save(k,String(v)); }catch(e){} };
  Storage.prototype.removeItem=function(k){ ri.call(this,k); try{ if(this===window.localStorage && K.indexOf(k)>=0) MHNative.del(k); }catch(e){} };
  var css='*{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent}input,textarea{-webkit-user-select:text;user-select:text}';
  function addCss(){ var s=document.createElement('style'); s.textContent=css; (document.head||document.documentElement).appendChild(s); }
  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',addCss); else addCss();
  window.addEventListener('contextmenu',function(e){e.preventDefault();});
  document.addEventListener('selectstart',function(e){var t=e.target; if(!(t&&t.tagName&&(t.tagName==='INPUT'||t.tagName==='TEXTAREA'))) e.preventDefault();});
  var cl=HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click=function(){
    if(this.download && this.href && this.href.indexOf('blob:')===0){
      var nm=this.download, h=this.href;
      fetch(h).then(function(r){return r.blob();}).then(function(b){var fr=new FileReader();fr.onload=function(){MHNative.saveFile(nm,String(fr.result).split(',')[1]||'');};fr.readAsDataURL(b);});
      return;
    }
    return cl.apply(this,arguments);
  };
})();
""".trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        remote = Remote(this)
        loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this)
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        s.allowFileAccess = false
        web.setBackgroundColor(0xFF0B0D12.toInt())
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(Bridge(), "MHNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                return loader.shouldInterceptRequest(u)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!docStart) view?.evaluateJavascript(startJs, null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request != null && request.isForMainFrame && mode == 1) io.execute { reevaluate(false) }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?,
            ): Boolean {
                chooser?.onReceiveValue(null)
                chooser = callback
                return try {
                    startActivityForResult(params!!.createIntent(), 77)
                    true
                } catch (e: Exception) {
                    chooser = null
                    false
                }
            }
        }
        docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) WebViewCompat.addDocumentStartJavaScript(web, startJs, setOf("*"))
        web.loadUrl("$host/assets/offline.html?loading=1")
        findAndLoad()
        watchdog()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 77) {
            chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            chooser = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        if (mode != 0) io.execute { reevaluate(false) }
    }

    override fun onDestroy() {
        alive = false
        web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ finding the device

    private fun http(url: String, method: String, token: String, body: String?, timeout: Int): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeout
            c.readTimeout = timeout
            if (token.isNotEmpty()) c.setRequestProperty("X-Token", token)
            c.setRequestProperty("Content-Type", "application/json")
            if (method != "GET" && method != "DELETE") {
                c.doOutput = true
                c.outputStream.use { it.write((body ?: "").toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return Pair(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun isDevice(b: String, timeout: Int): Boolean {
        return try {
            val r = http("$b/api/state", "GET", "", null, timeout)
            r.first == 200 && r.second.contains("\"ch\"")
        } catch (e: Exception) {
            false
        }
    }

    private fun candidates(): List<String> {
        val l = ArrayList<String>()
        fun add(a: String?) {
            if (a.isNullOrBlank()) return
            var x = a.trim().trimEnd('/')
            if (!x.startsWith("http")) x = "http://$x"
            if (!l.contains(x)) l.add(x)
        }
        add(prefs.getString("addr", null))
        add(prefs.getString("ip", null))
        add(if (base.isEmpty()) null else base)
        add("http://smartlight.local")
        add("http://192.168.4.1")
        return l
    }

    private fun phonePrefix(): String? {
        try {
            val all = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (ni in all) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.interfaceAddresses) {
                    val ip = a.address
                    if (ip is Inet4Address && ip.isSiteLocalAddress) {
                        val h = ip.hostAddress ?: continue
                        return h.substringBeforeLast('.')
                    }
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    /** Looks at every address on the phone's Wi-Fi network (the router may have given the device a new one). */
    private fun scanSubnet(): String? {
        val p = phonePrefix() ?: return null
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) {
            pool.execute {
                if (found.get() == null) {
                    val b = "http://$p.$i"
                    if (isDevice(b, 700)) found.compareAndSet(null, b)
                }
            }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(8, TimeUnit.SECONDS)
        } catch (e: Exception) {
        }
        val f = found.get()
        if (f != null) prefs.edit().putString("ip", f).apply()
        return f
    }

    private fun findDevice(scan: Boolean, timeout: Int = 1500): String? {
        for (c in candidates()) {
            if (isDevice(c, timeout)) {
                prefs.edit().putString("ip", c).apply()
                return c
            }
        }
        return if (scan) scanSubnet() else null
    }

    private fun hasTok(): Boolean = !prefs.getString("s_tok", "").isNullOrEmpty()

    /** Chooses which page to show: the device's (home), the app copy over the hidden link (away) or the offline page. */
    private fun reevaluate(scan: Boolean) {
        val b = findDevice(scan)
        runOnUiThread {
            if (b != null) {
                if (mode != 1 || b != base) {
                    base = b
                    mode = 1
                    web.loadUrl("$b/")
                }
            } else if (remote.ready && hasTok()) {
                if (mode != 2) {
                    mode = 2
                    web.loadUrl("$host/assets/index.html")
                }
            } else {
                if (mode != 3) {
                    mode = 3
                    web.loadUrl("$host/assets/offline.html")
                }
            }
        }
    }

    private fun findAndLoad() {
        if (finding) return
        finding = true
        io.execute {
            try {
                reevaluate(true)
            } finally {
                finding = false
            }
        }
    }

    private fun syncCfg(b: String) {
        val tok = prefs.getString("s_tok", "") ?: ""
        if (tok.isEmpty()) return
        try {
            val r = http("$b/api/fbcfg", "GET", tok, null, 3000)
            if (r.first == 200) remote.store(JSONObject(r.second))
        } catch (e: Exception) {
        }
    }

    private fun watchdog() {
        io.execute {
            var fails = 0
            var lastCfg = 0L
            while (alive) {
                try {
                    Thread.sleep(if (mode == 1) 5000L else 12000L)
                } catch (e: Exception) {
                }
                if (!alive) break
                val m = mode
                if (m == 0 || finding) continue
                if (m == 1) {
                    if (isDevice(base, 2000)) {
                        fails = 0
                        if (System.currentTimeMillis() - lastCfg > 600000L) {
                            lastCfg = System.currentTimeMillis()
                            syncCfg(base)
                        }
                    } else {
                        fails++
                        if (fails >= 2) {
                            fails = 0
                            reevaluate(true)
                        }
                    }
                } else {
                    reevaluate(false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ what the pages can call

    inner class Bridge {
        @JavascriptInterface
        fun load(k: String): String? = prefs.getString("s_$k", null)

        @JavascriptInterface
        fun save(k: String, v: String) {
            if (k in keys) prefs.edit().putString("s_$k", v).apply()
        }

        @JavascriptInterface
        fun del(k: String) {
            if (k in keys) prefs.edit().remove("s_$k").apply()
        }

        @JavascriptInterface
        fun setAddr(a: String) {
            prefs.edit().putString("addr", a.trim()).apply()
            findAndLoad()
        }

        @JavascriptInterface
        fun retry() {
            findAndLoad()
        }

        @JavascriptInterface
        fun saveFile(name: String, b64: String) {
            try {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val safe = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifEmpty { "file" }
                val f = File(dir, safe)
                f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                runOnUiThread { Toast.makeText(this@MainActivity, f.absolutePath, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
            }
        }

        /** Every /api/ request of the app copy: the home network first, then the hidden link. */
        @JavascriptInterface
        fun call(id: Int, method: String, path: String, token: String, body: String) {
            io.execute {
                var status = 0
                var text = ""
                try {
                    val canRemote = remote.ready && token.isNotEmpty()
                    var done = false
                    val b = if (base.isNotEmpty()) base else (prefs.getString("ip", "") ?: "")
                    if (b.isNotEmpty() && (mode != 2 || System.currentTimeMillis() - lastLocalFail > 15000L)) {
                        try {
                            val r = http(b + path, method, token, body, if (canRemote) 1500 else 6000)
                            status = r.first
                            text = r.second
                            done = true
                        } catch (e: Exception) {
                            lastLocalFail = System.currentTimeMillis()
                        }
                    }
                    if (!done && canRemote) {
                        val bj = if (body.isNotEmpty()) JSONObject(body) else null
                        val j = runBlocking { remote.call(path, method, bj, token, 12000) }
                        status = j.optInt("_s")
                        j.remove("_s")
                        text = j.toString()
                    }
                } catch (e: Exception) {
                    status = 0
                }
                val js = "window.__nat(" + id + "," + status + "," + JSONObject.quote(text) + ")"
                runOnUiThread { web.evaluateJavascript(js, null) }
            }
        }
    }
}
