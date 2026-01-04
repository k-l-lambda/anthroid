package tun.proxy.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.anthroid.R;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP proxy VPN service using tun2http native library.
 * Supports authenticated proxies via local AuthProxyForwarder.
 * Must be in this exact package/class for JNI binding to work.
 */
public class Tun2HttpVpnService extends VpnService {
    private static final String TAG = "Tun2HttpVpnService";
    private static final String CHANNEL_ID = "anthroid_http_vpn_channel";
    private static final int NOTIFICATION_ID = 1002;
    private static final int LOCAL_PROXY_PORT = 8888;

    public static final String ACTION_START = "tun.proxy.service.START";
    public static final String ACTION_STOP = "tun.proxy.service.STOP";

    public static final String EXTRA_PROXY_HOST = "proxy_host";
    public static final String EXTRA_PROXY_PORT = "proxy_port";
    public static final String EXTRA_PROXY_USER = "proxy_user";
    public static final String EXTRA_PROXY_PASS = "proxy_pass";
    public static final String EXTRA_TARGET_APPS = "target_apps";

    private static volatile Tun2HttpVpnService instance = null;
    private static volatile List<String> currentTargetApps = new ArrayList<>();
    private static volatile String currentProxyHost = "";
    private static volatile int currentProxyPort = 0;

    private ParcelFileDescriptor vpnInterface = null;
    private boolean isRunning = false;
    private String proxyHost = "localhost";
    private int proxyPort = 1091;
    private String proxyUser = "";
    private String proxyPass = "";
    private List<String> targetApps = new ArrayList<>();
    private AuthProxyForwarder authForwarder = null;

    static {
        try {
            System.loadLibrary("tun2http");
            Log.i(TAG, "Loaded libtun2http.so");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libtun2http.so", e);
        }
    }

    // Native methods - must match JNI signatures
    private native void jni_init();
    private native void jni_start(int tun, boolean fwd53, int rcode, String proxyIp, int proxyPort);
    private native void jni_stop(int tun);
    private native int jni_get_mtu();
    private native void jni_done();

    // Callback from native code
    @SuppressWarnings("unused")
    private void nativeExit(String reason) {
        Log.w(TAG, "Native exit: " + reason);
    }

    @SuppressWarnings("unused")
    private void nativeError(int error, String message) {
        Log.w(TAG, "Native error " + error + ": " + message);
    }

    public static boolean isRunning() {
        return instance != null && instance.isRunning;
    }

    public static List<String> getTargetApps() {
        return new ArrayList<>(currentTargetApps);
    }

    public static String getProxyInfo() {
        if (isRunning()) {
            return "HTTP proxy at " + currentProxyHost + ":" + currentProxyPort +
                   " for " + String.join(", ", currentTargetApps);
        }
        return "HTTP VPN not running";
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        jni_init();
        Log.i(TAG, "Tun2HttpVpnService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            proxyHost = intent.getStringExtra(EXTRA_PROXY_HOST);
            if (proxyHost == null) proxyHost = "localhost";
            proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, 1091);
            proxyUser = intent.getStringExtra(EXTRA_PROXY_USER);
            if (proxyUser == null) proxyUser = "";
            proxyPass = intent.getStringExtra(EXTRA_PROXY_PASS);
            if (proxyPass == null) proxyPass = "";
            ArrayList<String> apps = intent.getStringArrayListExtra(EXTRA_TARGET_APPS);
            targetApps = apps != null ? apps : new ArrayList<>();

            currentProxyHost = proxyHost;
            currentProxyPort = proxyPort;
            currentTargetApps = new ArrayList<>(targetApps);

            Log.i(TAG, "Starting HTTP VPN: " + proxyHost + ":" + proxyPort +
                       (proxyUser.isEmpty() ? " (no auth)" : " (with auth)"));
            startVpn();
        } else if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "Stopping HTTP VPN");
            stopVpn();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopVpn();
        jni_done();
        instance = null;
        currentTargetApps = new ArrayList<>();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopVpn();
        super.onRevoke();
    }

    private void startVpn() {
        if (isRunning) {
            stopVpn();
        }
        if (targetApps.isEmpty()) {
            Log.e(TAG, "No target apps specified");
            stopSelf();
            return;
        }
        try {
            Notification notification = createNotification();
            startForeground(NOTIFICATION_ID, notification);

            int mtu = jni_get_mtu();
            if (mtu <= 0) mtu = 1500;
            Log.i(TAG, "MTU=" + mtu);

            vpnInterface = establishVpn(mtu);
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN");
                stopSelf();
                return;
            }

            // Determine actual proxy endpoint
            String actualProxyHost;
            int actualProxyPort;

            if (!proxyUser.isEmpty() && !proxyPass.isEmpty()) {
                // Use local forwarder for authenticated proxy
                authForwarder = new AuthProxyForwarder(proxyHost, proxyPort, proxyUser, proxyPass, LOCAL_PROXY_PORT);
                authForwarder.start();
                actualProxyHost = "127.0.0.1";
                actualProxyPort = LOCAL_PROXY_PORT;
                Log.i(TAG, "Using auth forwarder at 127.0.0.1:" + LOCAL_PROXY_PORT);
            } else {
                // Direct connection (no auth needed)
                actualProxyHost = proxyHost;
                actualProxyPort = proxyPort;
            }

            int fd = vpnInterface.getFd();
            Log.i(TAG, "Starting tun2http fd=" + fd + " proxy=" + actualProxyHost + ":" + actualProxyPort);
            jni_start(fd, false, 3, actualProxyHost, actualProxyPort);
            isRunning = true;
            Log.i(TAG, "HTTP VPN started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start VPN", e);
            stopVpn();
        }
    }

    private void stopVpn() {
        if (isRunning && vpnInterface != null) {
            try {
                jni_stop(vpnInterface.getFd());
            } catch (Exception e) {
                Log.e(TAG, "Error stopping tun2http", e);
            }
        }
        isRunning = false;

        // Stop auth forwarder
        if (authForwarder != null) {
            authForwarder.stop();
            authForwarder = null;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
            vpnInterface = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private ParcelFileDescriptor establishVpn(int mtu) {
        try {
            Builder builder = new Builder();
            builder.setSession("Anthroid HTTP VPN");
            builder.setBlocking(false);
            builder.setMtu(mtu);
            builder.addAddress("10.1.10.1", 32);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("8.8.8.8");

            int addedApps = 0;
            for (String pkg : targetApps) {
                try {
                    getPackageManager().getPackageInfo(pkg, 0);
                    builder.addAllowedApplication(pkg);
                    addedApps++;
                    Log.i(TAG, "Added app: " + pkg);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "App not found: " + pkg);
                }
            }

            if (addedApps == 0) {
                Log.e(TAG, "No valid apps");
                return null;
            }
            return builder.establish();
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish VPN", e);
            return null;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Anthroid HTTP VPN", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("HTTP VPN proxy");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent stopIntent = new Intent(this, Tun2HttpVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        StringBuilder appNames = new StringBuilder();
        for (String pkg : targetApps) {
            try {
                String name = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
                if (appNames.length() > 0) appNames.append(", ");
                appNames.append(name);
            } catch (Exception e) {
                if (appNames.length() > 0) appNames.append(", ");
                appNames.append(pkg);
            }
        }

        String authInfo = (!proxyUser.isEmpty()) ? " (auth)" : "";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HTTP VPN Proxy Active")
            .setContentText(appNames + " -> HTTP " + proxyHost + ":" + proxyPort + authInfo)
            .setSmallIcon(R.drawable.ic_foreground)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build();
    }
}
