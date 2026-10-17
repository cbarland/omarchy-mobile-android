#include "android_guest_dialog.hpp"
#include <cassert>
#include <cstring>
#include <dirent.h>
#include <iostream>
#include <linux/filter.h>
#include <linux/sched.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <sys/prctl.h>
#include <sys/syscall.h>

using namespace Omamobile::GuestDialog;
static bool failSpawn = false;
extern "C" int __real_posix_spawn(pid_t*, const char*, const posix_spawn_file_actions_t*, const posix_spawnattr_t*, char* const[], char* const[]);
extern "C" int __wrap_posix_spawn(pid_t* pid, const char* path, const posix_spawn_file_actions_t* actions,
                                  const posix_spawnattr_t* attr, char* const argv[], char* const env[]) {
    return failSpawn ? EAGAIN : __real_posix_spawn(pid, path, actions, attr, argv, env);
}

static int fdCount() {
    DIR* directory = opendir("/proc/self/fd"); assert(directory);
    int count = 0;
    while (auto entry = readdir(directory)) if (entry->d_name[0] != '.') count++;
    closedir(directory); return count;
}

static void noForkGate() {
    sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)),
#ifdef __NR_fork
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_fork, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
#endif
#ifdef __NR_vfork
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_vfork, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
#endif
#ifdef __NR_clone3
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone3, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, CLONE_VM, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    sock_fprog program{static_cast<unsigned short>(std::size(filter)), filter};
    assert(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
    assert(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
#ifdef __NR_fork
    errno = 0; assert(syscall(__NR_fork) == -1 && errno == EPERM);
#endif
}

static std::string output(Child& child) {
    Fd fd(child.releaseOutput());
    std::string result;
    char buffer[4096];
    while (true) {
        pollfd ready{fd.get(), POLLIN, 0};
        assert(poll(&ready, 1, 2000) > 0);
        ssize_t count = read(fd.get(), buffer, sizeof(buffer));
        if (!count) return result;
        if (count < 0 && errno == EINTR) continue;
        assert(count > 0); result.append(buffer, count);
    }
}

static void reapedEventually(pid_t pid) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (kill(pid, 0) == 0) {
        assert(std::chrono::steady_clock::now() < deadline);
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    int status; assert(waitpid(pid, &status, WNOHANG) == -1 && errno == ECHILD);
}

int main(int argc, char** argv) {
    if (argc >= 2 && !strcmp(argv[1], "--child")) {
        std::cerr << "guest stderr remains visible\n";
        sigset_t mask; sigprocmask(SIG_SETMASK, nullptr, &mask);
        assert(!sigismember(&mask, SIGUSR1));
        for (int index = 2; index < argc; index++) std::cout << argv[index] << '\0';
        std::cout << "token=" << getenv("HL_EXEC_RULE_TOKEN") << '\0';
        std::cout << "pid=" << getpid() << '\0';
        return 0;
    }
    if (argc >= 2 && (!strcmp(argv[1], "--linger") || !strcmp(argv[1], "--silent-linger"))) {
        signal(SIGTERM, SIG_IGN);
        if (!strcmp(argv[1], "--linger")) { std::cout << "Wait\n" << std::flush; close(STDOUT_FILENO); }
        while (true) pause();
    }
    assert(argc == 4);
    const std::string wrapper = argv[1], unavailable = argv[2], slow = argv[3];
    setenv("HL_EXEC_RULE_TOKEN", "parent-token", 1);
    noForkGate();
    const int before = fdCount();
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGUSR1); sigprocmask(SIG_BLOCK, &mask, nullptr);
    std::vector<std::string> args = {argv[0], "--child", "spaces and tabs\t", "quote'\"", "$(touch SHOULD_NOT_EXIST)", "line1\nline2", "雪❄"};
    auto child = spawn(wrapper, args, "child token '$()\n雪"); assert(child);
    const pid_t pid = child->pid();
    std::string expected;
    for (size_t index = 2; index < args.size(); index++) { expected += args[index]; expected += '\0'; }
    expected += "token=child token '$()\n雪"; expected += '\0';
    expected += "pid=" + std::to_string(pid); expected += '\0';
    assert(output(*child) == expected);
    int status = -1; assert(child->waitUntil(status, std::chrono::steady_clock::now() + std::chrono::seconds(2)));
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0); assert(child->pid() == 0);
    assert(std::string(getenv("HL_EXEC_RULE_TOKEN")) == "parent-token"); child.reset();

    assert(available(wrapper.c_str())); assert(!available(unavailable.c_str()));
    auto started = std::chrono::steady_clock::now(); assert(!available(slow.c_str()));
    assert(std::chrono::steady_clock::now() - started < std::chrono::seconds(2));

    // Parent standard descriptors can be absent; pipe file actions must not alias them.
    int saved[3]; for (int index = 0; index < 3; index++) { saved[index] = fcntl(index, F_DUPFD_CLOEXEC, 10); assert(saved[index] >= 0); close(index); }
    child = spawn(wrapper, {argv[0], "--child", "closed standard fds"}); assert(child);
    const pid_t closedPid = child->pid(); const std::string closedOutput = output(*child);
    for (int index = 0; index < 3; index++) { assert(dup2(saved[index], index) == index); close(saved[index]); }
    assert(closedOutput.starts_with(std::string("closed standard fds\0", 20)));
    child.reset(); reapedEventually(closedPid);

    // EOF is not proof of exit. Deliver Wait, then dispose the still-live owned child.
    child = spawn(wrapper, {argv[0], "--linger"}); assert(child);
    const pid_t lingering = child->pid(); assert(output(*child) == "Wait\n");
    assert(kill(lingering, 0) == 0); child.reset(); reapedEventually(lingering);
    child = spawn(wrapper, {argv[0], "--silent-linger"}); assert(child);
    const pid_t cancelled = child->pid(); child.reset(); reapedEventually(cancelled);

    child = spawn("/nonexistent/wrapper", {"ignored"}); assert(child);
    assert(output(*child).empty()); assert(child->waitUntil(status, std::chrono::steady_clock::now() + std::chrono::seconds(2)));
    assert(!WIFEXITED(status) || WEXITSTATUS(status) != 0); child.reset();
    failSpawn = true; assert(!spawn(wrapper, {"ignored"})); assert(errno == EAGAIN); failSpawn = false;
    assert(fdCount() == before);
    std::cout << "argv/stdout/token/PID/signal-mask/no-fork/probe/failure/FD0-2/cancel/reaping: passed\n";
}
