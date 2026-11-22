package com.spoof.downloadguard;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/** 日志工具：hook 与目标 App 共用 /data/local/tmp 下的日志文件 */
public class LogUtil {
    private static final File LOG_DIR = new File("/data/local/tmp/DownloadGuard");
    private static final File LOG_FILE = new File(LOG_DIR, "log.txt");

    public static void log(String msg) {
        String line = "[" + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date()) + "] " + msg;
        XposedBridge.log("[DownloadGuard] " + msg);
        try {
            if (!LOG_DIR.exists()) LOG_DIR.mkdirs();
            BufferedWriter w = new BufferedWriter(new FileWriter(LOG_FILE, true));
            w.write(line);
            w.newLine();
            w.close();
            // 确保其他进程（模块 App）能读
            chmod(LOG_DIR, 777);
            chmod(LOG_FILE, 666);
        } catch (Throwable ignored) { }
    }

    public static List<String> readLogs() {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(LOG_FILE))) {
            String s;
            while ((s = r.readLine()) != null) out.add(s);
        } catch (Throwable ignored) { }
        return out;
    }

    public static void clearLogs() {
        try {
            new FileWriter(LOG_FILE, false).close();
        } catch (Throwable ignored) { }
    }

    private static void chmod(File f, int mode) {
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", String.valueOf(mode),
                    f.getAbsolutePath()}).waitFor();
        } catch (Throwable ignored) { }
    }
}
