#include "core/scanner.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <functional>
#include <iostream>
#include <map>
#include <sched.h>
#include <set>
#include <stdexcept>
#include <sys/mount.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <thread>
#include <chrono>
#include <unistd.h>

namespace {
using namespace disksize;

void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}
void check(int result, const char* operation) {
    if (result < 0) throw std::runtime_error(std::string(operation) + ": " + std::strerror(errno));
}

// Every cleanup starts at a fresh mkdtemp root owned by this test run. Names
// come from readdir and removal stays relative to its opened directory FDs.
void remove_children(int fd) {
    check(::fchmod(fd, 0700), "cleanup chmod");
    const int copy = ::openat(fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    check(copy, "cleanup open");
    DIR* stream = ::fdopendir(copy);
    if (!stream) { ::close(copy); throw std::runtime_error("cleanup fdopendir"); }
    while (const auto* entry = ::readdir(stream)) {
        const std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        struct stat metadata {};
        check(::fstatat(fd, name.c_str(), &metadata, AT_SYMLINK_NOFOLLOW), "cleanup stat");
        if (S_ISDIR(metadata.st_mode)) {
            check(::fchmodat(fd, name.c_str(), 0700, 0), "cleanup directory chmod");
            const int child = ::openat(fd, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            check(child, "cleanup child open");
            remove_children(child);
            ::close(child);
            check(::unlinkat(fd, name.c_str(), AT_REMOVEDIR), "cleanup rmdir");
        } else check(::unlinkat(fd, name.c_str(), 0), "cleanup unlink");
    }
    ::closedir(stream);
}

struct Fixture {
    std::string path;
    Fixture() {
        char pattern[] = "/data/local/tmp/disksize-phase2-test-XXXXXX";
        char* created = ::mkdtemp(pattern);
        require(created != nullptr, "mkdtemp failed");
        path = created;
        check(::chmod(path.c_str(), 0755), "chmod fixture");
    }
    ~Fixture() {
        try {
            const int fd = ::open(path.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            check(fd, "cleanup root");
            remove_children(fd);
            ::close(fd);
            check(::rmdir(path.c_str()), "cleanup root rmdir");
        } catch (const std::exception& error) {
            std::cerr << "Fixture cleanup failed: " << error.what() << '\n';
            std::terminate();
        }
    }
    std::string at(const std::string& name) const { return path + '/' + name; }
    void directory(const std::string& name, mode_t mode = 0755) const {
        check(::mkdir(at(name).c_str(), mode), "mkdir");
    }
    void file(const std::string& name, const std::string& contents, mode_t mode = 0644) const {
        const int fd = ::open(at(name).c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, mode);
        check(fd, "create fixture file");
        const auto written = ::write(fd, contents.data(), contents.size());
        ::close(fd);
        require(written == static_cast<ssize_t>(contents.size()), "write fixture file");
    }
};

ScanConfiguration config(const Fixture& fixture) {
    ScanConfiguration result;
    result.root_path = fixture.path;
    result.queue_capacity = 1;
    result.batch_size = 2;
    return result;
}
ScanResult scan(const ScanConfiguration& configuration, const ScanCallbacks& callbacks = {}) {
    CancellationToken token;
    return scan_directory(configuration, token, callbacks);
}
const ScanNode& node_at(const ScanResult& result, const std::string& path) {
    for (const auto& node : result.nodes) if (relative_path(result, node.id) == path) return node;
    throw std::runtime_error("missing expected node");
}
bool has_issue(const ScanResult& result, ScanErrorCode code) {
    return std::any_of(result.issues.begin(), result.issues.end(),
        [code](const auto& issue) { return issue.code == code; });
}
void bounded(const ScanResult& result, const ScanConfiguration& configuration) {
    require(result.metrics.peak_queued_directories <= configuration.queue_capacity, "queue bound");
    require(result.metrics.peak_active_workers <= configuration.worker_count, "worker bound");
    require(result.metrics.peak_open_fds <= 4 * configuration.worker_count + 4, "FD bound");
    require(result.metrics.open_fds_at_finish == 0, "tracked FD leak");
}

using Metadata = std::map<std::string, struct stat>;
Metadata metadata(const Fixture& fixture, const std::vector<std::string>& paths) {
    Metadata result;
    for (const auto& path : paths) check(::lstat(fixture.at(path).c_str(), &result[path]), "reference lstat");
    return result;
}
std::uint64_t reference_allocated(const Metadata& entries) {
    std::set<std::pair<dev_t, ino_t>> seen;
    std::uint64_t bytes = 0;
    for (const auto& [name, value] : entries) {
        (void)name;
        if (S_ISDIR(value.st_mode) || seen.emplace(value.st_dev, value.st_ino).second) {
            bytes += static_cast<std::uint64_t>(value.st_blocks) * 512;
        }
    }
    return bytes;
}
std::vector<std::string> basic_fixture(const Fixture& fixture) {
    fixture.directory("empty"); fixture.directory("a"); fixture.directory("z");
    fixture.file("a/original.bin", "hello");
    check(::link(fixture.at("a/original.bin").c_str(), fixture.at("z/alias.bin").c_str()), "hardlink");
    fixture.file("sparse", "");
    const int fd = ::open(fixture.at("sparse").c_str(), O_WRONLY | O_CLOEXEC);
    check(fd, "open sparse");
    const int truncated = ::ftruncate(fd, 5LL * 1024 * 1024 * 1024);
    ::close(fd); check(truncated, "truncate sparse");
    fixture.file("unreadable", "abc", 0000);
    fixture.file("line\nbreak", "name");
    fixture.file("中文 space", "ok");
    fixture.file(std::string("raw-") + '\xff', "x");
    check(::symlink("a/original.bin", fixture.at("linkfile").c_str()), "symlink");
    check(::link(fixture.at("linkfile").c_str(), fixture.at("linkfile-hardlink").c_str()), "symlink hardlink");
    check(::symlink(".", fixture.at("cycle").c_str()), "cycle symlink");
    check(::mkfifo(fixture.at("fifo").c_str(), 0600), "fifo");
    return {"", "empty", "a", "z", "a/original.bin", "z/alias.bin", "sparse", "unreadable",
            "line\nbreak", "中文 space", std::string("raw-") + '\xff', "linkfile", "linkfile-hardlink", "cycle", "fifo"};
}
void basic_and_deterministic() {
    Fixture fixture;
    const auto paths = basic_fixture(fixture);
    const auto before = metadata(fixture, paths);
    for (const std::uint32_t workers : {1, 2}) {
        auto configuration = config(fixture);
        configuration.worker_count = workers;
        configuration.batch_size = 1;
        std::set<NodeId> delivered;
        ScanCallbacks callbacks;
        callbacks.on_nodes = [&](std::span<const ScanNode> batch) {
            require(batch.size() <= configuration.batch_size, "batch bound");
            for (const auto& node : batch) {
                require(node.parent_id == 0 || delivered.contains(node.parent_id), "parent before child");
                require(delivered.insert(node.id).second, "duplicate node ID");
            }
        };
        const auto result = scan(configuration, callbacks);
        require(result.state == SessionState::Completed, "basic scan status");
        require(result.nodes.size() == 15 && delivered.size() == 15, "basic nodes");
        require(result.summary.regular_entries == 7 && result.summary.unique_regular_files == 6, "regular counts");
        require(result.summary.directories == 4 && result.summary.symlinks == 3 &&
                result.summary.special_entries == 1, "kind counts");
        require(result.summary.hardlink_aliases == 2, "hardlink aliases");
        require(result.summary.logical_bytes == 5ULL * 1024 * 1024 * 1024 + 15, "64-bit logical bytes");
        require(result.summary.allocated_bytes == reference_allocated(before), "lstat allocation reference");
        require(relative_path(result, node_at(result, "z/alias.bin").hardlink_owner_id) == "a/original.bin", "regular owner");
        require(relative_path(result, node_at(result, "linkfile-hardlink").hardlink_owner_id) == "linkfile", "symlink owner");
        require(node_at(result, "cycle").symlink_target == ".", "raw link target");
        require(node_at(result, std::string("raw-") + '\xff').raw_size == 1, "raw filename bytes");
        require(node_at(result, "sparse").raw_allocated_bytes < node_at(result, "sparse").raw_size, "sparse allocation");
        require(node_at(result, "empty").complete, "known empty complete");
        bounded(result, configuration);
    }
    const auto after = metadata(fixture, paths);
    for (const auto& [path, value] : before) {
        const auto& now = after.at(path);
        require(value.st_ino == now.st_ino && value.st_size == now.st_size &&
                value.st_blocks == now.st_blocks && value.st_mode == now.st_mode &&
                value.st_uid == now.st_uid && value.st_gid == now.st_gid &&
                value.st_mtim.tv_sec == now.st_mtim.tv_sec && value.st_mtim.tv_nsec == now.st_mtim.tv_nsec,
                "source metadata modified");
    }
    char bytes[6]{};
    const int fd = ::open(fixture.at("a/original.bin").c_str(), O_RDONLY | O_CLOEXEC);
    check(fd, "verify content open");
    const auto count = ::read(fd, bytes, 5); ::close(fd);
    require(count == 5 && std::string(bytes, 5) == "hello", "source contents changed");

    Fixture ordering;
    const std::string high_byte = std::string("byte-") + '\xff';
    ordering.file(high_byte, "x");
    check(::link(ordering.at(high_byte).c_str(), ordering.at("byte-z").c_str()), "byte-order link");
    for (const std::uint32_t workers : {1, 2}) {
        auto configuration = config(ordering); configuration.worker_count = workers;
        const auto result = scan(configuration);
        require(result.state == SessionState::Completed &&
                relative_path(result, node_at(result, high_byte).hardlink_owner_id) == "byte-z",
                "hardlink owner must use unsigned byte order");
    }
}

void wide_queue_and_limits() {
    Fixture fixture;
    for (int index = 0; index < 240; ++index) {
        const auto directory = "d" + std::to_string(index);
        fixture.directory(directory); fixture.file(directory + "/f", "123");
    }
    auto configuration = config(fixture);
    const auto result = scan(configuration);
    require(result.state == SessionState::Completed && result.nodes.size() == 481, "wide scan");
    bounded(result, configuration);
    configuration.max_nodes = 13;
    const auto limited = scan(configuration);
    require(limited.state == SessionState::Partial && limited.nodes.size() == 13 &&
            has_issue(limited, ScanErrorCode::ResourceLimit) && !limited.nodes.front().complete, "node limit");
    configuration.max_nodes = 1000; configuration.max_depth = 0; configuration.max_issues = 1;
    const auto depth = scan(configuration);
    require(depth.state == SessionState::Partial && depth.summary.issue_count == 240 &&
            depth.issues.size() == 1 && depth.summary.dropped_issues == 239, "depth and issue limits");
}

std::size_t fd_count() {
    DIR* directory = ::opendir("/proc/self/fd");
    require(directory != nullptr, "count FDs");
    std::size_t count = 0;
    while (const auto* entry = ::readdir(directory)) if (entry->d_name[0] != '.') ++count;
    ::closedir(directory);
    return count;
}
struct FdLimit {
    struct rlimit previous {};
    FdLimit() {
        check(::getrlimit(RLIMIT_NOFILE, &previous), "getrlimit");
        struct rlimit lowered = previous;
        lowered.rlim_cur = std::min<rlim_t>(previous.rlim_cur, 48);
        check(::setrlimit(RLIMIT_NOFILE, &lowered), "setrlimit");
    }
    ~FdLimit() { if (::setrlimit(RLIMIT_NOFILE, &previous) != 0) std::terminate(); }
};
void deep_paths_and_fd_lifetime() {
    Fixture fixture;
    int current = ::open(fixture.path.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    check(current, "deep root");
    const std::string part(60, 'd');
    for (int index = 0; index < 80; ++index) {
        check(::mkdirat(current, part.c_str(), 0755), "deep mkdir");
        const int next = ::openat(current, part.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        check(next, "deep open"); ::close(current); current = next;
    }
    const int leaf = ::openat(current, "leaf", O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0644);
    check(leaf, "deep leaf"); require(::write(leaf, "abc", 3) == 3, "deep write");
    ::close(leaf); ::close(current);
    const auto before = fd_count();
    {
        FdLimit limit;
        for (int iteration = 0; iteration < 8; ++iteration) {
            const auto configuration = config(fixture);
            const auto result = scan(configuration);
            require(result.state == SessionState::Completed && result.nodes.size() == 82 &&
                    result.summary.logical_bytes == 3, "deep scan under low FD limit");
            const auto& file = *std::find_if(result.nodes.begin(), result.nodes.end(),
                [](const auto& node) { return node.kind == NodeKind::Regular; });
            require(relative_path(result, file.id).size() > 4096, "deep path exceeds PATH_MAX");
            bounded(result, configuration);
        }
    }
    require(fd_count() == before, "process FD leak");
}

void cancellation_and_consumer_failure() {
    Fixture fixture;
    for (int index = 0; index < 30; ++index) fixture.directory("dir" + std::to_string(index));
    const auto configuration = config(fixture);
    CancellationToken token;
    std::size_t delivered = 0;
    ScanCallbacks callbacks;
    callbacks.on_nodes = [&](std::span<const ScanNode> nodes) {
        delivered += nodes.size(); if (delivered >= 3) token.request();
    };
    const auto cancelled = scan_directory(configuration, token, callbacks);
    require(cancelled.state == SessionState::Cancelled && !cancelled.nodes.front().complete, "callback cancellation");
    bounded(cancelled, configuration);
    const auto pre_cancelled = scan_directory(configuration, token);
    require(pre_cancelled.state == SessionState::Cancelled && pre_cancelled.nodes.empty(), "pre cancellation");
    callbacks.on_nodes = [](std::span<const ScanNode> nodes) {
        if (nodes.front().parent_id != 0) throw std::runtime_error("consumer refused batch");
    };
    const auto failed = scan(configuration, callbacks);
    require(failed.state == SessionState::Failed && has_issue(failed, ScanErrorCode::ConsumerFailure), "consumer failure");
    bounded(failed, configuration);

    // Real callers cancel from outside a callback. In particular, cancellation
    // can race worker startup while the coordinator is asleep on its queue.
    for (int iteration = 0; iteration < 40; ++iteration) {
        CancellationToken asynchronous;
        std::atomic<bool> root_seen{false};
        ScanCallbacks progress;
        progress.on_progress = [&](const ScanProgress&) { root_seen.store(true); };
        std::thread requester([&] {
            while (!root_seen.load()) std::this_thread::yield();
            std::this_thread::sleep_for(std::chrono::microseconds(100));
            asynchronous.request();
        });
        const auto result = scan_directory(configuration, asynchronous, progress);
        requester.join();
        require(result.state == SessionState::Cancelled || result.state == SessionState::Completed,
                "asynchronous cancellation state");
        bounded(result, configuration);
    }
}

void mutation_after_discovery() {
    {
        Fixture fixture; fixture.directory("vanish");
        ScanCallbacks callbacks;
        callbacks.on_nodes = [&](std::span<const ScanNode> nodes) {
            for (const auto& node : nodes) if (node.name == "vanish") check(::rmdir(fixture.at("vanish").c_str()), "remove discovered");
        };
        const auto result = scan(config(fixture), callbacks);
        require(result.state == SessionState::Partial && has_issue(result, ScanErrorCode::NotFound) &&
                !node_at(result, "vanish").complete, "deleted directory partial");
    }
    {
        Fixture fixture; Fixture outside;
        fixture.directory("ancestor"); fixture.directory("ancestor/child");
        outside.directory("child"); outside.file("child/sentinel", "must not be counted");
        ScanCallbacks callbacks;
        callbacks.on_nodes = [&](std::span<const ScanNode> nodes) {
            for (const auto& node : nodes) if (node.name == "child") {
                check(::rename(fixture.at("ancestor").c_str(), fixture.at("moved").c_str()), "move ancestor");
                check(::symlink(outside.path.c_str(), fixture.at("ancestor").c_str()), "replace ancestor");
            }
        };
        auto configuration = config(fixture); configuration.worker_count = 1;
        const auto result = scan(configuration, callbacks);
        require(result.state == SessionState::Partial && has_issue(result, ScanErrorCode::ChangedDuringScan), "ancestor replacement");
        require(result.summary.regular_entries == 0, "escaped through replaced ancestor");
    }
}

int child_status(const std::function<void()>& body) {
    std::cout.flush(); std::cerr.flush();
    const auto pid = ::fork(); check(pid, "fork");
    if (pid == 0) {
        try { body(); ::_exit(0); }
        catch (const std::exception& error) { std::cerr << error.what() << '\n'; ::_exit(1); }
    }
    int status = 0; check(::waitpid(pid, &status, 0), "waitpid");
    require(WIFEXITED(status), "child signalled");
    return WEXITSTATUS(status);
}
void permission_denial() {
    Fixture fixture;
    fixture.directory("allowed"); fixture.file("allowed/file", "ok");
    fixture.file("metadata-only", "hello", 0000);
    fixture.directory("protected"); fixture.file("protected/secret", "hidden");
    check(::chmod(fixture.at("protected").c_str(), 0000), "protect directory");
    // Search-only ancestor must be traversable without enumeration permission.
    fixture.directory("search-only", 0711); fixture.directory("search-only/selected");
    fixture.file("search-only/selected/file", "ok");
    const int status = child_status([&] {
        check(::setgid(2000), "setgid"); check(::setuid(2000), "setuid");
        auto configuration = config(fixture);
        const auto result = scan(configuration);
        require(result.state == SessionState::Partial && has_issue(result, ScanErrorCode::PermissionDenied), "DAC partial");
        require(!node_at(result, "protected").complete && !node_at(result, "protected").directory_enumerated,
                "inaccessible directory must stay unknown");
        require(node_at(result, "metadata-only").complete && node_at(result, "metadata-only").raw_size == 5,
                "metadata-only scanning must not require reading regular files");
        configuration.root_path = fixture.at("search-only/selected");
        const auto selected = scan(configuration);
        require(selected.state == SessionState::Completed && selected.summary.logical_bytes == 2,
                "search-only ancestor rejected");
    });
    require(status == 0, "permission child failed");
}

void excluded_and_configuration() {
    Fixture fixture;
    auto configuration = config(fixture);
    configuration.root_path = "/proc";
    const auto excluded = scan(configuration);
    require(excluded.state == SessionState::Partial && excluded.nodes.size() == 1 &&
            excluded.nodes.front().skipped == SkipReason::PseudoFilesystem && !excluded.nodes.front().complete,
            "proc exclusion");
    configuration = config(fixture); configuration.worker_count = 0;
    const auto invalid = scan(configuration);
    require(invalid.state == SessionState::Failed && has_issue(invalid, ScanErrorCode::InvalidConfiguration), "invalid config");
    configuration = config(fixture); configuration.follow_symbolic_links = true;
    require(has_issue(scan(configuration), ScanErrorCode::Unsupported), "unsupported follow policy");
    configuration.follow_symbolic_links = false; configuration.cross_mount_points = true;
    require(has_issue(scan(configuration), ScanErrorCode::Unsupported), "unsupported mount policy");
    configuration = config(fixture); configuration.root_path = fixture.at("absent");
    require(has_issue(scan(configuration), ScanErrorCode::NotFound), "missing root");
    // A selected root alias may be resolved once, unlike discovered symlinks.
    fixture.directory("real"); fixture.file("real/file", "root alias");
    check(::symlink("real", fixture.at("alias").c_str()), "root alias");
    configuration.root_path = fixture.at("alias");
    const auto alias = scan(configuration);
    require(alias.state == SessionState::Completed && alias.summary.logical_bytes == 10 &&
            alias.canonical_root == fixture.at("real"), "selected root resolution");
}

bool mount_boundary() {
    Fixture fixture;
    fixture.directory("source"); fixture.file("source/file", "abc");
    fixture.directory("alias"); fixture.file("file-alias", "");
    const int status = child_status([&] {
        // A failed namespace setup never falls back to mutating global mounts.
        if (::unshare(CLONE_NEWNS) != 0) ::_exit(77);
        if (::mount(nullptr, "/", nullptr, MS_REC | MS_PRIVATE, nullptr) != 0) ::_exit(77);
        check(::mount(fixture.at("source").c_str(), fixture.at("alias").c_str(), nullptr, MS_BIND, nullptr), "directory bind");
        check(::mount(fixture.at("source/file").c_str(), fixture.at("file-alias").c_str(), nullptr, MS_BIND, nullptr), "file bind");
        const auto result = scan(config(fixture));
        const auto& source = node_at(result, "source");
        const auto& alias = node_at(result, "alias");
        require(result.state == SessionState::Partial && result.summary.logical_bytes == 3 &&
                result.nodes.size() == 5, "bind scan totals");
        require(alias.device == source.device && alias.mount_id != source.mount_id &&
                alias.skipped == SkipReason::MountBoundary, "same-device directory bind boundary");
        require(node_at(result, "file-alias").skipped == SkipReason::MountBoundary, "file bind boundary");
    });
    require(status == 0 || status == 77, "mount child failed");
    return status == 0;
}
}  // namespace

int main() {
    if (::getuid() != 0) {
        std::cout << "{\"type\":\"fatal\",\"reason\":\"run-test-fixtures-as-root\"}\n";
        return 1;
    }
    int passed = 0;
    int failed = 0;
    int skipped = 0;
    const std::vector<std::pair<const char*, std::function<void()>>> tests = {
        {"sizes-hardlinks-raw-names-read-only", basic_and_deterministic},
        {"bounded-wide-queue-resource-limits", wide_queue_and_limits},
        {"deep-paths-fd-lifetime", deep_paths_and_fd_lifetime},
        {"cancellation-consumer-failure", cancellation_and_consumer_failure},
        {"deleted-and-replaced-ancestors", mutation_after_discovery},
        {"permission-denial-search-only-ancestors", permission_denial},
        {"pseudo-filesystems-configuration-root-alias", excluded_and_configuration},
    };
    for (const auto& [name, body] : tests) {
        try {
            body(); ++passed;
            std::cout << "{\"type\":\"test\",\"name\":\"" << name << "\",\"status\":\"passed\"}\n";
        } catch (const std::exception& error) {
            ++failed; std::cerr << name << ": " << error.what() << '\n';
            std::cout << "{\"type\":\"test\",\"name\":\"" << name << "\",\"status\":\"failed\"}\n";
        }
    }
    try {
        const bool available = mount_boundary();
        if (available) ++passed; else ++skipped;
        std::cout << "{\"type\":\"test\",\"name\":\"same-device-bind-mounts\",\"status\":\""
                  << (available ? "passed" : "skipped") << "\"}\n";
    } catch (const std::exception& error) {
        ++failed; std::cerr << "mount-boundary: " << error.what() << '\n';
        std::cout << "{\"type\":\"test\",\"name\":\"same-device-bind-mounts\",\"status\":\"failed\"}\n";
    }
    std::cout << "{\"type\":\"test-summary\",\"passed\":" << passed << ",\"failed\":" << failed
              << ",\"skipped\":" << skipped << "}\n";
    return failed == 0 ? 0 : 1;
}
