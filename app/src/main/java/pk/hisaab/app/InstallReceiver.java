package pk.hisaab.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Build;

/** Receives the result of a background update. Nothing to show: the new version is there next time the app opens. */
public class InstallReceiver extends BroadcastReceiver {
    static IntentSender sender(Context ctx) {
        Intent i = new Intent(ctx, InstallReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, 2, i, flags).getIntentSender();
    }

    @Override
    public void onReceive(Context ctx, Intent intent) { }
}
