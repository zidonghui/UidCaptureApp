package com.uid.capture

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.fastjson.JSON
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.LastHttpContent
import org.littleshoot.proxy.HttpFilters
import org.littleshoot.proxy.HttpFiltersAdapter
import org.littleshoot.proxy.HttpFiltersSourceAdapter
import org.littleshoot.proxy.HttpProxyServer
import org.littleshoot.proxy.impl.DefaultHttpProxyServer
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
                override fun filterRequest(originalRequest: io.netty.handler.codec.http.HttpRequest): HttpFilters {
                    return object : HttpFiltersAdapter(originalRequest) {
                        var responseBodyBuffer = StringBuilder()
                        val req = originalRequest

                        // ✅ 正确方法：serverToProxyResponse(HttpObject)
                        override fun serverToProxyResponse(httpObject: HttpObject): HttpObject {
                            if (httpObject is HttpResponse) {
                                // 响应头到达，清空buffer
                                responseBodyBuffer.clear()
                            }
                            if (httpObject is LastHttpContent) {
                                // 响应body全部接收完毕
                                val host = req.headers().get("Host") ?: ""
                                val path = req.uri ?: ""
                                if (host.contains("logs.quickapi.net") && path.contains("checkLogin")) {
                                    val buf: ByteBuf = httpObject.content()
                                    val bodyStr = buf.toString(StandardCharsets.UTF_8)
                                    responseBodyBuffer.append(bodyStr)
                                    try {
                                        val jsonObj = JSON.parseObject(responseBodyBuffer.toString())
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
                            return super.serverToProxyResponse(httpObject)
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
