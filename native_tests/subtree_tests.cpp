#include "core/stream_scanner.h"
#include "core/filesystem.h"
#include <filesystem>
#include <fcntl.h>
#include <iostream>
#include <sys/vfs.h>
#include <unistd.h>

namespace fs = std::filesystem;
using namespace disksize;
void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }

ScanConfiguration scope(const std::string& root, const std::string& path) {
    ScanConfiguration c; c.root_path = root; c.subtree = SubtreeScope{path, {}, 0};
    internal::FdMetrics metrics;
    internal::Fd fd(open(root.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC), metrics);
    require(static_cast<bool>(fd), "open fixture root");
    struct statfs filesystem{}; require(fstatfs(fd.get(), &filesystem) == 0, "filesystem");
    c.subtree->filesystem = filesystem.f_type;
    std::size_t position = 0;
    while (true) {
        struct stat st{}; require(fstat(fd.get(), &st) == 0, "stat scope");
        c.subtree->chain.push_back({st.st_dev, st.st_ino, internal::mount_id_for_fd(fd.get(), metrics)});
        if (position >= path.size()) break;
        const auto end = path.find('/', position);
        const auto name = path.substr(position, end == std::string::npos ? end : end - position);
        fd = internal::Fd(openat(fd.get(), name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC), metrics);
        require(static_cast<bool>(fd), "open scope component");
        position = end == std::string::npos ? path.size() : end + 1;
    }
    c.max_depth = 512 - (c.subtree->chain.size() - 1);
    return c;
}

StreamResult scan(const ScanConfiguration& c, const char* operation = nullptr, StreamCallbacks cb = {}) {
    CancellationToken token;
    bool seen = false;
    const auto observer = cb.on_issue;
    cb.on_issue = [&](const ScanIssue& issue) {
        if (operation && issue.operation == operation) seen = true;
        if (observer) observer(issue);
    };
    const auto result = stream_directory(c, token, cb);
    require(result.scan.metrics.open_fds_at_finish == 0, "scope descriptor leak");
    require(operation ? seen && result.scan.state != SessionState::Completed : result.scan.state == SessionState::Completed, "scope result");
    return result;
}

int main() {
    char pattern[] = "/data/local/tmp/disksize-subtree-XXXXXX";
    const char* created = mkdtemp(pattern); if (!created) return 1;
    const std::string root = created;
    try {
        fs::create_directories(root + "/parent/target");
        fs::create_directory(root + "/outside");
        const std::string raw = "raw\xff\n";
        fs::create_directory(root + "/parent/target/" + raw);
        auto c = scope(root, "parent/target");
        require(scan(c).node_count == 2, "subtree range");
        bool raw_seen = false; StreamCallbacks raw_cb;
        raw_cb.on_nodes = [&](std::span<const ScanNode> nodes) { if (nodes.front().name == raw) raw_seen = true; };
        scan(c, nullptr, raw_cb); require(raw_seen, "raw byte name");
        fs::rename(root + "/parent/target", root + "/parent/old");
        fs::create_directory(root + "/parent/target");
        require(scan(c).node_count == 1, "rebuilt target directory");
        fs::remove(root + "/parent/target");
        require(symlink("../outside", (root + "/parent/target").c_str()) == 0, "target symlink");
        require(scan(c, "scope-type-changed").node_count == 0, "followed target symlink");
        fs::remove(root + "/parent/target"); scan(c, "scope-missing");
        fs::create_directory(root + "/parent/target");
        fs::rename(root + "/parent", root + "/old-parent");
        fs::create_directories(root + "/parent/target"); scan(c, "scope-identity");
        fs::remove_all(root + "/parent");
        require(symlink("old-parent", (root + "/parent").c_str()) == 0, "ancestor symlink");
        scan(c, "scope-identity"); fs::remove(root + "/parent"); fs::rename(root + "/old-parent", root + "/parent");
        auto wrong = scope(root, "parent/target"); ++wrong.subtree->chain.back().mount; scan(wrong, "scope-identity");
        wrong = c; ++wrong.subtree->chain.back().device; scan(wrong, "scope-identity");
        wrong = c; ++wrong.subtree->filesystem; scan(wrong, "scope-identity");
        wrong = c; wrong.subtree->relative_path = "parent/../outside"; scan(wrong, "scope-invalid");
        c = scope(root, "parent/target");
        StreamCallbacks replaced;
        replaced.on_nodes = [&](std::span<const ScanNode> nodes) {
            if (nodes.front().id == 1) {
                fs::rename(root + "/parent/target", root + "/parent/detached");
                fs::create_directory(root + "/parent/target");
            }
        };
        scan(c, "scope-path-changed", replaced);
        c = scope(root, "parent/target");
        replaced.on_nodes = [&](std::span<const ScanNode> nodes) {
            if (nodes.front().id == 1) fs::rename(root + "/parent", root + "/moved-parent");
        };
        scan(c, "scope-path-changed", replaced);
        fs::create_directory(root + "/replace-root"); c = scope(root + "/replace-root", "");
        fs::rename(root + "/replace-root", root + "/old-root"); fs::create_directory(root + "/replace-root");
        scan(c);
        fs::remove_all(root);
        std::cout << "{\"suite\":\"subtree\",\"tests\":14,\"failed\":0}\n";
        return 0;
    } catch (const std::exception& error) { fs::remove_all(root); std::cerr << error.what() << '\n'; return 1; }
}
