package com.kasukusakura.sakuraloginsolver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.JsonParser
import com.kasukusakura.sakuraloginsolver.databinding.ActivityCaptchaBinding

class CaptchaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCaptchaBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCaptchaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val webview = binding.webview
        webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return onJsBridgeInvoke(request!!.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (!intent.hasExtra("raw-direct")) return

                webview.evaluateJavascript("document.body.textContent") { rspx ->
                    kotlin.runCatching {
                        val roxitx = JsonParser.parseString(rspx)
                        if (roxitx.isJsonPrimitive) {
                            roxitx.asString
                        } else {
                            rspx
                        }.also { JsonParser.parseString(it).asJsonObject }
                    }.onSuccess { erx ->
                        val intent = Intent()
                            .putExtras(intent)
                            .putExtra("srsp", erx)


                        setResult(RESULT_OK, intent)
                        finish()
                    }
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        intent.getStringExtra("url")?.let { webview.loadUrl(it) }
    }


    private fun onJsBridgeInvoke(request: Uri): Boolean {
        if (request.path.equals("/onVerifyCAPTCHA")) {
            val p = request.getQueryParameter("p")
            val jsData = JsonParser.parseString(p).asJsonObject
            authFinish(jsData["ticket"].asString)
        }
        return false
    }

    private fun authFinish(ticket: String) {
        val intent = Intent()
            .putExtras(this.intent)
            .putExtra("ticket", ticket)


        setResult(RESULT_OK, intent)
        finish()
    }
}