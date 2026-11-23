package com.spoof.downloadguard;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 日志工具：
 * - hook 进程只写 XposedBridge.log（沙箱进程写不了文件）
 * - root 转发模块把日志写到 App 的外部私有目录（重启后保留）
 * - App 自己读取该文件，无需任何权限
 */
public class LogUtil {
    private static Context appContext;
    private static final File LEGACY_FILE = new File("/data/local/tmp/DownloadGuard/log.txt");

    public static void init(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static void log(String msg) {
        XposedBridge.log("[DownloadGuard] " + msg);
    }

    /** App 外部私有目录，重启保留，App 自己可读写 */
    public static File getLogFile() {
        if (appContext != null) {
            File dir = appContext.getExternalFilesDir(null);
            if (dir != null) return new File(dir, "downloadguard.log");
        }
        return LEGACY_FILE;
    }

    public static List<String> readLogs() {
        List<String> out = new ArrayList<>();
        File primary = getLogFile();
        if (readInto(primary, out)) return out;
        readInto(LEGACY_FILE, out);   // 兼容旧版转发模块的路径
        return out;
    }

    private static boolean readInto(File f, List<String> out) {
        if (f == null || !f.exists()) return false;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s;
            while ((s = r.readLine()) != null) out.add(s);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void clearLogs() {
        for (File f : new File[]{getLogFile(), LEGACY_FILE}) {
            if (f == null) continue;
            try {
                File dir = f.getParentFile();
                if (dir != null) dir.mkdirs();
                BufferedWriter w = new BufferedWriter(new FileWriter(f, false));
                w.close();
            } catch (Throwable ignored) { }
        }
    }

    /** 返回日志文件指纹，用于检测变化触发自动刷新 */
    public static String logFingerprint() {
        File f = getLogFile();
        if (f == null || !f.exists()) return "";
        return f.getAbsolutePath() + ":" + f.length() + ":" + f.lastModified();
    }
}
