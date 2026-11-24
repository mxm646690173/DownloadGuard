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

    // 静默拦截用的无效地址：指向本地不存在端口，连接立即失败，
    // 浏览器视为"下载完成(失败)"，不弹任何提示
    private static final String SILENT_URL = "http://127.0.0.1:1/dl.silent";

    // 替换 Request 内部的 mUri（字段私有，反射改写；AOSP 各版本字段名一致）
    private static void replaceUri(DownloadManager.Request req, String newUrl) {
        try {
            XposedHelpers.setObjectField(req, "mUri", android.net.Uri.parse(newUrl));
        } catch (Throwable ignored) { }
    }

    // getHeaderField() 内部可能再次触发 getInputStream()，必须防重入
    private static final ThreadLocal<Boolean> tInHttpGetStream = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        // 只作用于普通 App 进程：跳过模块自身、系统进程（android=system_server、
        // com.android.*、com.google.*），避免 system_server 崩溃导致整机重启
        if (pkg == null || pkg.equals("com.spoof.downloadguard")
                || pkg.equals("org.lsposed.manager")
                || pkg.equals("android")
                || pkg.startsWith("com.android.")
                || pkg.startsWith("com.google.")) return;

        hookDownloadManager(lpparam, pkg);
        hookOkHttp(lpparam, pkg);
        hookHttpUrlConnection(lpparam, pkg);
        hookFileWrite(lpparam, pkg);
        hookWebView(lpparam, pkg);
        hookInstallIntent(lpparam, pkg);
        LogUtil.log("hooks installed for " + pkg);
    }

    // ---------------------------------------------------------- 安装 Intent
    /**
     * 拦截 APK 安装唤起：广告落地页下载完 APK 后通常用
     * Intent(type=application/vnd.android.package-archive 或 data=*.apk)
     * 发起安装，系统弹「使用软件包安装程序打开 / 仅此一次 / 始终」选择框。
     * 在 startActivity 阶段直接吞掉，弹窗就不会出现。
     */
    private static void hookInstallIntent(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        XC_MethodHook installHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                for (Object arg : p.args) {
                    if (!(arg instanceof android.content.Intent)) continue;
                    android.content.Intent it = (android.content.Intent) arg;
                    if (isInstallIntent(it)) {
                        LogUtil.log("BLOCK install intent act=" + it.getAction()
                                + " data=" + it.getDataString()
                                + " type=" + it.getType() + " pkg=" + pkg);
                        // 抛异常中断，避免 startActivity 继续执行
                        p.setThrowable(new SecurityException("DownloadGuard blocked"));
                        return;
                    }
                    // App 跳浏览器打开网页：全部改写为 about:blank。
                    // 浏览器正常打开一个空白页，无弹窗、无下载、无报错。
                    if (isBrowserViewIntent(it)) {
                        LogUtil.log("BLOCK browser view url=" + it.getDataString() + " pkg=" + pkg);
                        it.setData(android.net.Uri.parse("about:blank"));
                        return;
                    }
                }
            }
        };
        try {
            Class<?> ctx = XposedHelpers.findClass("android.content.Context", lp.classLoader);
            Class<?> act = XposedHelpers.findClass("android.app.Activity", lp.classLoader);
            // Context.startActivity(Intent) 与带 options 重载
            XposedHelpers.findAndHookMethod(ctx, "startActivity",
                    android.content.Intent.class, installHook);
            XposedHelpers.findAndHookMethod(ctx, "startActivity",
                    android.content.Intent.class, android.os.Bundle.class, installHook);
            // Activity.startActivityForResult（安装页常用它等结果）
            XposedHelpers.findAndHookMethod(act, "startActivityForResult",
                    android.content.Intent.class, int.class, installHook);
            XposedHelpers.findAndHookMethod(act, "startActivityForResult",
                    android.content.Intent.class, int.class, android.os.Bundle.class, installHook);
        } catch (Throwable t) { logSkip("InstallIntent", t); }
    }

    /** App 用系统浏览器打开 http(s) 页面的 Intent（含显式指定浏览器包名的跳转） */
    private static boolean isBrowserViewIntent(android.content.Intent it) {
        if (it == null) return false;
        if (!"android.intent.action.VIEW".equals(it.getAction())) return false;
        String data = it.getDataString();
        if (data == null) return false;
        String lower = data.toLowerCase();
        // 无显式包名的 VIEW http(s) 默认走浏览器，显式包名跳浏览器同理，全拦
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isInstallIntent(android.content.Intent it) {
        if (it == null) return false;
        String type = it.getType();
        if (type != null && isArchiveMime(type)) return true;

        String action = it.getAction();
        boolean installAction =
                "android.intent.action.VIEW".equals(action)
             || "android.intent.action.INSTALL_PACKAGE".equals(action)
             || "android.intent.action.VIEW_DOWNLOADS".equals(action);
        if (!installAction) return false;

        // data 带 .apk 后缀，或 type 声明了 archive MIME
        String data = it.getDataString();
        if (isBlocked(data)) return true;
        if (data != null && data.toLowerCase().contains("package-archive")) return true;
        return false;
    }

    // ---------------------------------------------------------- DownloadManager
    private static void hookDownloadManager(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        try {
            Class<?> dm = XposedHelpers.findClass("android.app.DownloadManager", lp.classLoader);
            XposedHelpers.findAndHookMethod(dm, "enqueue",
                    DownloadManager.Request.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            DownloadManager.Request req = (DownloadManager.Request) p.args[0];
                            String url = extractUrl(req);
                            if (isBlocked(url) || looksLikeApkDownload(url)) {
                                LogUtil.log("BLOCK DownloadManager.enqueue " + url + " pkg=" + pkg);
                                // 静默拦截：换成无效 URL，下载立刻失败完成，浏览器无提示
                                replaceUri(req, SILENT_URL);
                            }
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
                    if (isBlocked(url) || looksLikeApkDownload(url)) {
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
                                if (isBlocked(url) || looksLikeApkDownload(url)) {
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
                        boolean blocked = isBlocked(s) || looksLikeApkDownload(s);

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
    // 只拦"下载目录"里的危险后缀写入，避免误伤 App 内部缓存/配置
    // （很多 App 用 .zip/.jar 存缓存、游戏资源，全局拦会直接把 App 写崩）
    private static boolean isDownloadPath(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.contains("/download/") || lower.contains("/downloads/")
             || lower.contains("download_cache");
    }

    private static boolean shouldBlockWrite(String s) {
        return isDownloadPath(s) && isBlocked(s);
    }

    private static void hookFileWrite(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        try {
            Class<?> fos = XposedHelpers.findClass("java.io.FileOutputStream", lp.classLoader);
            XposedHelpers.findAndHookConstructor(fos, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            String path = (String) p.args[0];
                            if (shouldBlockWrite(path)) {
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
                            if (f != null && shouldBlockWrite(f.getPath())) {
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
                            if (path != null && shouldBlockWrite(path.toString())) {
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
                            if (shouldBlockWrite(path)) {
                                LogUtil.log("BLOCK RandomAccessFile=" + path + " pkg=" + pkg);
                                p.setThrowable(new IOException("DownloadGuard blocked"));
                            }
                        }
                    });
        } catch (Throwable t) { logSkip("RandomAccessFile", t); }

        // 第4条：拦"先写临时文件，下载完再改名成 .apk"的路径
        try {
            Class<?> fileCls = XposedHelpers.findClass("java.io.File", lp.classLoader);
            XposedHelpers.findAndHookMethod(fileCls, "renameTo", File.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            File dst = (File) p.args[0];
                            if (dst != null && shouldBlockWrite(dst.getPath())) {
                                LogUtil.log("BLOCK renameTo -> " + dst.getPath() + " pkg=" + pkg);
                                p.setResult(false);
                            }
                        }
                    });
        } catch (Throwable t) { logSkip("File.renameTo", t); }

        try {
            Class<?> files = XposedHelpers.findClass("java.nio.file.Files", lp.classLoader);
            XposedHelpers.findAndHookMethod(files, "move",
                    java.nio.file.Path.class, java.nio.file.Path.class,
                    java.nio.file.CopyOption[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            java.nio.file.Path dst = (java.nio.file.Path) p.args[1];
                            if (dst != null && shouldBlockWrite(dst.toString())) {
                                LogUtil.log("BLOCK Files.move -> " + dst + " pkg=" + pkg);
                                p.setThrowable(new IOException("DownloadGuard blocked"));
                            }
                        }
                    });
        } catch (Throwable t) { logSkip("Files.move", t); }
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
                                                && args != null && args.length >= 5) {
                                            String url = String.valueOf(args[0]);
                                            String disposition = String.valueOf(args[2]);
                                            String mime = String.valueOf(args[3]);
                                            // URL 无后缀时，靠 Content-Disposition 的文件名和 MIME 判定
                                            if (isBlocked(url) || isBlocked(disposition)
                                                    || isArchiveMime(mime)
                                                    || looksLikeApkDownload(url)) {
                                                LogUtil.log("BLOCK WebView download=" + url
                                                        + " disp=" + disposition + " mime=" + mime
                                                        + " pkg=" + pkg);
                                                // 静默拦截：把 URL 换成无效地址，
                                                // 浏览器会"正常完成"一个 0 字节下载，不弹提示
                                                args[0] = SILENT_URL;
                                                args[2] = null;
                                                args[3] = "application/octet-stream";
                                                args[4] = 0L;
                                            }
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

    /**
     * URL 无后缀但确实是 APK 下载的兜底识别。
     * 应用商店/广告落地页常见格式：/pkg/upload/... 带 md5hash/xcid/f_ver 等签名参数，
     * 或路径里含 apk/download 段。仅靠后缀会漏掉这类链接。
     */
    private static boolean looksLikeApkDownload(String s) {
        if (s == null) return false;
        String u = s.toLowerCase();

        // 路径段命中（只看 ? 之前，避免误伤普通页面的查询串）
        int cut = u.indexOf('?');
        if (cut < 0) cut = u.length();
        String path = u.substring(0, cut);
        // 路径段命中（只看 ? 之前，避免误伤普通页面的查询串）。
        // 注意：/download/、/package/ 太宽（App API 路径常见），会误杀正常请求，
        // 所以 URL 层只认带 .apk 的路径或强分发特征
        String[] pathMarks = {
            "/apk", "/apks/", "/pkg/upload", "/appcenter/pkg/", "/download.apk"
        };
        for (String mark : pathMarks) {
            if (path.contains(mark)) return true;
        }

        // 查询串里的 APK 分发签名参数组合（单个参数不足以判定，需组合）
        int marks = 0;
        String[] queryMarks = {"md5hash=", "xcid=", "xcsched=", "xauzkey=", "f_ver=", "xcresfp="};
        for (String mark : queryMarks) {
            if (u.contains(mark)) marks++;
        }
        return marks >= 3;
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
