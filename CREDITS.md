<h1>Credits and upstream projects</h1>

<p>This Android integration is possible because of the projects below and their
authors and contributors. The original work here is the Android adapter and
integration layer. The Linux desktop, phone shell, runtime and their ecosystems
come from upstream. This project is independent and does not imply endorsement.</p>

<h2>Foundations</h2>

<p><strong><a href="https://github.com/omacom/omarchy">Omarchy</a></strong> supplies
the desktop shell, menus, themes, configuration and normal Linux workflows.
Its contributors' MIT notices are retained in the installed source tree.
Pinned revision: <code>e71a816e9032f040225e086c7256f73e8080ae45</code>.</p>

<p><strong><a href="https://github.com/derekross/omarchy-mobile">Omarchy Mobile</a></strong>,
by Derek Ross and contributors, supplies the touch phone profile, launcher,
QML widgets, plugin integration and OWL compatibility layer. Its shell notices
are retained in the packaged Linux payload; its device-flashing and hardware
stack are not used here. Pinned revision:
<code>953bcb3058347459cdf7faaa1e149f16e0f7cfa6</code>.</p>

<p><strong><a href="https://github.com/wmww/tawc">TAWC</a></strong>, by Sophie Winter
and contributors, supplies the MIT-licensed <code>tawcroot</code> runtime that
makes rootless Linux execution possible. Its installation, bind and Android
command mechanisms also informed this adapter. The vendored sources retain
their copyright and the recorded downstream credential patch. Pinned revision:
<code>64e3736699684a4fc0029e71bdfb7cc1e0884323</code>.</p>

<h2>Graphics and Android integration</h2>

<p><a href="https://github.com/hyprwm/Hyprland">Hyprland</a> and
<a href="https://github.com/hyprwm/aquamarine">Aquamarine</a> provide the real
compositor and backend foundation. Taowen's
<a href="https://github.com/taowen/anhyprland">Android Hyprland port</a> provides
the Android Surface/GLES and input integration used here, under its retained
BSD-3-Clause notices. Port revision:
<code>bc13ae5859097791b3e93a32632727a7f7075354</code>.</p>

<p><a href="https://github.com/SimonSchubert/Omalauncher">Omalauncher</a>, by Simon
Schubert and contributors, supplies the MIT-licensed Ando client/broker used for
Android commands, itself derived from TAWC. Earlier Omalateral work informed the
display/input adapter. The frozen Omalauncher bridge work is separate from this
project.</p>

<p><a href="https://github.com/RikkaApps/Shizuku">Shizuku</a>, by RikkaApps and
contributors, supplies the Apache-2.0 API/provider and optional, explicitly
authorized Android shell service mechanism. It is not required for Linux itself.</p>

<h2>Shells, widgets, packages and supporting projects</h2>

<p><a href="https://quickshell.org/">Quickshell</a> runs the upstream QML shells.
<a href="https://archlinuxarm.org/">Arch Linux ARM</a> and
<a href="https://archlinux.org/">Arch Linux</a> supply the ordinary Linux userland
and signed packages. <a href="https://github.com/termux/termux-packages">Termux
packages</a> supply many Android-native graphics dependencies, extracted without
running package scripts. Their individual licenses remain applicable.</p>

<p><a href="https://github.com/SimonSchubert/omarchy-widgets">Omalauncher OWL
widgets</a> supplies the MIT-licensed store used through Mobile's existing
compatibility index (revision <code>b63886b75e02ccef1a8a11baeef2d6eff7a374af</code>).
<a href="https://github.com/nlboris/omarchy-scratchpad">Omarchy Scratchpad</a>
provides a representative tested community plugin; it is not bundled or enabled
in fresh installations.</p>

<p>Apache Commons Compress and its supporting Apache libraries, the Android
platform/NDK, Gradle and the other native dependencies are also essential.
Their notices and version/hash manifests are retained in the APK's
<code>assets/licenses/</code>. Complete corresponding-source delivery remains a
public binary release requirement.</p>

<p><a href="https://github.com/xerj-org/xerj">XERJ</a> and
<a href="https://github.com/cbarland/ripp">RIPP</a> were used as development and
reference-coding tools. They are optional and are not runtime dependencies.</p>

<p>See the <a href="https://github.com/cbarland/omarchy-mobile-android/blob/main/docs/REFERENCES.md">detailed
source provenance</a> for exact revisions, borrowed mechanisms and local patches,
and <a href="https://github.com/cbarland/omarchy-mobile-android/blob/main/docs/RELEASE-READINESS.md">release
readiness</a> for remaining distribution work. Credit summaries do not replace
the original license texts, copyright notices or source-delivery obligations.</p>
