"""Apply the issue 9 two-output feasibility patch to the pinned Android port.

The input is the already prepared anhyprland source tree used by build_port.py.
Every edit checks its expected anchor and a second run is a no-op.
"""
import argparse
from pathlib import Path
import subprocess

PIN = "bc13ae5859097791b3e93a32632727a7f7075354"


def change(path: Path, before: str, after: str) -> None:
    text = path.read_text()
    if before in after and after in text:
        return
    if text.count(before) == 1:
        path.write_text(text.replace(before, after))
    elif not after or after not in text:
        raise RuntimeError(f"Android port anchor changed: {path}: {before[:80]!r}")


def remove(path: Path, before: str, completed_anchor: str) -> None:
    text = path.read_text()
    if text.count(before) == 1:
        path.write_text(text.replace(before, ""))
    elif before in text or completed_anchor not in text:
        raise RuntimeError(f"Android port removal anchor changed: {path}: {before[:80]!r}")


def apply(source: Path) -> None:
    head = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if head != PIN:
        raise RuntimeError(f"Expected anhyprland {PIN}, got {head}")
    h = source / "src/android/Embed.h"
    change(h, "int  anhyprland_window(struct ANativeWindow* window, int width, int height);", "int  anhyprland_window(struct ANativeWindow* window, int width, int height);\n// Output IDs: 0 is the phone, 1 is the external display. Null detaches a surface.\nint  anhyprland_output(int id, struct ANativeWindow* window, int width, int height);")
    change(h, "int  anhyprland_pointer(float x, float y, uint32_t button, int pressed);", "int  anhyprland_pointer(float x, float y, uint32_t button, int pressed);\nint  anhyprland_pointer_output(int id, float x, float y, uint32_t button, int pressed);")

    cpp = source / "src/android/Embed.cpp"
    start = '''extern "C" int anhyprland_window(ANativeWindow* window, int width, int height) {
    if (window && (width <= 0 || height <= 0))
        return -EINVAL;
    if (window)
        ANativeWindow_acquire(window);
    auto retained = std::shared_ptr<ANativeWindow>(window, [](ANativeWindow* value) {
        if (value)
            ANativeWindow_release(value);
    });
    return enqueue([retained, width, height] {
        if (auto output = backend(); output && !output->setWindow(retained.get(), width, height))
            __android_log_print(ANDROID_LOG_ERROR, "anhyprland", "Cannot attach replacement Surface");
    });
}

extern "C" int anhyprland_pointer(float x, float y, uint32_t button, int pressed) {
    return enqueue([=] {
        if (auto layout = Layout::Tiled::CYibuAlgorithm::active(); layout && layout->pointer(x, y, button, pressed)) return;
        if (auto output = backend())
            output->pointer(x, y, button, pressed);
    });
}'''
    end = '''extern "C" int anhyprland_output(int id, ANativeWindow* window, int width, int height) {
    if (id < 0 || id > 1 || (window && (width <= 0 || height <= 0)))
        return -EINVAL;
    if (window)
        ANativeWindow_acquire(window);
    auto retained = std::shared_ptr<ANativeWindow>(window, [](ANativeWindow* value) {
        if (value)
            ANativeWindow_release(value);
    });
    return enqueue([id, retained, width, height] {
        if (auto output = backend(); output && !output->setWindow(id, retained.get(), width, height))
            __android_log_print(ANDROID_LOG_ERROR, "anhyprland", "Cannot attach Android output %d", id);
    });
}

extern "C" int anhyprland_window(ANativeWindow* window, int width, int height) {
    return anhyprland_output(0, window, width, height);
}

extern "C" int anhyprland_pointer_output(int id, float x, float y, uint32_t button, int pressed) {
    if (id < 0 || id > 1)
        return -EINVAL;
    return enqueue([=] {
        if (id == 0) {
            if (auto layout = Layout::Tiled::CYibuAlgorithm::active(); layout && layout->pointer(x, y, button, pressed)) return;
        }
        if (auto output = backend())
            output->pointer(id, x, y, button, pressed);
    });
}

extern "C" int anhyprland_pointer(float x, float y, uint32_t button, int pressed) {
    return anhyprland_pointer_output(0, x, y, button, pressed);
}'''
    change(cpp, start, end)

    h = source / "subprojects/aquamarine/include/aquamarine/backend/Android.hpp"
    change(h, '#include <android/native_window.h>', '#include <android/native_window.h>\n#include <array>')
    change(h, 'explicit CAndroidOutput(Hyprutils::Memory::CWeakPointer<CAndroidBackend> backend);', 'explicit CAndroidOutput(Hyprutils::Memory::CWeakPointer<CAndroidBackend> backend, int id);\n        const int id;')
    change(h, 'bool setWindow(ANativeWindow* window, int width, int height);\n        ANativeWindow* window() const;\n        void scheduleFrame();\n        bool frameScheduled() const;\n        void pointer(float x, float y, uint32_t button, bool pressed);', 'bool setWindow(int id, ANativeWindow* window, int width, int height);\n        ANativeWindow* window(int id) const;\n        void scheduleFrame(int id);\n        bool frameScheduled(int id) const;\n        void pointer(int id, float x, float y, uint32_t button, bool pressed);')
    change(h, 'std::function<bool(ANativeWindow*)> attachWindow;\n        std::function<bool(Hyprutils::Memory::CSharedPointer<IBuffer>)> present;', 'std::function<bool(int, ANativeWindow*)> attachWindow;\n        std::function<bool(int, Hyprutils::Memory::CSharedPointer<IBuffer>)> present;')
    remove(h, '''        ANativeWindow* m_window = nullptr;
        int m_width = 0, m_height = 0;
        bool m_frameScheduled = false;
        std::chrono::steady_clock::time_point m_lastFrame;
''', 'std::array<SDisplay, 2> m_displays;')
    change(h, '''        Hyprutils::Memory::CSharedPointer<CAndroidOutput> m_output;
        Hyprutils::Memory::CSharedPointer<CAndroidKeyboard> m_keyboard;''', '''        struct SDisplay {
            Hyprutils::Memory::CSharedPointer<CAndroidOutput> output;
            ANativeWindow* window = nullptr;
            int width = 0, height = 0;
            bool frameScheduled = false;
            std::chrono::steady_clock::time_point lastFrame;
            std::chrono::steady_clock::time_point nextFrame;
        };
        std::array<SDisplay, 2> m_displays;
        void armTimer();
        Hyprutils::Memory::CSharedPointer<CAndroidKeyboard> m_keyboard;''')
    cpp = source / "subprojects/aquamarine/src/backend/Android.cpp"
    change(cpp, 'CAndroidOutput::CAndroidOutput(CWeakPointer<CAndroidBackend> backend) : m_backend(backend) {\n    name = "ANDROID-1";', 'CAndroidOutput::CAndroidOutput(CWeakPointer<CAndroidBackend> backend, int id) : id(id), m_backend(backend) {\n    name = "ANDROID-" + std::to_string(id + 1);')
    change(cpp, 'pending.buffer && m_backend->window()', 'pending.buffer && m_backend->window(id)')
    change(cpp, 'm_backend->present(pending.buffer)', 'm_backend->present(id, pending.buffer)')
    change(cpp, 'm_backend->scheduleFrame();', 'm_backend->scheduleFrame(id);')
    change(cpp, 'm_backend->frameScheduled();', 'm_backend->frameScheduled(id);')
    change(cpp, '''CAndroidBackend::CAndroidBackend(CWeakPointer<CBackend> backend, ANativeWindow* window, int width, int height) :
    m_backend(backend), m_window(window), m_width(width), m_height(height) {
    if (m_window)
        ANativeWindow_acquire(m_window);
    m_timer = Hyprutils::OS::CFileDescriptor{timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC | TFD_NONBLOCK)};
    m_lastFrame = std::chrono::steady_clock::now();
}

CAndroidBackend::~CAndroidBackend() {
    if (m_window)
        ANativeWindow_release(m_window);
}''', '''CAndroidBackend::CAndroidBackend(CWeakPointer<CBackend> backend, ANativeWindow* window, int width, int height) : m_backend(backend) {
    auto& phone = m_displays[0];
    phone.window = window;
    phone.width = width;
    phone.height = height;
    if (window)
        ANativeWindow_acquire(window);
    m_timer = Hyprutils::OS::CFileDescriptor{timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC | TFD_NONBLOCK)};
    for (auto& display : m_displays)
        display.lastFrame = std::chrono::steady_clock::now();
}

CAndroidBackend::~CAndroidBackend() {
    for (auto& display : m_displays)
        if (display.window)
            ANativeWindow_release(display.window);
}''')
    change(cpp, 'if (!m_window || !m_timer.isValid() || m_width <= 0 || m_height <= 0)', 'if (!m_displays[0].window || !m_timer.isValid() || m_displays[0].width <= 0 || m_displays[0].height <= 0)')
    change(cpp, '''    m_frameScheduled = false;
    // Device listeners depend on compositor managers initialized after start().''', '''    // Device listeners depend on compositor managers initialized after start().''')
    change(cpp, '''    if (m_window && m_output) {
        m_lastFrame = std::chrono::steady_clock::now();
        m_output->events.frame.emit();
    }
    return true;''', '''    const auto now = std::chrono::steady_clock::now();
    for (auto& display : m_displays) {
        if (display.frameScheduled && display.nextFrame <= now) {
            display.frameScheduled = false;
            if (display.window && display.output) {
                display.lastFrame = now;
                display.output->events.frame.emit();
            }
        }
    }
    armTimer();
    return true;''')
    change(cpp, '''    createOutput();
    scheduleFrame();''', '''    createOutput();
    scheduleFrame(0);''')
    change(cpp, '''bool CAndroidBackend::createOutput(const std::string&) {
    if (m_output)
        return false;
    m_output = makeShared<CAndroidOutput>(self);
    m_output->modes.emplace_back(makeShared<SOutputMode>(Vector2D{m_width, m_height}, 60000, true));
    m_output->swapchain = CSwapchain::create(m_allocator, self.lock());
    m_backend->events.newOutput.emit(m_output);
    return true;
}''', '''bool CAndroidBackend::createOutput(const std::string& name) {
    const int id = name == "ANDROID-2" ? 1 : 0;
    auto& display = m_displays[id];
    if (display.output || !display.window)
        return false;
    display.output = makeShared<CAndroidOutput>(self, id);
    display.output->modes.emplace_back(makeShared<SOutputMode>(Vector2D{display.width, display.height}, 60000, true));
    display.output->swapchain = CSwapchain::create(m_allocator, self.lock());
    m_backend->events.newOutput.emit(display.output);
    return true;
}''')
    change(cpp, '''ANativeWindow* CAndroidBackend::window() const { return m_window; }
bool CAndroidBackend::frameScheduled() const { return m_frameScheduled; }

bool CAndroidBackend::setWindow(ANativeWindow* window, int width, int height) {
    if (window && (width <= 0 || height <= 0))
        return false;
    if (attachWindow && !attachWindow(window))
        return false;
    if (window)
        ANativeWindow_acquire(window);
    if (m_window)
        ANativeWindow_release(m_window);
    m_window = window;
    if (!window) {
        itimerspec disarm = {};
        timerfd_settime(m_timer.get(), 0, &disarm, nullptr);
        m_frameScheduled = false;
        return true;
    }
    const bool resized = width != m_width || height != m_height;
    m_width = width;
    m_height = height;
    if (resized && m_output)
        m_output->events.state.emit(IOutput::SStateEvent{.size = {width, height}});
    scheduleFrame();
    return true;
}

void CAndroidBackend::scheduleFrame() {
    if (!m_window || m_frameScheduled)
        return;
    const auto next = std::max(m_lastFrame + std::chrono::nanoseconds(16666667), std::chrono::steady_clock::now() + std::chrono::nanoseconds(1));
    const auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(next.time_since_epoch()).count();
    itimerspec timer = {.it_value = {.tv_sec = ns / 1000000000, .tv_nsec = ns % 1000000000}};
    m_frameScheduled = timerfd_settime(m_timer.get(), TFD_TIMER_ABSTIME, &timer, nullptr) == 0;
}

void CAndroidBackend::pointer(float x, float y, uint32_t button, bool pressed) {
    if (!m_pointer || !std::isfinite(x) || !std::isfinite(y))
        return;
    m_pointer->events.warp.emit(IPointer::SWarpEvent{.timeMs = timeMs(), .absolute = {x / m_width, y / m_height}, .output = m_output});''', '''ANativeWindow* CAndroidBackend::window(int id) const { return m_displays.at(id).window; }
bool CAndroidBackend::frameScheduled(int id) const { return m_displays.at(id).frameScheduled; }

bool CAndroidBackend::setWindow(int id, ANativeWindow* window, int width, int height) {
    if (id < 0 || id > 1 || (window && (width <= 0 || height <= 0)))
        return false;
    auto& display = m_displays[id];
    // Android rejects a second EGLSurface for an ANativeWindow already connected
    // to EGL. Keep the existing surface when the host repeats the same window.
    if (display.window != window && attachWindow && !attachWindow(id, window))
        return false;
    if (window)
        ANativeWindow_acquire(window);
    if (display.window)
        ANativeWindow_release(display.window);
    display.window = window;
    if (!window) {
        display.frameScheduled = false;
        armTimer();
        return true;
    }
    const bool resized = width != display.width || height != display.height;
    display.width = width;
    display.height = height;
    if (!display.output)
        createOutput(id == 0 ? "ANDROID-1" : "ANDROID-2");
    else if (resized)
        display.output->events.state.emit(IOutput::SStateEvent{.size = {width, height}});
    scheduleFrame(id);
    return true;
}

void CAndroidBackend::armTimer() {
    auto next = std::chrono::steady_clock::time_point::max();
    for (const auto& display : m_displays)
        if (display.frameScheduled && display.window)
            next = std::min(next, display.nextFrame);
    itimerspec timer = {};
    if (next != std::chrono::steady_clock::time_point::max()) {
        const auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(next.time_since_epoch()).count();
        timer.it_value = {.tv_sec = ns / 1000000000, .tv_nsec = ns % 1000000000};
    }
    timerfd_settime(m_timer.get(), TFD_TIMER_ABSTIME, &timer, nullptr);
}

void CAndroidBackend::scheduleFrame(int id) {
    auto& display = m_displays.at(id);
    if (!display.window || display.frameScheduled)
        return;
    display.nextFrame = std::max(display.lastFrame + std::chrono::nanoseconds(16666667), std::chrono::steady_clock::now() + std::chrono::nanoseconds(1));
    display.frameScheduled = true;
    armTimer();
}

void CAndroidBackend::pointer(int id, float x, float y, uint32_t button, bool pressed) {
    const auto& display = m_displays.at(id);
    if (!m_pointer || !display.window || !display.output || !std::isfinite(x) || !std::isfinite(y))
        return;
    m_pointer->events.warp.emit(IPointer::SWarpEvent{.timeMs = timeMs(), .absolute = {x / display.width, y / display.height}, .output = display.output});''')

    h = source / "src/render/OpenGL.hpp"
    change(h, '#include <map>', '#include <map>\n#include <array>')
    change(h, '''        bool       attachAndroidWindow(ANativeWindow* window);
        bool       presentAndroidBuffer(SP<Aquamarine::IBuffer> buffer);''', '''        bool       attachAndroidWindow(int id, ANativeWindow* window);
        bool       presentAndroidBuffer(int id, SP<Aquamarine::IBuffer> buffer);''')
    change(h, 'EGLSurface m_androidWindowSurface = EGL_NO_SURFACE;', 'std::array<EGLSurface, 2> m_androidWindowSurfaces = {EGL_NO_SURFACE, EGL_NO_SURFACE};')

    cpp = source / "src/render/AndroidEGL.cpp"
    change(cpp, 'backend && attachAndroidWindow(backend->window())', 'backend && attachAndroidWindow(0, backend->window(0))')
    change(cpp, 'backend->attachWindow = [this](ANativeWindow* window) { return attachAndroidWindow(window); };', 'backend->attachWindow = [this](int id, ANativeWindow* window) { return attachAndroidWindow(id, window); };')
    change(cpp, 'backend->present      = [this](SP<Aquamarine::IBuffer> buffer) { return presentAndroidBuffer(buffer); };', 'backend->present      = [this](int id, SP<Aquamarine::IBuffer> buffer) { return presentAndroidBuffer(id, buffer); };')
    change(cpp, '''bool CHyprOpenGLImpl::attachAndroidWindow(ANativeWindow* window) {
    if (!eglMakeCurrent(m_eglDisplay, m_androidIdleSurface, m_androidIdleSurface, m_eglContext))
        return false;
    if (m_androidWindowSurface != EGL_NO_SURFACE)
        eglDestroySurface(m_eglDisplay, m_androidWindowSurface);
    m_androidWindowSurface = EGL_NO_SURFACE;
    if (!window)
        return true;
    EGLint visual = 0;
    if (!eglGetConfigAttrib(m_eglDisplay, m_androidConfig, EGL_NATIVE_VISUAL_ID, &visual) || ANativeWindow_setBuffersGeometry(window, 0, 0, visual) != 0)
        return false;
    m_androidWindowSurface = eglCreateWindowSurface(m_eglDisplay, m_androidConfig, window, nullptr);
    return m_androidWindowSurface != EGL_NO_SURFACE;
}''', '''bool CHyprOpenGLImpl::attachAndroidWindow(int id, ANativeWindow* window) {
    if (id < 0 || id > 1 || !eglMakeCurrent(m_eglDisplay, m_androidIdleSurface, m_androidIdleSurface, m_eglContext))
        return false;
    EGLSurface replacement = EGL_NO_SURFACE;
    if (window) {
        EGLint visual = 0;
        if (!eglGetConfigAttrib(m_eglDisplay, m_androidConfig, EGL_NATIVE_VISUAL_ID, &visual) || ANativeWindow_setBuffersGeometry(window, 0, 0, visual) != 0)
            return false;
        replacement = eglCreateWindowSurface(m_eglDisplay, m_androidConfig, window, nullptr);
        if (replacement == EGL_NO_SURFACE)
            return false;
    }
    if (m_androidWindowSurfaces[id] != EGL_NO_SURFACE)
        eglDestroySurface(m_eglDisplay, m_androidWindowSurfaces[id]);
    m_androidWindowSurfaces[id] = replacement;
    return true;
}''')
    change(cpp, 'bool CHyprOpenGLImpl::presentAndroidBuffer(SP<Aquamarine::IBuffer> buffer) {\n    if (m_androidWindowSurface == EGL_NO_SURFACE', 'bool CHyprOpenGLImpl::presentAndroidBuffer(int id, SP<Aquamarine::IBuffer> buffer) {\n    if (id < 0 || id > 1 || m_androidWindowSurfaces[id] == EGL_NO_SURFACE')
    change(cpp, '''    if (!eglMakeCurrent(m_eglDisplay, m_androidWindowSurface, m_androidWindowSurface, m_eglContext))
        return false;''', '''    const auto surface = m_androidWindowSurfaces[id];
    if (!eglMakeCurrent(m_eglDisplay, surface, surface, m_eglContext))
        return false;''')
    change(cpp, 'eglQuerySurface(m_eglDisplay, m_androidWindowSurface, EGL_WIDTH, &width) && eglQuerySurface(m_eglDisplay, m_androidWindowSurface, EGL_HEIGHT, &height)', 'eglQuerySurface(m_eglDisplay, surface, EGL_WIDTH, &width) && eglQuerySurface(m_eglDisplay, surface, EGL_HEIGHT, &height)')
    change(cpp, 'eglSwapBuffers(m_eglDisplay, m_androidWindowSurface)', 'eglSwapBuffers(m_eglDisplay, surface)')
    change(cpp, '''    if (m_androidWindowSurface != EGL_NO_SURFACE)
        eglDestroySurface(m_eglDisplay, m_androidWindowSurface);
    if (m_androidIdleSurface != EGL_NO_SURFACE)''', '''    for (auto surface : m_androidWindowSurfaces)
        if (surface != EGL_NO_SURFACE)
            eglDestroySurface(m_eglDisplay, surface);
    if (m_androidIdleSurface != EGL_NO_SURFACE)''')


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    apply(parser.parse_args().source)
