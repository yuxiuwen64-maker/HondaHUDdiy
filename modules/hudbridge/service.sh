#!/system/bin/sh
# 开机后导出一份车机属性清单，并把 HUD 应用拉起来
PKG=io.github.hudfree
ACT=$PKG/.MainActivity
PROPS=/data/local/tmp/car_props.txt
LOG=/data/adb/hudbridge_free.log

log() { echo "$(date '+%F %T') $*" >> "$LOG" 2>/dev/null; }

# 单实例：这台 Magisk 会把 service.sh 启动两次
for p in $(pgrep -f "hudbridge_free/service.sh" 2>/dev/null); do
  [ "$p" != "$$" ] && kill -9 "$p" 2>/dev/null
done

i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 150 ]; do
  sleep 2
  i=$((i + 1))
done
sleep 15

# 把车机声明的属性 ID 全导出来，App 的「扫描属性」会读这个文件
dumpsys car_service 2>/dev/null | grep -o 'Property:0x[0-9a-fA-F]*' | sed 's/Property://' | sort -u > "$PROPS"
log "props exported: $(wc -l < $PROPS 2>/dev/null)"

# 同步保存车机 MeterService 的协议日志，便于判断数据到底有没有被接收
# /data/adb/hud_meter.log（超过 2MB 自动轮转一份 .1）
logcat -b main -v threadtime \
  -s NAVI_FUNCTION_LOG:V MAPINMETER_FUNCTION_LOG:V MeterServiceBinder:V \
  -r 2048 -n 2 -f /data/adb/hud_meter.log >/dev/null 2>&1 &
log "meter logcat started"

am start -n "$ACT" >/dev/null 2>&1
log "activity started"

# 如果用户在界面里勾了「开机自动运行」，直接拉起前台服务。
# BootReceiver 在部分车机上会被后台限制，这里用 root shell 兜底。
PREF=/data/data/$PKG/shared_prefs/hud.xml
if [ -f "$PREF" ] && grep -q 'name="enabled" value="true"' "$PREF"; then
  am start-foreground-service -n "$PKG/.HudService" >/dev/null 2>&1
  log "hud service started"
fi
