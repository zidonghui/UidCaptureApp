package io.github.krlvm.powertunnel.android.services;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.content.pm.PackageManager.NameNotFoundException;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TunnelingVpnService extends VpnService {
    private static final String TAG = "TunnelService";
    private ParcelFileDescriptor tunFd;
    private ExecutorService executor;
    private volatile boolean running = false;

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
        builder.setSession("CaptureTunnel");
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
        } catch (Exception e) {
            Log.e(TAG, "establish failed", e);
            return;
        }
        running = true;
        executor = Executors.newSingleThreadExecutor();
        executor.submit(this::tunnelLoop);
        Log.i(TAG, "VPN tunnel started");
    }

    private void tunnelLoop() {
        FileInputStream in = new FileInputStream(tunFd.getFileDescriptor());
        byte[] buf = new byte[4096];
        while (running) {
            try {
                int len = in.read(buf);
                if (len <= 0) break;
                ByteBuffer pkt = ByteBuffer.wrap(buf,0,len);
                // 这里拿到原始IP包，后续转发 + 抓包日志
                Log.d(TAG, "recv ip packet len=" + len);
            } catch (IOException e) {
                break;
            }
        }
    }

    private void stopTunnel() {
        running = false;
        if (executor != null) executor.shutdownNow();
        try {
            if (tunFd != null) tunFd.close();
        } catch (IOException ignored) {}
        tunFd = null;
        stopSelf();
        Log.i(TAG, "VPN tunnel stopped");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTunnel();
    }
}
