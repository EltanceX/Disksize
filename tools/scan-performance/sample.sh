#!/system/bin/sh

marker=$1
count=0
printf 'uptime_s\tpid\tuid\tname\tstart_ticks\tuser_ticks\tsystem_ticks\trss_kib\thwm_kib\tpss_kib\n'
while [ -f "$marker" ] && [ "$count" -lt 18000 ]; do
    read -r uptime rest < /proc/uptime
    ps -A -o PID,UID,NAME | while read -r pid uid name; do
        case "$name" in
            *disksize*)
                IFS= read -r stat < "/proc/$pid/stat" 2>/dev/null || continue
                stat=${stat##*) }
                set -- $stat
                status=$(awk '/^VmRSS:/ {rss=$2} /^VmHWM:/ {hwm=$2} END {printf "%s\t%s",rss,hwm}' "/proc/$pid/status" 2>/dev/null)
                pss=$(awk '/^Pss:/ {print $2; exit}' "/proc/$pid/smaps_rollup" 2>/dev/null)
                printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$uptime" "$pid" "$uid" "$name" "${20}" "${12}" "${13}" "$status" "$pss"
                ;;
        esac
    done
    count=$((count + 1))
    sleep 1
done
