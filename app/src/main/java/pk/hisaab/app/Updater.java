package pk.hisaab.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Finds new Hisaab releases on GitHub and installs them, without asking when Android allows it. */
final class Updater {
    static final String LATEST = "https://api.github.com/repos/ahmedsattarluna/hisaab/releases/latest";
    private static final int JOB_ID = 4242;

    static final class Release {
        final long code; final String name; final String url;
        Release(long code, String name, String url) { this.code = code; this.name = name; this.url = url; }
    }

    private Updater() { }

    static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences("updates", Context.MODE_PRIVATE); }

    static long installedVersion(Context ctx) {
        try {
            PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return Long.MAX_VALUE; }
    }

    static String installedName(Context ctx) {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    /** The newest release if it's newer than what's installed, otherwise null. Network call: not on the UI thread. */
    static Release newer(Context ctx) throws Exception {
        JSONObject rel = new JSONObject(get(LATEST));
        String tag = rel.optString("tag_name", "");
        long code = Long.parseLong(tag.substring(tag.lastIndexOf('.') + 1));
        if (code <= installedVersion(ctx)) return null;
        JSONArray assets = rel.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) return new Release(code, tag.replace("v", ""), a.getString("browser_download_url"));
        }
        return null;
    }

    /** True when Android will let Hisaab update itself with no confirmation screen (Android 12+, after Hisaab installed itself once). */
    static boolean canSilent(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return false;
        if (prefs(ctx).getBoolean("needsPrompt", false)) return false;
        try {
            if (!ctx.getPackageManager().canRequestPackageInstalls()) return false;
            String installer = ctx.getPackageManager().getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName();
            return ctx.getPackageName().equals(installer);
        } catch (Exception e) { return false; }
    }

    /** Downloads the APK into an install session and commits it. Network call: not on the UI thread. */
    static void install(Context ctx, String url, IntentSender status) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            HttpURLConnection c = open(url);
            try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("hisaab.apk", 0, -1)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            session.commit(status);
        } catch (Exception e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    /** Checks for updates about every 6 hours in the background, when there's internet. */
    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, UpdateJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6L * 60 * 60 * 1000);
        try { js.schedule(b.setPersisted(true).build()); }
        catch (Exception e) { try { js.schedule(b.setPersisted(false).build()); } catch (Exception ignored) { } }
    }

    static HttpURLConnection open(String url) throws Exception {
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

    static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
