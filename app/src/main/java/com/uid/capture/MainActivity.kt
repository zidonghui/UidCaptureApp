package com.uid.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.krlvm.powertunnel.android.services.TunnelingVpnService

class MainActivity : AppCompatActivity(), TunnelingVpnService.CaptureCallback {

    private lateinit var tvStatus: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvToken: TextView
    private lateinit var tvUserName: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnCopyUid: Button
    private lateinit var btnCopyToken: Button
    private var vpnRunning = false

    private var currentUid: String? = null
    private var currentToken: String? = null

    companion object {
        private const val VPN_REQUEST_CODE = 1001
        private const val TAG = "UidCapture"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 全局异常捕获，防止闪退
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "未捕获异常", throwable)
            val errorMsg = "崩溃: ${throwable.javaClass.simpleName}: ${throwable.message}"
            runOnUiThread {
                tvStatus.text = errorMsg
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
        }

        tvStatus = findViewById(R.id.tvStatus)
        tvUid = findViewById(R.id.tvUid)
        tvToken = findViewById(R.id.tvToken)
        tvUserName = findViewById(R.id.tvUserName)
        btnToggle = findViewById(R.id.btnToggle)
        btnCopyUid = findViewById(R.id.btnCopyUid)
        btnCopyToken = findViewById(R.id.btnCopyToken)

        btnToggle.setOnClickListener {
            if (vpnRunning) {
                stopVpn()
            } else {
                try {
                    prepareAndStartVpn()
                } catch (e: Exception) {
                    tvStatus.text = "启动失败: ${e.message}"
                    Log.e(TAG, "启动VPN失败", e)
                }
            }
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

    override fun onResume() {
        super.onResume()
        TunnelingVpnService.setCallback(this)
    }

    override fun onPause() {
        super.onPause()
        TunnelingVpnService.setCallback(null)
    }

    private fun prepareAndStartVpn() {
        tvStatus.text = "正在准备VPN权限..."
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
        tvStatus.text = "正在启动VPN服务..."
        try {
            val intent = Intent(this, TunnelingVpnService::class.java)
            startService(intent)
            vpnRunning = true
            btnToggle.text = "停止VPN抓包"
        } catch (e: Exception) {
            tvStatus.text = "启动服务失败: ${e.message}"
            Log.e(TAG, "startService失败", e)
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
        runOnUiThread {
            tvStatus.text = status ?: ""
        }
    }
}
