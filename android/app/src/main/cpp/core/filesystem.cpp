#include "core/filesystem.h"
#include "core/stream_scanner.h"

#include <cerrno>
#include <charconv>
#include <chrono>
#include <fcntl.h>
#include <linux/stat.h>
#include <string_view>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <unistd.h>
#include <utility>

namespace disksize::internal {

void FdMetrics::opened() noexcept {
    const auto value = current.fetch_add(1) + 1;
    auto previous = peak.load();
    while (previous < value && !peak.compare_exchange_weak(previous, value)) {}
}

Fd::Fd(int fd, FdMetrics& metrics) noexcept : fd_(fd), metrics_(&metrics) {
    if (fd_ >= 0) metrics_->opened();
}
Fd::~Fd() { reset(); }
Fd::Fd(Fd&& other) noexcept
    : fd_(std::exchange(other.fd_, -1)), metrics_(other.metrics_) {}
Fd& Fd::operator=(Fd&& other) noexcept {
    if (this != &other) {
        reset();
        fd_ = std::exchange(other.fd_, -1);
        metrics_ = other.metrics_;
    }
    return *this;
}
void Fd::reset() noexcept {
    if (fd_ >= 0) {
        const int saved_error = errno;
        ::close(fd_);
        metrics_->closed();
        fd_ = -1;
        errno = saved_error;
    }
}
int Fd::release() noexcept { return std::exchange(fd_, -1); }

DirStream::DirStream(Fd&& fd, FdMetrics& metrics) noexcept : metrics_(metrics) {
    const int raw = fd.release();
    directory_ = ::fdopendir(raw);
    if (directory_ == nullptr) {
        const int error = errno;
        ::close(raw);
        metrics_.closed();
        errno = error;
    }
}
DirStream::~DirStream() {
    if (directory_ != nullptr) {
        ::closedir(directory_);
        metrics_.closed();
    }
}

Fd open_canonical_directory(const std::string& path, FdMetrics& metrics) {
    // Ancestors need search permission only; an app may reach its own directory
    // through /data without permission to enumerate /data itself.
    Fd current(::open("/", O_PATH | O_DIRECTORY | O_CLOEXEC), metrics);
    if (!current) return {};
    std::size_t offset = 1;
    while (offset < path.size()) {
        const auto end = path.find('/', offset);
        const auto part = path.substr(offset, end - offset);
        Fd next(::openat(current.get(), part.c_str(),
                         O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC), metrics);
        if (!next) return {};
        current = std::move(next);
        if (end == std::string::npos) break;
        offset = end + 1;
    }
    return Fd(::openat(current.get(), ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC), metrics);
}

bool same_identity(const struct stat& left, const struct stat& right) noexcept {
    return left.st_dev == right.st_dev && left.st_ino == right.st_ino &&
           (left.st_mode & S_IFMT) == (right.st_mode & S_IFMT);
}

namespace {
std::uint64_t fdinfo_mount_id(int fd, FdMetrics& metrics) {
    const auto path = "/proc/self/fdinfo/" + std::to_string(fd);
    Fd info(::open(path.c_str(), O_RDONLY | O_CLOEXEC), metrics);
    if (!info) return 0;
    char buffer[4096];
    ssize_t length;
    do { length = ::read(info.get(), buffer, sizeof(buffer)); } while (length < 0 && errno == EINTR);
    if (length <= 0) { if (length == 0) errno = EIO; return 0; }
    std::string_view text(buffer, static_cast<std::size_t>(length));
    const auto position = text.find("mnt_id:");
    if (position == std::string_view::npos) { errno = ENOTSUP; return 0; }
    text.remove_prefix(position + 7);
    while (!text.empty() && (text.front() == ' ' || text.front() == '\t')) text.remove_prefix(1);
    std::uint64_t result = 0;
    const auto parsed = std::from_chars(text.data(), text.data() + text.size(), result);
    if (parsed.ec != std::errc{} || result == 0) { errno = ENOTSUP; return 0; }
    return result;
}

std::uint64_t entry_mount_from_fd(int parent, const char* name, const struct stat& expected,
                                 FdMetrics& metrics, bool fdinfo_only) {
    // O_PATH opens metadata only; it cannot read a regular file or block on a FIFO.
    Fd metadata(::openat(parent, name, O_PATH | O_NOFOLLOW | O_CLOEXEC), metrics);
    if (!metadata) return 0;
    struct stat actual {};
    if (::fstat(metadata.get(), &actual) != 0) return 0;
    if (!same_identity(actual, expected)) { errno = ESTALE; return 0; }
    return fdinfo_only ? fdinfo_mount_id(metadata.get(), metrics) : mount_id_for_fd(metadata.get(), metrics);
}

constexpr int entry_flags = AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT;
bool has_mount(const struct statx& s) {
    return (s.stx_mask & (STATX_MNT_ID | STATX_INO)) == (STATX_MNT_ID | STATX_INO) && s.stx_mnt_id != 0;
}
std::uint64_t checked_mount(const struct statx& s, const struct stat& expected) {
    if (s.stx_ino != expected.st_ino || s.stx_dev_major != major(expected.st_dev) ||
        s.stx_dev_minor != minor(expected.st_dev) ||
        ((s.stx_mask & STATX_TYPE) && (s.stx_mode & S_IFMT) != (expected.st_mode & S_IFMT))) {
        errno = ESTALE;
        return 0;
    }
    return s.stx_mnt_id;
}
bool copy_metadata(const struct statx& s, struct stat& out) {
    constexpr unsigned required = STATX_TYPE | STATX_MODE | STATX_NLINK | STATX_UID | STATX_GID |
        STATX_INO | STATX_SIZE | STATX_BLOCKS | STATX_MTIME;
    if ((s.stx_mask & required) != required || s.stx_size > INT64_MAX ||
        s.stx_blocks > INT64_MAX / 512 || s.stx_mtime.tv_nsec >= 1'000'000'000) return false;
    out.st_mode = s.stx_mode; out.st_uid = s.stx_uid; out.st_gid = s.stx_gid;
    out.st_dev = makedev(s.stx_dev_major, s.stx_dev_minor);
    out.st_ino = s.stx_ino; out.st_nlink = s.stx_nlink;
    out.st_size = s.stx_size; out.st_blocks = s.stx_blocks;
    out.st_mtim = {s.stx_mtime.tv_sec, s.stx_mtime.tv_nsec};
    return true;
}

template <typename Query>
auto measured(bool enabled, std::uint64_t& nanos, Query query) {
    if (!enabled) return query();
    const auto begin = std::chrono::steady_clock::now();
    const auto result = query();
    const auto error = errno;
    nanos += std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - begin).count();
    errno = error;
    return result;
}
}

std::uint64_t mount_id_for_fd(int fd, FdMetrics& metrics) {
    struct statx extended {};
    if (::syscall(__NR_statx, fd, "", AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW,
                  STATX_MNT_ID, &extended) == 0 &&
        (extended.stx_mask & STATX_MNT_ID) != 0 && extended.stx_mnt_id != 0) {
        return extended.stx_mnt_id;
    }
    return fdinfo_mount_id(fd, metrics);
}

std::uint64_t mount_id_at(int parent, const char* name,
                          const struct stat& expected, FdMetrics& metrics) {
    struct statx extended {};
    if (::syscall(__NR_statx, parent, name, entry_flags, STATX_BASIC_STATS | STATX_MNT_ID, &extended) == 0 &&
        has_mount(extended)) return checked_mount(extended, expected);
    return entry_mount_from_fd(parent, name, expected, metrics, false);
}

EntryReader::EntryReader(StreamMetadata strategy, EntryMeasurements* measurements) noexcept
    : strategy_(strategy), measurements_(measurements), statx_unavailable_(strategy == StreamMetadata::FdInfo) {}

EntryReader::~EntryReader() {
    if (!measurements_) return;
    measurements_->stat_micros = stat_ns_ / 1000;
    measurements_->statx_micros = statx_ns_ / 1000;
    measurements_->fd_fallback_micros = fallback_ns_ / 1000;
}

bool EntryReader::read(int parent, const char* name, struct stat& value, std::uint64_t& mount, FdMetrics& metrics) {
    const auto stat = [&] {
        if (measurements_) ++measurements_->stat_calls;
        return measured(measurements_, stat_ns_, [&] { return ::fstatat(parent, name, &value, entry_flags) == 0; });
    };
    const bool combined = strategy_ == StreamMetadata::Combined;
    if (!combined && !stat()) return false;
    struct statx extended {};
    bool extended_ok = false;
    if (!statx_unavailable_) {
        if (measurements_) ++measurements_->statx_calls;
        extended_ok = measured(measurements_, statx_ns_, [&] {
            return ::syscall(__NR_statx, parent, name, entry_flags, STATX_BASIC_STATS | STATX_MNT_ID, &extended) == 0;
        });
        if (!extended_ok && errno == ENOSYS) statx_unavailable_ = true;
        if (combined && extended_ok && has_mount(extended) && copy_metadata(extended, value)) {
            mount = extended.stx_mnt_id;
            return true;
        }
    }
    if (combined && !stat()) return false;
    if (extended_ok && has_mount(extended)) {
        mount = checked_mount(extended, value);
    } else {
        if (measurements_) ++measurements_->fd_fallbacks;
        mount = measured(measurements_, fallback_ns_, [&] {
            return entry_mount_from_fd(parent, name, value, metrics, statx_unavailable_);
        });
    }
    return true;
}

bool pseudo_filesystem(std::uint64_t type) noexcept {
    switch (type) {
        case 0x9fa0:      // proc
        case 0x62656572:  // sysfs
        case 0x1cd1:      // devpts
        case 0x27e0eb:    // cgroup
        case 0x63677270:  // cgroup2
        case 0x73636673:  // securityfs
        case 0x64626720:  // debugfs
        case 0x74726163:  // tracefs
        case 0xcafe4a11:  // bpf
        case 0xf97cff8c:  // selinuxfs
        case 0x19800202:  // mqueue
        case 0x6e736673:  // nsfs
            return true;
        default:
            return false;
    }
}

bool excluded_system_root(const std::string& path) noexcept {
    for (const std::string_view prefix : {"/proc", "/sys", "/dev"}) {
        if (path == prefix || (path.starts_with(prefix) && path[prefix.size()] == '/')) return true;
    }
    return false;
}

}  // namespace disksize::internal
