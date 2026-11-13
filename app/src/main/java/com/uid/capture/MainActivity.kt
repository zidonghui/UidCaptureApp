package com.uid.capture

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.krlvm.powertunnel.android.services.TunnelingVpnService

class MainActivity : AppCompatActivity() {
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        btnStart.setOnClickListener {
            val prepareIntent = VpnService.prepare(this@MainActivity)
            if (prepareIntent != null) {
                startActivityForResult(prepareIntent, 1001)
            } else {
                startVpn()
            }
        }
        btnStop.setOnClickListener {
            stopVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, TunnelingVpnService::class.java)
        intent.action = TunnelingVpnService.ACTION_START
        startService(intent)
        tvStatus.text = "VPN 已启动"
    }

    private fun stopVpn() {
        val intent = Intent(this, TunnelingVpnService::class.java)
        intent.action = TunnelingVpnService.ACTION_STOP
        startService(intent)
        tvStatus.text = "VPN 已停止"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            startVpn()
        }
    }
}
