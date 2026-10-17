"""Pinned Android dialog boundary; leave Hyprutils and desktop launch policy alone."""
from pathlib import Path


def once(text: str, old: str, new: str) -> str:
    if text.count(old) != 1:
        raise RuntimeError("Pinned guest dialog contract changed: " + old[:90])
    return text.replace(old, new, 1)


ANDROID_OPEN = r'''#ifdef __ANDROID__
    // OMAMOBILE_GUEST_DIALOG: argv and child-only exec metadata cross the same
    // wrapper boundary as other guest clients, without CProcess's fork path.
    if (m_readEventSource) return nullptr;
    m_stdout.clear();
    std::string buttonsString;
    for (const auto& button : m_buttons) buttonsString += button + ";";
    if (!buttonsString.empty()) buttonsString.pop_back();
    const char* wrapper = getenv("ARLINUX_EXEC_WRAPPER");
    if (!wrapper) return nullptr;
    auto child = Omamobile::GuestDialog::spawn(wrapper,
        {"hyprland-dialog", "--title", m_title, "--text", m_description, "--buttons", buttonsString}, m_execRuleToken);
    if (!child) {
        LOG(Log::ERR, "CAsyncDialogBox::open: Android guest spawn failed: {}", errno);
        return nullptr;
    }
    m_pipeReadFd = CFileDescriptor(child->releaseOutput());
    m_readEventSource = wl_event_loop_add_fd(g_pEventLoopManager->m_wayland.loop, m_pipeReadFd.get(), WL_EVENT_READABLE, ::onFdWrite, this);
    if (!m_readEventSource) {
        m_pipeReadFd = CFileDescriptor{};
        return nullptr; // Child closes, terminates if necessary, and reaps only its PID.
    }
    m_dialogPid = child->pid();
    m_guestChild = std::move(child);
    m_selfReference = m_selfWeakReference.lock();
    asyncDialogBoxes.emplace_back(m_dialogPid, m_selfWeakReference);
    LOG(Log::DEBUG, "CAsyncDialogBox: guest dialog pid {}", m_dialogPid);
    return CPromise<std::string>::make([this](SP<CPromiseResolver<std::string>> r) { m_promiseResolver = r; });
#else
'''

ANDROID_READ = r'''#ifdef __ANDROID__
    bool disconnected = mask & (WL_EVENT_HANGUP | WL_EVENT_ERROR);
    bool failed = mask & WL_EVENT_ERROR;
    if (mask & (WL_EVENT_READABLE | WL_EVENT_HANGUP | WL_EVENT_ERROR)) {
        int flags = fcntl(fd, F_GETFL, 0);
        if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) { disconnected = true; failed = true; }
        else {
            std::array<char, 1024> buffer;
            while (true) {
                ssize_t count = read(fd, buffer.data(), buffer.size());
                if (count > 0) {
                    if (m_stdout.size() + count > 65536) { m_stdout.clear(); disconnected = true; failed = true; break; }
                    m_stdout.append(buffer.data(), static_cast<size_t>(count));
                } else if (count == 0) { disconnected = true; break; }
                else if (errno == EINTR) continue;
                else { if (errno != EAGAIN && errno != EWOULDBLOCK) { disconnected = true; failed = true; } break; }
            }
            if (fcntl(fd, F_SETFL, flags) < 0) { disconnected = true; failed = true; }
        }
    }
    if (!disconnected) return;
    // Clear event-loop/PID ownership before the sole child owner may reap it.
    std::erase_if(asyncDialogBoxes, [this](const auto& entry) { return entry.first == m_dialogPid; });
    m_dialogPid = 0;
    if (m_readEventSource) wl_event_source_remove(m_readEventSource);
    m_readEventSource = nullptr;
    m_pipeReadFd = CFileDescriptor{};
    // Deliver a successful Wait/button response before disconnect cleanup.
    if (m_promiseResolver) {
        if (failed) m_promiseResolver->reject("guest dialog stdout failed");
        else m_promiseResolver->resolve(m_stdout);
    }
    m_promiseResolver.reset();
    m_guestChild.reset();
    m_selfReference.reset();
#else
'''


