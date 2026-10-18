# Private PCM output foundation

`AndroidPcmOutput` is a package-private, explicitly owned FIFO-to-`AudioTrack`
primitive for S16LE stereo at 48 kHz (four bytes per frame). It has no application
callers yet. DesktopService, PipeWire/Pulse installation or lifecycle, shared
phone/desktop audio, audio focus, and foreground-service policy are not wired.
It adds no permissions and makes no claim of audible hardware output.

The caller provides an existing FIFO beneath `Context.getFilesDir()`, owns the
producer, and must call `close()` even after worker EOF or failure. Android's
Context-provided app-directory alias is accepted; aliases beneath that directory,
missing files, regular files, changed inode identity, and a different FIFO owner
are rejected. The final open uses `O_NOFOLLOW`, `O_NONBLOCK`, and `O_CLOEXEC` and
checks the opened descriptor with `fstat`. This assumes an app-owned producer;
it is not a filesystem transaction against concurrent hostile same-UID changes.

One worker uses an 8192-byte buffer, polls the input and a cancellation pipe, and
submits only complete frames. It retains up to three bytes across reads. A writer
disconnect ends that worker; any final incomplete frame is counted and discarded.
EOF does not release the track: queued audio can continue playing until the caller
closes. `bytesRead`, `framesSubmitted`, and `partialBytes` describe transport,
not physical presentation. Playback head is the public unsigned 32-bit position,
not a wrap-extended lifetime counter.

Close cancels, pauses the track to interrupt a blocking write, signals a dedicated
pipe to wake a blocked poll, and joins before releasing any owned descriptor or
the track. Exactly one byte is sent to the otherwise empty cancellation pipe, so
the signal cannot fill it. Public `ParcelFileDescriptor.createPipe()` provides
close-on-exec descriptors on the supported API 29+ range. A join failure retains
ownership and reports an error so the caller can retry close. Close aborts queued
audio; it does not promise a graceful drain.

The public AudioTrack write contract explicitly allows another thread's pause
or stop to interrupt a blocking write and return a short transfer. The worker
accounts for accepted complete frames and never calls play after startup.
References: [Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[Android Os](https://developer.android.com/reference/android/system/Os), and
[ParcelFileDescriptor](https://developer.android.com/reference/android/os/ParcelFileDescriptor).

`AndroidPcmOutputTest` covers playback-head progress, fragmented reads, incomplete
frames, missing or stalled writers, cancellation during a blocked AudioTrack
write, repeated close/reopen, rejected paths, and release after join. It requires
explicit `-e audio true` and does not require a prepared Linux installation.
After installing the host and test APKs, run it with:

```sh
adb -s emulator-5562 shell am instrument -w -r -e audio true \
  -e class dev.omamobile.host.AndroidPcmOutputTest \
  dev.omamobile.host.test/androidx.test.runner.AndroidJUnitRunner
```

Playback-head progress verifies the public Android pipeline. Speakers, physical
device routing, ARM64 behavior, and a complete Linux audio service need separate
verification.

## PipeWire integration test

`PipeWireAndroidOutputTest` requires `-e pipewireAudio true` and prepared Linux
with the existing PipeWire/libpipewire 1:1.6.9-1, Python, pw-cli, and pw-link.
Missing prerequisites fail an opted-in test. The test-only fixture creates one
private foreground graph: the audiotestsrc default 440 Hz source, two explicit
links, and a pipe-tunnel sink emitting S16LE stereo at 48 kHz into AndroidPcmOutput.
Both private protocol sockets have explicit access; no global policy changes.

The normal test checks actual node formats, running states and active links,
complete FIFO-frame submission and advancing public playback head. The second
test cancels the Android consumer while PipeWire is alive, then injects a fixture
failure. Both require producer reaping, joined Android worker, released track and
descriptors, and exact FIFO/run-directory removal. Evidence remains in the app's
private `cache/pipewire-android-proof/<run>` directory. This extends transport
verification, with the same audibility and integration limits above; it adds no
application audio service, focus handling, package installation, or default-device
policy. All public-delivery holds remain.

The complete owned run holds the shared installation lease through cleanup.
The single-threaded Python fixture binds its foreground server with
`PR_SET_PDEATHSIG(SIGKILL)` before exec and checks its parent again after arming;
an arming failure fails startup. A third test uses public `Os.kill(SIGKILL)` on
the verified helper identity after the graph is running. It requires the exact
producer PID/start identity to disappear before FIFO removal and lease release.
Normal and controlled-failure cases assert Python reaped the server; the forced
case bypasses Python finally and proves parent-death termination and observed
process absence, with orphan reaping left to Android. This fixture-specific
binding does not establish ownership for a future application audio service.

## Ordinary Linux-client policy test

`PipeWirePolicyOutputTest` is a separate opt-in integration gate. Run it only on a
prepared disposable emulator with the signed Arch packages `pipewire`,
`libpipewire`, `pipewire-audio`, `pipewire-pulse` 1:1.6.9-1 and `wireplumber`
0.5.18-1. It checks the installed WirePlumber policy config hash and fails on
missing or changed prerequisites. Enable it with `-e pipewirePolicyAudio true`
and class `dev.omamobile.host.PipeWirePolicyOutputTest`.

Its private foreground fixture uses WirePlumber's installed policy profile,
PipeWire's internal software driver and a pipe-tunnel sink connected to a real
app-owned source FIFO. One owned foreground GNU `tee` relay copies those PCM
bytes to the Android FIFO and a regular capture file; it is the source FIFO's
only reader. It disables Pulse's fallback sink in a private XDG config. A
normal native `pw-play` stream, a second native stream after idle, and a normal
Pulse `paplay` stream must each automatically link to that default sink and
produce complete frames plus an advancing Android playback head while the
client is active. Each captured segment must contain the generated 440 Hz tone,
and Android's read/submitted byte counts must catch up to the capture length.
The relay requires the installed signed Arch `coreutils` package. There is no
synthetic test source or manual link. The test
also checks that the Android worker survives idle, that all exact foreground
children are gone after normal stop or forced helper death, and that the track,
descriptors, both FIFOs and run directory are released. Raw logs, graph snapshots,
identities and receipts remain under the app's private
`cache/pipewire-policy-proof/<run>` directory.

This is an application-to-Android pipeline gate, not a phone/desktop audio
service. It does not claim speaker audibility, Android focus/routing policy,
real hardware, or service lifecycle support.
