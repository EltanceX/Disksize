#!/system/bin/sh
set -eu
root=/data/local/tmp/disksize-phase4
# Refuse to reuse arbitrary existing data. Only our exact marked fixture is removed.
if [ -e "$root" ]; then
  [ -f "$root/.disksize-owned-fixture" ] || exit 1
  chmod 700 "$root/protected" 2>/dev/null || true
  rm -rf "$root"
fi
mkdir -m 700 "$root"
touch "$root/.disksize-owned-fixture"
printf abc > "$root/space name.bin"
printf abcde > "$root/中文.bin"
newline=$(printf 'line\nbreak.bin')
printf '1234567' > "$root/$newline"
invalid=$(printf 'invalid_\377.bin')
printf '12345678901' > "$root/$invalid"
mkdir "$root/protected"
printf secret > "$root/protected/secret.txt"
chmod 000 "$root/protected"
ln "$root/space name.bin" "$root/hardlink.bin"
ln -s . "$root/loop"
truncate -s 1073741824 "$root/sparse.bin"
# 11 nodes, 6 unique regular files plus the empty marker, 2 directories and 1 symlink.
# Logical = 1 GiB + 3 + 5 + 7 + 11 + 6 = 1,073,741,856.
find "$root" -exec stat -c '%f:%s:%b:%d:%i:%u:%g' {} +
