# DownloadGuard — 拦截静默下载 LSPosed 模块

## 覆盖的下载链路（全部直接拦截，不弹窗、不放行）
1. `DownloadManager.enqueue()` / `addCompletedDownload()` —— 系统下载
2. `OkHttp` 的 `RealCall.execute()` / `enqueue(Callback)` —— App 自写下载
3. `HttpURLConnection.getInputStream()` —— 原生网络，含 Content-Disposition / MIME 兜底
4. `FileOutputStream` / `java.nio.file.Files.write` / `RandomAccessFile` —— 写危险后缀文件
5. `WebView DownloadListener.onDownloadStart()` —— 网页内下载

命中规则直接取消调用或抛 IOException，下载不会完成。

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
- 用 native 代码（curl/自写 socket）下载且不经 Java 文件 API 的场景拦不到
- Kotlin 协程 Flow 里直接读 `InputStream` 写入内存再解密的场景，写盘时才触发第 4 层拦截
- 后缀判断基于 URL/路径/响应头；完全无特征且写内存不落盘的"下载"无法定义，也不该拦
- 不需要 Xposed Legacy 框架，需要 LSPosed（Zygisk 版）
