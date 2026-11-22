package com.spoof.downloadguard;

import android.app.DownloadManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 直接拦截 APK/ZIP/EXE 等文件类型的静默下载：
 * hook DownloadManager.enqueue()，命中规则时取消方法调用并返回 -1，
 * 不弹窗、不提示、不给放行选项。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String[] BLOCKED_EXT = {
        ".apk", ".apks", ".xapk", ".zip", ".rar", ".7z", ".exe", ".msi", ".scr", ".bat", ".js", ".jar"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> dm = XposedHelpers.findClass(
                    "android.app.DownloadManager", lpparam.classLoader);

            // API 24+ 的主入口
            XposedHelpers.findAndHookMethod(dm, "enqueue",
                    DownloadManager.Request.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            DownloadManager.Request req = (DownloadManager.Request) param.args[0];
                            String url = extractUrl(req);
                            if (isBlocked(url)) {
                                XposedBridge.log("[DownloadGuard] BLOCK " + url
                                        + " pkg=" + lpparam.packageName);
                                param.setResult(-1L);   // 无效 id，方法体不再执行
                            }
                        }
                    });

            // addCompletedDownload 是另一条写下载记录的入口，同样拦。
            // 这个方法各版本签名不同，单独 try，失败不影响主 hook。
            try {
                XposedHelpers.findAndHookMethod(dm, "addCompletedDownload",
                        String.class, String.class, boolean.class, String.class,
                        String.class, long.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                // 参数: title, description, isMediaScannerScannable,
                                //       mimeType, path, length, showNotification
                                String path = (String) param.args[4];
                                if (isBlocked(path)) {
                                    XposedBridge.log("[DownloadGuard] BLOCK completed " + path
                                            + " pkg=" + lpparam.packageName);
                                    param.setResult(-1L);
                                }
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log("[DownloadGuard] addCompletedDownload hook skipped: " + t.getMessage());
            }
        } catch (Throwable t) {
            XposedBridge.log("[DownloadGuard] hook failed: " + t);
        }
    }

    private static String extractUrl(DownloadManager.Request req) {
        try {
            Object uri = XposedHelpers.getObjectField(req, "mUri");
            if (uri != null) return uri.toString();
        } catch (Throwable ignored) { }
        try {
            Object uri = XposedHelpers.callMethod(req, "getUri");
            if (uri != null) return uri.toString();
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean isBlocked(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        for (String ext : BLOCKED_EXT) {
            int idx = lower.indexOf(ext);
            if (idx < 0) continue;
            String rest = lower.substring(idx + ext.length());
            if (rest.isEmpty() || rest.charAt(0) == '?' || rest.charAt(0) == '#') return true;
        }
        return false;
    }
}
