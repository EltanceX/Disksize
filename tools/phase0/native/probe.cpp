#include <cerrno>
#include <cinttypes>
#include <cstdio>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <unistd.h>

// Environment probe only. This does not implement directory traversal.
int main(int argc, char** argv) {
    std::printf("{\"uid\":%u,\"euid\":%u,\"pointer_bits\":%zu,\"page_size\":%ld",
                static_cast<unsigned>(getuid()), static_cast<unsigned>(geteuid()),
                sizeof(void*) * 8, sysconf(_SC_PAGESIZE));
    int result = 0;
    if (argc > 1) {
        struct stat metadata {};
        if (lstat(argv[1], &metadata) == 0) {
            std::printf(
                ",\"stat_ok\":true,\"size\":%" PRId64
                ",\"blocks_512\":%" PRId64 ",\"inode\":%" PRIu64
                ",\"device\":%" PRIu64 ",\"nlink\":%" PRIu64,
                static_cast<int64_t>(metadata.st_size),
                static_cast<int64_t>(metadata.st_blocks),
                static_cast<uint64_t>(metadata.st_ino),
                static_cast<uint64_t>(metadata.st_dev),
                static_cast<uint64_t>(metadata.st_nlink));
        } else {
            const int statError = errno;
            std::printf(",\"stat_ok\":false,\"errno\":%d", statError);
            result = 2;
        }
        struct statfs filesystem {};
        if (statfs(argv[1], &filesystem) == 0) {
            std::printf(",\"filesystem_type\":%" PRIu64,
                        static_cast<uint64_t>(filesystem.f_type));
        }
    }
    std::puts("}");
    return result;
}
