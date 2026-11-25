package com.spoof.downloadguard;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 只做两件事，在 Instrumentation.execStartActivity（所有 startActivity 的底层汇聚点）
 * 静默取消跳转，调用方零感知、不抛异常、不闪退：
 *   1. App 跳浏览器打开网页（ACTION_VIEW + http/https）
 *   2. App 唤起安装（触发系统「打开方式选择」弹窗的 Intent）
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
        hookUptime(lpparam, pkg);
        LogUtil.log("hooks installed for " + pkg);
    }

    // ---------------------------------------------------------------- 开机时间伪装
    // 进程级随机偏移：每次 App 进程启动重新随机 24~72 小时，
    // 同进程内固定（App 内部多次读取保持一致，避免时间倒流出错乱）。
    private static final java.util.concurrent.atomic.AtomicLong S_OFFSET_MS =
            new java.util.concurrent.atomic.AtomicLong(-1);

    private static long offsetMs() {
        long v = S_OFFSET_MS.get();
        if (v < 0) {
            // 24h = 86_400_000ms，随机 24~72h + 秒级抖动
            long secs = 86400L + (long) (Math.random() * (259200L - 86400L));
            long nv = secs * 1000L + (long) (Math.random() * 60000L);
            S_OFFSET_MS.compareAndSet(v, nv);
            v = nv;
        }
        return v;
    }

    private static void hookUptime(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        // hook SystemClock.elapsedRealtime / uptimeMillis / elapsedRealtimeNanos，
        // 返回值加随机偏移（Nanos 方法按纳秒单位加）。
        XC_MethodHook msHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Object r = p.getResult();
                if (!(r instanceof Long)) return;
                p.setResult((Long) r + offsetMs());
            }
        };
        XC_MethodHook nsHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Object r = p.getResult();
                if (!(r instanceof Long)) return;
                p.setResult((Long) r + offsetMs() * 1_000_000L);
            }
        };
        try {
            Class<?> sc = XposedHelpers.findClass("android.os.SystemClock", lp.classLoader);
            XposedHelpers.findAndHookMethod(sc, "elapsedRealtime", msHook);
            XposedHelpers.findAndHookMethod(sc, "uptimeMillis", msHook);
            XposedHelpers.findAndHookMethod(sc, "elapsedRealtimeNanos", nsHook);
        } catch (Throwable t) {
            LogUtil.log("uptime hook skipped: " + t.getMessage());
        }
    }

    private static void hookStartActivity(XC_LoadPackage.LoadPackageParam lp, String pkg) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null) return;
                for (int i = 0; i < p.args.length; i++) {
                    if (!(p.args[i] instanceof android.content.Intent)) continue;
                    android.content.Intent it = (android.content.Intent) p.args[i];
                    if (it == null) continue;

                    String data = it.getDataString();
                    String action = it.getAction();
                    boolean isView = "android.intent.action.VIEW".equals(action);
                    boolean isInstallAction =
                            "android.intent.action.INSTALL_PACKAGE".equals(action)
                         || "android.intent.action.VIEW_DOWNLOADS".equals(action)
                         || "android.intent.action.ACTION_INSTALL_PACKAGE".equals(action);

                    // 2. 安装唤起（会弹系统「打开方式选择」）→ 整个跳转静默取消
                    if (isInstallAction || isApkIntent(it)) {
                        LogUtil.log("BLOCK install/open-with act=" + action
                                + " data=" + data + " type=" + it.getType() + " pkg=" + pkg);
                        p.setResult(null);   // execStartActivity 返回 null = 无结果，静默取消
                        return;
                    }

                    // 1. 跳浏览器打开网页 → 整个跳转静默取消
                    if (isView && isHttpUrl(data)) {
                        LogUtil.log("BLOCK browser view url=" + data + " pkg=" + pkg);
                        p.setResult(null);
                        return;
                    }
                }
            }
        };
        try {
            // 拦 Instrumentation.execStartActivity：所有 startActivity 的底层汇聚点。
            // 命中时返回 null（合法返回值，表示无结果），调用方零感知、不崩溃。
            // 在这一层拦，系统不会再解析 Intent，也就不会出现
            // 「about: 无组件可处理」导致的 ActivityNotFoundException 闪退。
            Class<?> instr = XposedHelpers.findClass("android.app.Instrumentation", lp.classLoader);
            for (java.lang.reflect.Method m : instr.getDeclaredMethods()) {
                if (!"execStartActivity".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, hook);
            }
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
