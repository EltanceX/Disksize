#!/system/bin/sh
set -eu

run_id=${1:-}
case "$run_id" in
  ''|*[!a-zA-Z0-9_-]*)
    printf '%s\n' 'Usage: sh create-fixtures.sh <unique-alphanumeric-run-id>' >&2
    exit 2
    ;;
esac

if [ "$(id -u)" != 0 ]; then
  printf '%s\n' 'Create these controlled fixtures through su; Android SELinux may deny shell hard links.' >&2
  exit 4
fi

native_root="/data/local/tmp/disksize-phase0/$run_id"
shared_root="/sdcard/Download/DiskSize-phase0/$run_id"

# Create a fresh run only. Never delete or replace an existing dataset.
if [ -e "$native_root" ] || [ -e "$shared_root" ]; then
  printf '%s\n' 'Run already exists; choose a new run ID.' >&2
  exit 3
fi
mkdir -p /data/local/tmp/disksize-phase0 /sdcard/Download/DiskSize-phase0
mkdir "$native_root" "$shared_root"
mkdir "$native_root/empty" "$native_root/files" "$native_root/links" \
  "$native_root/protected" "$native_root/many" "$native_root/deep"

: > "$native_root/files/empty.bin"
dd if=/dev/zero of="$native_root/files/4k.bin" bs=4096 count=1 2>/dev/null
truncate -s 1073741824 "$native_root/files/sparse-1g.bin"
printf 'DiskSize\n' > "$native_root/files/空间 name.txt"
newline_name=$(printf 'line\nbreak.txt')
printf 'DiskSize\n' > "$native_root/files/$newline_name"
ln "$native_root/files/4k.bin" "$native_root/links/4k-hardlink.bin"
ln -s ../files/4k.bin "$native_root/links/file-symlink"
ln -s .. "$native_root/links/parent-cycle"
printf 'private\n' > "$native_root/protected/secret.txt"
chmod 000 "$native_root/protected"

file_index=0
while [ "$file_index" -lt 128 ]; do
  printf x > "$native_root/many/file-$file_index.txt"
  file_index=$((file_index + 1))
done
deep_path="$native_root/deep"
depth=0
while [ "$depth" -lt 24 ]; do
  deep_path="$deep_path/d"
  mkdir "$deep_path"
  depth=$((depth + 1))
done
printf end > "$deep_path/leaf.txt"

mkdir "$shared_root/empty"
dd if=/dev/zero of="$shared_root/shared-4k.bin" bs=4096 count=1 2>/dev/null
printf 'DiskSize\n' > "$shared_root/共享 name.txt"

printf 'fixture_version=1\nnative_root=%s\nshared_root=%s\n' "$native_root" "$shared_root"
printf 'native_regular_entries=136\nnative_unique_regular_files=135\n'
printf 'native_directories_including_root=31\nnative_symlinks=2\n'
printf 'native_unique_logical_bytes=1073746077\n'
printf 'shared_regular_entries=2\nshared_logical_bytes=4105\n'
