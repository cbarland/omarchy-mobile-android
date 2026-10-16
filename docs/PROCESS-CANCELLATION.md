# Linux command cancellation ownership

Android's `Process.destroyForcibly()` may delegate to `destroy()` and request
SIGTERM. A program that ignores TERM can survive. Requesting termination is not
evidence that installation ownership can be released.

Setup and ordinary console commands now keep their direct child owned until
`Process.waitFor()` observes exit. Cleanup requests termination, allows a
two-second soft-stop attempt, calls `destroyForcibly()`, then continues waiting
if the child remains alive. It reports retained ownership once. Interruptions
cannot abandon cleanup; the worker's interrupt flag is restored after exit.
These waits run on the worker, not Android's UI thread.

For setup, this barrier runs before returning to RootfsImporter's staging
rollback and before the enclosing exclusive installation lease closes. Ordinary
commands hold a shared installation lease before inspecting or changing the
rootfs and through their direct child's cleanup. A resistant child keeps the job
busy and the lease held until actual exit or application force-stop; this is not
a bounded hard-kill mechanism.

The barrier observes the direct child. It does not track detached/background
descendants or prove whole-tree quiescence. Application force-stop remains the
existing process-tree boundary. Broker and Android shell-service lifecycles are
unchanged.

`ProcessCleanupTest` requires `-e processCleanup true`. Its opted-in Android
tests use app-private shell/FIFO fixtures without installing Linux. A synthetic
RootfsImporter transaction observes that real staging rollback and exclusive
lease release wait for a TERM-ignoring child, including interruption beyond the
soft-stop deadline. A shared-lease command case and graceful exit are also
checked. Tests use identity-checked public `Os.kill(SIGKILL)` only to finish their
owned resistant fixtures, then verify exit and lease reacquisition. No public
`Process.pid`, hidden reflection, or PID parsing of diagnostic strings is used.

The API35 x86_64 emulator run executed all three cases with no skips. It does
not establish ARM64 behavior or expand the public binary delivery gates.
