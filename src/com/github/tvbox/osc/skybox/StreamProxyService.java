package com.github.tvbox.osc.skybox;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.util.Map;

/** Keeps TVBox's header-bearing relay alive while the VR player is foreground. */
public final class StreamProxyService extends Service {
    private static StreamRelay relay;
    private static boolean returned;
    private static String previousDiagnostic = "尚无外部播放记录";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final String CHANNEL = "skybox_stream";
    private static final String STOP = "com.github.tvbox.osc.skybox.STOP";

    public static synchronized String prepare(Activity activity, String url, Map<String, String> headers) throws IOException {
        StreamRelay candidate = new StreamRelay(url, headers);
        try {
            String result = candidate.mediaUrl();
            Intent service = new Intent(activity, StreamProxyService.class);
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(service);
            else activity.startService(service);
            if (relay != null) { previousDiagnostic = relay.diagnostic(); relay.close(); }
            relay = candidate;
            returned = false;
            return result;
        } catch (IOException | RuntimeException e) { candidate.close(); throw e; }
    }

    public static synchronized String diagnostic() {
        return relay == null ? previousDiagnostic : relay.diagnostic();
    }

    public static void onReturn(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        synchronized (StreamProxyService.class) {
            if (relay == null || returned) return;
            returned = true;
            if (!relay.needsAttention()) return;
        }
        showDiagnostic(activity);
    }

    public static void showDiagnostic(final Activity activity) {
        new AlertDialog.Builder(activity).setTitle("SKYBOX 播放诊断")
                .setMessage(diagnostic())
                .setPositiveButton("确定", null)
                .setNeutralButton("复制诊断", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("SKYBOX 播放诊断", diagnostic()));
                        Toast.makeText(activity, "诊断已复制", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "SKYBOX 视频转发", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        Intent detail = new Intent(this, StreamDiagnosticsActivity.class);
        Intent stop = new Intent(this, StreamProxyService.class).setAction(STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在向 SKYBOX 提供视频")
                .setContentText("点击查看播放诊断；播放结束后可停止")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, detail, flags))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", PendingIntent.getService(this, 1, stop, flags));
        startForeground(21561, builder.build());
        handler.postDelayed(idleCheck, 30000);
    }

    private final Runnable idleCheck = new Runnable() {
        public void run() {
            synchronized (StreamProxyService.class) {
                if (relay == null || relay.idleMillis() > 15 * 60 * 1000L
                        || (relay.needsAttention() && relay.idleMillis() > 2 * 60 * 1000L)) {
                    stopSelf(); return;
                }
            }
            handler.postDelayed(this, 30000);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        synchronized (StreamProxyService.class) {
            if (relay != null) {
                try { relay.close(); } catch (IOException ignored) {}
                previousDiagnostic = relay.diagnostic();
                relay = null;
            }
        }
        try (Writer file = new OutputStreamWriter(new FileOutputStream(new File(getFilesDir(), "skybox-last-diagnostic.txt")), "UTF-8")) {
            file.write(previousDiagnostic);
        } catch (IOException ignored) {}
        stopForeground(true);
        super.onDestroy();
    }
}
