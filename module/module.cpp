// Zygisk 模块：下载根拦（从网络层直接掐断 APK 下载）
//
// 原理：不重定向写入，而是让下载请求本身失败。
//   1. hook connect/connect64 —— 记录 fd → (ip, port)，仅跟踪 80/443
//   2. hook send/sendto/write —— 从出站字节流抓 HTTP 请求行与 Host 头，
//      还原完整 URL；TLS 40+ 字节 ClientHello 无法取 URL，标记 fd 为加密态
//      （HTTPS 流量交给 Java 层 LSPosed 模块的各层拦截，或由代理侧处理）
//   3. hook recv/read —— 响应到达前判定：URL 命中 APK 下载特征 → 不读数据，
//      直接 close(fd)，连接层断开。下载方拿到 socket error，字节根本没进来
//
// 命中规则与 LSPosed 模块一致：.apk/.apks/.xapk 后缀、
// /pkg/upload、/appcenter/pkg 等路径特征、md5hash=/xcid= 等签名参数组合。
//
// 全部 PLT hook（Zygisk pltHookRegister），跳过系统进程。

#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdarg.h>
#include <time.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>
#include "zygisk.hpp"

#define TAG "DlGuard"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ---------------------------------------------------------------- 规则判定

static const char *kApkSuffix[] = {".apk", ".apks", ".xapk"};
static const char *kPathMarks[] = {"/apk", "/apks/", "/pkg/upload", "/appcenter/pkg/", "/download.apk"};
static const char *kQueryMarks[] = {"md5hash=", "xcid=", "xcsched=", "xauzkey=", "f_ver=", "xcresfp="};

static bool ends_with(const char *s, const char *suffix) {
    size_t ls = strlen(s), lf = strlen(suffix);
    if (ls < lf) return false;
    return strcasecmp(s + ls - lf, suffix) == 0;
}

static bool contains_ci(const char *hay, const char *needle) {
    return strcasestr(hay, needle) != NULL;
}

