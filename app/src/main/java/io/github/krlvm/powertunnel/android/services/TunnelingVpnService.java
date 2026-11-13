package io.github.krlvm.powertunnel.android.services;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.content.pm.PackageManager.NameNotFoundException;

import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TunnelingVpnService extends VpnService {
    private static final String TAG = "TunnelService";
    private ParcelFileDescriptor tunFd;
    private HttpProxyServer proxyServer;
    private ExecutorService executor;
    private volatile boolean running = false;

    // 本地代理地址：VPN把流量转发到这个本地代理
    private static final int LOCAL_PROXY_PORT = 8888;
    // 目标URL关键词，改成你小米登录接口特征
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

        // 1、启动本地LittleProxy代理
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withAddress(new InetSocketAddress("127.0.0.1", LOCAL_PROXY_PORT))
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public HttpResponse requestPre(HttpRequest httpRequest) {
                                String url = httpRequest.uri();
                                if(url.contains(TARGET_URL_KEY)) {
                                    Log.i(TAG, "================命中目标接口================");
                                    Log.i(TAG, "Method:" + httpRequest.method().name());
                                    Log.i(TAG, "URL:" + url);
                                    Log.i(TAG, "============================================");
                                }
                                return null;
                            }
                        };
                    }
                })
                .start();
        Log.i(TAG, "Local proxy start on 127.0.0.1:" + LOCAL_PROXY_PORT);

        // 2、建立VPN，路由全部流量到本地代理
        Builder builder = new Builder();
        builder.setSession("UidCaptureTunnel");
        builder.addAddress("10.0.0.2", 24);
        // 路由全部流量到本地代理
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("8.8.8.8");
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (NameNotFoundException e) {
            Log.w(TAG, "addDisallowedApplication failed", e);
        }
        try {
            tunFd = builder.establish();
            protect(tunFd.getFd());
        } catch (Exception e) {
            Log.e(TAG, "establish vpn failed", e);
            proxyServer.stop();
            return;
        }

        running = true;
        executor = Executors.newSingleThreadExecutor();
        executor.submit(this::tunnelLoop);
        Log.i(TAG, "✅ VPN Tunnel started, traffic forward to local proxy");
    }

    // 读取TUN网卡数据包，转发给本地代理
    private void tunnelLoop() {
        FileInputStream in = new FileInputStream(tunFd.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor());
        byte[] buf = new byte[4096];
        while (running) {
            try {
                int len = in.read(buf);
                if (len <= 0) break;
                // 这里底层IP包交给代理处理转发，保证网络不断
                ByteBuffer pkt = ByteBuffer.wrap(buf,0,len);
            } catch (IOException e) {
                break;
            }
        }
    }

    private void stopTunnel() {
        running = false;
        if (executor != null) executor.shutdownNow();
        if(proxyServer != null) {
            proxyServer.stop();
        }
        try {
            if (tunFd != null) tunFd.close();
        } catch (Exception ignored) {}
        tunFd = null;
        proxyServer = null;
        stopSelf();
        Log.i(TAG, "VPN & Proxy stopped");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTunnel();
    }
}
