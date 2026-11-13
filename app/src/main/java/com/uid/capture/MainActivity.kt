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
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import top.sankokomi.wirebare.kernel.common.WireBare
import top.sankokomi.wirebare.kernel.interceptor.http.HttpHeaderParserInterceptor
import top.sankokomi.wirebare.kernel.interceptor.http.HttpInterceptorFactory
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpInterceptorFactory

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvToken: TextView
    private lateinit var tvUserName: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnCopyUid: Button
    private lateinit var btnCopyToken: Button
    private var vpnRunning = false

    private var currentUid: String? = null
    private var currentToken: String? = null

    companion object {
        private const val VPN_REQUEST_CODE = 1001
        private const val NOTIFICATION_REQUEST_CODE = 1002
        private const val TAG = "UidCaptureApp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvUid = findViewById(R.id.tvUid)
        tvToken = findViewById(R.id.tvToken)
        tvUserName = findViewById(R.id.tvUserName)
        tvLog = findViewById(R.id.tvLog)
        btnToggle = findViewById(R.id.btnToggle)
        btnCopyUid = findViewById(R.id.btnCopyUid)
        btnCopyToken = findViewById(R.id.btnCopyToken)

        requestNotificationPermission()

        WireBare.init(this)

        UidCaptureInterceptor.onUidCaptured = { uid, token, userName ->
            runOnUiThread {
                currentUid = uid
                currentToken = token
                tvUid.text = uid
                tvToken.text = token
                tvUserName.text = "账号: $userName"
                tvStatus.text = "✅ 捕获成功！"
                Toast.makeText(this, "✅ 捕获UID: $uid", Toast.LENGTH_LONG).show()
            }
        }

        UidCaptureInterceptor.onLog = { log ->
            runOnUiThread {
                tvLog.append("$log\n")
                Log.d(TAG, log)
            }
        }

        btnToggle.setOnClickListener {
            if (vpnRunning) {
                stopVpn()
            } else {
                prepareAndStartVpn()
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
            WireBare.startProxy {
                addRoutes("0.0.0.0" to 0)
                addDnsServers("8.8.8.8", "114.114.114.114")

                addHttpInterceptor(listOf(
                    HttpInterceptorFactory { HttpHeaderParserInterceptor() }
                ))

                addAsyncHttpInterceptor(listOf(
                    AsyncHttpInterceptorFactory {
                        UidCaptureInterceptor()
                    }
                ))
            }

            vpnRunning = true
            btnToggle.text = "停止VPN抓包"
            tvStatus.text = "✅ VPN抓包已启动\n\n请打开游戏并登录"
            tvLog.append("--- VPN启动成功 ---\n")
        } catch (e: Exception) {
            tvStatus.text = "启动失败: ${e.message}"
            e.printStackTrace()
            Log.e(TAG, "启动失败", e)
        }
    }

    private fun stopVpn() {
        try {
            WireBare.stopProxy()
            vpnRunning = false
            btnToggle.text = "启动VPN抓包"
            tvStatus.text = "VPN已停止"
            tvLog.append("--- VPN已停止 ---\n")
        } catch (e: Exception) {
            tvStatus.text = "停止失败: ${e.message}"
            e.printStackTrace()
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "✅ $label 已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
