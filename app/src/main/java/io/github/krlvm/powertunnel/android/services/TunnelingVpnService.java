package io.github.krlvm.powertunnel.android.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

public class TunnelingVpnService extends VpnService {

    private static final String TAG = "UidVpnService";
    private static final String CHANNEL_ID = "uid_capture_vpn";
    private static final String CHANNEL_CAPTURED = "uid_captured";
    private static final int NOTIFICATION_ID = 1;
    private static final int CAPTURED_NOTIFY_ID = 2;
    private static final int PROXY_PORT = 8888;

    private ParcelFileDescriptor vpnInterface = null;
    private HttpProxyServer proxyServer = null;

    public interface CaptureCallback {
        void onCaptured(CapturedData data);
        void onStatusChanged(String status);
    }

    public static class CapturedData {
        public String uid;
        public String token;
        public String userName;
        public String userToken;
        public String age;
        public String realName;
    }

    private static volatile CaptureCallback callback = null;

    public static void setCallback(CaptureCallback cb) {
        callback = cb;
    }

    private void notifyStatus(String status) {
        CaptureCallback cb = callback;
        if (cb != null) {
            cb.onStatusChanged(status);
        }
    }

    private void notifyCaptured(CapturedData data) {
        CaptureCallback cb = callback;
        if (cb != null) {
            cb.onCaptured(data);
        }
        // 同时弹Toast
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Toast.makeText(this, "✅ 捕获UID: " + data.uid, Toast.LENGTH_LONG).show();
        });
        // 通知栏也显示
        showCapturedNotification(data.uid);
    }

    static {
        System.loadLibrary("tun2http");
    }

    private native void jni_init();
    private native void jni_start(int tun, boolean fwd53, int rcode, String proxyIp, int proxyPort, boolean resolveHosts);
    private native void jni_stop(int tun);
    private native void jni_done();
    private native int jni_get_mtu();
    private static native String jni_getprop(String name);

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        jni_init();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        startForeground(NOTIFICATION_ID, buildNotification("VPN抓包运行中"));

        // 1. Start LittleProxy on 127.0.0.1:8888
        notifyStatus("正在启动本地代理...");
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(PROXY_PORT)
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            private final ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
                            private boolean isTargetRequest = false;
                            private boolean isGzip = false;

                            @Override
                            public HttpResponse proxyToServerRequest(HttpObject httpObject) {
                                // 判断是否目标请求
                                if (httpObject instanceof HttpRequest) {
                                    HttpRequest req = (HttpRequest) httpObject;
                                    String host = req.headers().get("Host");
                                    String uri = req.uri();
                                    if (host != null && host.contains("logs.quickapi.net")
                                            && uri != null && uri.contains("checkLogin")) {
                                        isTargetRequest = true;
                                        Log.d(TAG, "🎯 命中目标请求: " + host + uri);
                                    }
                                }
                                return super.proxyToServerRequest(httpObject);
                            }

                            @Override
                            public HttpObject serverToProxyResponse(HttpObject httpObject) {
                                if (!isTargetRequest) {
                                    return super.serverToProxyResponse(httpObject);
                                }

                                if (httpObject instanceof HttpResponse) {
                                    bodyStream.reset();
                                    HttpResponse resp = (HttpResponse) httpObject;
                                    // 检查是否gzip压缩
                                    String encoding = resp.headers().get("Content-Encoding");
                                    isGzip = encoding != null && encoding.contains("gzip");
                                    Log.d(TAG, "响应 Content-Encoding: " + encoding + ", isGzip=" + isGzip);
                                }

                                if (httpObject instanceof LastHttpContent) {
                                    ByteBuf buf = ((LastHttpContent) httpObject).content();
                                    byte[] bytes = new byte[buf.readableBytes()];
                                    buf.readBytes(bytes);
                                    try {
                                        bodyStream.write(bytes);
                                    } catch (Exception e) {
                                        Log.e(TAG, "write body error", e);
                                    }

                                    // 全部接收完，解析
                                    byte[] rawBody = bodyStream.toByteArray();
                                    parseResponse(rawBody, isGzip);
                                }
                                return super.serverToProxyResponse(httpObject);
                            }
                        };
                    }
                })
                .start();

        // 2. Establish VPN
        notifyStatus("正在建立VPN连接...");
        Builder builder = new Builder();
        builder.setSession("UidCapture VPN");
        builder.setConfigureIntent(PendingIntent.getActivity(
                this, 0, new Intent(this, com.uid.capture.MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE
        ));

        builder.addAddress("10.1.10.1", 32);
        builder.addRoute("0.0.0.0", 0);
        builder.setMtu(jni_get_mtu());

        // DNS servers
        List<String> dnsServers = getDefaultDns();
        if (dnsServers.isEmpty()) {
            builder.addDnsServer("8.8.8.8");
        } else {
            for (String dns : dnsServers) {
                builder.addDnsServer(dns);
            }
        }

        // Exclude our own app from VPN to avoid loops
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "Cannot exclude own package", e);
        }

        vpnInterface = builder.establish();
        if (vpnInterface == null) {
            notifyStatus("❌ VPN建立失败，请检查权限");
            stopVpn();
            return;
        }

        // 3. Start native tun2http, forwarding to local proxy
        notifyStatus("正在转发流量到代理...");
        jni_start(vpnInterface.getFd(), false, 3, "127.0.0.1", PROXY_PORT, true);

        notifyStatus("✅ VPN抓包已启动\n\n请打开游戏并登录\nUID将自动显示在这里");
    }

    private void parseResponse(byte[] rawBody, boolean isGzip) {
        try {
            String body;
            if (isGzip) {
                // gzip解压
                ByteArrayInputStream bis = new ByteArrayInputStream(rawBody);
                GZIPInputStream gis = new GZIPInputStream(bis);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int len;
                while ((len = gis.read(buffer)) != -1) {
                    bos.write(buffer, 0, len);
                }
                body = bos.toString("UTF-8");
                gis.close();
            } else {
                body = new String(rawBody, "UTF-8");
            }

            Log.d(TAG, "📦 响应明文: " + body);

            JSONObject jsonObj = JSON.parseObject(body);
            JSONObject data = jsonObj.getJSONObject("data");
            if (data != null) {
                CapturedData cd = new CapturedData();
                cd.uid = data.getString("uid");
                cd.token = data.getString("token");
                cd.userName = data.getString("user_name");
                cd.userToken = data.getString("user_token");
                cd.age = data.getString("age");
                cd.realName = data.getString("realName");

                Log.d(TAG, "✅ 成功解析 UID=" + cd.uid);
                notifyCaptured(cd);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析响应失败", e);
            notifyStatus("❌ 解析失败: " + e.getMessage());
        }
    }

    private void stopVpn() {
        try {
            if (vpnInterface != null) {
                try {
                    jni_stop(vpnInterface.getFd());
                } catch (Throwable t) {
                    try { jni_stop(-1); } catch (Throwable ignored) {}
                }
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing VPN", e);
        }

        if (proxyServer != null) {
            try { proxyServer.stop(); } catch (Exception ignored) {}
            proxyServer = null;
        }

        notifyStatus("VPN已停止");
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        stopVpn();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
        jni_done();
    }

    private List<String> getDefaultDns() {
        List<String> servers = new ArrayList<>();
        try {
            for (int i = 1; i <= 4; i++) {
                String dns = jni_getprop("net.dns" + i);
                if (dns != null && !dns.isEmpty()) {
                    servers.add(dns);
                }
            }
        } catch (Exception ignored) {}
        return servers;
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "UidCapture VPN", NotificationManager.IMPORTANCE_LOW);
            if (nm != null) nm.createNotificationChannel(channel);

            NotificationChannel capturedChannel = new NotificationChannel(
                    CHANNEL_CAPTURED, "捕获成功通知", NotificationManager.IMPORTANCE_HIGH);
            if (nm != null) nm.createNotificationChannel(capturedChannel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("UidCapture")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(true)
                .build();
    }

    private void showCapturedNotification(String uid) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent intent = new Intent(this, com.uid.capture.MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_CAPTURED)
                .setContentTitle("✅ 捕获成功")
                .setContentText("UID: " + uid)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        nm.notify(CAPTURED_NOTIFY_ID, notif);
    }
}
