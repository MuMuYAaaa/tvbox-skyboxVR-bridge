package com.github.tvbox.osc.skybox;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.io.IOException;

/** Standard Android video handoff, matching Kazumi's openWithMime protocol. */
public final class SkyboxPlayer {
    private SkyboxPlayer() {}

    public static Boolean play(Activity activity, String url, String title, HashMap<String, String> headers) {
        if (activity == null) return Boolean.FALSE;
        if (url == null || url.length() == 0) {
            Toast.makeText(activity, "没有可播放的视频地址", Toast.LENGTH_LONG).show();
            return Boolean.FALSE;
        }
        // Preserve the resolved URL byte-for-byte, including tokens and escaping.
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(url), "video/mp4");
        if (title != null) intent.putExtra("title", title);
        try {
            List<ResolveInfo> handlers = activity.getPackageManager()
                    .queryIntentActivities(intent, 0);
            ResolveInfo skybox = null;
            int count = 0;
            if (handlers != null) {
                for (ResolveInfo handler : handlers) {
                    if (handler.activityInfo == null || !handler.activityInfo.exported
                            || !handler.activityInfo.enabled) continue;
                    String name = handler.activityInfo.packageName + " "
                            + handler.activityInfo.name + " "
                            + handler.loadLabel(activity.getPackageManager());
                    if (name.toLowerCase(Locale.ROOT).replace(" ", "").contains("skybox")) {
                        skybox = handler;
                        count++;
                    }
                }
            }
            if (handlers == null || handlers.isEmpty()) {
                Toast.makeText(activity, "未找到外部播放器，请先在本设备安装 SKYBOX", Toast.LENGTH_LONG).show();
                return Boolean.FALSE;
            }
            String scheme = intent.getData().getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                // SKYBOX need not understand custom extras: TVBox applies headers itself.
                String localUrl = StreamProxyService.prepare(activity, url, headers);
                intent.setDataAndType(Uri.parse(localUrl), "video/mp4");
            }
            if (count == 1) {
                intent.setClassName(skybox.activityInfo.packageName, skybox.activityInfo.name);
                try {
                    activity.startActivity(intent);
                    return Boolean.TRUE;
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    // A package can change between discovery and launch. Let Android retry.
                    intent.setComponent(null);
                }
            }
            // Unrecognized store variants remain usable through Android's own picker.
            activity.startActivity(Intent.createChooser(intent, "选择 SKYBOX 播放"));
            return Boolean.TRUE;
        } catch (IOException failure) {
            Toast.makeText(activity, "视频转发启动失败，请返回 TVBox 后重试", Toast.LENGTH_LONG).show();
            return Boolean.FALSE;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, "无法打开外部播放器，请确认 SKYBOX 已安装并可用", Toast.LENGTH_LONG).show();
            return Boolean.FALSE;
        } catch (RuntimeException failure) {
            Toast.makeText(activity, "外部播放启动失败，请换一条线路后重试", Toast.LENGTH_LONG).show();
            return Boolean.FALSE;
        }
    }
}
