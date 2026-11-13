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
import java.net.DatagramPacket
import java.net.DatagramSocket
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

    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val dnsSocket = DatagramSocket()

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

                protect(dnsSocket)
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

            bb.position(12)
            val srcIp = ByteArray(4)
            bb.get(srcIp)
            val dstIp = ByteArray(4)
            bb.get(dstIp)

            when (protocol) {
                6 -> processTcpPacket(data, length, srcIp, dstIp, ihl, fos)
                17 -> processUdpPacket(data, length, srcIp, dstIp, ihl, fos)
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理包错误", e)
        }
    }

    private fun processTcpPacket(data: ByteArray, length: Int, srcIp: ByteArray, dstIp: ByteArray, ihl: Int, fos: FileOutputStream) {
        val bb = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
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

        val sessionKey = "${srcIp.joinToString(".") { (it.toInt() and 0xFF).toString() }}:$srcPort"
        val payloadStart = ihl + dataOffset
        val payloadLen = length - payloadStart

        when {
            syn != 0 && ack == 0 -> {
                MainActivity.onLog?.invoke("🔌 新TCP连接: $sessionKey -> ${dstIp.joinToString(".") { (it.toInt() and 0xFF).toString() }}:$dstPort")
                val session = TcpSession(srcIp, srcPort, dstIp, dstPort, fos, seqNum)
                tcpSessions[sessionKey] = session
                session.start()
            }
            rst != 0 -> {
                tcpSessions.remove(sessionKey)?.close()
            }
            fin != 0 -> {
                tcpSessions.remove(sessionKey)?.onFin()
            }
            payloadLen > 0 -> {
                val session = tcpSessions[sessionKey]
                if (session != null) {
                    val payload = ByteArray(payloadLen)
                    bb.position(payloadStart)
                    bb.get(payload)
                    session.sendClientData(payload, seqNum)
                }
            }
        }
    }

    private fun processUdpPacket(data: ByteArray, length: Int, srcIp: ByteArray, dstIp: ByteArray, ihl: Int, fos: FileOutputStream) {
        val bb = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        bb.position(ihl)
        val srcPort = bb.short.toInt() and 0xFFFF
        val dstPort = bb.short.toInt() and 0xFFFF

        // 只处理DNS请求（53端口）
        if (dstPort != 53) return

        val payloadStart = ihl + 8
        val payloadLen = length - payloadStart
        val dnsQuery = ByteArray(payloadLen)
        bb.position(payloadStart)
        bb.get(dnsQuery)

        Thread {
            try {
                // 转发DNS请求到8.8.8.8
                dnsSocket.send(DatagramPacket(dnsQuery, dnsQuery.size, InetSocketAddress("8.8.8.8", 53)))
                
                // 接收DNS响应
                val responseBuf = ByteArray(1024)
                dnsSocket.soTimeout = 5000
                val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
                dnsSocket.receive(responsePacket)

                // 封装成IP+UDP包写回TUN
                sendUdpResponse(srcIp, srcPort, dstIp, responsePacket.data, responsePacket.length, fos)
            } catch (e: Exception) {
                Log.e(TAG, "DNS错误", e)
            }
        }.start()
    }

    private fun sendUdpResponse(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, data: ByteArray, length: Int, fos: FileOutputStream) {
        try {
            val udpLen = 8 + length
            val ipLen = 20 + udpLen
            val packet = ByteArray(ipLen)
            val bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

            // IP头
            bb.put(0x45)
            bb.put(0x00)
            bb.putShort(ipLen.toShort())
            bb.putShort(0)
            bb.putShort(0x4000.toShort())
            bb.put(64.toByte())
            bb.put(17) // UDP
            bb.putShort(0)
            bb.put(dstIp) // 源IP是原来的目标IP（DNS服务器）
            bb.put(srcIp) // 目标IP是原来的源IP（客户端）

            // UDP头
            bb.putShort(53.toShort()) // 源端口53
            bb.putShort(srcPort.toShort()) // 目标端口
            bb.putShort(udpLen.toShort())
            bb.putShort(0) // checksum

            // payload
            bb.put(data, 0, length)

            // 计算IP校验和
            val ipChecksum = calculateChecksum(packet, 0, 20)
            bb.position(10)
            bb.putShort(ipChecksum)

            fos.write(packet)
            fos.flush()
        } catch (e: Exception) {
            Log.e(TAG, "发送UDP响应错误", e)
        }
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (offset + length - 1 < offset + length) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
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
        private var serverSeq: Int = (Math.random() * 1000000).toInt()

        fun start() {
            running = true
            Thread {
                try {
                    socket = Socket()
                    protect(socket!!)
                    socket!!.connect(InetSocketAddress(dstIp.joinToString(".") { (it.toInt() and 0xFF).toString() }, dstPort), 10000)
                    MainActivity.onLog?.invoke("✅ 已连接服务器")

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
                                sendTcpPacket(clientSeq + 1, 0x18, data) // PSH+ACK
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "读服务器错误", e)
                        }
                    }.start()

                } catch (e: Exception) {
                    Log.e(TAG, "连接服务器失败", e)
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
                bb.putShort(0) // checksum 先填0
                bb.putShort(0) // urgent pointer

                // payload
                bb.put(payload)

                // 计算IP校验和
                val ipChecksum = calculateChecksum(packet, 0, 20)
                bb.position(10)
                bb.putShort(ipChecksum)

                // 计算TCP校验和（需要伪首部）
                val tcpChecksum = calculateTcpChecksum(packet, dstIp, srcIp, tcpLen)
                bb.position(36) // TCP checksum位置（IP头20 + TCP头16）
                bb.putShort(tcpChecksum)

                // 写回TUN设备
                fos.write(packet)
                fos.flush()

                serverSeq += payload.size
            } catch (e: Exception) {
                Log.e(TAG, "发包错误", e)
            }
        }

        private fun calculateTcpChecksum(packet: ByteArray, srcIp: ByteArray, dstIp: ByteArray, tcpLen: Int): Short {
            // 伪首部：源IP(4) + 目标IP(4) + 保留(1) + 协议(1) + TCP长度(2)
            val pseudoHeader = ByteArray(12)
            System.arraycopy(srcIp, 0, pseudoHeader, 0, 4)
            System.arraycopy(dstIp, 0, pseudoHeader, 4, 4)
            pseudoHeader[8] = 0
            pseudoHeader[9] = 6 // TCP
            pseudoHeader[10] = ((tcpLen shr 8) and 0xFF).toByte()
            pseudoHeader[11] = (tcpLen and 0xFF).toByte()

            var sum = 0L
            // 伪首部
            for (i in 0 until 12 step 2) {
                sum += ((pseudoHeader[i].toInt() and 0xFF) shl 8) or (pseudoHeader[i + 1].toInt() and 0xFF)
            }
            // TCP头 + 数据
            for (i in 20 until packet.size step 2) {
                if (i + 1 < packet.size) {
                    sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
                } else {
                    sum += (packet[i].toInt() and 0xFF) shl 8
                }
            }
            while (sum shr 16 > 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return (sum.inv() and 0xFFFF).toShort()
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
        tcpSessions.values.forEach { it.close() }
        tcpSessions.clear()
        try {
            dnsSocket.close()
        } catch (e: Exception) {}
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }
}
