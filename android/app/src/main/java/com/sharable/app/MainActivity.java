package com.sharable.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.*;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_SHARE = 1, REQ_CHOOSE = 2;
    private TextView status, addr; private Button toggle; private LinearLayout home; private FrameLayout webBox; private WebView web;
    private ValueCallback<Uri[]> chooser;
    private final BroadcastReceiver stateRx = new BroadcastReceiver() { public void onReceive(Context c, Intent i) { refresh(); } };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private Button btn(String t, int color) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setBackgroundColor(color); b.setPadding(dp(16), dp(12), dp(16), dp(12)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10); b.setLayoutParams(lp); return b; }
    private TextView tv(String t, int sp, boolean bold) { TextView v = new TextView(this); v.setText(t); v.setTextSize(sp); v.setTextColor(Color.parseColor("#1e1b4b")); if (bold) v.setTypeface(Typeface.DEFAULT_BOLD); v.setPadding(0, dp(6), 0, dp(2)); return v; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.parseColor("#f5f3ff"));
        ScrollView sv = new ScrollView(this); home = new LinearLayout(this); home.setOrientation(LinearLayout.VERTICAL); home.setPadding(dp(20), dp(36), dp(20), dp(24)); sv.addView(home);
        TextView title = tv("Sharable", 30, true); title.setTextColor(Color.parseColor("#7c3aed")); home.addView(title);
        home.addView(tv("Send big files between phones and laptops over the same Wi-Fi or hotspot. No cloud.", 15, false));
        status = tv("", 16, true); home.addView(status);
        addr = tv("", 14, false); addr.setTextIsSelectable(true); home.addView(addr);
        toggle = btn("Start hosting on this phone", Color.parseColor("#7c3aed")); home.addView(toggle);
        Button copy = btn("Copy address with PIN", Color.parseColor("#06b6d4")); home.addView(copy);
        Button openOwn = btn("Open this phone's page here", Color.parseColor("#0891b2")); home.addView(openOwn);
        Button share = btn("Share a file from this phone", Color.parseColor("#f59e0b")); home.addView(share);
        home.addView(tv("Connect to another Sharable (a laptop or another phone)", 15, true));
        final EditText url = new EditText(this); url.setHint("http://192.168.x.x:8080/#pin=123456"); url.setSingleLine(true); home.addView(url);
        Button go = btn("Open", Color.parseColor("#7c3aed")); home.addView(go);
        home.addView(tv("Files sent to this phone are kept in the app folder shown after you start. Use \"Open this phone's page here\" or another device's page to download files.", 13, false));
        TextView limits = tv("Test build. Tested so far: server logic on a computer, not on a real phone over Wi-Fi.", 13, false); limits.setTextColor(Color.parseColor("#b45309")); home.addView(limits);
        root.addView(sv);
        webBox = new FrameLayout(this); webBox.setVisibility(View.GONE); webBox.setBackgroundColor(Color.WHITE);
        web = new WebView(this); WebSettings ws = web.getSettings(); ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true); ws.setAllowFileAccess(false); ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null); chooser = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(Intent.createChooser(i, "Choose files"), REQ_CHOOSE); } catch (Exception e) { chooser = null; return false; }
                return true; } });
        web.setDownloadListener((u, ua, cd, mime, len) -> {
            String name = URLUtil.guessFileName(u, cd, "application/octet-stream");
            try { DownloadManager.Request r = new DownloadManager.Request(Uri.parse(u)); r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r); Toast.makeText(this, "Downloading " + name + " to Downloads", Toast.LENGTH_LONG).show();
            } catch (Exception e) { Toast.makeText(this, "Download failed to start: " + e.getMessage(), Toast.LENGTH_LONG).show(); } });
        webBox.addView(web, new FrameLayout.LayoutParams(-1, -1));
        Button close = btn("Close page", Color.parseColor("#7c3aed")); FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); cl.setMargins(dp(12), dp(12), dp(12), dp(12)); close.setLayoutParams(cl); close.setOnClickListener(v -> closeWeb()); webBox.addView(close);
        root.addView(webBox, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        toggle.setOnClickListener(v -> {
            if (HostService.server == null) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9);
                Intent i = new Intent(this, HostService.class).setAction(HostService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            } else startService(new Intent(this, HostService.class).setAction(HostService.ACTION_STOP));
        });
        copy.setOnClickListener(v -> { String a = fullAddr(); if (a == null) { Toast.makeText(this, "Start hosting first", Toast.LENGTH_SHORT).show(); return; } ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Sharable", a)); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show(); });
        openOwn.setOnClickListener(v -> { if (HostService.server == null) { Toast.makeText(this, "Start hosting first", Toast.LENGTH_SHORT).show(); return; } openWeb("http://127.0.0.1:" + HostService.port + "/#pin=" + HostService.server.pin()); });
        share.setOnClickListener(v -> { if (HostService.server == null) { Toast.makeText(this, "Start hosting first", Toast.LENGTH_SHORT).show(); return; }
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); startActivityForResult(i, REQ_SHARE); });
        go.setOnClickListener(v -> { String u = url.getText().toString().trim(); if (u.isEmpty()) return; if (!u.startsWith("http")) u = "http://" + u; openWeb(u); });
        refresh();
    }
    private String fullAddr() { if (HostService.server == null) return null; List<String> ips = HostService.ips(); String ip = ips.isEmpty() ? "(no Wi-Fi address)" : ips.get(0); return "http://" + ip + ":" + HostService.port + "/#pin=" + HostService.server.pin(); }
    private void refresh() {
        runOnUiThread(() -> {
            if (HostService.server == null) { status.setText("Not hosting"); addr.setText(""); toggle.setText("Start hosting on this phone"); }
            else { status.setText("Hosting. Keep this phone on the same Wi-Fi or hotspot."); StringBuilder sb = new StringBuilder(); List<String> ips = HostService.ips(); for (String ip : ips) sb.append("http://").append(ip).append(":").append(HostService.port).append("\n"); if (ips.isEmpty()) sb.append("No network address found. Turn on Wi-Fi or hotspot.\n");
                sb.append("PIN: ").append(HostService.server.pin()).append("\nReceived files: ").append(HostService.dir); addr.setText(sb.toString()); toggle.setText("Stop hosting"); }
        });
    }
    private void openWeb(String u) { home.setVisibility(View.GONE); ((View) home.getParent()).setVisibility(View.GONE); webBox.setVisibility(View.VISIBLE); web.loadUrl(u); }
    private void closeWeb() { webBox.setVisibility(View.GONE); web.loadUrl("about:blank"); ((View) home.getParent()).setVisibility(View.VISIBLE); home.setVisibility(View.VISIBLE); }
    @Override public void onBackPressed() { if (webBox.getVisibility() == View.VISIBLE) closeWeb(); else super.onBackPressed(); }
    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == REQ_CHOOSE && chooser != null) { Uri[] r = null; if (res == RESULT_OK && d != null) { if (d.getClipData() != null) { r = new Uri[d.getClipData().getItemCount()]; for (int i = 0; i < r.length; i++) r[i] = d.getClipData().getItemAt(i).getUri(); } else if (d.getData() != null) r = new Uri[]{d.getData()}; } chooser.onReceiveValue(r); chooser = null; return; }
        if (req == REQ_SHARE && res == RESULT_OK && d != null) {
            if (d.getClipData() != null) for (int i = 0; i < d.getClipData().getItemCount(); i++) HostService.addShared(this, d.getClipData().getItemAt(i).getUri()); else if (d.getData() != null) HostService.addShared(this, d.getData());
            Toast.makeText(this, "Shared. Other devices can download it from your page.", Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onResume() { super.onResume(); IntentFilter f = new IntentFilter("com.sharable.STATE"); if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateRx, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(stateRx, f); refresh(); }
    @Override protected void onPause() { super.onPause(); try { unregisterReceiver(stateRx); } catch (Exception ignored) {} }
}
