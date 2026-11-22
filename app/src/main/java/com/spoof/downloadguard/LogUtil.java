package com.spoof.downloadguard;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import de.robv.android.xposed.XposedBridge;

/** 日志工具：hook 进程用 XposedBridge.log 输出，App 内通过 logcat 读取 */
public class LogUtil {
    public static void log(String msg) {
        XposedBridge.log("[DownloadGuard] " + msg);
    }

    /** 从 logcat 读取本模块的所有日志（App 自身有读 logcat 权限） */
    public static java.util.List<String> readLogs() {
        java.util.List<String> result = new java.util.ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-d", "-t", "500", "-s", "LSPosedFramework:I"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("[DownloadGuard]")) {
                    // 只保留 [DownloadGuard] 之后的部分
                    int i = line.indexOf("[DownloadGuard]");
                    result.add(line.substring(i));
                }
            }
            r.close();
            p.destroy();
        } catch (Throwable ignored) { }
        return result;
    }

    public static void clearLogs() {
        try {
            Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();
        } catch (Throwable ignored) { }
    }
}
