"""One foreground shared audio graph; Android owns focus, playback and device routing."""
import ctypes
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import time

ROOT = Path(sys.argv[1])
FIFO = ROOT / 'output.pcm'
PARENT = os.getpid()
LIBC = ctypes.CDLL(None, use_errno=True)
LIBC.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]
LIBC.prctl.restype = ctypes.c_int
CHILDREN = []
STOPPING = False


def identity(pid):
    return {'pid': pid, 'start': Path('/proc/%d/stat' % pid).read_text().rsplit(')', 1)[1].split()[19]}


def parent_bound():
    if LIBC.prctl(1, signal.SIGKILL, 0, 0, 0) != 0 or os.getppid() != PARENT:
        os._exit(125)


def atomic_json(name, value):
    pending = ROOT / (name + '.tmp')
    pending.write_text(json.dumps(value))
    os.replace(pending, ROOT / name)


def emit(event, **values):
    print(json.dumps({'event': event, **values}), flush=True)


def spawn(name, argv, env):
    log = (ROOT / (name + '.log')).open('wb')
    try:
        process = subprocess.Popen(argv, env=env, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, preexec_fn=parent_bound)
    except BaseException:
        log.close()
        raise
    CHILDREN.append((process, name, log))
    records = [{'name': label, **identity(child.pid)} for child, label, _ in CHILDREN]
    atomic_json('children.json', records)


def healthy():
    for child, name, _ in CHILDREN:
        if child.poll() is not None:
            raise RuntimeError('%s exited (%d)' % (name, child.returncode))


def until(predicate, seconds, label):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if STOPPING:
            raise InterruptedError('audio startup cancelled')
        healthy()
        if predicate():
            return
        time.sleep(.05)
    raise TimeoutError(label)


def configuration():
    return '''context.properties = {
 core.daemon = true core.name = pipewire-0 support.dbus = false loop.rt-prio = 0
 mem.allow-mlock = false default.clock.rate = 48000
 default.clock.allowed-rates = [ 48000 ] default.clock.quantum = 1024
 default.clock.min-quantum = 1024 default.clock.max-quantum = 1024
}
context.spa-libs = {
 audio.convert.* = audioconvert/libspa-audioconvert
 support.* = support/libspa-support
}
context.modules = [
 { name = libpipewire-module-protocol-native }
 { name = libpipewire-module-access args = {
   access.legacy = false access.socket = { pipewire-0 = unrestricted pipewire-0-manager = unrestricted }
 } }
 { name = libpipewire-module-spa-node-factory }
 { name = libpipewire-module-client-node }
 { name = libpipewire-module-link-factory }
 { name = libpipewire-module-adapter }
 { name = libpipewire-module-metadata }
 { name = libpipewire-module-session-manager }
 { name = libpipewire-module-pipe-tunnel args = {
   tunnel.mode = sink tunnel.may-pause = false pipe.filename = "''' + str(FIFO) + '''"
   node.name = omamobile-android node.description = "Android audio output"
   media.class = Audio/Sink node.always-process = true
   audio.format = S16LE audio.rate = 48000
   audio.channels = 2 audio.position = [ FL FR ]
 } }
]
context.objects = [
 { factory = spa-node-factory args = {
   factory.name = support.node.driver node.name = Dummy-Driver
   node.group = pipewire.dummy node.sync-group = sync.dummy priority.driver = 200000
 } }
]
'''


def signal_received(number, _):
    global STOPPING
    if number in (signal.SIGTERM, signal.SIGINT):
        STOPPING = True


def main(wakeup):
    if not ROOT.is_dir() or not FIFO.is_fifo() or not ROOT.name.startswith('a-'):
        raise RuntimeError('existing app-owned runtime and FIFO required')
    for binary in ('pipewire', 'wireplumber', 'pipewire-pulse', 'pactl'):
        if not (Path('/usr/bin') / binary).is_file():
            raise RuntimeError('Missing signed Linux package for ' + binary + '; update Linux integration first')
    for name in ('home', 'config', 'cache', 'state'):
        (ROOT / name).mkdir(mode=0o700)
    pulse = ROOT / 'config/pipewire/pipewire-pulse.conf.d'
    pulse.mkdir(parents=True)
    (pulse / 'no-fallback.conf').write_text('pulse.properties = { pulse.cmd.always-sink = false }\n')
    (ROOT / 'pipewire.conf').write_text(configuration())
    atomic_json('helper.json', identity(os.getpid()))
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(('PIPEWIRE_', 'WIREPLUMBER_', 'PULSE_', 'XDG_'))}
    env.update(HOME=str(ROOT / 'home'), XDG_CONFIG_HOME=str(ROOT / 'config'),
               XDG_CACHE_HOME=str(ROOT / 'cache'), XDG_STATE_HOME=str(ROOT / 'state'),
               XDG_RUNTIME_DIR=str(ROOT), PIPEWIRE_RUNTIME_DIR=str(ROOT),
               PIPEWIRE_REMOTE='pipewire-0', PULSE_SERVER='unix:' + str(ROOT / 'pulse/native'))
    spawn('pipewire', ['/usr/bin/pipewire', '-c', str(ROOT / 'pipewire.conf')], env)
    until(lambda: (ROOT / 'pipewire-0').exists(), 5, 'PipeWire socket readiness')
    spawn('wireplumber', ['/usr/bin/wireplumber', '--profile', 'policy'], env)
    spawn('pipewire-pulse', ['/usr/bin/pipewire-pulse'], env)

    def ready():
        if not (ROOT / 'pulse/native').exists():
            return False
        result = subprocess.run(['/usr/bin/pactl', 'info'], env=env,
                                capture_output=True, text=True, timeout=2)
        return result.returncode == 0 and 'Default Sink: omamobile-android' in result.stdout

    until(ready, 8, 'Android output default sink readiness')
    atomic_json('ready.json', {'helper': identity(os.getpid()), 'sink': 'omamobile-android'})
    emit('ready', sink='omamobile-android')
    while not STOPPING:
        healthy()
        readable, _, _ = select.select([sys.stdin, wakeup], [], [])
        if wakeup in readable:
            try:
                os.read(wakeup, 4096)
            except BlockingIOError:
                pass
        if sys.stdin in readable:
            command = sys.stdin.readline()
            if not command or command.strip() == 'stop':
                return
            raise RuntimeError('Unknown audio control command')


if __name__ == '__main__':
    os.umask(0o077)
    read, write = os.pipe2(os.O_NONBLOCK | os.O_CLOEXEC)
    signal.set_wakeup_fd(write)
    for value in (signal.SIGCHLD, signal.SIGTERM, signal.SIGINT):
        signal.signal(value, signal_received)
    error = None
    cleanup_errors = []
    try:
        main(read)
    except BaseException as failure:
        error = str(failure)
        emit('error', message=error)
    finally:
        for child, name, log in reversed(CHILDREN):
            try:
                if child.poll() is None:
                    child.terminate()
                try:
                    child.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=2)
            except BaseException as failure:
                cleanup_errors.append(name + ': ' + str(failure))
            finally:
                log.close()
        signal.set_wakeup_fd(-1)
        os.close(read)
        os.close(write)
        atomic_json('cleanup.json', {'error': error, 'cleanupErrors': cleanup_errors,
                                    'allChildrenReaped': all(p.poll() is not None for p, _, _ in CHILDREN)})
        emit('stopped', error=error, cleanupErrors=cleanup_errors)
    sys.exit(1 if error or cleanup_errors else 0)
