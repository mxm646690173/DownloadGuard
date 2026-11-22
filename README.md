# DownloadGuard — 拦截静默下载 LSPosed 模块

## 作用
**直接拦截，不弹窗、不提示、不给放行选项。**

Hook `DownloadManager.enqueue()`：当下载 URL 命中危险后缀（`.apk` / `.apks` / `.xapk` /
`.zip` / `.rar` / `.7z` / `.exe` / `.msi` / `.scr` / `.bat` / `.js` / `.jar`）时，
直接取消调用并返回 `-1`（无效下载 id），文件不会被下载。

同时 hook `addCompletedDownload()`，拦住伪造"下载已完成"记录的调用。

## 安装
1. GitHub Actions 编译完成后下载 `DownloadGuard-APK`
2. 安装到模拟器，在 LSPosed 管理器里启用模块
3. 作用域勾选需要拦截的浏览器 / 应用（`xposedscope=*` 表示可选任意应用）
4. 重启

## 验证
```bash
adb logcat -s Xposed "*:E" | grep DownloadGuard
# 拦截时输出: [DownloadGuard] BLOCK <url> pkg=<包名>
```

## 编译
不本地编译。把本目录推到 GitHub 后，`.github/workflows/build.yml` 会：
1. 装 JDK 17 + Android SDK 34 + Gradle 8.7
2. `gradle assembleRelease`
3. 用 debug 密钥签名，上传 artifact `DownloadGuard-APK`

## 已知限制
- 只拦 `DownloadManager` 路径；如果 App 自己用 OkHttp 下载 APK 到私有目录，不经过系统
  DownloadManager，本模块拦不到（那属于 App 自带下载器，需要 hook OkHttp 或文件写入）
- 后缀判断基于 URL，如果 URL 无后缀但 Content-Disposition 给出 `.apk` 文件名，可能漏拦
- 需要 LSPosed（Zygisk 版）；不需要 Xposed Legacy 框架
