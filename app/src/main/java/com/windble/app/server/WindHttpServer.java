package com.windble.app.server;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.windble.app.model.WindData;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP server (no external dependencies) that:
 *   GET /          → serves wind_display.html from assets
 *   GET /data      → Server-Sent Events stream of JSON wind packets
 *   GET /snapshot  → single JSON object with latest data (for polling fallback)
 *
 * Clients (Kindle, PC browser, etc.) connect via WiFi to http://<phone-ip>:8765/
 */
public class WindHttpServer {

    private static final String TAG  = "WindHttpServer";
    public  static final int    PORT = 8888;

    public interface ServerListener {
        void onStarted(String url);
        void onStopped();
        void onError(String msg);
        void onClientConnected(int count);
    }

    private final Context      mContext;
    private ServerListener     mListener;
    private ServerSocket       mServerSocket;
    private ExecutorService    mPool;
    private volatile boolean   mRunning = false;

    // All active SSE client streams
    private final List<SseClient> mSseClients = new CopyOnWriteArrayList<>();

    // Latest wind snapshot (sent to new clients immediately)
    private volatile String mLastJson = "{}";

    public WindHttpServer(Context context) {
        mContext = context;
    }

    public void setListener(ServerListener l) { mListener = l; }

    // ---- Lifecycle ----

    public void start() {
        if (mRunning) return;
        mRunning = true;
        mPool = Executors.newCachedThreadPool();
        mPool.execute(() -> {
            try {
                mServerSocket = new ServerSocket(PORT);
                String url = "http://" + getWifiIpAddress() + ":" + PORT + "/";
                Log.i(TAG, "Server started: " + url);
                if (mListener != null) mListener.onStarted(url);

                while (mRunning) {
                    try {
                        Socket client = mServerSocket.accept();
                        mPool.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (mRunning) Log.w(TAG, "Accept error", e);
                    }
                }
            } catch (IOException e) {
                mRunning = false;
                Log.e(TAG, "Server error", e);
                if (mListener != null) mListener.onError(e.getMessage());
            }
        });
    }

    public void stop() {
        mRunning = false;
        for (SseClient c : mSseClients) c.close();
        mSseClients.clear();
        closeQuietly(mServerSocket);
        if (mPool != null) mPool.shutdownNow();
        if (mListener != null) mListener.onStopped();
        Log.i(TAG, "Server stopped");
    }

    public boolean isRunning() { return mRunning; }

    /** Push new wind data to all connected SSE clients. */
    public void pushWindData(WindData wd, String speedUnit) {
        if (wd == null) return;
        mLastJson = toJson(wd, speedUnit);
        for (SseClient c : mSseClients) {
            c.send(mLastJson);
        }
    }

    /** Push a wind-shift event banner. */
    public void pushShiftEvent(float shiftDeg, String type, float twd) {
        String json = String.format(Locale.US,
                "{\"event\":\"shift\",\"shift\":%.1f,\"type\":\"%s\",\"twd\":%.1f}",
                shiftDeg, type, twd);
        for (SseClient c : mSseClients) c.sendEvent("shift", json);
    }

    // ---- Request handling ----

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(10_000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            String requestLine = reader.readLine();
            if (requestLine == null) { closeQuietly(socket); return; }

            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) { /* skip */ }

            String[] parts = requestLine.split(" ");
            String path = (parts.length >= 2) ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            Log.d(TAG, "Request: " + path);

