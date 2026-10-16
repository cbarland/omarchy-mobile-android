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
close-on-exec descriptors on the supported API29+ range. A join failure retains
ownership and reports an error so the caller can retry close. Close aborts queued
audio; it does not promise a graceful drain.

The public AudioTrack write contract explicitly allows another thread's pause
or stop to interrupt a blocking write and return a short transfer. The worker
accounts for accepted complete frames and never calls play after startup.
References: [Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[Android Os](https://developer.android.com/reference/android/system/Os), and
[ParcelFileDescriptor](https://developer.android.com/reference/android/os/ParcelFileDescriptor).

`AndroidPcmOutputTest` requires explicit `-e audio true`. On an unprepared API35
x86_64 emulator, six tests executed with no skips: real synthetic PCM playback
head progress, fragmented reads, an incomplete final frame, no-writer and
connected-writer read stalls, cancellation during an observed AudioTrack write,
repeated close/reopen, rejected paths, and track/descriptor release after join.
The final run submitted 48000 frames from 192000 bytes, observed head45696, and
closed an observed backpressured write in3 ms. These observations verify the
public playback pipeline and bounded cleanup on that emulator, not speakers,
ARM64, device audio routing, or a complete Linux audio service.

Run the selected test using the normal test APK runner:

```sh
adb -s emulator-5562 shell am instrument -w -e audio true \
  -e class dev.omamobile.host.AndroidPcmOutputTest \
  dev.omamobile.host.test/androidx.test.runner.AndroidJUnitRunner
```

Offline core APK/test assembly and lint passed. The existing Windows JVM suite
ran74 tests with72 passed, one skipped, and the unchanged
`InstallationLeaseTest.processDeathReleasesSharedLease` failing immediately after
forced process death; this unit does not change that lease implementation/test.
All seven public-delivery holds remain.
