package com.github.tvbox.osc.skybox;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import android.net.wifi.WifiManager;
import java.io.*;
import java.util.Map;

/** Keeps TVBox's header-bearing relay alive while the VR player is foreground. */
public final class StreamProxyService extends Service {
    private static StreamRelay relay;
    private static boolean returned;
    private static String previousDiagnostic = "尚无外部播放记录";
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private String stopReason = "服务被系统结束";
    private static String guardStatus = "尚未启动";
    private static final String PREFERENCES = "skybox_diagnostics";
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
            activity.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean("session_open", true).putString("summary", candidate.diagnostic()).apply();
            return result;
        } catch (IOException | RuntimeException e) { candidate.close(); throw e; }
    }

    public static synchronized String diagnostic() {
        return (relay == null ? previousDiagnostic : relay.diagnostic()) + "\n后台保护：" + guardStatus;
    }

    public static synchronized String diagnostic(Context context) {
        if (relay == null && "尚无外部播放记录".equals(previousDiagnostic)) {
            SharedPreferences stored = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE);
            previousDiagnostic = stored.getString("summary", previousDiagnostic);
            if (stored.getBoolean("session_open", false)) previousDiagnostic += "\n上次会话未正常结束：转发进程可能被系统回收";
        }
        return diagnostic();
    }

    public static void onReturn(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        synchronized (StreamProxyService.class) {
            if (returned) return;
            returned = true;
            if (relay != null && !relay.needsAttention() && !relay.isClosed()) return;
            if (relay == null && "尚无外部播放记录".equals(previousDiagnostic)
                    && !activity.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("session_open", false)) return;
        }
        showDiagnostic(activity);
    }

    public static void showDiagnostic(final Activity activity) {
        new AlertDialog.Builder(activity).setTitle("SKYBOX 播放诊断")
                .setMessage(diagnostic(activity))
                .setPositiveButton("确定", null)
                .setNeutralButton("复制诊断", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("SKYBOX 播放诊断", diagnostic(activity)));
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
                .setContentText("保持长时间播放；播放结束后请点停止")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, detail, flags))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", PendingIntent.getService(this, 1, stop, flags));
        startForeground(21561, builder.build());
        acquireGuards();
        handler.postDelayed(idleCheck, 30000);
    }

    private void acquireGuards() {
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            if (manager != null) {
                cpuLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName()+":skybox-stream");
                cpuLock.setReferenceCounted(false);
                cpuLock.acquire();
            }
        } catch (RuntimeException ignored) { cpuLock = null; }
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (manager != null) {
                wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVBox:skybox-stream");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (RuntimeException ignored) { wifiLock = null; }
        guardStatus = "CPU " + (cpuLock != null && cpuLock.isHeld() ? "已保持" : "不可用")
                + "；Wi-Fi " + (wifiLock != null && wifiLock.isHeld() ? "已保持" : "不可用");
    }

    private void releaseGuards() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (RuntimeException ignored) {}
        try { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); } catch (RuntimeException ignored) {}
        wifiLock = null; cpuLock = null;
        guardStatus = "已释放；" + stopReason;
    }

    private final Runnable idleCheck = new Runnable() {
        public void run() {
            synchronized (StreamProxyService.class) {
                // Buffered playback produces long gaps in HTTP traffic. It still owns its relay.
                if (relay == null || relay.shouldStopWhenUnattended()) {
                    stopReason = "未开始播放的空会话已清理";
                    stopSelf(); return;
                }
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                        .putBoolean("session_open", true).putString("summary", diagnostic()).apply();
            }
            handler.postDelayed(this, 30000);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopReason = "用户点击停止"; stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        synchronized (StreamProxyService.class) {
            if (relay != null) {
                try { relay.close(stopReason); } catch (IOException ignored) {}
                previousDiagnostic = relay.diagnostic();
                relay = null;
            }
        }
        releaseGuards();
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean("session_open", false).putString("summary", diagnostic()).apply();
        try (Writer file = new OutputStreamWriter(new FileOutputStream(new File(getFilesDir(), "skybox-last-diagnostic.txt")), "UTF-8")) {
            file.write(previousDiagnostic);
        } catch (IOException ignored) {}
        stopForeground(true);
        super.onDestroy();
    }
}
