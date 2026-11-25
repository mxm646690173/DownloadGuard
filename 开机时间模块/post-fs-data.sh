#!/system/bin/sh
# 每次开机生成随机偏移(秒),写入系统属性
# 24h=86400s, 72h=259200s
H=$((24 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 49))
M=$(( $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 60 ))
S=$(( $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 60 ))
OFFSET=$((H * 3600 + M * 60 + S))
resetprop persist.spoof.uptime_offset "$OFFSET"