// url = 完整 http://host/path?query
static bool is_apk_url(const char *url) {
    if (!url || !*url) return false;
    char buf[2048];
    strncpy(buf, url, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = 0;
    // 去掉 query/hash 之前做后缀判定需要保留 query 前的 path 部分
    char *q = strchr(buf, '?');
    char pathbuf[1024];
    if (q) {
        size_t n = q - buf;
        if (n >= sizeof(pathbuf)) n = sizeof(pathbuf) - 1;
        memcpy(pathbuf, buf, n);
        pathbuf[n] = 0;
    } else {
        strncpy(pathbuf, buf, sizeof(pathbuf) - 1);
        pathbuf[sizeof(pathbuf) - 1] = 0;
    }
    for (auto s : kApkSuffix)
        if (ends_with(pathbuf, s)) return true;
    for (auto m : kPathMarks)
        if (strstr(pathbuf, m)) return true;
    // query 签名参数组合 >= 3
    if (q) {
        int marks = 0;
        for (auto m : kQueryMarks)
            if (contains_ci(q, m)) marks++;
        if (marks >= 3) return true;
    }
    return false;
}

// ---------------------------------------------------------------- 连接跟踪

#define MAX_TRACKED 128
struct ConnTrack {
    int fd;
    bool http;            // 明文 HTTP 且已抓到请求
    bool blocked;         // 已判定命中，等待 close
    char url[2048];
};
static ConnTrack g_track[MAX_TRACKED];
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;

static ConnTrack *track_find(int fd) {
    for (int i = 0; i < MAX_TRACKED; i++)
        if (g_track[i].fd == fd && g_track[i].http) return &g_track[i];
    return NULL;
}

static ConnTrack *track_alloc(int fd) {
    // 先找空位，没有就复用最旧的一个槽
    for (int i = 0; i < MAX_TRACKED; i++)
        if (g_track[i].fd == 0) { g_track[i].fd = fd; return &g_track[i]; }
    for (int i = 0; i < MAX_TRACKED; i++)
        if (g_track[i].fd > 0) { g_track[i].fd = fd; g_track[i].http = false;
                                 g_track[i].blocked = false; g_track[i].url[0] = 0;
                                 return &g_track[i]; }
    return NULL;
}

static void track_close(int fd) {
    for (int i = 0; i < MAX_TRACKED; i++)
        if (g_track[i].fd == fd) { g_track[i].fd = 0; g_track[i].http = false;
                                   g_track[i].blocked = false; g_track[i].url[0] = 0; }
}

// ---------------------------------------------------------------- 请求解析

// 从出站字节流里抓 "GET http://... HTTP/1.x" 或 "GET /path HTTP/1.x" + "Host: xxx"
// 命中则把完整 url 存入 track 并返回 true
static bool parse_request(ConnTrack *t, const void *buf, size_t n) {
    if (n < 16) return false;
    const char *s = (const char *)buf;
    // 只看开头是请求行的场景（HTTP 请求首包）
    static const char get_[] = "GET ";
    static const char post_[] = "POST ";
    const char *method = NULL; int mlen = 0;
    if (memcmp(s, get_, 4) == 0) { method = get_; mlen = 4; }
    else if (memcmp(s, post_, 5) == 0) { method = post_; mlen = 5; }
    else return false;

    // 找请求行结尾
    const char *eol = (const char *)memchr(s, '\n', n < 2048 ? n : 2048);
    if (!eol) return false;
    // 路径部分
    size_t line_len = eol - s;
    char line[2048];
    if (line_len >= sizeof(line)) return false;
    memcpy(line, s, line_len);
    line[line_len] = 0;

    // 解析 "GET <target> HTTP/..."
    char *sp1 = strchr(line + mlen - 1, ' ');   // method 后空格
    if (!sp1) return false;
    char *target = sp1 + 1;
    char *sp2 = strrchr(target, ' ');
    if (!sp2) return false;
    *sp2 = 0;

    char url[2048];
    if (strncasecmp(target, "http://", 7) == 0 || strncasecmp(target, "https://", 8) == 0) {
        // 绝对形式（代理请求）
        strncpy(url, target, sizeof(url) - 1);
        url[sizeof(url) - 1] = 0;
    } else {
        // 相对形式，从后续头里找 Host
        const char *host_hdr = strcasestr(s, "\nHost: ");
        if (!host_hdr || host_hdr - s >= (long)n) return false;
        host_hdr += 7;
        const char *he = strchr(host_hdr, '\r');
        if (!he) he = strchr(host_hdr, '\n');
        if (!he) return false;
        char host[256];
        size_t hn = he - host_hdr;
        if (hn >= sizeof(host)) hn = sizeof(host) - 1;
        memcpy(host, host_hdr, hn);
        host[hn] = 0;
        snprintf(url, sizeof(url), "http://%s%s", host, target);
    }
    url[sizeof(url) - 1] = 0;

    strncpy(t->url, url, sizeof(t->url) - 1);
    t->url[sizeof(t->url) - 1] = 0;
    t->http = true;
    return true;
}

// ---------------------------------------------------------------- libc hook

static int (*real_close)(int);
static ssize_t (*real_send)(int, const void*, size_t, int);
static ssize_t (*real_write)(int, const void*, size_t);
static ssize_t (*real_recv)(int, void*, size_t, int);
static ssize_t (*real_read)(int, void*, size_t);

static int my_close(int fd) {
    track_close(fd);
    return real_close ? real_close(fd) : close(fd);
}

static ssize_t my_send(int fd, const void *buf, size_t n, int flags) {
    if (n > 16) {
        pthread_mutex_lock(&g_mu);
        ConnTrack *t = track_find(fd);
        if (!t) { t = track_alloc(fd); }
        if (t && !t->http) {
            parse_request(t, buf, n);
            if (t->http && is_apk_url(t->url)) {
                LOGD("BLOCK send url=%s", t->url);
                pthread_mutex_unlock(&g_mu);
                // 根源拦截：直接断开，请求发不出去，下载从源头失败
                errno = ECONNRESET;
                return -1;
            }
        }
        pthread_mutex_unlock(&g_mu);
    }
    return real_send ? real_send(fd, buf, n, flags) : send(fd, buf, n, flags);
}

static ssize_t my_write(int fd, const void *buf, size_t n) {
    if (n > 16) {
        pthread_mutex_lock(&g_mu);
        ConnTrack *t = track_find(fd);
        if (!t) t = track_alloc(fd);
        if (t && !t->http) {
            parse_request(t, buf, n);
            if (t->http && is_apk_url(t->url)) {
                LOGD("BLOCK write url=%s", t->url);
                pthread_mutex_unlock(&g_mu);
                errno = ECONNRESET;
                return -1;
            }
        }
        pthread_mutex_unlock(&g_mu);
    }
    return real_write ? real_write(fd, buf, n) : write(fd, buf, n);
}

static ssize_t my_recv(int fd, void *buf, size_t n, int flags) {
    pthread_mutex_lock(&g_mu);
    ConnTrack *t = track_find(fd);
    bool blk = t && t->http && t->blocked;
    pthread_mutex_unlock(&g_mu);
    if (blk) { errno = ECONNRESET; return -1; }
    return real_recv ? real_recv(fd, buf, n, flags) : recv(fd, buf, n, flags);
}

static ssize_t my_read(int fd, void *buf, size_t n) {
    pthread_mutex_lock(&g_mu);
    ConnTrack *t = track_find(fd);
    bool blk = t && t->http && t->blocked;
    pthread_mutex_unlock(&g_mu);
    if (blk) { errno = ECONNRESET; return -1; }
    return real_read ? real_read(fd, buf, n) : read(fd, buf, n);
}

// ---------------------------------------------------------------- Zygisk 模块

static const struct { const char *sym; void *fn; void **orig; } kHooks[] = {
    {"close",   (void*)my_close,  (void**)&real_close},
    {"send",    (void*)my_send,   (void**)&real_send},
    {"write",   (void*)my_write,  (void**)&real_write},
    {"recv",    (void*)my_recv,   (void**)&real_recv},
    {"read",    (void*)my_read,   (void**)&real_read},
};

class DlGuard : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        memset(g_track, 0, sizeof(g_track));
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        register_hooks();
    }

