package pk.hisaab.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;

/** Background check: installs a new version on its own when Android allows silent self-updates. */
public class UpdateJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                if (Updater.canSilent(this)) {
                    Updater.Release r = Updater.newer(this);
                    if (r != null) Updater.install(this, r.url, InstallReceiver.sender(this));
                }
            } catch (Exception ignored) {
                // Offline or GitHub unreachable: the next run tries again.
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
