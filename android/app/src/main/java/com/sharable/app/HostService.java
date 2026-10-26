package com.sharable.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.OpenableColumns;
import com.sharable.core.SharableServer;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HostService extends Service {
    public static final String ACTION_START = "start", ACTION_STOP = "stop";
    public static volatile SharableServer server; public static volatile int port; public static volatile File dir;
    public static final Map<String, SharableServer.Source> shared = new ConcurrentHashMap<>();
    private WifiManager.WifiLock wifiLock; private PowerManager.WakeLock wakeLock;

    public static List<String> ips() {
        List<String> out = new ArrayList<>();
        try { for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress()); } } catch (Exception ignored) {}
        return out;
    }

    public static void addShared(Context c, Uri uri) {
        final ContentResolver cr = c.getContentResolver(); String name = "shared-file"; long size = -1;
        try (Cursor q = cr.query(uri, null, null, null, null)) {
            if (q != null && q.moveToFirst()) { int i = q.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = q.getString(i); int j = q.getColumnIndex(OpenableColumns.SIZE); if (j >= 0 && !q.isNull(j)) size = q.getLong(j); }
        } catch (Exception ignored) {}
        if (size < 0) { try (ParcelFileDescriptor p = cr.openFileDescriptor(uri, "r")) { size = p.getStatSize(); } catch (Exception ignored) {} }
        if (size < 0) return;
        final long fsize = size; final Uri u = uri;
        String base = name.replace('/', '_'), n = base; int i = 1;
        while (shared.containsKey(n)) n = "(" + (i++) + ") " + base;
        shared.put(n, new SharableServer.Source() {
            public long size() { return fsize; }
            public InputStream open(long off) throws IOException {
                final ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "r");
                final FileInputStream fi = new FileInputStream(pfd.getFileDescriptor());
                if (off > 0) fi.getChannel().position(off);
                return new FilterInputStream(fi) { public void close() throws IOException { try { super.close(); } finally { pfd.close(); } } };
            }
        });
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(act)) { shutdown(); stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        if (server != null) return START_NOT_STICKY;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("host", "Sharable hosting", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "host") : new Notification.Builder(this);
        Notification n = nb.setContentTitle("Sharable is hosting").setContentText("Other devices on this network can send and get files").setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(1, n);
        File base = getExternalFilesDir(null); if (base == null) base = getFilesDir();
        dir = new File(base, "Sharable-files");
        final android.content.res.AssetManager am = getAssets();
        try {
            SharableServer s = new SharableServer(dir, SharableServer.newPin(), name -> {
                if (!name.matches("[A-Za-z0-9._-]+")) return null;
                try (InputStream is = am.open(name); ByteArrayOutputStream bo = new ByteArrayOutputStream()) { byte[] b = new byte[8192]; int k; while ((k = is.read(b)) > 0) bo.write(b, 0, k); return bo.toByteArray(); }
                catch (IOException e) { return null; }
            }, () -> shared);
            port = s.start(8080); server = s;
        } catch (IOException e) { stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) { wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sharable"); wifiLock.setReferenceCounted(false); wifiLock.acquire(); }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sharable:host"); wakeLock.setReferenceCounted(false); wakeLock.acquire();
        sendBroadcast(new Intent("com.sharable.STATE").setPackage(getPackageName()));
        return START_NOT_STICKY;
    }

    private void shutdown() {
        if (server != null) { server.stop(); server = null; }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        shared.clear();
        sendBroadcast(new Intent("com.sharable.STATE").setPackage(getPackageName()));
    }
    @Override public void onDestroy() { shutdown(); super.onDestroy(); }
}
