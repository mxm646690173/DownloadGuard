package com.spoof.downloadguard;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 只做两件事，全部静默改写，不抛异常：
 *   1. App 跳浏览器打开网页（ACTION_VIEW + http/https）→ 改写为 about:blank
 *   2. App 唤起安装（触发系统「打开方式选择」弹窗的 Intent）→ 改写为 about:blank
 *
 * 下载、文件写入、网络请求等一律放行，不拦截。
 */
public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        // 跳过模块自身、LSPosed 管理器与系统进程
        if (pkg == null || pkg.equals("com.spoof.downloadguard")
                || pkg.equals("org.lsposed.manager")
                || pkg.equals("android")
                || pkg.startsWith("com.android.")
                || pkg.startsWith("com.google.")) return;

        hookStartActivity(lpparam, pkg);
        hookWebViewLoad(lpparam, pkg);
        LogUtil.log("hooks installed for " + pkg);
    }

    /**
     * 无论 WebView 收到什么 http(s) 地址，一律强制加载空白页。
     * 浏览器的一切网页渲染都走 WebView，广告页在源头就被换成空白。
     */
    private static void hookWebViewLoad(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        try {
            Class<?> wv = XposedHelpers.findClass("android.webkit.WebView", lp.classLoader);
            XC_MethodHook blankHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object urlArg = p.args != null && p.args.length > 0 ? p.args[0] : null;
                    String url = urlArg == null ? null : urlArg.toString();
                    if (isHttpUrl(url)) {
                        LogUtil.log("BLOCK webview load url=" + url + " pkg=" + pkg);
                        p.args[0] = "about:blank";
                        if (p.args.length > 1 && p.args[1] instanceof java.util.Map) {
                            p.args[1] = null;   // 清掉附加请求头
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(wv, "loadUrl", String.class, blankHook);
            XposedHelpers.findAndHookMethod(wv, "loadUrl", String.class, java.util.Map.class, blankHook);
            XposedHelpers.findAndHookMethod(wv, "postUrl", String.class, byte[].class, blankHook);
        } catch (Throwable t) {
            LogUtil.log("webview hook skipped: " + t.getMessage());
        }
    }

    private static void hookStartActivity(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null) return;
                for (Object arg : p.args) {
                    if (!(arg instanceof android.content.Intent)) continue;
                    android.content.Intent it = (android.content.Intent) arg;
                    if (it == null) continue;

                    String data = it.getDataString();
                    String action = it.getAction();
                    boolean isView = "android.intent.action.VIEW".equals(action);
                    boolean isInstallAction =
                            "android.intent.action.INSTALL_PACKAGE".equals(action)
                         || "android.intent.action.VIEW_DOWNLOADS".equals(action)
                         || "android.intent.action.ACTION_INSTALL_PACKAGE".equals(action);

                    // 2. 安装唤起（会弹系统「打开方式选择」）→ 改写 about:blank
                    if (isInstallAction || isApkIntent(it)) {
                        LogUtil.log("BLOCK install/open-with act=" + action
                                + " data=" + data + " type=" + it.getType() + " pkg=" + pkg);
                        it.setData(android.net.Uri.parse("about:blank"));
                        it.setType(null);
                        return;
                    }

                    // 1. 跳浏览器打开网页 → 改写 about:blank
                    if (isView && isHttpUrl(data)) {
                        LogUtil.log("BLOCK browser view url=" + data + " pkg=" + pkg);
                        it.setData(android.net.Uri.parse("about:blank"));
                        return;
                    }
                }
            }
        };
        try {
            Class<?> ctx = XposedHelpers.findClass("android.content.Context", lp.classLoader);
            Class<?> act = XposedHelpers.findClass("android.app.Activity", lp.classLoader);
            XposedHelpers.findAndHookMethod(ctx, "startActivity",
                    android.content.Intent.class, hook);
            XposedHelpers.findAndHookMethod(ctx, "startActivity",
                    android.content.Intent.class, android.os.Bundle.class, hook);
            XposedHelpers.findAndHookMethod(act, "startActivityForResult",
                    android.content.Intent.class, int.class, hook);
            XposedHelpers.findAndHookMethod(act, "startActivityForResult",
                    android.content.Intent.class, int.class, android.os.Bundle.class, hook);
        } catch (Throwable t) {
            LogUtil.log("startActivity hook skipped: " + t.getMessage());
        }
    }

    /** data 带 .apk 等后缀，或 MIME 声明为安装包 */
    private static boolean isApkIntent(android.content.Intent it) {
        String type = it.getType();
        if (type != null) {
            String t = type.toLowerCase();
            if (t.contains("application/vnd.android.package-archive")
                    || t.contains("application/zip")
                    || t.contains("application/x-zip-compressed")
                    || t.contains("application/x-msdownload")) return true;
        }
        return isHttpApkUrl(it.getDataString());
    }

    private static boolean isHttpApkUrl(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")
                && !lower.startsWith("content://") && !lower.startsWith("file://")) return false;
        int cut = lower.indexOf('?');
        if (cut < 0) cut = lower.length();
        int h = lower.indexOf('#');
        if (h >= 0 && h < cut) cut = h;
        String path = lower.substring(0, cut);
        return path.endsWith(".apk") || path.endsWith(".apks") || path.endsWith(".xapk");
    }

    private static boolean isHttpUrl(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
