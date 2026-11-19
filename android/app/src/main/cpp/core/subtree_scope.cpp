#include "core/subtree_scope.h"
#include <cerrno>
#include <cstdlib>
#include <fcntl.h>
#include <memory>
#include <sys/vfs.h>
#include <unistd.h>

namespace disksize::internal {
namespace {
std::string resolve_root(const std::string& path) {
    std::unique_ptr<char, decltype(&std::free)> value(realpath(path.c_str(), nullptr), &std::free);
    if (!value) throw ScopeError{"scope-missing", errno};
    return value.get();
}
}

ScopedRoot::ScopedRoot(const ScanConfiguration& c, FdMetrics& metrics, CancellationToken& token)
    : configuration_(c), metrics_(metrics) {
    const auto& scope = *c.subtree;
    const auto& path = scope.relative_path;
    if (path.size() > 512 * 256 || path.find('\0') != std::string::npos || scope.filesystem == 0)
        throw ScopeError{"scope-invalid", EINVAL};
    std::size_t start = 0;
    while (start < path.size()) {
        const auto end = path.find('/', start);
        auto name = path.substr(start, end == std::string::npos ? end : end - start);
        if (name.empty() || name.size() > 255 || name == "." || name == "..") throw ScopeError{"scope-invalid", EINVAL};
        names_.push_back(std::move(name));
        if (end == std::string::npos) break;
        start = end + 1;
        if (start == path.size()) throw ScopeError{"scope-invalid", EINVAL};
    }
    if (names_.size() > 512 || scope.chain.size() != names_.size() + 1)
        throw ScopeError{"scope-invalid", EINVAL};
    try { canonical_ = resolve_root(c.root_path); }
    catch (const ScopeError& e) { throw ScopeError{names_.empty() ? e.operation : "scope-identity", e.error}; }
    for (std::size_t i = 0; i <= names_.size(); ++i) {
        if (token.requested()) throw ScopeError{"scope-cancelled", ECANCELED};
        Fd fd = i == 0 ? open_canonical_directory(canonical_, metrics_) :
            Fd(openat(chain_.back().get(), names_[i - 1].c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC), metrics_);
        if (!fd) {
            const bool target = i == names_.size();
            throw ScopeError{target ? (errno == ENOENT ? "scope-missing" :
                errno == ELOOP || errno == ENOTDIR ? "scope-type-changed" : "scope-unreadable") : "scope-identity", errno};
        }
        struct stat st{}; struct statfs fs{};
        if (fstat(fd.get(), &st) || fstatfs(fd.get(), &fs)) throw ScopeError{"scope-unreadable", errno};
        const auto& expected = scope.chain[i];
        // Legacy root aliases remain usable only while their original root inode still exists.
        const bool compare_inode = i < names_.size() || (i == 0 && canonical_ != c.root_path);
        if (!S_ISDIR(st.st_mode) || st.st_dev != expected.device ||
            (compare_inode && st.st_ino != expected.inode) || expected.mount == 0 ||
            expected.device != scope.chain.front().device || expected.mount != scope.chain.front().mount ||
            mount_id_for_fd(fd.get(), metrics_) != expected.mount || static_cast<std::uint64_t>(fs.f_type) != scope.filesystem)
            throw ScopeError{"scope-identity", ESTALE};
        opened_.push_back(st); chain_.push_back(std::move(fd));
    }
}

Fd ScopedRoot::duplicate() const {
    Fd value(fcntl(chain_.back().get(), F_DUPFD_CLOEXEC, 0), metrics_);
    if (!value) throw ScopeError{"scope-unreadable", errno};
    return value;
}

void ScopedRoot::verify() const {
    try {
        if (resolve_root(configuration_.root_path) != canonical_) throw ScopeError{"scope-path-changed", ESTALE};
    } catch (const ScopeError&) { throw ScopeError{"scope-path-changed", ESTALE}; }
    auto root = open_canonical_directory(canonical_, metrics_);
    struct stat st{};
    if (!root || fstat(root.get(), &st) || !same_identity(st, opened_.front()) ||
        mount_id_for_fd(root.get(), metrics_) != configuration_.subtree->chain.front().mount)
        throw ScopeError{"scope-path-changed", ESTALE};
    for (std::size_t i = 1; i < chain_.size(); ++i) {
        if (fstatat(chain_[i - 1].get(), names_[i - 1].c_str(), &st, AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT) ||
            !same_identity(st, opened_[i]) ||
            mount_id_at(chain_[i - 1].get(), names_[i - 1].c_str(), st, metrics_) != configuration_.subtree->chain[i].mount)
            throw ScopeError{"scope-path-changed", ESTALE};
    }
}
}
