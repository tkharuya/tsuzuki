package jp.haruyalab.novelshelf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

/** Holds the local callback listener while the user completes the explicit browser sign-in. */
public final class ChatGptLoginService extends Service {
    static final String CANCEL = "jp.haruyalab.novelshelf.CHATGPT_CANCEL";
    private static final String CHANNEL = "chatgpt-login";
    private static final int NOTIFICATION = 208;
    private long generation = -1;
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "ChatGPTログイン", NotificationManager.IMPORTANCE_LOW));
        generation = ChatGptSession.get(this).currentGeneration();
        showForeground(generation);
    }
    private void showForeground(long currentGeneration) {
        PendingIntent open = PendingIntent.getActivity(this, 208, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancelIntent = new Intent(this, ChatGptLoginService.class).setAction(CANCEL)
                .setData(Uri.parse("tsuzuki://chatgpt/cancel/" + currentGeneration)).putExtra("generation", currentGeneration);
        PendingIntent cancel = PendingIntent.getService(this, 209, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("ChatGPTへのログインを待っています")
                .setContentText("ブラウザーで認証してください。タップで「つづき」に戻れます。")
                .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
                .addAction(new Notification.Action.Builder(null, "中断", cancel).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION, notification);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CANCEL.equals(intent.getAction())) ChatGptSession.get(this).cancel(intent.getLongExtra("generation", -1));
        // A queued old start must not stop a newer sign-in on the same service instance.
        generation = ChatGptSession.get(this).currentGeneration();
        if (generation < 0) stopSelf(startId);
        else showForeground(generation);
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId, int foregroundServiceType) { ChatGptSession.get(this).serviceStopped(generation); stopSelf(); }
    @Override public void onDestroy() { ChatGptSession.get(this).serviceStopped(generation); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
