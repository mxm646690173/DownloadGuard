package com.spoof.downloadguard;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** 模块入口 Activity：显示拦截日志（从 logcat 读取） */
public class MainActivity extends Activity {
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = 24;
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("下载拦截器 · 拦截日志");
        title.setTextSize(18);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("触发下载后点「刷新」，BLOCK 表示拦截成功");
        hint.setTextSize(12);
        root.addView(hint);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setMovementMethod(new ScrollingMovementMethod());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = 20;
        logView.setLayoutParams(lp);
        root.addView(logView);

        LinearLayout btns = new LinearLayout(this);
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(v -> loadLog());
        Button clear = new Button(this);
        clear.setText("清空");
        clear.setOnClickListener(v -> {
            LogUtil.clearLogs();
            loadLog();
        });
        btns.addView(refresh);
        btns.addView(clear);
        root.addView(btns);

        setContentView(root);
        loadLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadLog();
    }

    private void loadLog() {
        List<String> lines = LogUtil.readLogs();
        if (lines.isEmpty()) {
            logView.setText("暂无日志。\n\n触发一次下载后回来点「刷新」。");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.size() - 1; i >= 0; i--) sb.append(lines.get(i)).append('\n');
            logView.setText(sb.toString());
        }
    }
}
