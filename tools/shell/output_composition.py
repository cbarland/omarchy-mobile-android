"""Pinned QML payload edits for one shell with two named Android outputs."""


def once(text: str, before: str, after: str, name: str) -> str:
    count = text.count(before)
    if count != 1:
        raise ValueError(f"{name}: expected one insertion point, found {count}: {before[:70]!r}")
    return text.replace(before, after, 1)


def compose(relative: str, data: bytes) -> bytes:
    targets = {
        "shell/shell.qml", "shell/plugins/bar/Bar.qml", "shell/Ui/OverlayWindow.qml",
        "shell/Ui/ScreenMoveRemap.qml",
        "shell/services/PluginRegistry.qml",
        "bin/omarchy-hyprland-monitor-scaling",
        "packages/omamobile-shell/plugins/omamobile.bar/Bar.qml",
        "packages/omamobile-shell/plugins/omamobile.home/Home.qml",
        "packages/omamobile-shell/plugins/omamobile.home/ClockWidget.qml",
        "packages/omamobile-shell/plugins/omamobile.launcher/Launcher.qml",
        "packages/omamobile-shell/plugins/omamobile.quicksettings/QuickSettings.qml",
        "packages/omamobile-shell/plugins/omamobile.switcher/Switcher.qml",
    }
    if relative not in targets:
        return data
    text = data.decode("utf-8")
    if relative == "shell/shell.qml":
        text = once(text, '      if (configured) return configured\n',
            '      if (configured && !(shell.unifiedOutputs && configured === '
            'Util.canonicalWidgetId(String(shell.mobileBarConfig.id || "")))) return configured\n', relative)
        text = once(text, '    return String(pluginId || "") === shell.activeBarId\n',
            '    var id = String(pluginId || "")\n'
            '    return id === shell.activeBarId || (shell.unifiedOutputs && id === String(shell.mobileBarConfig.id || ""))\n', relative)
        text = once(text, '  property var bar: null\n', '''  property var bar: null
  readonly property bool unifiedOutputs: Quickshell.env("OMAMOBILE_PROFILE") === "unified"
  readonly property var mobileBarConfig: shellConfig && Util.isPlainObject(shellConfig.mobileBar)
    ? shellConfig.mobileBar : ({})
  readonly property var mobileBarManifest: {
    var revision = shell.pluginRegistry.registryRevision
    return shell.barManifestFor("omamobile.bar")
  }
  readonly property string mobileBarSourceUrl: shell.pluginRegistry.entryPointUrl(mobileBarManifest, "bar")

  function configureMobileBar(target) {
    if (!target) return
    if ("omarchyPath" in target) target.omarchyPath = shell.omarchyPath
    if ("shell" in target) target.shell = shell.pluginShellFor(shell.mobileBarManifest)
    if ("manifest" in target) target.manifest = shell.publicPluginManifest(shell.mobileBarManifest)
    if ("barConfig" in target) target.barConfig = shell.mobileBarConfig
  }
  onMobileBarConfigChanged: if (mobileBarLoader.item) mobileBarLoader.item.barConfig = shell.mobileBarConfig
''', relative)
        text = once(text, '  // ------------------------------------------------------------- services\n', '''  Loader {
    id: mobileBarLoader
    active: shell.unifiedOutputs && !shell.pluginReloading && shell.mobileBarSourceUrl !== ""
    source: shell.mobileBarSourceUrl
    asynchronous: true
    onLoaded: shell.configureMobileBar(item)
    onStatusChanged: if (status === Loader.Error) console.warn("unified mobile bar failed to load")
  }

  // ------------------------------------------------------------- services
''', relative)
    elif relative == "shell/services/PluginRegistry.qml":
        text = once(text, '        return selectedBar === key\n',
            '        var mobileBar = Quickshell.env("OMAMOBILE_PROFILE") === "unified"\n'
            '          && Util.isPlainObject(config) && Util.isPlainObject(config.mobileBar)\n'
            '          ? Util.canonicalWidgetId(String(config.mobileBar.id || "")) : ""\n'
            '        return selectedBar === key || mobileBar === key\n', relative)
        text = once(text, '    var manifest = installedPlugins[key]\n    if (value && !manifest) {\n',
            '    var manifest = installedPlugins[key]\n'
            '    var currentConfig = shellConfigProvider ? shellConfigProvider() : null\n'
            '    if (Quickshell.env("OMAMOBILE_PROFILE") === "unified"\n'
            '        && Util.isPlainObject(currentConfig) && Util.isPlainObject(currentConfig.mobileBar)\n'
            '        && key === Util.canonicalWidgetId(String(currentConfig.mobileBar.id || ""))) {\n'
            '      lastEnableError = "phone bar is reserved for ANDROID-1"\n'
            '      return false\n'
            '    }\n'
            '    if (value && !manifest) {\n', relative)
    elif relative == "bin/omarchy-hyprland-monitor-scaling":
        text = once(text, '  local new_scale="$(clean_scale "$requested_scale" "$width" "$height")"\n', '''  local monitor_lua="$HOME/.config/hypr/monitors.lua"
  local unified_variable=""
  if [[ ${OMAMOBILE_PROFILE:-} == unified ]]; then
    case $active_monitor in
      ANDROID-1) unified_variable=omamobile_phone_scale ;;
      ANDROID-2) unified_variable=omarchy_monitor_scale ;;
      *) echo "Refusing scale for unknown unified output: $active_monitor" >&2; return 1 ;;
    esac
    if [[ ! -f $monitor_lua ]] || ! grep -Eq "^local ${unified_variable} = [0-9]+([.][0-9]+)?$" "$monitor_lua"; then
      echo "Missing unified scale setting: $unified_variable" >&2
      return 1
    fi
  fi

  local new_scale="$(clean_scale "$requested_scale" "$width" "$height")"
''', relative)
        text = once(text, '  local monitor_lua="$HOME/.config/hypr/monitors.lua"\n\n  hyprctl eval',
            '  hyprctl eval', relative)
        text = once(text, '  audit_scale_change "$requested" "$active_monitor" "$current_scale" "$new_scale"\n\n', '''  audit_scale_change "$requested" "$active_monitor" "$current_scale" "$new_scale"

  if [[ -n $unified_variable ]]; then
    sed -i -E "s|^local ${unified_variable} = [0-9]+([.][0-9]+)?$|local ${unified_variable} = ${new_scale}|" "$monitor_lua"
    return
  fi

''', relative)
    elif relative == "shell/plugins/bar/Bar.qml":
        text = once(text, '  Variants {\n    model: Quickshell.screens\n\n    delegate: Component {\n      BarPanel {', '''  Variants {
    model: Quickshell.env("OMAMOBILE_PROFILE") === "unified" ? [] : Quickshell.screens

    delegate: Component {
      BarPanel {''', relative)
        text = once(text, '  Variants {\n    model: Quickshell.env("OMAMOBILE_PROFILE") === "unified" ? [] : Quickshell.screens\n', '''  readonly property var unifiedBarScreen: {
    if (Quickshell.env("OMAMOBILE_PROFILE") !== "unified") return null
    var phone = null
    for (var i = 0; i < Quickshell.screens.length; i++) {
      var candidate = Quickshell.screens[i]
      if (candidate.name === "ANDROID-2") return candidate
      if (candidate.name === "ANDROID-1") phone = candidate
    }
    return phone
  }

  Loader {
    active: Quickshell.env("OMAMOBILE_PROFILE") === "unified"
    sourceComponent: Component { BarPanel { screen: root.unifiedBarScreen } }
  }

  Variants {
    model: Quickshell.env("OMAMOBILE_PROFILE") === "unified" ? [] : Quickshell.screens
''', relative)
        text = text.replace('model: Quickshell.screens',
            'model: Quickshell.env("OMAMOBILE_PROFILE") === "unified"\n'
            '      ? Quickshell.screens.filter(function(screen) { return screen && screen.name === "ANDROID-2" })\n'
            '      : Quickshell.screens')
        if text.count('screen.name === "ANDROID-2"') != 2:
            raise ValueError("desktop bar: expected two external ghost models")
        text = once(text, '    id: barWindow\n', '''    id: barWindow
    readonly property bool phoneFallback: Quickshell.env("OMAMOBILE_PROFILE") === "unified"
      && root.unifiedBarScreen && root.unifiedBarScreen.name === "ANDROID-1"
''', relative)
        text = once(text, '    ScreenMoveRemap {\n      id: remapGuard\n      window: barWindow\n    }\n', '''    ScreenMoveRemap {
      id: remapGuard
      window: barWindow
    }

    Connections {
      target: root
      function onUnifiedBarScreenChanged() {
        if (Quickshell.env("OMAMOBILE_PROFILE") === "unified" && root.unifiedBarScreen)
          remapGuard.scheduleRemap()
      }
    }
''', relative)
        text = once(text, '    visible: !remapGuard.remapping && (cutoutWaitOver || !cutoutPending)\n',
            '    visible: (Quickshell.env("OMAMOBILE_PROFILE") !== "unified" || !!root.unifiedBarScreen)\n'
            '      && !remapGuard.remapping && (cutoutWaitOver || !cutoutPending)\n', relative)
        text = once(text, '    exclusionMode: root.barHidden ? ExclusionMode.Ignore : ExclusionMode.Auto\n',
            '    exclusionMode: phoneFallback || root.barHidden ? ExclusionMode.Ignore : ExclusionMode.Auto\n', relative)
        margins = 'root.barHidden && root.position === '
        if text.count(margins) != 4:
            raise ValueError("desktop bar: expected four parking margins")
        text = text.replace(margins, '(phoneFallback || root.barHidden) && root.position === ')
    elif relative == "shell/Ui/OverlayWindow.qml":
        text = once(text, '  function focusedScreen() {\n', '''  function focusedScreen() {
    if (Quickshell.env("OMAMOBILE_PROFILE") === "unified") {
      var phone = null
      for (var i = 0; i < Quickshell.screens.length; i++)
        if (Quickshell.screens[i].name === "ANDROID-2") return Quickshell.screens[i]
        else if (Quickshell.screens[i].name === "ANDROID-1") phone = Quickshell.screens[i]
      return phone
    }
''', relative)
    elif relative == "shell/Ui/ScreenMoveRemap.qml":
        text = once(text, '  visible: false\n', '''  visible: false

  function scheduleRemap() { settleTimer.restart() }
''', relative)
        text = once(text, 'function onXChanged() { settleTimer.restart() }\n    function onYChanged() { settleTimer.restart() }',
            'function onXChanged() { root.scheduleRemap() }\n    function onYChanged() { root.scheduleRemap() }', relative)
    elif relative == "packages/omamobile-shell/plugins/omamobile.bar/Bar.qml":
        text = once(text, '    model: Quickshell.screens\n',
            '    model: Quickshell.env("OMAMOBILE_PROFILE") === "unified"\n'
            '      ? Quickshell.screens.filter(function(screen) { return screen && screen.name === "ANDROID-1" })\n'
            '      : Quickshell.screens\n', relative)
    elif relative == "packages/omamobile-shell/plugins/omamobile.home/Home.qml":
        text = once(text, '  readonly property string pluginId:', '''  readonly property var phoneScreen: {
    if (Quickshell.env("OMAMOBILE_PROFILE") !== "unified")
      return Quickshell.screens.length ? Quickshell.screens[0] : null
    for (var i = 0; i < Quickshell.screens.length; i++)
      if (Quickshell.screens[i].name === "ANDROID-1") return Quickshell.screens[i]
    return null
  }

  readonly property string pluginId:''', relative)
        text = once(text, '    screen: Quickshell.screens.length > 0 ? Quickshell.screens[0] : null\n',
            '    screen: root.phoneScreen\n    visible: !!root.phoneScreen\n', relative)
        text = once(text, 'path: Quickshell.env("HOME") + "/.config/omarchy/shell.json"',
            'path: Quickshell.env("OMARCHY_SHELL_CONFIG") || Quickshell.env("HOME") + "/.config/omarchy/shell.json"', relative)
        text = once(text, 'shellConfig.bar && shellConfig.bar.clockFormat ? String(shellConfig.bar.clockFormat) : ""',
            '(shellConfig.mobileBar || shellConfig.bar) && (shellConfig.mobileBar || shellConfig.bar).clockFormat\n'
            '          ? String((shellConfig.mobileBar || shellConfig.bar).clockFormat) : ""', relative)
    elif relative == "packages/omamobile-shell/plugins/omamobile.home/ClockWidget.qml":
        text = once(text, 'path: Quickshell.env("HOME") + "/.config/omarchy/shell.json"',
            'path: Quickshell.env("OMARCHY_SHELL_CONFIG") || Quickshell.env("HOME") + "/.config/omarchy/shell.json"', relative)
        text = once(text, 'shell.bar && shell.bar.clockFormat ? String(shell.bar.clockFormat) : ""',
            '(shell.mobileBar || shell.bar) && (shell.mobileBar || shell.bar).clockFormat\n'
            '          ? String((shell.mobileBar || shell.bar).clockFormat) : ""', relative)
    elif relative == "packages/omamobile-shell/plugins/omamobile.launcher/Launcher.qml":
        text = once(text, '  id: root\n', '''  id: root
  readonly property var phoneScreen: {
    if (Quickshell.env("OMAMOBILE_PROFILE") !== "unified")
      return Quickshell.screens.length ? Quickshell.screens[0] : null
    for (var i = 0; i < Quickshell.screens.length; i++)
      if (Quickshell.screens[i].name === "ANDROID-1") return Quickshell.screens[i]
    return null
  }
''', relative)
        text = once(text, '    model: Quickshell.screens\n',
            '    model: Quickshell.env("OMAMOBILE_PROFILE") === "unified"\n'
            '      ? Quickshell.screens.filter(function(screen) { return screen && screen.name === "ANDROID-1" })\n'
            '      : Quickshell.screens\n', relative)
        text = once(text, '    id: drawer\n', '    id: drawer\n    screen: root.phoneScreen\n', relative)
        text = once(text, '    visible: root.opened || root.reveal > 0\n',
            '    visible: !!root.phoneScreen && (root.opened || root.reveal > 0)\n', relative)
        text = once(text, 'path: Quickshell.env("HOME") + "/.config/omarchy/shell.json"',
            'path: Quickshell.env("OMARCHY_SHELL_CONFIG") || Quickshell.env("HOME") + "/.config/omarchy/shell.json"', relative)
    elif relative in ("packages/omamobile-shell/plugins/omamobile.quicksettings/QuickSettings.qml",
                      "packages/omamobile-shell/plugins/omamobile.switcher/Switcher.qml"):
        text = once(text, '  id: root\n', '''  id: root
  readonly property var phoneScreen: {
    if (Quickshell.env("OMAMOBILE_PROFILE") !== "unified")
      return Quickshell.screens.length ? Quickshell.screens[0] : null
    for (var i = 0; i < Quickshell.screens.length; i++)
      if (Quickshell.screens[i].name === "ANDROID-1") return Quickshell.screens[i]
    return null
  }
''', relative)
        marker = '    id: window\n' if "quicksettings" in relative else '    id: overlay\n'
        text = once(text, marker, marker + '    screen: root.phoneScreen\n', relative)
        visible = '    visible: root.opened || root.reveal > 0\n' if "quicksettings" in relative else '    visible: root.opened\n'
        gated = ('    visible: !!root.phoneScreen && (root.opened || root.reveal > 0)\n'
                 if "quicksettings" in relative else '    visible: !!root.phoneScreen && root.opened\n')
        text = once(text, visible, gated, relative)
        if "quicksettings" in relative:
            command = [line for line in text.splitlines(keepends=True)
                       if line.lstrip().startswith('command: ["sh", "-c", "grep -o ')]
            if len(command) != 1:
                raise ValueError(f"{relative}: expected one upstream clock reader")
            text = once(text, command[0],
                '''    command: ["sh", "-c", "jq -r '(.mobileBar // .bar).clockFormat // \\"\\"' \\"${OMARCHY_SHELL_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/omarchy/shell.json}\\""]
''', relative)
    return text.encode("utf-8")
