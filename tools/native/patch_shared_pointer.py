"""Add a generation-guarded shared mouse seat to the reviewed two-output port."""
import argparse
import hashlib
import json
from pathlib import Path
from patch_multi_output import change
from patch_output_lifecycle import prepare as prepare_lifecycle

HERE = Path(__file__).resolve().parent


def is_applied(source):
    hashes = json.loads((HERE / 'shared_pointer_hashes.json').read_text())
    return all(hashlib.sha256((source / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == digest
               for name, digest in hashes.items())


def apply(source):
    hashes = json.loads((HERE / 'output_lifecycle_hashes.json').read_text())
    for name, expected in hashes.items():
        data = (source / name).read_bytes().replace(b'\r\n', b'\n')
        if hashlib.sha256(data).hexdigest() != expected['phase2']:
            raise RuntimeError('Shared pointer input differs from reviewed lifecycle source: ' + name)
    header = source / 'subprojects/aquamarine/include/aquamarine/backend/Android.hpp'
    change(header, 'void pointer(int id, float x, float y, uint32_t button, bool pressed);',
           'void pointer(int id, float x, float y, uint32_t button, bool pressed);\n        void relativePointer(float dx, float dy, uint32_t button, bool pressed);')
    change(header, 'std::array<SDisplay, 2> m_displays;', 'std::array<SDisplay, 2> m_displays;\n        bool m_relativePointer = false;')
    cpp = source / 'subprojects/aquamarine/src/backend/Android.cpp'
    change(cpp, '    m_displays[id].hasPointer = true;', '    m_relativePointer = false;\n    m_displays[id].hasPointer = true;')
    change(cpp, 'void CAndroidBackend::axis(int id, float dx, float dy) {', '''void CAndroidBackend::relativePointer(float dx, float dy, uint32_t button, bool pressed) {
    if (!m_pointer || !std::isfinite(dx) || !std::isfinite(dy)) return;
    m_relativePointer = true;
    if (dx || dy)
        m_pointer->events.move.emit(IPointer::SMoveEvent{.timeMs = timeMs(), .delta = {dx, dy}, .unaccel = {dx, dy}});
    if (button)
        m_pointer->events.button.emit(IPointer::SButtonEvent{.timeMs = timeMs(), .button = button, .pressed = pressed});
    m_pointer->events.frame.emit();
}

void CAndroidBackend::axis(int id, float dx, float dy) {''')
    change(cpp, '''    if (!display.window || !display.output || !display.hasPointer) return;
    m_pointer->events.warp.emit(IPointer::SWarpEvent{.timeMs = timeMs(), .absolute = {display.pointerX / display.width, display.pointerY / display.height}, .output = display.output});''',
           '''    if (!display.window || !display.output) return;
    if (!m_relativePointer) {
        if (!display.hasPointer) return;
        m_pointer->events.warp.emit(IPointer::SWarpEvent{.timeMs = timeMs(), .absolute = {display.pointerX / display.width, display.pointerY / display.height}, .output = display.output});
    }''')
    header = source / 'src/android/Embed.h'
    declaration = 'int anhyprland_pointer_at(int id, uint64_t generation, float x, float y, uint32_t button, int pressed);'
    change(header, declaration, declaration + '\nint anhyprland_relative_pointer_at(int id, uint64_t generation, float dx, float dy, uint32_t button, int pressed);')
    cpp = source / 'src/android/Embed.cpp'
    change(cpp, '#include <atomic>', '#include <atomic>\n#include <cmath>')
    change(cpp, '#include "../managers/SeatManager.hpp"', '#include "../managers/SeatManager.hpp"\n#include "../managers/input/InputManager.hpp"')
    text = cpp.read_text()
    start = text.index('static bool focusOutput(int id) {')
    end = text.index('static void releaseInputs(int id)', start)
    cpp.write_text(text[:start] + text[end:])
    change(cpp, '        if (focused) focusOutput(id);',
           '''        if (focused) {
            if (g_pInputManager) g_pInputManager->simulateMouseMovement();
        }''')
    change(cpp, 'extern "C" int anhyprland_touch_at(', '''extern "C" int anhyprland_relative_pointer_at(int id, uint64_t generation, float dx, float dy, uint32_t button, int pressed) {
    if (id < 0 || id > 1 || !generation || !std::isfinite(dx) || !std::isfinite(dy)
        || (button && (button < 272 || button > 274))) return -EINVAL;
    return enqueue([=] {
        if (!taggedActive(id, generation)) return;
        if (auto output = backend()) output->relativePointer(dx, dy, button, pressed);
        if (button) {
            if (pressed) embed.pressedButtons[id].insert(button);
            else embed.pressedButtons[id].erase(button);
        }
    });
}

extern "C" int anhyprland_touch_at(''')
    # Android's capturing output is not necessarily the shared seat's current output.
    change(cpp, '        if (pressed) focusOutput(id);\n        if (auto output = backend()) output->key(evdev, pressed);',
           '        if (auto output = backend()) output->key(evdev, pressed);')
    change(cpp, '        focusOutput(id);\n        sendUnicode(codepoint);', '        sendUnicode(codepoint);')


def prepare(source):
    if is_applied(source):
        return
    prepare_lifecycle(source)
    apply(source)
    if not is_applied(source):
        raise RuntimeError('Shared pointer output differs from reviewed hashes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    prepare(parser.parse_args().source)
