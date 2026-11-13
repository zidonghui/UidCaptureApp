package io.github.krlvm.powertunnel.android.services;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.content.pm.PackageManager.NameNotFoundException;

import java.nio.charset.StandardCharsets;

public class TunnelingVpnService extends VpnService implements Tun2HttpNative.HttpListener {
    private static final String TAG = "TunnelService";
    private ParcelFileDescriptor tunFd;
    private Tun2HttpNative tun2Http;
    private volatile boolean running = false;

    // ========== 在这里填你要抓的目标URL关键词 ==========
    private static final String TARGET_URL_KEY = "checkLogin";

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        if (ACTION_START.equals(intent.getAction())) {
            startTunnel();
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
        }
        return START_NOT_STICKY;
    }

    private void startTunnel() {
        if (running) return;
        Builder builder = new Builder();
        builder.setSession("UidCaptureTunnel");
        builder.addAddress("10.0.0.2", 24);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("8.8.8.8");
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (NameNotFoundException e) {
            Log.w(TAG, "addDisallowedApplication failed", e);
        }
        try {
            tunFd = builder.establish();
            // 修复这里：传入int fd
            protect(tunFd.getFd());
        } catch (Exception e) {
            Log.e(TAG, "establish vpn failed", e);
            return;
        }

        tun2Http = new Tun2HttpNative();
        Tun2HttpNative.setListener(this);
        tun2Http.start(tunFd.getFd());

        running = true;
        Log.i(TAG, "✅ VPN + tun2http started, waiting for http packets");
    }

    // 【回调：所有HTTP请求都会到这里】
    @Override
    public void onHttpRequest(String method, String url, String host, String path, byte[] body) {
        // 过滤：只处理包含目标关键词的接口
        if(url.contains(TARGET_URL_KEY)) {
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            Log.i(TAG, "==================== 命中目标接口 ====================");
            Log.i(TAG, "method: " + method);
            Log.i(TAG, "url: " + url);
            Log.i(TAG, "host: " + host);
            Log.i(TAG, "path: " + path);
            Log.i(TAG, "body: " + bodyStr);
            // 你后续在这里解析json，提取uid
            Log.i(TAG, "======================================================");
        }
    }

    private void stopTunnel() {
        running = false;
        if(tun2Http != null) {
            tun2Http.stop();
        }
        try {
            if (tunFd != null) tunFd.close();
        } catch (Exception ignored) {}
        tunFd = null;
        tun2Http = null;
        Tun2HttpNative.setListener(null);
        stopSelf();
        Log.i(TAG, "VPN tunnel stopped");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTunnel();
    }
}
