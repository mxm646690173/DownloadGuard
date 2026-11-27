# DlGuard — Zygisk 下载根拦

## 原理
不重定向写入，**从网络层直接掐断下载**：

1. PLT hook `send`/`write`——从出站字节流抓 HTTP 请求行 + Host 头，还原完整 URL
2. URL 命中 APK 下载特征 → `errno=ECONNRESET` 返回 -1——**请求根本发不出去**，
   下载方拿到连接错误，字节流从未到达，写入/落盘/安装链路全部不会发生
3. hook `recv`/`read`——已判定命中的连接后续读取同样返回 ECONNRESET
4. hook `close`——清理连接跟踪

命中规则与 LSPosed 模块一致：`.apk/.apks/.xapk` 后缀、`/pkg/upload`、
`/appcenter/pkg` 等路径特征、`md5hash=`/`xcid=`/`xcsched=`/`xauzkey=`/`f_ver=`/`xcresfp=`
签名参数组合（≥3）。

## 覆盖范围
- **明文 HTTP**：完整覆盖（请求行可读）
- **HTTPS**：出站字节是 TLS 加密帧，无法在 PLT 层取 URL——这部分由
  LSPosed 模块（v4.3+）的 Java 层各层兜底（DownloadManager / WebView 回调 /
  文件写入）。两层互补。
- 所有走 socket 的下载（OkHttp/HttpURLConnection/浏览器自研下载器/curl），
  只要是明文 HTTP 或可被 Java 层看到的，都会被覆盖

## 环境要求
Magisk/KernelSU + Zygisk（Zygisk Next 或内置均可），**不需要 LSPosed**。
与 LSPosed 版 DownloadGuard 同时使用效果最佳。

## 编译
推到 GitHub，Actions 出四 ABI so 并打包 `DlGuard-Zygisk.zip` artifact。

## 验证
```bash
adb logcat -s DlGuard
# 期望: BLOCK send url=http://... pkg=... / hooks registered
```

## 已知边界
- HTTPS 明文层取不到 URL（TLS 加密），依赖 Java 层兜底
- HTTP/2 多路复用的帧格式与 HTTP/1.1 请求行不同，明文 h2c 场景抓不到
  （浏览器/App 对普通下载几乎都用 HTTP/1.1，风险很低）
- 直接 raw socket + 自写 TLS 的下载无法识别
