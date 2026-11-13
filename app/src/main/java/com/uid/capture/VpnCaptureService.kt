package com.uid.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class VpnCaptureService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var running = false
    private val TAG = "UidVpn"

    companion object {
        private const val CHANNEL_ID = "uid_capture_vpn"
        private const val NOTIFICATION_ID = 1
    }

    private val sessions = ConcurrentHashMap<String, TcpSession>()

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        running = true
        Thread {
            try {
                val builder = Builder()
                    .setSession("UidCapture")
                    .addAddress("10.1.10.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)

                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    MainActivity.onLog?.invoke("❌ VPN建立失败")
                    return@Thread
                }

                MainActivity.onLog?.invoke("✅ VPN已启动，正在转发流量")
                val fis = FileInputStream(vpnInterface!!.fileDescriptor)
                val fos = FileOutputStream(vpnInterface!!.fileDescriptor)
                val buffer = ByteArray(32767)

                while (running) {
                    val len = fis.read(buffer)
                    if (len <= 0) break
                    processPacket(buffer, len, fos)
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPN错误", e)
                MainActivity.onLog?.invoke("❌ VPN错误: ${e.message}")
            }
        }.start()
    }

    private fun processPacket(data: ByteArray, length: Int, fos: FileOutputStream) {
        try {
            val bb = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            val versionIhl = bb.get().toInt()
            val version = versionIhl shr 4
            val ihl = (versionIhl and 0x0F) * 4
            if (version != 4) return

            bb.position(9)
            val protocol = bb.get().toInt()
            if (protocol != 6) return

            bb.position(12)
            val srcIp = "${bb.get().toInt() and 0xFF}.${bb.get().toInt() and 0xFF}.${bb.get().toInt() and 0xFF}.${bb.get().toInt() and 0xFF}"
            val dstIp = "${bb.get().toInt() and 0xFF}.${bb.get().toInt() and 0xFF}.${bb.get().toInt() and 0xFF}.${bb.get().toInt() and 0xFF}"

            bb.position(ihl)
            val srcPort = bb.short.toInt() and 0xFFFF
            val dstPort = bb.short.toInt() and 0xFFFF

            val dataOffsetReserved = bb.get().toInt()
            val flags = bb.get().toInt()
            val fin = flags and 0x01
            val syn = flags and 0x02
            val ack = flags and 0x10

            val sessionKey = "$srcIp:$srcPort"
            val payloadStart = ihl + 20

            if (syn != 0 && ack == 0) {
                MainActivity.onLog?.invoke("🔌 新TCP连接: $srcIp:$srcPort -> $dstIp:$dstPort")
                val session = TcpSession(srcIp, srcPort, dstIp, dstPort, fos)
                sessions[sessionKey] = session
                session.start()
            }

            if (payloadStart < length) {
                val session = sessions[sessionKey]
                if (session != null) {
                    val payload = String(data, payloadStart, length - payloadStart)
                    if (dstPort == 80 && (payload.contains("logs.quickapi.net") || payload.contains("checkLogin"))) {
                        MainActivity.onLog?.invoke("🎯 检测到目标请求: $payload")
                    }
                    session.sendData(data, payloadStart, length - payloadStart)
                }
            }

            if (fin != 0) {
                sessions.remove(sessionKey)?.close()
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理包错误", e)
        }
    }

    private inner class TcpSession(
        private val srcIp: String,
        private val srcPort: Int,
        private val dstIp: String,
        private val dstPort: Int,
        private val fos: FileOutputStream
    ) {
        private var socket: Socket? = null
        private var running = false

        fun start() {
            running = true
            Thread {
                try {
                    socket = Socket()
                    protect(socket)
                    socket?.connect(InetSocketAddress(dstIp, dstPort), 10000)
                    MainActivity.onLog?.invoke("✅ 已连接: $dstIp:$dstPort")

                    val input = socket?.getInputStream()
                    val buffer = ByteArray(4096)
                    while (running) {
                        val len = input?.read(buffer) ?: break
                        if (len <= 0) break

                        val response = String(buffer, 0, len)
                        if (response.contains("uid")) {
                            val uidRegex = Regex("\"uid\"\\s*:\\s*\"?(\\d+)\"?")
                            val match = uidRegex.find(response)
                            if (match != null) {
                                val uid = match.groupValues[1]
                                MainActivity.onLog?.invoke("✅ 捕获UID: $uid")
                                MainActivity.onUidCaptured?.invoke(uid)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "会话错误", e)
                }
            }.start()
        }

        fun sendData(data: ByteArray, offset: Int, length: Int) {
            try {
                val output = socket?.getOutputStream()
                output?.write(data, offset, length)
                output?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "发送数据错误", e)
            }
        }

        fun close() {
            running = false
            try {
                socket?.close()
            } catch (e: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "UidCapture VPN", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("UidCapture")
            .setContentText("VPN抓包运行中")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    private fun stopVpn() {
        running = false
        sessions.values.forEach { it.close() }
        sessions.clear()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }
}
