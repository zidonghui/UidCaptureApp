package com.uid.capture

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.fastjson.JSON
import org.littleshoot.proxy.HttpFilters
import org.littleshoot.proxy.HttpFiltersAdapter
import org.littleshoot.proxy.HttpFiltersSourceAdapter
import org.littleshoot.proxy.HttpProxyServer
import org.littleshoot.proxy.impl.DefaultHttpProxyServer
import io.netty.buffer.ByteBufUtil
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private var proxyServer: HttpProxyServer? = null
    private lateinit var tvResult: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvResult = findViewById(R.id.tvResult)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        btnStart.setOnClickListener {
            startProxy()
        }
        btnStop.setOnClickListener {
            stopProxy()
        }
    }

    private fun startProxy() {
        if (proxyServer != null) return
        proxyServer = DefaultHttpProxyServer.bootstrap()
            .withPort(8888)
            .withFiltersSource(object : HttpFiltersSourceAdapter() {
                override fun filterRequest(request: HttpRequest): HttpFilters {
                    return object : HttpFiltersAdapter(request) {
                        // 新版LittleProxy回调：responseReceived
                        override fun responseReceived(serverResponse: HttpResponse?) {
                            super.responseReceived(serverResponse)
                            val host = request.headers().get("Host") ?: ""
                            val path = request.uri
                            if (host.contains("logs.quickapi.net") && path.contains("checkLogin")) {
                                serverResponse?.content()?.let { buf ->
                                    val content = ByteBufUtil.decodeString(buf, StandardCharsets.UTF_8)
                                    try {
                                        val jsonObj = JSON.parseObject(content)
                                        val data = jsonObj.getJSONObject("data")
                                        val uid = data.getString("uid")
                                        val token = data.getString("token")
                                        runOnUiThread {
                                            tvResult.text = "✅抓到登录信息\nuid: $uid\ntoken:$token"
                                        }
                                    } catch (e: Exception) {
                                        runOnUiThread {
                                            tvResult.text = "解析失败:${e.message}"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            })
            .start()
        runOnUiThread {
            tvResult.text = "✅代理已启动，端口8888\n手机WiFi代理设置：本机IP:8888"
        }
    }

    private fun stopProxy() {
        proxyServer?.stop()
        proxyServer = null
        runOnUiThread {
            tvResult.text = "❌代理已停止"
        }
    }
}
