package tun.proxy.service;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local proxy forwarder that adds HTTP proxy authentication.
 * Listens on localhost and forwards to upstream proxy with Proxy-Authorization header.
 */
public class AuthProxyForwarder {
    private static final String TAG = "AuthProxyForwarder";

    private final String upstreamHost;
    private final int upstreamPort;
    private final String username;
    private final String password;
    private final int localPort;

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;

    public AuthProxyForwarder(String upstreamHost, int upstreamPort,
                              String username, String password, int localPort) {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.username = username;
        this.password = password;
        this.localPort = localPort;
    }

    public void start() throws IOException {
        if (running.get()) {
            stop();
        }

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", localPort));

        executor = Executors.newCachedThreadPool();
        running.set(true);

        acceptThread = new Thread(() -> {
            Log.i(TAG, "Proxy forwarder listening on 127.0.0.1:" + localPort);
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleConnection(client));
                } catch (IOException e) {
                    if (running.get()) {
                        Log.e(TAG, "Accept error", e);
                    }
                }
            }
        });
        acceptThread.start();
        Log.i(TAG, "Auth proxy forwarder started");
    }

    public void stop() {
        running.set(false);

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
            serverSocket = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }

        Log.i(TAG, "Auth proxy forwarder stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    private void handleConnection(Socket client) {
        Socket upstream = null;
        try {
            // Read the CONNECT request from client
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            // Read first line (e.g., "CONNECT www.google.com:443 HTTP/1.1")
            StringBuilder requestLine = new StringBuilder();
            int b;
            while ((b = clientIn.read()) != -1) {
                requestLine.append((char) b);
                if (requestLine.toString().endsWith("\r\n")) {
                    break;
                }
            }

            String firstLine = requestLine.toString().trim();
            Log.d(TAG, "Request: " + firstLine);

            // Read remaining headers until empty line
            StringBuilder headers = new StringBuilder();
            StringBuilder line = new StringBuilder();
            while ((b = clientIn.read()) != -1) {
                line.append((char) b);
                if (line.toString().endsWith("\r\n")) {
                    if (line.toString().equals("\r\n")) {
                        break;
                    }
                    headers.append(line);
                    line = new StringBuilder();
                }
            }

            // Connect to upstream proxy
            upstream = new Socket();
            upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), 10000);

            InputStream upstreamIn = upstream.getInputStream();
            OutputStream upstreamOut = upstream.getOutputStream();

            // Send CONNECT request with authentication
            String auth = username + ":" + password;
            String authBase64 = Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            StringBuilder proxyRequest = new StringBuilder();
            proxyRequest.append(firstLine).append("\r\n");
            proxyRequest.append("Proxy-Authorization: Basic ").append(authBase64).append("\r\n");
            proxyRequest.append(headers);
            proxyRequest.append("\r\n");

            upstreamOut.write(proxyRequest.toString().getBytes(StandardCharsets.UTF_8));
            upstreamOut.flush();

            // Read response from upstream
            StringBuilder response = new StringBuilder();
            while ((b = upstreamIn.read()) != -1) {
                response.append((char) b);
                if (response.toString().contains("\r\n\r\n")) {
                    break;
                }
            }

            String responseStr = response.toString();
            Log.d(TAG, "Upstream response: " + responseStr.split("\r\n")[0]);

            // Forward response to client
            clientOut.write(responseStr.getBytes(StandardCharsets.UTF_8));
            clientOut.flush();

            // Check if connection established
            if (!responseStr.contains("200")) {
                Log.w(TAG, "Proxy connection failed: " + responseStr);
                return;
            }

            // Bidirectional forwarding
            final Socket finalUpstream = upstream;
            Thread clientToUpstream = new Thread(() -> {
                try {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = clientIn.read(buffer)) != -1) {
                        upstreamOut.write(buffer, 0, len);
                        upstreamOut.flush();
                    }
                } catch (IOException e) {
                    // Connection closed
                }
            });

            Thread upstreamToClient = new Thread(() -> {
                try {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = upstreamIn.read(buffer)) != -1) {
                        clientOut.write(buffer, 0, len);
                        clientOut.flush();
                    }
                } catch (IOException e) {
                    // Connection closed
                }
            });

            clientToUpstream.start();
            upstreamToClient.start();

            // Wait for both threads
            clientToUpstream.join();
            upstreamToClient.join();

        } catch (Exception e) {
            Log.e(TAG, "Connection error", e);
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                // ignore
            }
            if (upstream != null) {
                try {
                    upstream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }
}
