#!/system/bin/sh
# Read-only monitoring. A fixed marker and a 30-minute bound stop this helper.
sample=0
while [ -f /data/local/tmp/disksize-phase5-monitor ] && [ "$sample" -lt 1800 ]; do
  stamp=$(cut -d' ' -f1 /proc/uptime)
  ps -A -o PID,UID,NAME | while read -r pid uid name; do
    case "$name" in
      *disksize*)
        rss=$(awk '/^VmRSS:/ {print $2}' "/proc/$pid/status" 2>/dev/null)
        hwm=$(awk '/^VmHWM:/ {print $2}' "/proc/$pid/status" 2>/dev/null)
        pss=$(awk '/^Pss:/ {print $2}' "/proc/$pid/smaps_rollup" 2>/dev/null)
        [ -n "$rss" ] && echo "$stamp $pid $uid $name $rss $hwm ${pss:-0}"
        ;;
    esac
  done
  sample=$((sample + 1))
  sleep 1
done
