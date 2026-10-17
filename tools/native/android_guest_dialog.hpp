#pragma once

// Android compositor -> guest argv boundary. No Hyprutils fork/exec path and no
// process-wide signal handler. Also compiled by the Linux behavioral harness.
#include <cerrno>
#include <chrono>
#include <csignal>
#include <fcntl.h>
#include <memory>
#include <spawn.h>
#include <string>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <utility>
#include <vector>

extern char** environ;

namespace Omamobile::GuestDialog {
class Fd {
  public:
    explicit Fd(int fd = -1) : m_fd(fd) {}
    ~Fd() { reset(); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    Fd(Fd&& other) noexcept : m_fd(other.release()) {}
    Fd& operator=(Fd&& other) noexcept { reset(other.release()); return *this; }
    int get() const { return m_fd; }
    int release() { return std::exchange(m_fd, -1); }
    void reset(int fd = -1) { if (m_fd >= 0) close(m_fd); m_fd = fd; }
  private:
    int m_fd;
};

inline bool pollExit(pid_t& pid, int& status, std::chrono::steady_clock::time_point deadline) {
    while (pid > 0) {
        pid_t result = waitpid(pid, &status, WNOHANG);
        if (result == pid) { pid = 0; return true; }
        if (result < 0 && errno == ECHILD) { pid = 0; status = -1; return true; }
        if (result < 0 && errno != EINTR) return false;
        if (std::chrono::steady_clock::now() >= deadline) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return true;
}

// Only this owner waits for/signals this unreaped PID. A zombie continues to
// fence PID reuse until waitpid consumes it; never signal after a successful reap.
inline void finish(pid_t pid) {
    int status = 0;
    if (pollExit(pid, status, std::chrono::steady_clock::now() + std::chrono::milliseconds(100))) return;
    kill(pid, SIGTERM);
    if (pollExit(pid, status, std::chrono::steady_clock::now() + std::chrono::milliseconds(100))) return;
    kill(pid, SIGKILL);
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
}

class Child {
  public:
    Child(pid_t pid, Fd output) : m_pid(pid), m_output(std::move(output)) {}
    Child(const Child&) = delete;
    Child& operator=(const Child&) = delete;
    ~Child() {
        m_output.reset();
        const pid_t pid = std::exchange(m_pid, 0);
        if (pid <= 0) return;
        // The event-loop owner clears dialog identity before releasing Child.
        // Closing stdout while remaining alive cannot leave an eternal waiter.
        try { std::thread([pid] { finish(pid); }).detach(); }
        catch (...) { finish(pid); }
    }
    pid_t pid() const { return m_pid; }
    int releaseOutput() { return m_output.release(); }
    void terminate() const { if (m_pid > 0) kill(m_pid, SIGKILL); }
    bool waitUntil(int& status, std::chrono::steady_clock::time_point deadline) { return pollExit(m_pid, status, deadline); }
    void stopAndWait() { const pid_t pid = std::exchange(m_pid, 0); if (pid > 0) finish(pid); }
  private:
    pid_t m_pid;
    Fd m_output;
};

inline std::unique_ptr<Child> spawn(const std::string& wrapper, const std::vector<std::string>& guest,
                                  const std::string& token = "") {
    if (wrapper.empty() || wrapper.front() != '/' || guest.empty()) { errno = EINVAL; return nullptr; }
    std::vector<std::string> environment;
    for (char** item = environ; item && *item; ++item) {
        if (!token.empty() && std::string(*item).rfind("HL_EXEC_RULE_TOKEN=", 0) == 0) continue;
        environment.emplace_back(*item);
    }
    if (!token.empty()) environment.emplace_back("HL_EXEC_RULE_TOKEN=" + token);
    std::vector<char*> envp;
    for (auto& value : environment) envp.push_back(value.data());
    envp.push_back(nullptr);
    std::vector<std::string> arguments = {"sh", wrapper, "--argv"};
    arguments.insert(arguments.end(), guest.begin(), guest.end());
    std::vector<char*> argv;
    for (auto& value : arguments) argv.push_back(value.data());
    argv.push_back(nullptr);

    int descriptors[2];
    if (pipe2(descriptors, O_CLOEXEC) < 0) return nullptr;
    Fd read(descriptors[0]), write(descriptors[1]);
    // File actions also manipulate 0/1/2. Normalize first, even if the host was
    // started with a standard FD closed, so dup2/open/close cannot alias them.
    for (Fd* fd : {&read, &write}) {
        if (fd->get() > STDERR_FILENO) continue;
        int duplicate = fcntl(fd->get(), F_DUPFD_CLOEXEC, STDERR_FILENO + 1);
        if (duplicate < 0) return nullptr;
        fd->reset(duplicate);
    }
    posix_spawn_file_actions_t actions;
    int error = posix_spawn_file_actions_init(&actions);
    if (error) { errno = error; return nullptr; }
    auto action = [&](int result) { if (!error) error = result; };
    action(posix_spawn_file_actions_addopen(&actions, STDIN_FILENO, "/dev/null", O_RDONLY, 0));
    action(posix_spawn_file_actions_adddup2(&actions, write.get(), STDOUT_FILENO));
    // Keep the compositor's native log sink for guest launch diagnostics. A host
    // with stderr absent still gets a valid standard FD without aliasing pipes.
    if (fcntl(STDERR_FILENO, F_GETFD) < 0) {
        if (errno == EBADF) action(posix_spawn_file_actions_addopen(&actions, STDERR_FILENO, "/dev/null", O_WRONLY, 0));
        else action(errno);
    }
    action(posix_spawn_file_actions_addclose(&actions, read.get()));
    action(posix_spawn_file_actions_addclose(&actions, write.get()));
    posix_spawnattr_t attributes;
    int initialized = posix_spawnattr_init(&attributes);
    if (!initialized) {
        sigset_t mask;
        sigemptyset(&mask);
        action(posix_spawnattr_setsigmask(&attributes, &mask));
        action(posix_spawnattr_setflags(&attributes, POSIX_SPAWN_SETSIGMASK));
    } else action(initialized);
    pid_t pid = -1;
#ifdef __ANDROID__
    constexpr const char* shell = "/system/bin/sh";
#else
    constexpr const char* shell = "/bin/sh";
#endif
    if (!error) error = posix_spawn(&pid, shell, &actions, &attributes, argv.data(), envp.data());
    if (!initialized) posix_spawnattr_destroy(&attributes);
    posix_spawn_file_actions_destroy(&actions);
    if (error) { errno = error; return nullptr; }
    write.reset();
    try { return std::make_unique<Child>(pid, std::move(read)); }
    catch (...) { finish(pid); throw; }
}

inline bool available(const char* wrapper) {
    if (!wrapper) return false;
    auto child = spawn(wrapper, {"/bin/sh", "-c", "command -v \"$1\" >/dev/null 2>&1", "sh", "hyprland-dialog"});
    if (!child) return false;
    int status = 0;
    bool exited = child->waitUntil(status, std::chrono::steady_clock::now() + std::chrono::seconds(1));
    if (!exited) child->stopAndWait();
    return exited && WIFEXITED(status) && WEXITSTATUS(status) == 0;
}
} // namespace Omamobile::GuestDialog
