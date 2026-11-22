SKIPUNZIP=1
ui_print "- DownloadGuard 日志转发模块"
unzip -o "$ZIPFILE" -x "META-INF/*" -d "$MODPATH" >/dev/null
set_perm_recursive $MODPATH 0 0 0755 0644
