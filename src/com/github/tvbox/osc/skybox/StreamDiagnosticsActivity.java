package com.github.tvbox.osc.skybox;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

public final class StreamDiagnosticsActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("SKYBOX 播放诊断");
        TextView text = new TextView(this);
        text.setText(StreamProxyService.diagnostic(this));
        text.setTextSize(18);
        text.setPadding(24, 24, 24, 24);
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
    }
}
