"""Small, revision-checked Android adapter patches to the public port."""
from pathlib import Path


def apply(source: Path):
    path = source / "src/config/supplementary/executor/Executor.cpp"
    text = path.read_text()
    if "OMAMOBILE_POSIX_SPAWN" in text:
        return
    text = text.replace("#include <cerrno>", "#include <cerrno>\n#include <spawn.h>\n#include <unistd.h>")
    marker = "    pid_t      child = fork();"
    if text.count(marker) != 1:
        raise RuntimeError("Pinned Android executor contract changed")
    android = r'''#ifdef __ANDROID__
    // OMAMOBILE_POSIX_SPAWN: never run C++ allocation/setenv between fork and
    // exec in an ART process. Preserve launch metadata in a child-only env.
    const char* wrapper = getenv("ARLINUX_EXEC_WRAPPER");
    if (!wrapper || wrapper[0] != '/') return std::nullopt;
    std::vector<std::string> values;
    for (char** item = environ; *item; ++item) values.emplace_back(*item);
    auto put = [&](const std::string& key, const std::string& value) {
        const auto prefix = key + "=";
        for (auto& item : values) {
            if (item.starts_with(prefix)) { item = prefix + value; return; }
        }
        values.emplace_back(prefix + value);
    };
    for (const auto& e : HLENV) put(e.first, e.second);
    put("WAYLAND_DISPLAY", g_pCompositor->m_wlDisplaySocket);
    if (!execRuleToken.empty()) put(Desktop::Rule::EXEC_RULE_ENV_NAME, execRuleToken);
    std::vector<char*> environment;
    for (auto& value : values) environment.push_back(value.data());
    environment.push_back(nullptr);
    char* argv[] = {const_cast<char*>("sh"), const_cast<char*>(wrapper), const_cast<char*>(args.c_str()), nullptr};
    posix_spawn_file_actions_t actions;
    posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_addopen(&actions, STDIN_FILENO, "/dev/null", O_RDONLY, 0);
    posix_spawn_file_actions_addopen(&actions, STDOUT_FILENO, "/dev/null", O_WRONLY, 0);
    posix_spawn_file_actions_addopen(&actions, STDERR_FILENO, "/dev/null", O_WRONLY, 0);
    posix_spawnattr_t attr;
    posix_spawnattr_init(&attr);
    sigset_t mask;
    sigemptyset(&mask);
    posix_spawnattr_setsigmask(&attr, &mask);
    posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSIGMASK);
    pid_t child = -1;
    int error = posix_spawn(&child, "/system/bin/sh", &actions, &attr, argv, environment.data());
    posix_spawn_file_actions_destroy(&actions);
    posix_spawnattr_destroy(&attr);
    if (error) {
        LOG(Log::ERR, "[executor] Android posix_spawn failed: {}", error);
        return std::nullopt;
    }
#else
'''
    text = text.replace(marker, android + marker)
    text = text.replace("    // run in parent\n", "#endif\n    // run in parent\n")
    path.write_text(text)


def apply_renderer(source: Path):
    path = source / "src/render/OpenGL.cpp"
    text = path.read_text()
    if "OMAMOBILE_GLES_RESET_QUERY" in text:
        return
    call = "glGetGraphicsResetStatus();"
    if text.count(call) != 2:
        raise RuntimeError("Pinned GLES reset-query contract changed")
    text = text.replace(call, "omamobileGraphicsResetStatus();")
    helper = r'''
// OMAMOBILE_GLES_RESET_QUERY: GLES 3.0's exported core 3.2 entry point may
// be an unsupported emulator stub. Select the advertised robustness API;
// actual reset statuses still go through Hyprland's existing failure path.
static GLenum omamobileGraphicsResetStatus() {
#ifdef __ANDROID__
    using Query = GLenum (*)();
    static Query query = []() -> Query {
        GLint major = 0, minor = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &major);
        glGetIntegerv(GL_MINOR_VERSION, &minor);
        if (major > 3 || (major == 3 && minor >= 2)) return &glGetGraphicsResetStatus;
        GLint count = 0;
        glGetIntegerv(GL_NUM_EXTENSIONS, &count);
        bool khr = false, ext = false;
        for (GLint i = 0; i < count; ++i) {
            const char* name = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, i));
            if (!name) continue;
            khr = khr || !strcmp(name, "GL_KHR_robustness");
            ext = ext || !strcmp(name, "GL_EXT_robustness");
        }
        if (khr) return reinterpret_cast<Query>(eglGetProcAddress("glGetGraphicsResetStatusKHR"));
        if (ext) return reinterpret_cast<Query>(eglGetProcAddress("glGetGraphicsResetStatusEXT"));
        return nullptr;
    }();
    return query ? query() : GL_NO_ERROR;
#else
    return glGetGraphicsResetStatus();
#endif
}

'''
    marker = "using namespace Hyprutils::OS;"
    if text.count(marker) != 1:
        raise RuntimeError("Pinned OpenGL insertion point changed")
    path.write_text(text.replace(marker, helper + marker))
