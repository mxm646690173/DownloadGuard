package com.spoof.downloadguard;

import android.app.DownloadManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 全面拦截静默下载：
 *   1. DownloadManager.enqueue / addCompletedDownload（系统下载）
 *   2. OkHttp Call.execute() / enqueue()（App 自写下载）
 *   3. HttpURLConnection.getInputStream()（原生网络）
 *   4. FileOutputStream / Files.write / RandomAccessFile（写危险后缀文件）
 *   5. WebView DownloadListener（网页内下载监听）
 *
 * 命中规则直接取消，不弹窗、不放行。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String[] BLOCKED_EXT = {
        ".apk", ".apks", ".xapk", ".zip", ".rar", ".7z", ".exe", ".msi", ".scr", ".bat", ".js", ".jar"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        // 跳过模块自身与系统核心进程
        if (pkg == null || pkg.equals("com.spoof.downloadguard")
                || pkg.equals("android") || pkg.startsWith("com.android.")) return;

        hookDownloadManager(lpparam, pkg);
        hookOkHttp(lpparam, pkg);
        hookHttpUrlConnection(lpparam, pkg);
        hookFileWrite(lpparam, pkg);
        hookWebView(lpparam, pkg);
    }

    // ---------------------------------------------------------- DownloadManager
    private static void hookDownloadManager(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        try {
            Class<?> dm = XposedHelpers.findClass("android.app.DownloadManager", lp.classLoader);
            XposedHelpers.findAndHookMethod(dm, "enqueue",
                    DownloadManager.Request.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            String url = extractUrl((DownloadManager.Request) p.args[0]);
                            if (isBlocked(url)) block(p, "DownloadManager.enqueue", url, pkg);
                        }
                    });
        } catch (Throwable t) { logSkip("DownloadManager.enqueue", t); }

        try {
            Class<?> dm = XposedHelpers.findClass("android.app.DownloadManager", lp.classLoader);
            XposedHelpers.findAndHookMethod(dm, "addCompletedDownload",
                    String.class, String.class, boolean.class, String.class,
                    String.class, long.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            String path = (String) p.args[4];
                            if (isBlocked(path)) block(p, "addCompletedDownload", path, pkg);
                        }
                    });
        } catch (Throwable t) { logSkip("addCompletedDownload", t); }
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

    // ---------------------------------------------------------- OkHttp
    private static void hookOkHttp(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        // hook RealCall.execute()/enqueue()：拿到关联的 Request.url()，
        // 命中则抛 IOException 让下载直接失败。不 hook Request.url() 本身，
        // 避免每个网络请求都被拦截逻辑拖慢。
        Class<?> callCls = findClassAny(lp.classLoader,
                "okhttp3.internal.connection.RealCall",
                "okhttp3.Call",
                "com.squareup.okhttp.Call");
        if (callCls == null) return;

        try {
            XposedHelpers.findAndHookMethod(callCls, "execute", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    String url = requestUrl(p.thisObject);
                    if (isBlocked(url)) {
                        XposedBridge.log("[DownloadGuard] BLOCK OkHttp.execute " + url + " pkg=" + pkg);
                        p.setThrowable(new IOException("DownloadGuard blocked"));
                    }
                }
            });
        } catch (Throwable t) { logSkip("OkHttp.execute", t); }

        // 异步调用：OkHttp 3 是 enqueue(Callback)；OkHttp 4 签名相同（Kotlin 接口）
        try {
            Class<?> callback = findClassAny(lp.classLoader,
                    "okhttp3.Callback", "com.squareup.okhttp.Callback");
            if (callback != null) {
                XposedHelpers.findAndHookMethod(callCls, "enqueue", callback,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                String url = requestUrl(p.thisObject);
                                if (isBlocked(url)) {
                                    XposedBridge.log("[DownloadGuard] BLOCK OkHttp.enqueue " + url
                                            + " pkg=" + pkg);
                                    p.setThrowable(new IOException("DownloadGuard blocked"));
                                }
                            }
                        });
            }
        } catch (Throwable t) { logSkip("OkHttp.enqueue", t); }
    }

    private static String requestUrl(Object call) {
        try {
            Object req = XposedHelpers.callMethod(call, "request");
            Object url = req == null ? null : XposedHelpers.callMethod(req, "url");
            return url == null ? null : url.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------- HttpURLConnection
    private static void hookHttpUrlConnection(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        try {
            Class<?> huc = XposedHelpers.findClass(
                    "java.net.HttpURLConnection", lp.classLoader);
            XposedHelpers.findAndHookMethod(huc, "getInputStream", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object url = XposedHelpers.callMethod(p.thisObject, "getURL");
                        String s = url == null ? null : url.toString();
                        boolean blocked = isBlocked(s);

                        // URL 无后缀时，用 Content-Disposition 里的文件名兜底
                        if (!blocked) {
                            Object disp = XposedHelpers.callMethod(
                                    p.thisObject, "getHeaderField", "Content-Disposition");
                            if (disp != null) blocked = isBlocked(disp.toString());
                        }
                        if (!blocked) {
                            Object ct = XposedHelpers.callMethod(
                                    p.thisObject, "getHeaderField", "Content-Type");
                            // application/vnd.android.package-archive = APK MIME
                            if (ct != null) {
                                String c = ct.toString().toLowerCase();
                                blocked = c.contains("application/vnd.android.package-archive")
                                       || c.contains("application/zip")
                                       || c.contains("application/x-msdownload");
                            }
                        }

                        if (blocked) {
                            XposedBridge.log("[DownloadGuard] BLOCK HttpURLConnection " + s
                                    + " pkg=" + pkg);
                            p.setThrowable(new IOException("DownloadGuard blocked"));
                        }
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable t) { logSkip("HttpURLConnection", t); }
    }

    // ---------------------------------------------------------- 文件写入
    private static void hookFileWrite(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        // FileOutputStream(String, boolean) / (File, boolean)
        try {
            Class<?> fos = XposedHelpers.findClass("java.io.FileOutputStream", lp.classLoader);
            XposedHelpers.findAndHookConstructor(fos, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            String path = (String) p.args[0];
                            if (isBlocked(path)) {
                                XposedBridge.log("[DownloadGuard] BLOCK write file=" + path + " pkg=" + pkg);
                                p.setThrowable(new IOException("DownloadGuard blocked"));
                            }
                        }
                    });
            XposedHelpers.findAndHookConstructor(fos, File.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            File f = (File) p.args[0];
                            if (f != null && isBlocked(f.getPath())) {
                                XposedBridge.log("[DownloadGuard] BLOCK write file=" + f.getPath() + " pkg=" + pkg);
                                p.setThrowable(new IOException("DownloadGuard blocked"));
                            }
                        }
                    });
        } catch (Throwable t) { logSkip("FileOutputStream", t); }

        // java.nio.file.Files.write(Path, byte[], OpenOption...)
        try {
            Class<?> files = XposedHelpers.findClass("java.nio.file.Files", lp.classLoader);
            XposedHelpers.findAndHookMethod(files, "write",
                    java.nio.file.Path.class, byte[].class, java.nio.file.OpenOption[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            java.nio.file.Path path = (java.nio.file.Path) p.args[0];
                            if (path != null && isBlocked(path.toString())) {
                                XposedBridge.log("[DownloadGuard] BLOCK Files.write=" + path + " pkg=" + pkg);
                                p.setThrowable(new IOException("DownloadGuard blocked"));
                            }
                        }
                    });
        } catch (Throwable t) { logSkip("Files.write", t); }

        // RandomAccessFile
        try {
            Class<?> raf = XposedHelpers.findClass("java.io.RandomAccessFile", lp.classLoader);
            XposedHelpers.findAndHookConstructor(raf, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            String path = (String) p.args[0];
                            if (isBlocked(path)) {
                                XposedBridge.log("[DownloadGuard] BLOCK RandomAccessFile=" + path + " pkg=" + pkg);
                                p.setThrowable(new IOException("DownloadGuard blocked"));
                            }
                        }
                    });
        } catch (Throwable t) { logSkip("RandomAccessFile", t); }
    }

    // ---------------------------------------------------------- WebView
    private static void hookWebView(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        try {
            Class<?> dcp = findClassAny(lp.classLoader,
                    "android.webkit.DownloadListener", "com.android.webview.chromium.DownloadDelegate");
            if (dcp == null) return;
            XposedHelpers.findAndHookMethod(dcp, "onDownloadStart",
                    String.class, String.class, String.class, String.class, long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            String url = (String) p.args[0];
                            if (isBlocked(url)) {
                                XposedBridge.log("[DownloadGuard] BLOCK WebView download=" + url + " pkg=" + pkg);
                                // void 方法无法用 setResult 阻断，抛异常中断回调
                                p.setThrowable(new RuntimeException("DownloadGuard blocked"));
                            }
                        }
                    });
        } catch (Throwable t) { logSkip("WebView", t); }
    }

    // ---------------------------------------------------------- 工具
    private static Class<?> findClassAny(ClassLoader cl, String... names) {
        for (String n : names) {
            try { return XposedHelpers.findClass(n, cl); } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void block(XC_MethodHook.MethodHookParam p, String hook, String target, String pkg) {
        XposedBridge.log("[DownloadGuard] BLOCK " + hook + " " + target + " pkg=" + pkg);
        p.setResult(-1L);
    }

    private static void logSkip(String hook, Throwable t) {
        XposedBridge.log("[DownloadGuard] " + hook + " hook skipped: " + t.getMessage());
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
