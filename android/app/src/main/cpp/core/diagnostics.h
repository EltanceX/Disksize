#pragma once

#include <cstdint>

namespace disksize {

inline constexpr char kCoreVersion[] = "0.1.0";
inline constexpr std::int32_t kProtocolVersion = 1;

struct RuntimeInfo {
    std::int64_t process_id;
    std::int64_t uid;
    std::int64_t effective_uid;
    std::int64_t page_size_bytes;
    std::int64_t pointer_bits;
    bool self_test_passed;
};

[[nodiscard]] RuntimeInfo read_runtime_info() noexcept;
[[nodiscard]] const char* native_abi() noexcept;

}  // namespace disksize
