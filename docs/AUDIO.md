# Shared Linux audio output

Starting Omarchy screens binds one app-private `SharedAudioService` in `:audio`.
Phone, desktop and shared-compositor clients use that same output. Display
replacement and graphics-mode switching retain its owner. Stop screens or remove
the controls task to release it. Audio is output-only; microphone input is not
implemented.

Fresh signed Linux provisioning includes `pipewire`, `pipewire-audio`,
`pipewire-pulse` and `wireplumber`. Existing images missing these packages need
an explicit signed package transaction, for example:

```sh
pacman -Syu --needed pipewire pipewire-audio pipewire-pulse wireplumber
```

Review that transaction before running it. Playback never installs packages.
Updating APK-provided shell files and updating Linux packages are separate
operations.

## Ownership and routing

One foreground Python helper owns PipeWire, WirePlumber's policy profile and the
Pulse compatibility server. Ordinary native and Pulse clients automatically link
to the `omamobile-android` sink. This is a real FIFO sink, not an emulated hardware
card. All profiles use `/run/omamobile/a-shared`; their Wayland and D-Bus runtimes
remain separate. Private XDG configuration leaves user PipeWire settings alone.

`ManagedAudioOutput` waits for FIFO readability without consuming the first PCM.
It requests Android media focus before opening `AudioTrack`, and releases the
track and focus after idle. Denied or lost-focus output is discarded without
repeated focus requests during a continuing stream. Transient gain can resume
output. This silences Linux audio; it does not pause individual Linux players.
Android chooses the physical output route and media volume.

The FIFO-to-`AudioTrack` primitive transports S16LE stereo at 48 kHz, retains
partial frames across reads and counts incomplete trailing bytes. It validates
an app-private, same-UID FIFO using `O_NOFOLLOW` and opened-descriptor identity.
Close cancels and joins its worker before releasing descriptors. Failed cleanup
retains ownership so a later stop can retry; it does not hide the failure.

The audio JVM holds a shared installation lease independently of main-process
Linux commands. Exact helper and child PID/start identities guard cleanup. The
helper blocks on control and child-exit events when idle. Shutdown silences
output, reaps the graph and verifies children are gone before releasing its
files and lease. Logs are in app-private `files/audio/` and Android logcat under
`OmamobileAudio`. Startup errors are logged without preventing graphics startup.

## Verification and limits

The disposable API 35 x86_64 emulator passed six `AndroidPcmOutputTest` cases,
four `ManagedAudioOutputTest` cases and the prepared-Linux
`SharedAudioServiceTest`. The service test verifies normal native/Pulse clients,
two simultaneous streams actively linked to one sink, actual Android transient
focus loss/recovery, owner-process death and rebinding, renewed playback, idle
release, child reaping and exclusive-installation access after stop.

Normal host controls also passed playback before/after a real integration update,
idle release, unchanged audio ownership across graphics-mode switching and
external-display reconnect, and cleanup after main-process death. The combined
run's early screenshots were incomplete; graphical readiness and painting are
tracked separately from this audio pipeline result.

These checks observe bytes, submitted frames and advancing public playback-head
positions. They do not establish speaker audibility, physical Bluetooth/USB
routing, ARM64 operation, latency, power efficiency, per-application volume UI,
microphone capture or media-key/MPRIS integration. All native binary publication
holds remain.

After installing the host and instrumentation APKs on a disposable prepared
emulator, enable the relevant opt-in tests explicitly:

```sh
adb -s emulator-5562 shell am instrument -w -r -e audio true -e managedAudio true -e sharedAudio true -e class dev.omamobile.host.AndroidPcmOutputTest,dev.omamobile.host.ManagedAudioOutputTest,dev.omamobile.host.SharedAudioServiceTest dev.omamobile.host.test/androidx.test.runner.AndroidJUnitRunner
```

Earlier isolated graph fixtures remain opt-in as `PipeWireAndroidOutputTest`
(`pipewireAudio=true`) and `PipeWirePolicyOutputTest`
(`pipewirePolicyAudio=true`). They require the signed package versions documented
in their test prerequisites. They check transport, tone content and cleanup;
they are not substitutes for the service and normal-controls checks.

Platform contracts: [Android audio focus](https://developer.android.com/media/optimize/audio-focus),
[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[Os](https://developer.android.com/reference/android/os/Os) and
[ParcelFileDescriptor](https://developer.android.com/reference/android/os/ParcelFileDescriptor).
