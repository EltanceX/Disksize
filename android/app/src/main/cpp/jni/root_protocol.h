#pragma once

#include "core/scanner.h"
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <poll.h>
#include <stdexcept>
#include <string_view>
#include <unistd.h>
#include <vector>
#include <fcntl.h>

namespace disksize::wire {
using Bytes = std::vector<std::uint8_t>;
inline void u32(Bytes& out, std::uint32_t value) {
    for (int shift = 24; shift >= 0; shift -= 8) out.push_back((value >> shift) & 255);
}
inline void u64(Bytes& out, std::uint64_t value) {
    for (int shift = 56; shift >= 0; shift -= 8) out.push_back((value >> shift) & 255);
}
inline void blob(Bytes& out, std::string_view value) {
    u32(out, value.size()); out.insert(out.end(), value.begin(), value.end());
}

// All integers are big endian. Header: magic, version/type (u16), payload
// length (u32), UUID (16 raw bytes), sequence (u64). Max payload is 64 KiB.
class Writer {
public:
    Writer(int output, Bytes session, CancellationToken& cancellation)
        : fd_(dup(output)), session_(std::move(session)), cancellation_(cancellation) {
        if (fd_ < 0 || session_.size() != 16) {
            if (fd_ >= 0) close(fd_);
            throw std::runtime_error("Invalid pipe/session");
        }
        if (fcntl(fd_, F_SETFL, fcntl(fd_, F_GETFL) | O_NONBLOCK) == -1) {
            close(fd_); fd_ = -1; throw std::runtime_error("Nonblocking pipe failed");
        }
        // Android normally ignores SIGPIPE. Also make this explicit for app_process.
        std::signal(SIGPIPE, SIG_IGN);
    }
    ~Writer() { if (fd_ >= 0) close(fd_); }
    Writer(const Writer&) = delete;
    Writer& operator=(const Writer&) = delete;

    void frame(std::uint16_t type, const Bytes& body, bool terminal = false) {
        if (body.size() > 65536) throw std::runtime_error("Frame too large");
        Bytes header; u32(header, 0x44535a34); u32(header, (1U << 16) | type);
        u32(header, body.size()); header.insert(header.end(), session_.begin(), session_.end());
        u64(header, sequence_++);
        write(header, terminal); write(body, terminal);
    }
    void progress(const ScanProgress& value) {
        if (cancellation_.requested()) return;
        Bytes body; u64(body, value.discovered_nodes); u64(body, value.observed_logical_bytes);
        u64(body, value.issue_count); u64(body, value.elapsed_microseconds); frame(2, body);
    }
    void result(const ScanResult& result) {
        Bytes batch; u32(batch, 0); std::uint32_t count = 0;
        auto flush = [&] {
            if (!count) return;
            batch[0] = (count >> 24) & 255; batch[1] = (count >> 16) & 255;
            batch[2] = (count >> 8) & 255; batch[3] = count & 255;
            frame(3, batch, true); batch.clear(); u32(batch, 0); count = 0;
        };
        for (const auto& node : result.nodes) {
            Bytes item; u64(item, node.id); u64(item, node.parent_id); blob(item, node.name);
            item.push_back(static_cast<std::uint8_t>(node.kind)); item.push_back(node.complete ? 1 : 0);
            item.push_back(static_cast<std::uint8_t>(node.skipped)); item.push_back(0);
            u64(item, node.subtree_logical_bytes); u64(item, node.subtree_allocated_bytes);
            if (batch.size() + item.size() > 65536) flush();
            batch.insert(batch.end(), item.begin(), item.end()); ++count;
        }
        flush();
        for (const auto& issue : result.issues) {
            Bytes body; u64(body, issue.node_id); u32(body, static_cast<std::uint32_t>(issue.code));
            u32(body, issue.system_error); blob(body, issue.operation);
            const auto path = relative_path(result, issue.node_id);
            u32(body, path.size() <= 4096 ? 1 : 0);
            blob(body, path.size() <= 4096 ? std::string_view(path) : std::string_view{});
            frame(4, body, true);
        }
        Bytes body; u32(body, static_cast<std::uint32_t>(result.state));
        u32(body, !result.nodes.empty() && result.nodes.front().complete ? 1 : 0);
        u64(body, result.nodes.size()); u64(body, result.summary.logical_bytes);
        u64(body, result.summary.allocated_bytes); u64(body, result.summary.issue_count);
        u64(body, result.summary.dropped_issues); u64(body, result.metrics.peak_open_fds);
        u64(body, result.metrics.open_fds_at_finish); u64(body, result.root_mount_id);
        u64(body, result.root_filesystem_type); u32(body, result.issues.size());
        frame(5, body, true); frame(6, {}, true);
    }
private:
    void write(const Bytes& bytes, bool terminal) {
        std::size_t offset = 0;
        auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
        while (offset < bytes.size()) {
            if (!terminal && cancellation_.requested()) throw std::runtime_error("Pipe cancelled");
            const auto size = ::write(fd_, bytes.data() + offset, bytes.size() - offset);
            if (size > 0) { offset += size; deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10); continue; }
            if (size < 0 && errno == EINTR) continue;
            if (size < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Pipe consumer stalled");
                pollfd descriptor{fd_, POLLOUT, 0};
                if (poll(&descriptor, 1, 100) < 0 && errno != EINTR) throw std::runtime_error("Pipe poll failed");
                if (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) throw std::runtime_error("Pipe disconnected");
                continue;
            }
            throw std::runtime_error("Pipe write failed");
        }
    }
    int fd_;
    Bytes session_;
    CancellationToken& cancellation_;
    std::uint64_t sequence_ = 0;
};
} // namespace disksize::wire
