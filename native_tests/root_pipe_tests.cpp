#include "../android/app/src/main/cpp/jni/root_protocol.h"

#include <atomic>
#include <chrono>
#include <iostream>
#include <thread>

namespace {
using namespace disksize;
void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }

void broken_reader() {
    int pipe_fds[2]; require(pipe(pipe_fds) == 0, "pipe");
    CancellationToken token;
    {
        wire::Writer writer(pipe_fds[1], wire::Bytes(16, 1), token);
        close(pipe_fds[0]);
        bool rejected = false;
        try { writer.frame(1, wire::Bytes(12, 0)); } catch (...) { rejected = true; }
        require(rejected, "Broken pipe was accepted");
    }
    close(pipe_fds[1]);
}

void cancelled_backpressure() {
    int pipe_fds[2]; require(pipe(pipe_fds) == 0, "pipe");
    CancellationToken token;
    std::atomic<bool> writing{false};
    std::atomic<bool> rejected{false};
    auto start = std::chrono::steady_clock::now();
    std::thread worker([&] {
        wire::Writer writer(pipe_fds[1], wire::Bytes(16, 1), token);
        writing.store(true);
        try {
            for (int index = 0; index < 64; ++index) writer.frame(3, wire::Bytes(65536, 42));
        } catch (...) { rejected.store(true); }
    });
    while (!writing.load()) std::this_thread::yield();
    std::this_thread::sleep_for(std::chrono::milliseconds(150));
    token.request(); worker.join();
    const auto elapsed = std::chrono::steady_clock::now() - start;
    close(pipe_fds[0]); close(pipe_fds[1]);
    require(rejected.load(), "Blocked consumer ignored cancellation");
    require(elapsed < std::chrono::seconds(2), "Cancellation did not break pipe backpressure");
}

void stalled_final_output() {
    int pipe_fds[2]; require(pipe(pipe_fds) == 0, "pipe");
    CancellationToken token;
    std::atomic<bool> rejected{false};
    std::thread worker([&] {
        wire::Writer writer(pipe_fds[1], wire::Bytes(16, 1), token);
        try { for (int index = 0; index < 64; ++index) writer.frame(3, wire::Bytes(65536, 42), true); }
        catch (...) { rejected.store(true); }
    });
    // A reader that stays alive but ceases consumption must not hold root
    // resources forever, including during the terminal result transfer.
    const auto start = std::chrono::steady_clock::now(); worker.join();
    const auto elapsed = std::chrono::steady_clock::now() - start;
    close(pipe_fds[0]); close(pipe_fds[1]);
    require(rejected.load(), "Stalled final consumer was accepted");
    require(elapsed >= std::chrono::seconds(9) && elapsed < std::chrono::seconds(13), "Unexpected stall deadline");
}
} // namespace

int main() {
    try {
        broken_reader(); cancelled_backpressure(); stalled_final_output();
        std::cout << "{\"tests\":3,\"failed\":0,\"suite\":\"root-pipe\"}\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n'; return 1;
    }
}
