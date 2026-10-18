#!/usr/bin/env python3
"""Check the prepared single-host shell contract before device runtime testing."""

import argparse
import json
import tarfile


def verify(path):
    with tarfile.open(path, "r:gz") as archive:
        names = {member.name for member in archive.getmembers()}

        def read(name):
            assert name in names, name
            return archive.extractfile(name).read().decode("utf-8")

        config = json.loads(read("usr/share/omamobile/android/unified.json"))
        assert config["bar"].get("id", "omarchy.bar") == "omarchy.bar"
        assert config["mobileBar"]["id"] == "omamobile.bar"
        enabled = {entry["id"] for entry in config["plugins"]}
        assert {"omamobile.home", "omamobile.launcher", "omamobile.switcher",
                "omamobile.quicksettings", "omamobile.access"} <= enabled
        shell = read("root/.local/share/omarchy/shell/shell.qml")
        assert shell.count("PluginRegistry { }") == 1
        assert shell.count("id: mobileBarLoader") == 1
        assert 'shell.unifiedOutputs && !shell.pluginReloading' in shell
        assert 'configured && !(shell.unifiedOutputs && configured === ' in shell
        assert 'id === shell.activeBarId || (shell.unifiedOutputs && id === String(shell.mobileBarConfig.id || ""))' in shell
        assert 'shell.bar = target' in shell  # desktop bar keeps the host slot
        registry = read("root/.local/share/omarchy/shell/services/PluginRegistry.qml")
        assert 'return selectedBar === key || mobileBar === key' in registry
        assert 'lastEnableError = "phone bar is reserved for ANDROID-1"' in registry
        assert 'OMAMOBILE_PROFILE=$profile' in read("root/.local/share/omamobile/start-shell")

        routes = {
            "root/.local/share/omarchy/shell/plugins/bar/Bar.qml": "ANDROID-2",
            "root/.local/share/omarchy/shell/Ui/OverlayWindow.qml": "ANDROID-2",
            "usr/share/omamobile/shell/plugins/omamobile.bar/Bar.qml": "ANDROID-1",
            "usr/share/omamobile/shell/plugins/omamobile.home/Home.qml": "ANDROID-1",
            "usr/share/omamobile/shell/plugins/omamobile.launcher/Launcher.qml": "ANDROID-1",
            "usr/share/omamobile/shell/plugins/omamobile.quicksettings/QuickSettings.qml": "ANDROID-1",
            "usr/share/omamobile/shell/plugins/omamobile.switcher/Switcher.qml": "ANDROID-1",
        }
        for name, output in routes.items():
            assert output in read(name), name
        desktop_bar = read("root/.local/share/omarchy/shell/plugins/bar/Bar.qml")
        assert desktop_bar.count("      BarPanel {") == 1  # legacy delegate; unified uses one persistent Loader
        assert 'sourceComponent: Component { BarPanel { screen: root.unifiedBarScreen } }' in desktop_bar
        assert 'model: Quickshell.env("OMAMOBILE_PROFILE") === "unified" ? [] : Quickshell.screens' in desktop_bar
        assert 'if (candidate.name === "ANDROID-2") return candidate' in desktop_bar
        assert 'if (candidate.name === "ANDROID-1") phone = candidate' in desktop_bar
        assert '&& !phoneFallback && !remapGuard.remapping' in desktop_bar
        assert 'exclusionMode: phoneFallback || root.barHidden ? ExclusionMode.Ignore : ExclusionMode.Auto' in desktop_bar
        quick_settings = read("usr/share/omamobile/shell/plugins/omamobile.quicksettings/QuickSettings.qml")
        assert 'visible: !!root.phoneScreen && (root.opened || root.reveal > 0)' in quick_settings
        assert 'jq -r \'(.mobileBar // .bar).clockFormat // \\"\\"\'' in quick_settings
        for name in ["omamobile.home/Home.qml", "omamobile.home/ClockWidget.qml",
                     "omamobile.launcher/Launcher.qml"]:
            assert 'Quickshell.env("OMARCHY_SHELL_CONFIG")' in read(
                "usr/share/omamobile/shell/plugins/" + name), name

    print(json.dumps({"passed": True, "payload": str(path), "routedComponents": len(routes)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("payload")
    verify(parser.parse_args().payload)
