package com.uid.capture

import android.util.Log
import com.alibaba.fastjson.JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.sankokomi.wirebare.kernel.interceptor.http.HttpSession
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpInterceptChain
import top.sankokomi.wirebare.kernel.interceptor.http.async.AsyncHttpInterceptor
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class UidCaptureInterceptor : AsyncHttpInterceptor {

    companion object {
        private const val TAG = "UidCapture"
        private const val TARGET_HOST = "logs.quickapi.net"
        private const val TARGET_PATH = "/v2/users/checkLogin"

        var onUidCaptured: ((uid: String, token: String, userName: String) -> Unit)? = null
        var onLog: ((String) -> Unit)? = null
    }

    private val responseBuffers = ConcurrentHashMap<HttpSession, ByteArray>()

    override suspend fun onResponse(
        chain: AsyncHttpInterceptChain,
        buffer: ByteBuffer,
        session: HttpSession
    ) {
        val host = session.request.host ?: ""
        val path = session.request.path ?: ""

        if (host.contains(TARGET_HOST) && path.contains(TARGET_PATH)) {
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            buffer.rewind()

            val existing = responseBuffers[session] ?: ByteArray(0)
            responseBuffers[session] = existing + data

            onLog?.invoke("🎯 收到目标响应数据，长度: ${data.size}")
            Log.d(TAG, "收到目标响应数据，长度: ${data.size}")
        }

        chain.processResponseNext(this, buffer, session)
    }

    override suspend fun onResponseFinished(
        chain: AsyncHttpInterceptChain,
        session: HttpSession
    ) {
        val host = session.request.host ?: ""
        val path = session.request.path ?: ""

        if (host.contains(TARGET_HOST) && path.contains(TARGET_PATH)) {
            val data = responseBuffers.remove(session)
            if (data != null && data.isNotEmpty()) {
                parseResponse(data)
            }
        }

        chain.processResponseFinishedNext(this, session)
    }

    private fun parseResponse(data: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = String(data, Charsets.UTF_8)
                onLog?.invoke("📦 响应内容: $body")
                Log.d(TAG, "响应内容: $body")

                val jsonObj = JSON.parseObject(body)
                val dataObj = jsonObj.getJSONObject("data")
                if (dataObj != null) {
                    val uid = dataObj.getString("uid")
                    val token = dataObj.getString("token") ?: ""
                    val userName = dataObj.getString("user_name") ?: ""

                    onLog?.invoke("✅ 成功捕获 UID: $uid")
                    Log.d(TAG, "成功捕获 UID: $uid")

                    onUidCaptured?.invoke(uid, token, userName)
                }
            } catch (e: Exception) {
                onLog?.invoke("❌ 解析失败: ${e.message}")
                Log.e(TAG, "解析失败", e)
            }
        }
    }
}
