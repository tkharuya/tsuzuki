package jp.haruyalab.novelshelf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.util.Locale;
import org.json.JSONObject;

/** Keeps the explicit, resumable model download alive while the screen is off. */
public final class OfflineVoiceDownloadService extends Service {
    public static final String ACTION_START = "jp.haruyalab.novelshelf.voicepacks.START";
    public static final String ACTION_CANCEL = "jp.haruyalab.novelshelf.voicepacks.CANCEL";
    private static final String CHANNEL = "tsuzuki_voice_download";
    private static final int NOTIFICATION_ID = 1202;
    private OfflineVoicePacks packs;
    private boolean finished;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        packs = new OfflineVoicePacks(this);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "オフライン音声の取得",
                NotificationManager.IMPORTANCE_LOW));
        Notification initial = notification(packs.snapshot());
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID, initial);
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tsuzuki:VoiceDownload");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        OfflineVoicePacks.observeService(this::onProgress);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            packs.cancel();
            if (!OfflineVoicePacks.isWorking()) finish();
            return START_NOT_STICKY;
        }
        if (!packs.beginDownload()) finish();
        return START_NOT_STICKY;
    }

    private void onProgress(JSONObject state) {
        if (finished) return;
        String status = state.optString("status");
        if ("ready".equals(status) || "paused".equals(status) || "error".equals(status)
                || "not_installed".equals(status)) {
            finish();
        } else {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(state));
        }
    }

    private Notification notification(JSONObject state) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1202, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(this, OfflineVoiceDownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent pause = PendingIntent.getService(this, 1203, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long done = state.optLong("downloadedBytes"), total = OfflineVoicePacks.DOWNLOAD_BYTES;
        boolean verifying = "verifying".equals(state.optString("status"));
        String progress = String.format(Locale.JAPAN, "%.1f / %.1f MB", done / 1000000d, total / 1000000d);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_book)
                .setContentTitle(verifying ? "音声データを検証・展開しています" : "10種類のオフライン音声を取得しています")
                .setContentText(progress).setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(1000, (int) Math.min(1000, done * 1000 / total), verifying)
                .addAction(new Notification.Action.Builder(null, "一時停止", pause).build()).build();
    }

    private void finish() {
        finished = true;
        OfflineVoicePacks.observeService(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopSelf();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        // Android 15+ caps data-sync foreground services. Preserve verified files and partial bytes.
        packs.cancel();
        finish();
    }

    @Override public void onDestroy() {
        if (!finished) packs.cancel();
        OfflineVoicePacks.observeService(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