def apply(source: Path):
    helper = Path(__file__).with_name("android_guest_dialog.hpp").read_text()
    path = source / "src/helpers/AndroidGuestDialog.hpp"
    if not path.exists() or path.read_text() != helper:
        path.write_text(helper)

    path = source / "src/helpers/AsyncDialogBox.hpp"
    text = path.read_text()
    if "OMAMOBILE_GUEST_DIALOG" not in text:
        text = once(text, "struct wl_event_source;", """// OMAMOBILE_GUEST_DIALOG
#ifdef __ANDROID__
#include "AndroidGuestDialog.hpp"
#endif
struct wl_event_source;""")
        text = once(text, "    static bool                isAsyncDialogBox", "    static bool                available();\n    static bool                isAsyncDialogBox")
        text = once(text, "    pid_t                             m_dialogPid", """#ifdef __ANDROID__
    std::unique_ptr<Omamobile::GuestDialog::Child> m_guestChild;
#endif
    pid_t                             m_dialogPid""")
        path.write_text(text)

    path = source / "src/helpers/AsyncDialogBox.cpp"
    text = path.read_text()
    if "OMAMOBILE_GUEST_DIALOG" not in text:
        text = once(text, "//\nSP<CAsyncDialogBox>", """// OMAMOBILE_GUEST_DIALOG
bool CAsyncDialogBox::available() {
#ifdef __ANDROID__
    return Omamobile::GuestDialog::available(getenv("ARLINUX_EXEC_WRAPPER"));
#else
    return NFsUtils::executableExistsInPath("hyprland-dialog");
#endif
}

//
SP<CAsyncDialogBox>""")
        text = once(text, 'if (!NFsUtils::executableExistsInPath("hyprland-dialog"))', 'if (!available())')
        start = "void CAsyncDialogBox::onWrite(int fd, uint32_t mask) {\n"
        end = "\n}\n\nSP<CPromise<std::string>> CAsyncDialogBox::open()"
        text = once(text, start, start + ANDROID_READ)
        text = once(text, end, "\n#endif" + end)
        start = "SP<CPromise<std::string>> CAsyncDialogBox::open() {\n"
        end = "\n}\n\nvoid CAsyncDialogBox::kill()"
        text = once(text, start, start + ANDROID_OPEN)
        text = once(text, end, "\n#endif" + end)
        text = once(text, "    ::kill(m_dialogPid, SIGKILL);", """#ifdef __ANDROID__
    if (m_guestChild) m_guestChild->terminate();
#else
    ::kill(m_dialogPid, SIGKILL);
#endif""")
        path.write_text(text)

    path = source / "src/Compositor.cpp"
    text = path.read_text()
    if "OMAMOBILE_GUEST_DIALOG" not in text:
        text = once(text, 'if (!NFsUtils::executableExistsInPath("hyprland-dialog"))', 'if (!CAsyncDialogBox::available()) /* OMAMOBILE_GUEST_DIALOG */')
        text = once(text, "    box->open()->then(", """#ifdef __ANDROID__
    if (!box) return;
#endif
    auto promise = box->open();
#ifdef __ANDROID__
    if (!promise) return;
#endif
    promise->then(""")
        path.write_text(text)

    path = source / "src/managers/ANRManager.hpp"
    text = path.read_text()
    if "OMAMOBILE_ANR_OWNER" not in text:
        text = once(text,
                    "        void                            runDialog(const std::string& appName, const std::string appClass, pid_t dialogWmPID);",
                    "        // OMAMOBILE_ANR_OWNER: promise callbacks borrow the real shared actor.\n"
                    "        void                            runDialog(const std::string& appName, const std::string appClass, pid_t dialogWmPID, const WP<SANRData>& owner);")
        path.write_text(text)

    path = source / "src/managers/ANRManager.cpp"
    text = path.read_text()
    if "OMAMOBILE_GUEST_DIALOG" not in text:
        text = once(text, 'if (!NFsUtils::executableExistsInPath("hyprland-dialog"))', 'if (!CAsyncDialogBox::available()) /* OMAMOBILE_GUEST_DIALOG */')
        text = once(text, "hyprland-dialog missing from PATH, cannot start ANRManager", "hyprland-dialog unavailable, cannot start ANRManager")
        created = "    dialogBox = CAsyncDialogBox::create(I18n::i18nEngine()->localize(I18n::TXT_KEY_ANR_TITLE, {}), DESCRIPTION_STR, OPTIONS);"
        text = once(text, created, created + """
#ifdef __ANDROID__
    if (!dialogBox) return;
#endif""")
        text = once(text, "    dialogBox->open()->then(", """    auto promise = dialogBox->open();
#ifdef __ANDROID__
    if (!promise) { dialogBox.reset(); return; }
#endif
    promise->then(""")
    if "OMAMOBILE_ANR_OWNER" not in text:
        text = once(text,
                    "                data->runDialog(firstWindow->metadata().title(), firstWindow->metadata().appID(), data->pid);",
                    "                data->runDialog(firstWindow->metadata().title(), firstWindow->metadata().appID(), data->pid, WP<SANRData>(data));")
        text = once(text,
                    "void CANRManager::SANRData::runDialog(const std::string& appName, const std::string appClass, pid_t dialogWmPID) {",
                    "// OMAMOBILE_ANR_OWNER\nvoid CANRManager::SANRData::runDialog(const std::string& appName, const std::string appClass, pid_t dialogWmPID, const WP<SANRData>& owner) {")
        text = once(text,
                    "promise->then([dialogWmPID, this, OPTION_TERMINATE_STR, OPTION_WAIT_STR](SP<CPromiseResult<std::string>> r) {",
                    "promise->then([dialogWmPID, owner, currentDialog = WP<CAsyncDialogBox>(dialogBox), OPTION_TERMINATE_STR, OPTION_WAIT_STR](SP<CPromiseResult<std::string>> r) {\n"
                    "        const auto data = owner.lock();\n"
                    "        const auto dialog = currentDialog.lock();\n"
                    "        if (!data || !dialog || data->dialogBox != dialog) return;")
        text = once(text, "            dialogSaidWait = true;", "            data->dialogSaidWait = true;")
    path.write_text(text)

    path = source / "src/managers/permissions/DynamicPermissionManager.cpp"
    text = path.read_text()
    if "OMAMOBILE_GUEST_DIALOG" not in text:
        text = once(text, "    rule->m_dialogBox->m_priority = true;\n", "")
        fallback = '''    if (!rule->m_dialogBox) {
        LOG(Log::ERR, "CDynamicPermissionManager::askForPermission: hyprland-guiutils likely missing, cannot ask! Disabling permission control...");
        rule->m_allowMode = PERMISSION_RULE_ALLOW_MODE_ALLOW;
        return;
    }'''
        text = once(text, fallback, """    // OMAMOBILE_GUEST_DIALOG: test before dereferencing; retain the desktop fallback.
    if (!rule->m_dialogBox) {
#ifdef __ANDROID__
        LOG(Log::ERR, "CDynamicPermissionManager::askForPermission: guest dialog unavailable, denying permission");
        rule->m_allowMode = PERMISSION_RULE_ALLOW_MODE_DENY;
#else
        LOG(Log::ERR, "CDynamicPermissionManager::askForPermission: hyprland-guiutils likely missing, cannot ask! Disabling permission control...");
        rule->m_allowMode = PERMISSION_RULE_ALLOW_MODE_ALLOW;
#endif
        return;
    }""")
        text = once(text, "    rule->m_promise = rule->m_dialogBox->open();", """    rule->m_dialogBox->m_priority = true;
    rule->m_promise = rule->m_dialogBox->open();""")
        text = once(text, "    rule->m_promise = rule->m_dialogBox->open();", """    rule->m_promise = rule->m_dialogBox->open();
#ifdef __ANDROID__
    if (!rule->m_promise) {
        rule->m_allowMode = PERMISSION_RULE_ALLOW_MODE_DENY;
        rule->m_dialogBox.reset();
        return;
    }
#endif""")
        text = once(text, "        if (pr->hasError()) {", """        if (pr->hasError()) {
#ifdef __ANDROID__
            r->m_allowMode = PERMISSION_RULE_ALLOW_MODE_DENY;
            if (r->m_promiseResolverForExternal) r->m_promiseResolverForExternal->resolve(r->m_allowMode);
            r->m_promiseResolverForExternal.reset();
            r->m_promise.reset();
            return;
#endif""")
        text = once(text, "        } else if (result.starts_with(ALLOW))\n            r->m_allowMode = PERMISSION_RULE_ALLOW_MODE_ALLOW;", """        } else if (result.starts_with(ALLOW))
            r->m_allowMode = PERMISSION_RULE_ALLOW_MODE_ALLOW;
#ifdef __ANDROID__
        else r->m_allowMode = PERMISSION_RULE_ALLOW_MODE_DENY;
#endif""")
        old = "    if (!(*rule)->m_promise)\n        return nullptr;"
        if text.count(old) != 3: raise RuntimeError("Pinned permission promise lookup changed")
        text = text.replace(old, """#ifdef __ANDROID__
    // A synchronous create/open failure still completes the caller's pending request.
    if ((*rule)->m_allowMode == PERMISSION_RULE_ALLOW_MODE_DENY)
        return CPromise<eDynamicPermissionAllowMode>::make([](SP<CPromiseResolver<eDynamicPermissionAllowMode>> r) { r->resolve(PERMISSION_RULE_ALLOW_MODE_DENY); });
#endif
""" + old)
        path.write_text(text)