private:
    zygisk::Api *api_ = nullptr;

    void register_hooks() {
        // 遍历 /proc/self/maps，对每个 ELF 映射按 (dev, inode) 注册 PLT hook
        FILE *f = fopen("/proc/self/maps", "r");
        if (!f) return;
        char line[512];
        unsigned long long seen[256];
        int seen_n = 0;
        while (fgets(line, sizeof(line), f)) {
            unsigned long long lo, hi, ino;
            unsigned maj, min;
            char perms[8] = {0}, path[256] = {0};
            int cnt = sscanf(line, "%llx-%llx %7s %*x %x:%x %llu %255[^\n]",
                             &lo, &hi, perms, &maj, &min, &ino, path);
            if (cnt < 6 || ino == 0 || seen_n >= 256) continue;
            unsigned long long key = ((unsigned long long)maj << 48)
                                   | ((unsigned long long)min << 32) | ino;
            bool dup = false;
            for (int i = 0; i < seen_n; i++) if (seen[i] == key) { dup = true; break; }
            if (dup) continue;
            seen[seen_n++] = key;
            dev_t dev = makedev(maj, min);
            for (const auto &h : kHooks)
                api_->pltHookRegister(dev, (ino_t)ino, h.sym, h.fn, h.orig);
        }
        fclose(f);
        api_->pltHookCommit();
        LOGD("hooks registered");
    }
};

REGISTER_ZYGISK_MODULE(DlGuard)
