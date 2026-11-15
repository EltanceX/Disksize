#include "core/diagnostics.h"
#include "core/scan_types.h"

#include <unistd.h>

namespace disksize {

RuntimeInfo read_runtime_info() noexcept {
    const auto page_size = static_cast<std::int64_t>(sysconf(_SC_PAGESIZE));
    CancellationToken cancellation;
    const bool starts_active = !cancellation.requested();
    cancellation.request();
    constexpr std::uint64_t large_counter = (std::uint64_t{1} << 40) + 1;
    const bool self_test = starts_active && cancellation.requested() &&
        large_counter > (std::uint64_t{1} << 32) && sizeof(void*) == 8 &&
        page_size > 0 && (page_size & (page_size - 1)) == 0;
    return {
        static_cast<std::int64_t>(getpid()),
        static_cast<std::int64_t>(getuid()),
        static_cast<std::int64_t>(geteuid()),
        page_size,
        static_cast<std::int64_t>(sizeof(void*) * 8),
        self_test,
    };
}

const char* native_abi() noexcept {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__x86_64__)
    return "x86_64";
#else
#error "DiskSize currently supports arm64-v8a and x86_64 only."
#endif
}

}  // namespace disksize