            switch (path) {
                case "/data":
                    handleSse(socket);
                    break;
                case "/snapshot":
                    handleSnapshot(socket);
                    break;
                default:
                    handleHtml(socket);
                    break;
            }
        } catch (IOException e) {
            Log.w(TAG, "Client error", e);
            closeQuietly(socket);
        }
    }

    private void handleHtml(Socket socket) throws IOException {
        byte[] html = loadAsset("wind_display.html");
        OutputStream out = socket.getOutputStream();
        PrintWriter head = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
        head.printf("HTTP/1.1 200 OK\r\n");
        head.printf("Content-Type: text/html; charset=utf-8\r\n");
        head.printf("Content-Length: %d\r\n", html.length);
        head.printf("Connection: close\r\n");
        head.printf("\r\n");
        head.flush();
        out.write(html);
        out.flush();
        closeQuietly(socket);
    }

    private void handleSnapshot(Socket socket) throws IOException {
        byte[] body = mLastJson.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        PrintWriter head = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
        head.printf("HTTP/1.1 200 OK\r\n");
        head.printf("Content-Type: application/json\r\n");
        head.printf("Content-Length: %d\r\n", body.length);
        head.printf("Access-Control-Allow-Origin: *\r\n");
        head.printf("Connection: close\r\n");
        head.printf("\r\n");
        head.flush();
        out.write(body);
        out.flush();
        closeQuietly(socket);
    }

    private void handleSse(Socket socket) {
        try {
            socket.setSoTimeout(0);
            OutputStream out = socket.getOutputStream();
            PrintWriter head = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
            head.printf("HTTP/1.1 200 OK\r\n");
            head.printf("Content-Type: text/event-stream\r\n");
            head.printf("Cache-Control: no-cache\r\n");
            head.printf("Access-Control-Allow-Origin: *\r\n");
            head.printf("Connection: keep-alive\r\n");
            head.printf("\r\n");
            head.flush();

            SseClient client = new SseClient(socket, out);
            mSseClients.add(client);
            if (mListener != null) mListener.onClientConnected(mSseClients.size());

            client.send(mLastJson);
            client.waitForDisconnect();

        } catch (IOException e) {
            Log.d(TAG, "SSE client gone");
        } finally {
            mSseClients.removeIf(c -> c.socket == socket);
            closeQuietly(socket);
            if (mListener != null) mListener.onClientConnected(mSseClients.size());
        }
    }

    private static class SseClient {
        final Socket socket;
        private final OutputStream out;
        private volatile boolean dead = false;

        SseClient(Socket s, OutputStream o) { socket = s; out = o; }

        void send(String json) {
            sendEvent("wind", json);
        }

        void sendEvent(String event, String json) {
            if (dead) return;
            try {
                String msg = "event: " + event + "\ndata: " + json + "\n\n";
                out.write(msg.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                dead = true;
            }
        }

        void waitForDisconnect() {
            while (!dead) {
                try {
                    Thread.sleep(15_000);
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception e) {
                    dead = true;
                }
            }
        }

        void close() {
            dead = true;
            closeQuietly(socket);
        }
    }

    private String toJson(WindData wd, String unit) {
        return String.format(Locale.US,
                "{\"aws\":%.2f,\"awa\":%.1f,\"aws1m\":%.2f,\"awsMax1m\":%.2f,\"aws1h\":%.2f,\"awsMax1h\":%.2f," +
                        "\"tws\":%.2f,\"twa\":%.1f,\"twd\":%.1f," +
                        "\"sog\":%.2f,\"cog\":%.1f,\"hdg\":%.1f," +
                        "\"unit\":\"%s\",\"ts\":%d}",
                wd.aws, wd.awa, wd.awsAvg1m, wd.awsMax1m, wd.awsAvg1h, wd.awsMax1h,
                wd.tws, wd.twa, wd.twd,
                wd.sog, wd.cog, wd.heading,
                unit, System.currentTimeMillis());
    }

    private byte[] loadAsset(String name) {
        try {
            AssetManager am = mContext.getAssets();
            InputStream is = am.open(name);
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return buf;
        } catch (IOException e) {
            Log.e(TAG, "Asset not found: " + name, e);
            return "<h1>wind_display.html not found in assets</h1>".getBytes(StandardCharsets.UTF_8);
        }
    }

    public String getWifiIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr.isLoopbackAddress() || addr.getHostAddress().contains(":")) continue;
                    String ip = addr.getHostAddress();
                    if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172."))
                        return ip;
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "IP lookup failed", e);
        }
        return "0.0.0.0";
    }

    private static void closeQuietly(Closeable c) {
        try { if (c != null) c.close(); } catch (IOException ignored) {}
    }
}
