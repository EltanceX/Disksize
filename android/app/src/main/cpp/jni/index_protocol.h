#pragma once
#include "root_protocol.h"
#include "core/stream_scanner.h"

namespace disksize::wire {
class IndexWriter {
public:
    IndexWriter(int fd, Bytes id, CancellationToken& token) : wire_(fd, std::move(id), token, 3) {
        Bytes b; u32(b, getuid()); u32(b, geteuid()); u32(b, getpid()); wire_.frame(1, b, true);
        u32(batch_, 0);
    }
    void node(const ScanNode& n) {
        Bytes b; u64(b, n.id); u64(b, n.parent_id); blob(b, n.name); blob(b, n.symlink_target);
        u32(b, static_cast<std::uint32_t>(n.kind)); u32(b, n.depth);
        u32(b, n.mode); u32(b, n.uid); u32(b, n.gid);
        u64(b, n.device); u64(b, n.inode); u64(b, n.mount_id); u64(b, n.link_count);
        u64(b, n.raw_size); u64(b, n.raw_allocated_bytes);
        u64(b, n.mtime_seconds); u64(b, n.mtime_nanoseconds);
        u32(b, n.metadata_valid); u32(b, n.complete); u32(b, static_cast<std::uint32_t>(n.skipped));
        if (batch_.size() + b.size() > 32768 || count_ == 256) flush();
        batch_.insert(batch_.end(), b.begin(), b.end()); ++count_;
    }
    void flush() {
        if (!count_) return;
        batch_[0] = (count_ >> 24) & 255; batch_[1] = (count_ >> 16) & 255;
        batch_[2] = (count_ >> 8) & 255; batch_[3] = count_ & 255;
        wire_.frame(3, batch_, true); batch_.clear(); u32(batch_, 0); count_ = 0;
    }
    void directory(const ScanNode& n) {
        flush(); Bytes b; u64(b, n.id); u32(b, n.directory_enumerated); u32(b, n.complete);
        u32(b, static_cast<std::uint32_t>(n.skipped)); wire_.frame(7, b, true);
    }
    void issue(const ScanIssue& i) {
        flush(); Bytes b; u64(b, i.node_id); u32(b, static_cast<std::uint32_t>(i.code));
        u32(b, i.system_error); blob(b, i.operation); wire_.frame(4, b, true);
    }
    void progress(const ScanProgress& p) { flush(); wire_.progress(p); }
    void finish(const StreamResult& r) {
        if (r.volume.valid) {
            flush(); Bytes v; u64(v, r.volume.total); u64(v, r.volume.free);
            u64(v, r.volume.available); u64(v, r.volume.captured_millis); wire_.frame(8, v, true);
        }
        flush(); Bytes b; u32(b, static_cast<std::uint32_t>(r.scan.state)); u64(b, r.node_count);
        u64(b, r.scan.summary.issue_count); u64(b, r.scan.metrics.elapsed_microseconds);
        u64(b, r.scan.metrics.peak_open_fds); u64(b, r.scan.metrics.open_fds_at_finish);
        u64(b, r.scan.root_mount_id); u64(b, r.scan.root_filesystem_type);
        wire_.frame(5, b, true); wire_.frame(6, {}, true);
    }
private:
    Writer wire_; Bytes batch_; std::uint32_t count_ = 0;
};
}
