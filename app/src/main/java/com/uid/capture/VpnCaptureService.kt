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
                    .addAddress("10.10.10.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)

                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    MainActivity.onLog?.invoke("❌ VPN建立失败")
                    return@Thread
                }

                MainActivity.onLog?.invoke("✅ VPN已启动，双向转发中")
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
            val srcIpBytes = ByteArray(4)
            bb.get(srcIpBytes)
            val dstIpBytes = ByteArray(4)
            bb.get(dstIpBytes)

            bb.position(ihl)
            val srcPort = bb.short.toInt() and 0xFFFF
            val dstPort = bb.short.toInt() and 0xFFFF
            val seqNum = bb.int
            val ackNum = bb.int
            val dataOffsetFlags = bb.short.toInt()
            val dataOffset = (dataOffsetFlags shr 12 and 0xF) * 4
            val flags = dataOffsetFlags and 0x1FF
            val fin = flags and 0x01
            val syn = flags and 0x02
            val rst = flags and 0x04
            val psh = flags and 0x08
            val ack = flags and 0x10

            val srcIpStr = srcIpBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            val dstIpStr = dstIpBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            val sessionKey = "$srcIpStr:$srcPort"
            val payloadStart = ihl + dataOffset
            val payloadLen = length - payloadStart

            when {
                syn != 0 && ack == 0 -> {
                    MainActivity.onLog?.invoke("🔌 新连接: $srcIpStr:$srcPort -> $dstIpStr:$dstPort")
                    val session = TcpSession(
                        srcIpBytes, srcPort, dstIpBytes, dstPort, fos, seqNum
                    )
                    sessions[sessionKey] = session
                    session.start()
                }
                rst != 0 -> {
                    sessions.remove(sessionKey)?.close()
                }
                fin != 0 -> {
                    sessions.remove(sessionKey)?.onFin()
                }
                payloadLen > 0 -> {
                    val session = sessions[sessionKey]
                    if (session != null) {
                        val payload = ByteArray(payloadLen)
                        bb.position(payloadStart)
                        bb.get(payload)
                        
                        val payloadStr = String(payload)
                        if (dstPort == 80 && (payloadStr.contains("logs.quickapi.net") || payloadStr.contains("checkLogin"))) {
                            MainActivity.onLog?.invoke("🎯 检测到目标请求")
                        }
                        
                        session.sendClientData(payload, seqNum)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理包错误", e)
        }
    }

    private inner class TcpSession(
        private val srcIp: ByteArray,
        private val srcPort: Int,
        private val dstIp: ByteArray,
        private val dstPort: Int,
        private val fos: FileOutputStream,
        private var clientSeq: Int
    ) {
        private var socket: Socket? = null
        private var running = false
        private var serverSeq: Int = 1000

        fun start() {
            running = true
            Thread {
                try {
                    socket = Socket()
                    protect(socket!!)
                    socket!!.connect(InetSocketAddress(dstIp.joinToString(".") { (it.toInt() and 0xFF).toString() }, dstPort), 10000)
                    MainActivity.onLog?.invoke("✅ 已连接服务器: ${dstIp.joinToString(".") { (it.toInt() and 0xFF).toString() }}:$dstPort")

                    // 回SYN+ACK给客户端
                    sendTcpPacket(clientSeq + 1, 0x12, ByteArray(0))

                    // 启动读取服务器响应的线程
                    Thread {
                        try {
                            val input = socket!!.getInputStream()
                            val buf = ByteArray(4096)
                            while (running) {
                                val len = input.read(buf)
                                if (len <= 0) break

                                val data = buf.copyOf(len)
                                
                                // 检查UID
                                val respStr = String(data)
                                if (respStr.contains("uid")) {
                                    val uidRegex = Regex("\"uid\"\\s*:\\s*\"?(\\d+)\"?")
                                    val match = uidRegex.find(respStr)
                                    if (match != null) {
                                        val uid = match.groupValues[1]
                                        MainActivity.onLog?.invoke("✅ 捕获UID: $uid")
                                        MainActivity.onUidCaptured?.invoke(uid)
                                    }
                                }

                                // 把服务器响应封装成IP+TCP包，写回TUN
                                sendToClient(data)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "读服务器错误", e)
                        }
                    }.start()

                } catch (e: Exception) {
                    Log.e(TAG, "连接服务器失败", e)
                    MainActivity.onLog?.invoke("❌ 连接失败: ${e.message}")
                }
            }.start()
        }

        fun sendClientData(data: ByteArray, seqNum: Int) {
            try {
                val output = socket?.getOutputStream()
                output?.write(data)
                output?.flush()
                clientSeq = seqNum + data.size
                // 回ACK给客户端
                sendTcpPacket(clientSeq, 0x10, ByteArray(0))
            } catch (e: Exception) {
                Log.e(TAG, "发送客户端数据错误", e)
            }
        }

        private fun sendToClient(data: ByteArray) {
            sendTcpPacket(clientSeq + 1, 0x18, data) // PSH+ACK
            serverSeq += data.size
        }

        private fun sendTcpPacket(ackNum: Int, flags: Int, payload: ByteArray) {
            try {
                val tcpLen = 20 + payload.size
                val ipLen = 20 + tcpLen

                val packet = ByteArray(ipLen)
                val bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

                // IP头
                bb.put(0x45) // version + IHL
                bb.put(0x00) // TOS
                bb.putShort(ipLen.toShort()) // 总长度
                bb.putShort(0) // 标识
                bb.putShort(0x4000.toShort()) // DF
                bb.put(64.toByte()) // TTL
                bb.put(6) // protocol TCP
                bb.putShort(0) // checksum 先填0
                bb.put(dstIp) // 源IP（服务器→客户端，源IP是原来的目标IP）
                bb.put(srcIp) // 目标IP（原来的源IP）

                // TCP头
                bb.putShort(dstPort.toShort()) // 源端口（原来的目标端口）
                bb.putShort(srcPort.toShort()) // 目标端口（原来的源端口）
                bb.putInt(serverSeq) // 序列号
                bb.putInt(ackNum) // 确认号
                bb.putShort((0x5000 or flags).toShort()) // data offset + flags
                bb.putShort(1460.toShort()) // window
                bb.putShort(0) // checksum
                bb.putShort(0) // urgent pointer

                // payload
                bb.put(payload)

                // 计算IP校验和
                bb.position(10)
                var sum = 0L
                for (i in 0 until 10) {
                    sum += (bb.short.toLong() and 0xFFFF)
                }
                while (sum shr 16 > 0) {
                    sum = (sum and 0xFFFF) + (sum shr 16)
                }
                val ipChecksum = (sum.inv() and 0xFFFF).toShort()
                bb.position(10)
                bb.putShort(ipChecksum)

                // 写回TUN设备
                fos.write(packet)
                fos.flush()

                if (payload.size > 0) {
                    serverSeq += payload.size
                }
            } catch (e: Exception) {
                Log.e(TAG, "发包错误", e)
            }
        }

        fun onFin() {
            close()
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
