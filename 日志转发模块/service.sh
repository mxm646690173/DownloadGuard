#!/system/bin/sh
# 把 logcat 里的 DownloadGuard 日志导出到共享文件，供模块 App 界面显示
LOGDIR=/data/local/tmp/DownloadGuard
LOGFILE=$LOGDIR/log.txt
mkdir -p $LOGDIR
chmod 777 $LOGDIR
touch $LOGFILE
chmod 666 $LOGFILE

# 先把 logcat 缓冲区里已有的拦截记录导出
logcat -d -b main | awk '/\[DownloadGuard\]/{ sub(/.*\[DownloadGuard\] /, ""); print }' >> $LOGFILE
chmod 666 $LOGFILE

# 再持续跟踪新日志（awk 的 fflush 保证行缓冲）
logcat -b main | awk '/\[DownloadGuard\]/{ sub(/.*\[DownloadGuard\] /, ""); print; fflush() }' | while IFS= read -r msg; do
  echo "[$(date '+%m-%d %H:%M:%S')] $msg" >> $LOGFILE
  chmod 666 $LOGFILE 2>/dev/null
done
