package jp.haruyalab.novelshelf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONObject;

/** Keeps a user-started analysis alive across reader navigation and screen-off. */
public final class SpeechAnalysisService extends Service {
    private static final String CHANNEL = "tsuzuki_analysis";
    private static final String CANCEL = "jp.haruyalab.novelshelf.ANALYSIS_CANCEL";
    private static final int NOTIFICATION = 1202;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String protectedJobId = "", notificationKey = "";
    private int lastStartId;
    private boolean initialized, foregroundReady, destroyed;
    private PowerManager.WakeLock wake;
    private long nextWakeRenewal;

    static void protect(Context context, JSONObject started) {
        String jobId = started.optString("jobId");
        String status = started.optString("status");
        if (jobId.isEmpty() || !("running".equals(status) || "queued".equals(status) || "loading".equals(status))) return;
        try {
            context.getApplicationContext().startForegroundService(new Intent(context, SpeechAnalysisService.class)
                    .putExtra("jobId", jobId).putExtra("bookId", started.optString("bookId")));
        } catch (RuntimeException unavailable) {
            SpeechAnalysis.cancelJob(jobId);
            throw new IllegalStateException("解析サービスを開始できませんでした。アプリを開いて解析を再開してください。");
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL, "GPT解析", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("指定した範囲の解析進捗と中断"); channel.setSound(null, null);
            manager.createNotificationChannel(channel);
            JSONObject current = SpeechAnalysis.activeSnapshot(this);
            protectedJobId = current.optString("jobId");
            promote(current);
            PowerManager power = getSystemService(PowerManager.class);
            if (power != null) {
                wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tsuzuki:SpeechAnalysis");
                wake.setReferenceCounted(false);
            }
            initialized = true;
        } catch (RuntimeException unavailable) {
            JSONObject active = SpeechAnalysis.activeSnapshot(this);
            SpeechAnalysis.cancelJob(active.optString("jobId"));
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (!initialized) { stopSelf(startId); return START_NOT_STICKY; }
        if (intent != null && CANCEL.equals(intent.getAction()))
            SpeechAnalysis.cancelJob(intent.getStringExtra("jobId"));
        // A new start can reuse this instance after an idle tick removed its foreground status.
        // Every startForegroundService request must promote again, not merely notify().
        try {
            JSONObject state = SpeechAnalysis.activeSnapshot(this);
            protectedJobId = state.optString("jobId"); promote(state); notificationKey = "";
        } catch (RuntimeException unavailable) {
            SpeechAnalysis.cancelJob(protectedJobId); releaseWake(); stopSelf(startId); return START_NOT_STICKY;
        }
        main.removeCallbacks(tick); tick.run();
        return START_NOT_STICKY;
    }

    private void promote(JSONObject state) {
        Notification notification = notification(state);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION, notification);
        foregroundReady = true;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            JSONObject state = SpeechAnalysis.activeSnapshot(SpeechAnalysisService.this);
            if (state.optString("jobId").isEmpty()) {
                releaseWake(); stopForeground(STOP_FOREGROUND_REMOVE); foregroundReady = false; stopSelf(lastStartId); return;
            }
            protectedJobId = state.optString("jobId");
            try {
                long now = SystemClock.elapsedRealtime();
                if (wake != null && (!wake.isHeld() || now >= nextWakeRenewal)) {
                    wake.acquire(60000L); nextWakeRenewal = now + 30000L;
                }
                String key = protectedJobId + "|" + state.optString("status") + "|" + state.optInt("completedChapters")
                        + "|" + state.optInt("currentNumber") + "|" + state.optInt("analyzedUnits") + "|" + state.optInt("totalUnits")
                        + "|" + state.optString("analysisPhase") + "|" + state.optInt("historyCompleted");
                JSONObject activity = state.optJSONObject("analysisActivity");
                if (activity != null) key += "|" + activity.optString("stage") + "|" + activity.optInt("requestNumber")
                        + "|" + activity.optInt("chapterFromNumber") + "|" + activity.optInt("chapterToNumber");
                if (!key.equals(notificationKey)) {
                    getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(state));
                    notificationKey = key;
                }
            } catch (RuntimeException unavailable) {
                SpeechAnalysis.cancelJob(protectedJobId); releaseWake(); stopSelf(lastStartId); return;
            }
            main.postDelayed(this, 1000L);
        }
    };

    private Notification notification(JSONObject state) {
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String jobId = state.optString("jobId");
        long total = Math.max(0, state.optLong("count")), completed = Math.max(0, state.optLong("completedChapters"));
        String title = jobId.isEmpty() ? "GPT解析を準備しています" : "GPT解析 · " + completed + " / " + total + "話完了";
        String detail = state.optInt("currentNumber") > 0 ? "第" + state.optInt("currentNumber") + "話" : "解析結果を保存しています";
        JSONObject activity = state.optJSONObject("analysisActivity");
        if (activity != null && activity.length() > 0) {
            int from = activity.optInt("chapterFromNumber"), to = activity.optInt("chapterToNumber");
            detail = ("history".equals(activity.optString("scope")) ? "作品メモ · " : "")
                    + (from > 0 ? "第" + from + (to > from ? "〜" + to : "") + "話 · " : "")
                    + activityLabel(activity.optString("stage"));
        }
        else if ("loading".equals(state.optString("phase"))) detail += " · 本文を読み込み中";
        else if ("memory".equals(state.optString("analysisPhase"))) detail += " · 作品メモを準備中"
                + (state.optInt("historyTotal") > 0 ? " " + state.optInt("historyCompleted") + " / " + state.optInt("historyTotal") + "話" : "");
        else if (state.optInt("totalUnits") > 0) detail += " · " + state.optInt("analyzedUnits") + " / " + state.optInt("totalUnits") + "区間";
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(title).setContentText(detail).setContentIntent(open).setOngoing(true)
                .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        if (total > 0 && total <= Integer.MAX_VALUE) builder.setProgress((int) total, (int) Math.min(total, completed), false);
        else builder.setProgress(0, 0, true);
        if (!jobId.isEmpty()) {
            Intent cancel = new Intent(this, SpeechAnalysisService.class).setAction(CANCEL)
                    .setData(Uri.parse("tsuzuki://analysis/cancel/" + Uri.encode(jobId))).putExtra("jobId", jobId);
            PendingIntent pending = PendingIntent.getService(this, NOTIFICATION + 1, cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "中断", pending).build());
        }
        return builder.build();
    }

    private static String activityLabel(String stage) {
        switch (stage) {
            case "loading": return "本文を読み込み中";
            case "preparing": return "入力を準備中";
            case "authorizing": return "接続情報を確認中";
            case "waiting": return "GPTへ送信・応答待ち";
            case "validating": return "応答を検証中";
            case "saving": return "結果を保存中";
            case "complete": return "完了";
            case "paused": return "中断";
            case "error": return "エラーで停止";
            default: return "処理中";
        }
    }

    private void releaseWake() { if (wake != null && wake.isHeld()) wake.release(); }
    @Override public void onTimeout(int startId, int foregroundServiceType) {
        SpeechAnalysis.cancelJob(protectedJobId); releaseWake(); stopSelf();
    }
    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null);
        SpeechAnalysis.cancelJob(protectedJobId); releaseWake(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
