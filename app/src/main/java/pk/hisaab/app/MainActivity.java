package pk.hisaab.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.Manifest;
import android.database.Cursor;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
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


public class MainActivity extends Activity {
    private static final String ACTION_INSTALL_STATUS = "pk.hisaab.app.INSTALL_STATUS";

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pendingApkUrl;
    private long lastCheck;

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
        takeShare(getIntent());
        handleInstallStatus(getIntent());
        Updater.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        long now = System.currentTimeMillis();
        if (now - lastCheck > 30 * 60 * 1000L) { lastCheck = now; checkForUpdate(); }
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
        takeShare(intent);
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


    /* ---------------- messages shared to Hisaab ---------------- */

    private String sharedText;

    private void takeShare(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null && t.length() > 0) sharedText = t.toString();
            setIntent(new Intent(this, MainActivity.class));
        }
    }

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
        /** Returns text shared from another app (once), or an empty string. */
        @JavascriptInterface
        public String takeShared() {
            String t = sharedText;
            sharedText = null;
            return t == null ? "" : t;
        }

        @JavascriptInterface
        public String version() { return Updater.installedName(MainActivity.this); }

        /** Opens Android's share sheet with the given text (used for backups). */
        @JavascriptInterface
        public void shareText(String text) {
            ui.post(() -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Hisaab backup")
                    .putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "Save Hisaab backup"));
            });
        }

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

    private Updater.Release ready;   // found while the app is open; installed once you leave it
    private boolean asked;

    private void checkForUpdate() {
        new Thread(() -> {
            try {
                Updater.Release r = Updater.newer(this);
                if (r == null) return;
                if (Updater.canSilent(this)) { ready = r; return; }
                ui.post(() -> {
                    if (isFinishing() || asked) return;
                    asked = true;
                    new AlertDialog.Builder(this)
                        .setTitle("Update ready")
                        .setMessage("Install Hisaab " + r.name + " now. After this one, updates install by themselves. Your entries stay as they are.")
                        .setPositiveButton("Install", (d, w) -> startUpdate(r.url))
                        .setNegativeButton("Later", null)
                        .show();
                });
            } catch (Exception ignored) {
                // Offline or GitHub unreachable: try again next time the app opens.
            }
        }).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Installing replaces the running app, so wait until you've left it.
        final Updater.Release r = ready;
        if (r == null) return;
        ready = null;
        new Thread(() -> {
            try { Updater.install(getApplicationContext(), r.url, InstallReceiver.sender(getApplicationContext())); }
            catch (Exception ignored) { }
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
            try {
                Intent status = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                Updater.install(this, url, PendingIntent.getActivity(this, 1, status, flags).getIntentSender());
            } catch (Exception e) {
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
}
