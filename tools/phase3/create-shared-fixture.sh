#!/system/bin/sh
set -eu
root=$(mktemp -d /storage/emulated/0/Download/disksize-phase3-revoke-XXXXXX)
trap 'rm -r "$root"' EXIT HUP INT TERM
cd "$root"
i=0
while [ "$i" -lt 100 ]; do
    mkdir level
    cd level
    printf 'fixture' > file
    i=$((i + 1))
done
printf '%s\n' "$root"
trap - EXIT HUP INT TERM
