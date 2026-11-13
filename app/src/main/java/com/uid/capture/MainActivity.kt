package com.uid.capture

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wirebare.kernel.WireBare
import com.wirebare.kernel.interceptor.http.HttpInterceptor
import com.wirebare.kernel.model.HttpMessage
import com.wirebare.kernel.net.Port
import com.wirebare.kernel.tcpip.IpProtocol
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var wireBare: WireBare? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvLog = findViewById(R.id.tvLog)
        btnStart = findViewById(R.id.btnStartVpn)
        btnStop = findViewById(R.id.btnStopVpn)

        // 注册HTTP拦截器，捕获uid
        val captureInterceptor = object : HttpInterceptor {
            override fun onHttpMessage(msg: HttpMessage) {
                val body = msg.body.toString(StandardCharsets.UTF_8)
                if (body.contains("uid")) {
                    runOnUiThread {
                        tvLog.append("\n✅抓到uid响应：\n$body")
                    }
                }
            }
        }

        btnStart.setOnClickListener {
            val prepareIntent = VpnService.prepare(this@MainActivity)
            if (prepareIntent != null) {
                startActivityForResult(prepareIntent, 1001)
            } else {
                startWireBare()
            }
        }

        btnStop.setOnClickListener {
            wireBare?.stop()
            tvLog.text = "WireBare VPN 已停止"
        }

        wireBare = WireBare.Builder()
            .addInterceptor(captureInterceptor)
            .build()
    }

    private fun startWireBare() {
        wireBare?.start(this)
        tvLog.text = "WireBare VPN已启动，打开游戏抓uid..."
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode == 1001 && resultCode == RESULT_OK){
            startWireBare()
        }
    }
}
