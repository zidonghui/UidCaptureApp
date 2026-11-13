package com.uid.capture

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var tvLog: TextView
    private lateinit var tvUid: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    companion object {
        private const val VPN_REQUEST_CODE = 1001
        var onLog: ((String) -> Unit)? = null
        var onUidCaptured: ((String) -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvLog = findViewById(R.id.tvLog)
        tvUid = findViewById(R.id.tvUid)
        btnStart = findViewById(R.id.btnStartVpn)
        btnStop = findViewById(R.id.btnStopVpn)

        onLog = { msg ->
            runOnUiThread { tvLog.append("\n$msg") }
        }

        onUidCaptured = { uid ->
            runOnUiThread {
                tvUid.text = "UID: $uid"
                Toast.makeText(this, "✅ 捕获UID: $uid", Toast.LENGTH_LONG).show()
            }
        }

        btnStart.setOnClickListener {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
            } else {
                startVpn()
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, VpnCaptureService::class.java))
            tvLog.append("\nVPN已停止")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpn()
        }
    }

    private fun startVpn() {
        startService(Intent(this, VpnCaptureService::class.java))
        tvLog.append("\nVPN已启动，请打开游戏登录")
    }
}
