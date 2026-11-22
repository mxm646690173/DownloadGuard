#!/system/bin/sh
# 把 logcat 中 LSPosedFramework 的 DownloadGuard 行导出到共享文件
LOGDIR=/data/local/tmp/DownloadGuard
LOGFILE=$LOGDIR/log.txt
mkdir -p $LOGDIR
chmod 777 $LOGDIR
touch $LOGFILE
chmod 666 $LOGFILE

# 持续跟踪 logcat，只保留 DownloadGuard 相关行
logcat -b main -v brief | grep --line-buffered '\[DownloadGuard\]' | while read line; do
  # 只取 [DownloadGuard] 之后的内容，加时间
  msg=$(echo "$line" | sed 's/.*\[DownloadGuard\] //')
  echo "[$(date '+%m-%d %H:%M:%S')] $msg" >> $LOGFILE
  chmod 666 $LOGFILE 2>/dev/null
done
