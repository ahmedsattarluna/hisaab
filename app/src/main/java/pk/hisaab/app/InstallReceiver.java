package pk.hisaab.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Result of a background update. If Android wants a confirmation (or blocks it), remember that so the app asks next time it opens. */
public class InstallReceiver extends BroadcastReceiver {
    static IntentSender sender(Context ctx) {
        Intent i = new Intent(ctx, InstallReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, 2, i, flags).getIntentSender();
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        Updater.prefs(ctx).edit().putBoolean("needsPrompt", true).apply();
    }
}
