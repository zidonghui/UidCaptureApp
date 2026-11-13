package io.github.krlvm.powertunnel.android.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.krlvm.powertunnel.PowerTunnel;
import io.github.krlvm.powertunnel.mitm.MITMAuthority;
import io.github.krlvm.powertunnel.sdk.proxy.ProxyAddress;
import io.github.krlvm.powertunnel.sdk.types.PowerTunnelPlatform;

public class TunnelingVpnService extends VpnService {

    private static final String TAG = "UidVpnService";
    private static final String CHANNEL_ID = "uid_capture_vpn";
    private static final String CHANNEL_CAPTURED = "uid_captured";
    private static final int NOTIFICATION_ID = 1;
    private static final int CAPTURED_NOTIFY_ID = 2;
    private static final int PROXY_PORT = 8888;

    private ParcelFileDescriptor vpnInterface = null;
    private PowerTunnel server = null;
    private int requestCount = 0;

    public interface CaptureCallback {
        void onCaptured(CapturedData data);
        void onStatusChanged(String status);
        void onDebugLog(String log);
        void onTargetHit(String info);
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
        if (cb != null) cb.onStatusChanged(status);
    }

    private void notifyDebug(String log) {
        CaptureCallback cb = callback;
        if (cb != null) cb.onDebugLog(log);
    }

    private void notifyTargetHit(String info) {
        CaptureCallback cb = callback;
        if (cb != null) cb.onTargetHit(info);
    }

    private void notifyCaptured(CapturedData data) {
        CaptureCallback cb = callback;
        if (cb != null) cb.onCaptured(data);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try { Toast.makeText(this, "✅ 捕获UID: " + data.uid, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        });
        showCapturedNotification(data.uid);
    }

    static {
        try {
            System.loadLibrary("tun2http");
            Log.d(TAG, "native库加载成功");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "native库加载失败!", e);
        }
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
        try { jni_init(); } catch (Throwable t) { Log.e(TAG, "jni_init失败", t); }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        try { startVpn(); } catch (Exception e) {
            Log.e(TAG, "startVpn异常", e);
            notifyStatus("❌ 启动失败: " + e.getMessage());
        }
        return START_STICKY;
    }

    private void startVpn() {
        startForeground(NOTIFICATION_ID, buildNotification("VPN抓包运行中"));

        notifyStatus("正在启动官方代理...");
        try {
            // 创建 MITM 证书（官方方式）
            MITMAuthority mitmAuthority = MITMAuthority.create(
                    new File(getFilesDir(), "cert.p12"),
                    "uidcapture".toCharArray()
            );

            // DNS 服务器
            List<String> dnsServers = getDefaultDns();
            if (dnsServers.isEmpty()) {
                dnsServers = new ArrayList<>();
                dnsServers.add("8.8.8.8");
            }

            // 创建官方 PowerTunnel 代理（完全复用官方配置）
            server = new PowerTunnel(
                    new ProxyAddress("127.0.0.1", PROXY_PORT),
                    PowerTunnelPlatform.ANDROID,
                    getFilesDir(),
                    true,  // transparent_mode = true（官方默认）
                    true,  // allowNonStrictDns
                    dnsServers,
                    new ArrayList<String>(),  // dnsSearchPath
                    mitmAuthority,
                    null,  // externalConfigsDir
                    null
            );

            // 启动代理（后台线程）
            new Thread(() -> {
                try {
                    server.start();
                    Log.d(TAG, "✅ 官方代理启动成功，端口: " + PROXY_PORT);
                    notifyStatus("✅ 代理已启动，端口: " + PROXY_PORT);
                } catch (Exception e) {
                    Log.e(TAG, "代理启动失败", e);
                    notifyStatus("❌ 代理启动失败: " + e.getMessage());
                }
            }, "Proxy Bootstrap").start();

        } catch (Exception e) {
            Log.e(TAG, "代理初始化失败", e);
            notifyStatus("❌ 代理初始化失败: " + e.getMessage());
            return;
        }

        // 建立 VPN
        Builder builder = new Builder();
        builder.setSession("UidCapture VPN");
        builder.setConfigureIntent(PendingIntent.getActivity(
                this, 0, new Intent(this, com.uid.capture.MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE
        ));

        builder.addAddress("10.1.10.1", 32);
        builder.addRoute("0.0.0.0", 0);
        try { builder.setMtu(jni_get_mtu()); } catch (Throwable t) { builder.setMtu(1500); }

        List<String> dnsServers = getDefaultDns();
        if (dnsServers.isEmpty()) {
            builder.addDnsServer("8.8.8.8");
        } else {
            for (String dns : dnsServers) builder.addDnsServer(dns);
        }

        try { builder.addDisallowedApplication(getPackageName()); } catch (Exception e) {}

        try {
            vpnInterface = builder.establish();
        } catch (Exception e) {
            Log.e(TAG, "VPN建立失败", e);
            notifyStatus("❌ VPN建立失败: " + e.getMessage());
            stopVpn();
            return;
        }

        if (vpnInterface == null) {
            notifyStatus("❌ VPN建立失败");
            stopVpn();
            return;
        }

        // 等待代理启动后再启动 tun2http
        new Thread(() -> {
            try {
                // 等待代理启动
                Thread.sleep(2000);

                notifyStatus("正在转发流量到代理...");
                jni_start(vpnInterface.getFd(), false, 3, "127.0.0.1", PROXY_PORT, true);
                notifyStatus("✅ VPN抓包已启动\n\n请打开游戏并登录\n\n--- 调试: 所有请求 ---");
            } catch (Throwable t) {
                Log.e(TAG, "jni_start失败", t);
                notifyStatus("❌ 流量转发失败: " + t.getMessage());
            }
        }, "Tun2Http Starter").start();
    }

    private void stopVpn() {
        try {
            if (vpnInterface != null) {
                try { jni_stop(vpnInterface.getFd()); } catch (Throwable ignored) {}
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {}
        if (server != null) {
            try { server.stop(true); } catch (Exception ignored) {}
            server = null;
        }
        notifyStatus("VPN已停止");
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onRevoke() { super.onRevoke(); stopVpn(); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
        try { jni_done(); } catch (Throwable ignored) {}
    }

    private List<String> getDefaultDns() {
        List<String> servers = new ArrayList<>();
        try {
            for (int i = 1; i <= 4; i++) {
                String dns = jni_getprop("net.dns" + i);
                if (dns != null && !dns.isEmpty()) servers.add(dns);
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
