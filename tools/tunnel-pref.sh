#!/usr/bin/env bash
# 读写「内嵌隧道」的 SharedPreferences，用于真机/模拟器上的手工与对照测试。
#
# 为什么需要它：偏好文件只在界面第一次写入时才存在，直接 sed 会静默失败——
# 实测踩过一次：脚本以为打开了隧道，其实什么都没改，整轮对照作废。
# 注意：run-as 只对 debuggable 包有效；release 包用这个脚本会失败（那就走界面）。
#
# 用法：tools/tunnel-pref.sh <设备序列号> <包名> [get|on|off]
set -euo pipefail
SER=${1:?设备序列号}; PKG=${2:?包名}; ACT=${3:-get}
FILE="shared_prefs/tailscale_tunnel.xml"

if [ "$ACT" = "get" ]; then
  out=$(adb -s "$SER" shell "run-as $PKG cat $FILE" 2>/dev/null | tr -d '\r' || true)
  if [ -z "$out" ]; then echo "（没有偏好文件：这个实例从没改过隧道设置）"; else echo "$out"; fi
  exit 0
fi

VAL=true; [ "$ACT" = "off" ] && VAL=false
TMP="/tmp/tunnel-pref.xml"
printf '%s' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map version=\"1\"><boolean name=\"tunnel_enabled\" value=\"${VAL}\" /></map>" > "$TMP"
adb -s "$SER" push "$TMP" /data/local/tmp/tunnel-pref.xml >/dev/null
adb -s "$SER" shell am force-stop "$PKG"
adb -s "$SER" shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/tunnel-pref.xml ${FILE}'"
echo "已设为 tunnel_enabled=${VAL}；下次进入前台会据此自动拉起"
