#!/system/bin/sh

marker=$1
index=$2
count=0
printf 'uptime_s\tpid\tuid\tname\tstart_ticks\tuser_ticks\tsystem_ticks\trss_kib\thwm_kib\tpss_kib\tfds\tdatabase_bytes\twal_bytes\n'
while [ -f "$marker" ] && [ "$count" -lt 18000 ]; do
    read -r uptime rest < /proc/uptime
    database_bytes=$(stat -c %s "$index" 2>/dev/null)
    wal_bytes=$(stat -c %s "$index-wal" 2>/dev/null)
    ps -A -o PID,UID,NAME | while read -r pid uid name; do
        case "$name" in
            *disksize*)
                IFS= read -r stat < "/proc/$pid/stat" 2>/dev/null || continue
                stat=${stat##*) }
                set -- $stat
                status=$(awk '/^VmRSS:/ {rss=$2} /^VmHWM:/ {hwm=$2} END {printf "%s\t%s",rss,hwm}' "/proc/$pid/status" 2>/dev/null) || continue
                pss=$(awk '/^Pss:/ {print $2; exit}' "/proc/$pid/smaps_rollup" 2>/dev/null)
                fds=$(set -- "/proc/$pid/fd/"[0-9]*; if [ -L "$1" ]; then printf '%s' "$#"; fi)
                printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
                    "$uptime" "$pid" "$uid" "$name" "${20}" "${12}" "${13}" "$status" "$pss" "$fds" "$database_bytes" "$wal_bytes"
                ;;
        esac
    done
    count=$((count + 1))
    sleep 1
done
