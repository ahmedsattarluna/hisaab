package pk.hisaab.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.Manifest;
import android.database.Cursor;
import android.webkit.JavascriptInterface;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String LATEST = "https://api.github.com/repos/ahmedsattarluna/hisaab/releases/latest";
    private static final String ACTION_INSTALL_STATUS = "pk.hisaab.app.INSTALL_STATUS";

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pendingApkUrl;
    private boolean checked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF1B1715);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "HisaabNative");
        setContentView(web);
        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
        handleInstallStatus(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!checked) { checked = true; checkForUpdate(); }
        web.evaluateJavascript("window.hisaabResume&&window.hisaabResume()", null);
        // Back from the "install unknown apps" settings screen: continue the update.
        if (pendingApkUrl != null && canInstall()) {
            String url = pendingApkUrl;
            pendingApkUrl = null;
            downloadAndInstall(url);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleInstallStatus(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.hisaabBack && window.hisaabBack()) ? 1 : 0", result -> {
            if (!"1".equals(result)) finish();
        });
    }


    /* ---------------- Easypaisa SMS ---------------- */

    private static final int SMS_REQ = 7;

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != SMS_REQ) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        boolean blocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS);
        web.evaluateJavascript("window.hisaabSmsResult&&window.hisaabSmsResult(" + granted + "," + blocked + ")", null);
    }

    private class Bridge {
        @JavascriptInterface
        public boolean hasSms() {
            return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestSms() {
            ui.post(() -> requestPermissions(new String[]{Manifest.permission.READ_SMS}, SMS_REQ));
        }

        @JavascriptInterface
        public void openAppSettings() {
            ui.post(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        }

        /** Inbox messages newer than `since` (ms) whose sender matches one of the comma-separated names. */
        @JavascriptInterface
        public String readSms(double since, String senders) {
            JSONArray out = new JSONArray();
            if (!hasSms()) return out.toString();
            String[] tokens = (senders == null ? "" : senders).toLowerCase().split(",");
            try (Cursor c = getContentResolver().query(Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body", "date"}, "date > ?",
                    new String[]{String.valueOf((long) since)}, "date ASC")) {
                if (c == null) return out.toString();
                int seen = 0;
                while (c.moveToNext() && seen++ < 5000 && out.length() < 800) {
                    String addr = c.getString(0);
                    if (addr == null || !senderMatches(addr, tokens)) continue;
                    JSONObject m = new JSONObject();
                    m.put("address", addr);
                    m.put("body", c.getString(1));
                    m.put("date", c.getLong(2));
                    out.put(m);
                }
            } catch (Exception ignored) { }
            return out.toString();
        }
    }

    private static boolean senderMatches(String addr, String[] tokens) {
        String a = addr.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (String t : tokens) {
            String k = t.trim().replaceAll("[^a-z0-9]", "");
            if (k.isEmpty()) continue;
            if (a.equals(k)) return true;
            if (!k.matches("[0-9]+") && a.contains(k)) return true;
        }
        return false;
    }

    /* ---------------- updates ---------------- */

    private long installedVersion() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return Long.MAX_VALUE; }
    }

    private void checkForUpdate() {
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(get(LATEST));
                String tag = rel.optString("tag_name", "");
                long latest = Long.parseLong(tag.substring(tag.lastIndexOf('.') + 1));
                if (latest <= installedVersion()) return;
                JSONArray assets = rel.getJSONArray("assets");
                String apk = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) apk = a.getString("browser_download_url");
                }
                if (apk == null) return;
                final String url = apk;
                final String name = tag.replace("v", "");
                ui.post(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(this)
                        .setTitle("Update available")
                        .setMessage("Hisaab " + name + " is ready. Your entries stay as they are.")
                        .setPositiveButton("Update now", (d, w) -> startUpdate(url))
                        .setNegativeButton("Later", null)
                        .show();
                });
            } catch (Exception ignored) {
                // Offline or GitHub unreachable: try again next time the app opens.
            }
        }).start();
    }

    private boolean canInstall() {
        return getPackageManager().canRequestPackageInstalls();
    }

    private void startUpdate(String url) {
        if (!canInstall()) {
            pendingApkUrl = url;
            Toast.makeText(this, "Allow Hisaab to install updates, then come back.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        downloadAndInstall(url);
    }

    private void downloadAndInstall(String url) {
        Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int id = pi.createSession(params);
                session = pi.openSession(id);
                HttpURLConnection c = open(url);
                try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("hisaab.apk", 0, -1)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent status = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent p = PendingIntent.getActivity(this, 1, status, flags);
                session.commit(p.getIntentSender());
            } catch (Exception e) {
                if (session != null) session.abandon();
                ui.post(() -> Toast.makeText(this,
                    "Couldn't download the update. Check your internet and reopen the app to try again.",
                    Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void handleInstallStatus(Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(this, "The update wasn't installed. Reopen the app to try again.", Toast.LENGTH_LONG).show();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        // Follow GitHub's redirects to its download servers by hand, up to 5 hops.
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Hisaab-Android");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { url = c.getHeaderField("Location"); c.disconnect(); continue; }
            if (code != 200) throw new Exception("HTTP " + code);
            return c;
        }
        throw new Exception("Too many redirects");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
