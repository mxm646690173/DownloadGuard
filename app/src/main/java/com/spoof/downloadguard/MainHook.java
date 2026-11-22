package com.spoof.downloadguard;

import android.app.DownloadManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 全面拦截静默下载：
 *   1. DownloadManager.enqueue / addCompletedDownload（系统下载）
 *   2. OkHttp RealCall.execute() / enqueue()（App 自写下载）
 *   3. HttpURLConnection.getInputStream()（原生网络，含响应头兜底）
 *   4. FileOutputStream / Files.write / RandomAccessFile（写危险后缀文件）
 *   5. WebView.setDownloadListener（代理替换监听器，拦截网页下载）
 *
 * 命中规则直接取消，不弹窗、不放行。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String[] BLOCKED_EXT = {
        ".apk", ".apks", ".xapk", ".zip", ".rar", ".7z", ".exe", ".msi", ".scr", ".bat", ".jar"
    };

    // getHeaderField() 内部可能再次触发 getInputStream()，必须防重入
    private static final ThreadLocal<Boolean> tInHttpGetStream = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        // 只跳过模块自身与 LSPosed 管理器；作用域由 LSPosed 控制，不再按包名前缀过滤
        if (pkg == null || pkg.equals("com.spoof.downloadguard")
                || pkg.equals("org.lsposed.manager")) return;

        hookDownloadManager(lpparam, pkg);
        hookOkHttp(lpparam, pkg);
        hookHttpUrlConnection(lpparam, pkg);
        hookFileWrite(lpparam, pkg);
        hookWebView(lpparam, pkg);
        LogUtil.log("hooks installed for " + pkg);
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
                            // title, description, isMediaScannerScannable, mimeType, path, length, showNotification
                            String path = (String) p.args[4];
                            String mime = (String) p.args[3];
                            if (isBlocked(path) || isBlocked(mime)
                                    || isArchiveMime(mime)) {
                                block(p, "addCompletedDownload", path, pkg);
                            }
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
        // 只 hook 具体实现类 RealCall。hook okhttp3.Call 接口方法不会拦到实现类的调用。
        Class<?> callCls = findClassAny(lp.classLoader,
                "okhttp3.internal.connection.RealCall",
                "com.squareup.okhttp.internal.http.RealCall");
        if (callCls == null) return;

        try {
            XposedHelpers.findAndHookMethod(callCls, "execute", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    String url = requestUrl(p.thisObject);
                    if (isBlocked(url)) {
                        LogUtil.log("BLOCK OkHttp.execute " + url + " pkg=" + pkg);
                        p.setThrowable(new IOException("DownloadGuard blocked"));
                    }
                }
            });
        } catch (Throwable t) { logSkip("OkHttp.execute", t); }

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
                                    LogUtil.log("BLOCK OkHttp.enqueue " + url + " pkg=" + pkg);
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
            // getInputStream() 声明在 java.net.URLConnection，不在 HttpURLConnection 自身
            Class<?> uc = XposedHelpers.findClass("java.net.URLConnection", lp.classLoader);
            XposedHelpers.findAndHookMethod(uc, "getInputStream", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    // getHeaderField() 内部可能再调 getInputStream()，防重入
                    if (Boolean.TRUE.equals(tInHttpGetStream.get())) return;
                    tInHttpGetStream.set(true);
                    try {
                        Object url = XposedHelpers.callMethod(p.thisObject, "getURL");
                        String s = url == null ? null : url.toString();
                        boolean blocked = isBlocked(s);

                        // 响应头在原始方法执行后才有值，所以兜底检查必须放在 after
                        if (!blocked) {
                            Object disp = XposedHelpers.callMethod(
                                    p.thisObject, "getHeaderField", "Content-Disposition");
                            if (disp != null) blocked = isBlocked(disp.toString());
                        }
                        if (!blocked) {
                            Object ct = XposedHelpers.callMethod(
                                    p.thisObject, "getHeaderField", "Content-Type");
                            if (ct != null) blocked = isArchiveMime(ct.toString());
                        }

                        if (blocked) {
                            LogUtil.log("BLOCK HttpURLConnection " + s + " pkg=" + pkg);
                            Object result = p.getResult();
                            if (result instanceof InputStream) {
                                try { ((InputStream) result).close(); } catch (Throwable ignored) { }
                            }
                            p.setThrowable(new IOException("DownloadGuard blocked"));
                        }
                    } finally {
                        tInHttpGetStream.remove();
                    }
                }
            });
        } catch (Throwable t) { logSkip("HttpURLConnection", t); }
    }

    // ---------------------------------------------------------- 文件写入
    private static void hookFileWrite(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        try {
            Class<?> fos = XposedHelpers.findClass("java.io.FileOutputStream", lp.classLoader);
            XposedHelpers.findAndHookConstructor(fos, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            String path = (String) p.args[0];
                            if (isBlocked(path)) {
                                LogUtil.log("BLOCK write file=" + path + " pkg=" + pkg);
                                p.setThrowable(new IOException("DownloadGuard blocked"));
                            }
                        }
                    });
            XposedHelpers.findAndHookConstructor(fos, File.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            File f = (File) p.args[0];
                            if (f != null && isBlocked(f.getPath())) {
                                LogUtil.log("BLOCK write file=" + f.getPath() + " pkg=" + pkg);
                                p.setThrowable(new IOException("DownloadGuard blocked"));
                            }
                        }
                    });
        } catch (Throwable t) { logSkip("FileOutputStream", t); }

        try {
            Class<?> files = XposedHelpers.findClass("java.nio.file.Files", lp.classLoader);
            XposedHelpers.findAndHookMethod(files, "write",
                    java.nio.file.Path.class, byte[].class, java.nio.file.OpenOption[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            java.nio.file.Path path = (java.nio.file.Path) p.args[0];
                            if (path != null && isBlocked(path.toString())) {
                                LogUtil.log("BLOCK Files.write=" + path + " pkg=" + pkg);
                                p.setThrowable(new IOException("DownloadGuard blocked"));
                            }
                        }
                    });
        } catch (Throwable t) { logSkip("Files.write", t); }

        try {
            Class<?> raf = XposedHelpers.findClass("java.io.RandomAccessFile", lp.classLoader);
            XposedHelpers.findAndHookConstructor(raf, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            String path = (String) p.args[0];
                            if (isBlocked(path)) {
                                LogUtil.log("BLOCK RandomAccessFile=" + path + " pkg=" + pkg);
                                p.setThrowable(new IOException("DownloadGuard blocked"));
                            }
                        }
                    });
        } catch (Throwable t) { logSkip("RandomAccessFile", t); }
    }

    // ---------------------------------------------------------- WebView
    private static void hookWebView(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        try {
            final Class<?> dl = XposedHelpers.findClass("android.webkit.DownloadListener", lp.classLoader);
            Class<?> wv = XposedHelpers.findClass("android.webkit.WebView", lp.classLoader);
            final ClassLoader targetLoader = lp.classLoader;
            // DownloadListener 是接口，直接 hook 它的 onDownloadStart 不会拦到实现类。
            // 改为 hook WebView.setDownloadListener，用动态代理替换应用注册的监听器。
            XposedHelpers.findAndHookMethod(wv, "setDownloadListener", dl,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            final Object origin = p.args[0];
                            if (origin == null) return;
                            Object proxy = Proxy.newProxyInstance(
                                    targetLoader,
                                    new Class<?>[]{dl},
                                    (obj, method, args) -> {
                                        if ("onDownloadStart".equals(method.getName())
                                                && args != null && args.length > 0
                                                && isBlocked(String.valueOf(args[0]))) {
                                            LogUtil.log("BLOCK WebView download=" + args[0] + " pkg=" + pkg);
                                            return null;   // void 方法，吞掉回调
                                        }
                                        try {
                                            return method.invoke(origin, args);
                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                    });
                            p.args[0] = proxy;
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
        LogUtil.log("BLOCK " + hook + " " + target + " pkg=" + pkg);
        p.setResult(-1L);   // DownloadManager.INVALID_DOWNLOAD_ID
    }

    private static void logSkip(String hook, Throwable t) {
        LogUtil.log(hook + " hook skipped: " + t.getMessage());
    }

    private static boolean isArchiveMime(String s) {
        if (s == null) return false;
        String c = s.toLowerCase();
        return c.contains("application/vnd.android.package-archive")
            || c.contains("application/zip")
            || c.contains("application/x-zip-compressed")
            || c.contains("application/x-msdownload")
            || c.contains("application/x-rar-compressed")
            || c.contains("application/x-7z-compressed");
    }

    private static boolean isBlocked(String s) {
        if (s == null) return false;
        int cut = s.length();
        int q = s.indexOf('?');
        if (q >= 0) cut = q;
        int h = s.indexOf('#');
        if (h >= 0 && h < cut) cut = h;
        String path = s.substring(0, cut).toLowerCase();
        for (String ext : BLOCKED_EXT) {
            if (path.endsWith(ext)) return true;
        }
        return false;
    }
}
