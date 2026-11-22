package com.spoof.downloadguard;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** 模块入口 Activity，让 LSPosed 管理器能识别并显示本应用 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("下载拦截器已安装\n请在 LSPosed 管理器中启用模块并选择作用域");
        tv.setTextSize(16);
        tv.setPadding(40, 40, 40, 40);
        setContentView(tv);
    }
}
