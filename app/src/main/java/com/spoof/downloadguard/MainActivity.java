package com.spoof.downloadguard;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** 模块入口 Activity：显示拦截日志，自动刷新 */
public class MainActivity extends Activity {
    private TextView logView;
    private final Handler handler = new Handler();
    private String lastFingerprint = "";
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            String fp = LogUtil.logFingerprint();
            if (!fp.equals(lastFingerprint)) {
                lastFingerprint = fp;
                loadLog();
            }
            handler.postDelayed(this, 1000);   // 每秒检查一次
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogUtil.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = 24;
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("下载拦截器 · 拦截日志");
        title.setTextSize(18);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("自动刷新 · BLOCK 表示拦截成功 · 日志重启后保留");
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

        Button clear = new Button(this);
        clear.setText("清空日志");
        clear.setOnClickListener(v -> {
            LogUtil.clearLogs();
            lastFingerprint = "";
            loadLog();
        });
        root.addView(clear);

        setContentView(root);
        loadLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogUtil.init(this);
        lastFingerprint = "";
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshTask);
    }

    private void loadLog() {
        List<String> lines = LogUtil.readLogs();
        if (lines.isEmpty()) {
            logView.setText("暂无日志。\n\n触发下载后这里会自动显示记录。");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.size() - 1; i >= 0; i--) sb.append(lines.get(i)).append('\n');
            logView.setText(sb.toString());
        }
    }
}
