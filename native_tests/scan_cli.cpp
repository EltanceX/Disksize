#include "core/scanner.h"

#include <charconv>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string_view>
#include <sys/resource.h>
#include <unistd.h>

namespace {

std::string hex(std::string_view bytes) {
    constexpr char digits[] = "0123456789abcdef";
    std::string result;
    result.reserve(bytes.size() * 2);
    for (unsigned char byte : bytes) {
        result += digits[byte >> 4];
        result += digits[byte & 15];
    }
    return result;
}

std::uint64_t integer(std::string_view value) {
    std::uint64_t result = 0;
    const auto parsed = std::from_chars(value.data(), value.data() + value.size(), result);
    if (parsed.ec != std::errc{} || parsed.ptr != value.data() + value.size()) {
        throw std::invalid_argument("invalid integer");
    }
    return result;
}

void node_json(const disksize::ScanNode& node, const char* type,
               const disksize::ScanResult* result = nullptr) {
    std::cout << "{\"type\":\"" << type << "\",\"id\":" << node.id
        << ",\"parent_id\":" << node.parent_id << ",\"name_hex\":\"" << hex(node.name)
        << "\",\"kind\":\"" << disksize::node_kind_name(node.kind)
        << "\",\"metadata_valid\":" << (node.metadata_valid ? "true" : "false")
        << ",\"raw_size\":" << node.raw_size << ",\"raw_allocated_bytes\":" << node.raw_allocated_bytes
        << ",\"device\":" << node.device << ",\"inode\":" << node.inode
        << ",\"mount_id\":" << node.mount_id << ",\"symlink_target_hex\":\"" << hex(node.symlink_target)
        << "\",\"skipped\":\"" << disksize::skip_reason_name(node.skipped) << '"';
    if (result != nullptr) {
        std::cout << ",\"path_hex\":\"" << hex(disksize::relative_path(*result, node.id))
            << "\",\"hardlink_owner_id\":" << node.hardlink_owner_id
            << ",\"counted_logical_bytes\":" << node.counted_logical_bytes
            << ",\"counted_allocated_bytes\":" << node.counted_allocated_bytes
            << ",\"subtree_logical_bytes\":" << node.subtree_logical_bytes
            << ",\"subtree_allocated_bytes\":" << node.subtree_allocated_bytes
            << ",\"complete\":" << (node.complete ? "true" : "false");
    }
    std::cout << "}\n";
}

}  // namespace

int main(int argc, char** argv) {
    try {
        disksize::ScanConfiguration configuration;
        bool nodes = false;
        bool events = false;
        std::uint64_t cancel_after = 0;
        for (int index = 1; index < argc; ++index) {
            const std::string_view option(argv[index]);
            if (option == "--nodes") { nodes = true; continue; }
            if (option == "--events") { events = true; continue; }
            if (index + 1 >= argc) throw std::invalid_argument("missing option value");
            const std::string_view value(argv[++index]);
            if (option == "--root") configuration.root_path = value;
            else if (option == "--max-nodes") configuration.max_nodes = integer(value);
            else if (option == "--cancel-after") cancel_after = integer(value);
            else {
                const auto number = integer(value);
                if (number > std::numeric_limits<std::uint32_t>::max()) throw std::invalid_argument("option overflow");
                if (option == "--workers") configuration.worker_count = static_cast<std::uint32_t>(number);
                else if (option == "--queue") configuration.queue_capacity = static_cast<std::uint32_t>(number);
                else if (option == "--batch") configuration.batch_size = static_cast<std::uint32_t>(number);
                else if (option == "--max-depth") configuration.max_depth = static_cast<std::uint32_t>(number);
                else throw std::invalid_argument("unknown option");
            }
        }
        disksize::CancellationToken cancellation;
        disksize::ScanCallbacks callbacks;
        std::uint64_t delivered = 0;
        callbacks.on_nodes = [&](std::span<const disksize::ScanNode> batch) {
            delivered += batch.size();
            if (events) for (const auto& node : batch) node_json(node, "discovered");
            if (cancel_after != 0 && delivered >= cancel_after) cancellation.request();
        };
        if (events) {
            callbacks.on_progress = [](const disksize::ScanProgress& progress) {
                std::cout << "{\"type\":\"progress\",\"provisional\":true,\"nodes\":" << progress.discovered_nodes
                    << ",\"observed_logical_bytes\":" << progress.observed_logical_bytes
                    << ",\"elapsed_us\":" << progress.elapsed_microseconds << "}\n";
            };
        }
        auto result = disksize::scan_directory(configuration, cancellation, callbacks);
        if (nodes) for (const auto& node : result.nodes) node_json(node, "node", &result);
        for (const auto& issue : result.issues) {
            std::cout << "{\"type\":\"issue\",\"node_id\":" << issue.node_id
                << ",\"code\":\"" << disksize::error_code_name(issue.code)
                << "\",\"operation\":\"" << issue.operation << "\",\"errno\":" << issue.system_error << "}\n";
        }
        struct rusage usage {};
        ::getrusage(RUSAGE_SELF, &usage);
        const auto& summary = result.summary;
        const auto& metrics = result.metrics;
        std::cout << "{\"type\":\"summary\",\"status\":\"" << disksize::state_name(result.state)
            << "\",\"uid\":" << ::getuid() << ",\"atomic_snapshot\":false,\"root_path_hex\":\"" << hex(result.canonical_root)
            << "\",\"root_mount_id\":" << result.root_mount_id << ",\"filesystem_type\":" << result.root_filesystem_type
            << ",\"root_complete\":" << (!result.nodes.empty() && result.nodes.front().complete ? "true" : "false")
            << ",\"nodes\":" << result.nodes.size() << ",\"regular_entries\":" << summary.regular_entries
            << ",\"unique_regular_files\":" << summary.unique_regular_files << ",\"hardlink_aliases\":" << summary.hardlink_aliases
            << ",\"directories\":" << summary.directories << ",\"symlinks\":" << summary.symlinks
            << ",\"special_entries\":" << summary.special_entries << ",\"unknown_entries\":" << summary.unknown_entries
            << ",\"skipped_entries\":" << summary.skipped_entries << ",\"logical_bytes\":" << summary.logical_bytes
            << ",\"allocated_bytes\":" << summary.allocated_bytes << ",\"issues\":" << summary.issue_count
            << ",\"dropped_issues\":" << summary.dropped_issues << ",\"elapsed_us\":" << metrics.elapsed_microseconds
            << ",\"peak_queue\":" << metrics.peak_queued_directories << ",\"peak_workers\":" << metrics.peak_active_workers
            << ",\"peak_open_fds\":" << metrics.peak_open_fds << ",\"open_fds_at_finish\":" << metrics.open_fds_at_finish
            << ",\"max_rss_kib\":" << usage.ru_maxrss << "}\n";
        if (result.state == disksize::SessionState::Completed) return 0;
        if (result.state == disksize::SessionState::Partial) return 2;
        if (result.state == disksize::SessionState::Cancelled) return 3;
        return 1;
    } catch (const std::exception&) {
        std::cout << "{\"type\":\"fatal\",\"status\":\"failed\",\"code\":\"invalid-arguments-or-runtime-failure\"}\n";
        return 64;
    }
}
