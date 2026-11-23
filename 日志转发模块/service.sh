#!/system/bin/sh
# 把 logcat 里的 DownloadGuard 日志导出到 App 外部私有目录（重启后保留）
# App 自己可直接读写该目录，无需任何权限
APPDIR=/storage/emulated/0/Android/data/com.spoof.downloadguard/files
LOGFILE=$APPDIR/downloadguard.log

mkdir -p $APPDIR
touch $LOGFILE
chmod 666 $LOGFILE

# 兼容旧路径：把旧日志迁移过来一次
OLDLOG=/data/local/tmp/DownloadGuard/log.txt
if [ -f "$OLDLOG" ] && [ ! -s "$LOGFILE" ]; then
  cat "$OLDLOG" >> "$LOGFILE"
  chmod 666 $LOGFILE
fi

# 先把 logcat 缓冲区里已有的拦截记录导出
logcat -d -b main | awk '/\[DownloadGuard\]/{ sub(/.*\[DownloadGuard\] /, ""); print }' >> $LOGFILE
chmod 666 $LOGFILE

# 再持续跟踪新日志（awk 的 fflush 保证行缓冲）
logcat -b main | awk '/\[DownloadGuard\]/{ sub(/.*\[DownloadGuard\] /, ""); print; fflush() }' | while IFS= read -r msg; do
  echo "[$(date '+%m-%d %H:%M:%S')] $msg" >> $LOGFILE
  chmod 666 $LOGFILE 2>/dev/null
done
