package com.uid.capture

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.krlvm.powertunnel.android.services.TunnelingVpnService

class MainActivity : AppCompatActivity(), TunnelingVpnService.CaptureCallback {

    private lateinit var tvStatus: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvToken: TextView
    private lateinit var tvUserName: TextView
    private lateinit var tvTargetHit: TextView
    private lateinit var tvDebug: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnCopyUid: Button
    private lateinit var btnCopyToken: Button
    private var vpnRunning = false

    private var currentUid: String? = null
    private var currentToken: String? = null
    private val debugLogs = StringBuilder()

    companion object {
        private const val VPN_REQUEST_CODE = 1001
        private const val NOTIFICATION_REQUEST_CODE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvUid = findViewById(R.id.tvUid)
        tvToken = findViewById(R.id.tvToken)
        tvUserName = findViewById(R.id.tvUserName)
        tvTargetHit = findViewById(R.id.tvTargetHit)
        tvDebug = findViewById(R.id.tvDebug)
        btnToggle = findViewById(R.id.btnToggle)
        btnCopyUid = findViewById(R.id.btnCopyUid)
        btnCopyToken = findViewById(R.id.btnCopyToken)

        requestNotificationPermission()

        btnToggle.setOnClickListener {
            if (vpnRunning) stopVpn() else prepareAndStartVpn()
        }

        btnCopyUid.setOnClickListener {
            if (currentUid.isNullOrEmpty()) {
                Toast.makeText(this, "还没有捕获到UID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard("UID", currentUid!!)
        }

        btnCopyToken.setOnClickListener {
            if (currentToken.isNullOrEmpty()) {
                Toast.makeText(this, "还没有捕获到Token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard("Token", currentToken!!)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_REQUEST_CODE
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TunnelingVpnService.setCallback(this)
    }

    override fun onPause() {
        super.onPause()
        TunnelingVpnService.setCallback(null)
    }

    private fun prepareAndStartVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
        } else {
            startVpn()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpn()
        } else {
            tvStatus.text = "❌ VPN权限被拒绝，无法抓包"
        }
    }

    private fun startVpn() {
        try {
            // 启动VPN时清空所有日志和命中记录
            debugLogs.clear()
            tvDebug.text = "✅ 已清空，等待新请求..."
            tvTargetHit.text = ""
            tvUid.text = "--"
            tvToken.text = "--"
            currentUid = null
            currentToken = null

            val intent = Intent(this, TunnelingVpnService::class.java)
            startForegroundService(intent)
            vpnRunning = true
            btnToggle.text = "停止VPN抓包"
            tvStatus.text = "正在启动VPN..."
        } catch (e: Exception) {
            tvStatus.text = "启动失败: ${e.message}"
            e.printStackTrace()
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, TunnelingVpnService::class.java)
        intent.action = "STOP"
        startService(intent)
        vpnRunning = false
        btnToggle.text = "启动VPN抓包"
        tvStatus.text = "VPN已停止"
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "✅ $label 已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    override fun onCaptured(data: TunnelingVpnService.CapturedData?) {
        runOnUiThread {
            data ?: return@runOnUiThread
            currentUid = data.uid
            currentToken = data.token
            tvUid.text = data.uid ?: "未知"
            tvToken.text = data.token ?: "未知"
            tvUserName.text = "账号: ${data.userName ?: "未知"}  年龄: ${data.age ?: "?"}"
            tvStatus.text = "✅ 捕获成功！"
        }
    }

    override fun onStatusChanged(status: String?) {
        runOnUiThread { tvStatus.text = status ?: "" }
    }

    override fun onDebugLog(log: String?) {
        runOnUiThread {
            log ?: return@runOnUiThread
            debugLogs.append(log).append("\n")
            val lines = debugLogs.toString().lines()
            tvDebug.text = if (lines.size > 20) {
                lines.takeLast(20).joinToString("\n")
            } else {
                debugLogs.toString()
            }
        }
    }

    override fun onTargetHit(info: String?) {
        runOnUiThread {
            info ?: return@runOnUiThread
            tvTargetHit.text = info
            tvTargetHit.setTextColor(0xFFE53935.toInt())
        }
    }
}
