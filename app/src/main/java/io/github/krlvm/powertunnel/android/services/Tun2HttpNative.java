package io.github.krlvm.powertunnel.android.services;

public class Tun2HttpNative {
    static {
        System.loadLibrary("tun2http");
    }

    public interface HttpListener {
        void onHttpRequest(String method, String url, String host, String path, byte[] body);
    }

    private static HttpListener listener;

    public static void setListener(HttpListener l) {
        listener = l;
    }

    // 给native回调Java
    public static void dispatchRequest(String method, String url, String host, String path, byte[] body) {
        if(listener != null) {
            listener.onHttpRequest(method, url, host, path, body);
        }
    }

    // 启动tun2http，传入tun fd
    public native int start(int tunFd);
    public native void stop();
}
