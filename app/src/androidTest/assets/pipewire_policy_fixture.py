"""Opt-in ordinary-client PipeWire policy fixture; one foreground helper owns every child."""
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import select
import signal
import struct
import subprocess
import sys
import time
import wave

R, FIFO = Path(sys.argv[1]), Path(sys.argv[2])
WP_CONFIG = Path('/usr/share/wireplumber/wireplumber.conf')
WP_SHA256 = '704605702bb4cee39d99bf0da838ebe6a4f0510d861e1e244ce7bd2c8d7d8d34'
CHILDREN = []
RECEIPT = {'error': None, 'children': [], 'phases': []}
PARENT_PID = os.getpid()
LIBC = ctypes.CDLL(None, use_errno=True)
LIBC.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong,
                       ctypes.c_ulong, ctypes.c_ulong]
LIBC.prctl.restype = ctypes.c_int


def starttime(pid):
    return Path('/proc/%d/stat' % pid).read_text().rsplit(')', 1)[1].split()[19]


def bind_child():
    if LIBC.prctl(1, signal.SIGKILL, 0, 0, 0) != 0 or os.getppid() != PARENT_PID:
        os._exit(125)  # PR_SET_PDEATHSIG failed, or helper died before arming.


def save_children():
    temp = R / 'children.json.tmp'
    temp.write_text(json.dumps(RECEIPT['children'], indent=2))
    os.replace(temp, R / 'children.json')


def spawn(name, argv, env):
    log = (R / (name + '.log')).open('wb')
    child = subprocess.Popen(argv, env=env, stdin=subprocess.DEVNULL,
                             stdout=log, stderr=subprocess.STDOUT,
                             preexec_fn=bind_child)
    try:
        started = starttime(child.pid)
    except FileNotFoundError:
        rc = child.wait(timeout=3)
        log.close()
        raise RuntimeError(name + ' exited before identity capture rc=' + str(rc))
    identity = {'name': name, 'pid': child.pid, 'starttime': started}
    CHILDREN.append((child, identity, log))
    RECEIPT['children'].append(identity)
    save_children()
    return child


def query(name, argv, env, timeout=3):
    result = subprocess.run(argv, env=env, capture_output=True, text=True, timeout=timeout)
    (R / (name + '.stdout')).write_text(result.stdout)
    (R / (name + '.stderr')).write_text(result.stderr)
    if result.returncode:
        raise RuntimeError('%s rc=%d: %s' % (name, result.returncode, result.stderr[-400:]))
    return result.stdout


def graph(name, env):
    return json.loads(query(name, ['/usr/bin/pw-dump'], env))


def active_link(objects, pid):
    clients = {item['id']: item['info'].get('props', {}) for item in objects
               if item['type'].endswith(':Client') and 'info' in item}
    nodes = {item['id']: item['info'].get('props', {}) for item in objects
             if item['type'].endswith(':Node') and 'info' in item}
    sinks = [key for key, props in nodes.items()
             if props.get('node.name') == 'oa-sink' and props.get('media.class') == 'Audio/Sink']
    streams = {key for key, props in nodes.items()
               if props.get('media.class') == 'Stream/Output/Audio'
               and str(clients.get(props.get('client.id'), {}).get('application.process.id',
                                   props.get('application.process.id'))) == str(pid)}
    return len(sinks) == 1 and any(
        item['info'].get('input-node-id') == sinks[0]
        and item['info'].get('output-node-id') in streams
        and item['info'].get('state') == 'active'
        for item in objects if item['type'].endswith(':Link') and 'info' in item)


def wait_for(check, seconds, label):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        for child, identity, _ in CHILDREN[:3]:
            if child.poll() is not None:
                raise RuntimeError(identity['name'] + ' exited during ' + label)
        if check():
            return
        time.sleep(.05)
    raise TimeoutError(label)


def command_line(seconds=15):
    if not select.select([sys.stdin], [], [], seconds)[0]:
        raise TimeoutError('Java control deadline')
    line = sys.stdin.readline()
    if not line:
        raise RuntimeError('Java control EOF')
    return line.strip()


def wav(path):
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(48000)
        frames = bytearray()
        for i in range(48000 * 3):
            value = int(5000 * math.sin(2 * math.pi * 440 * i / 48000))
            frames += struct.pack('<hh', value, value)
        output.writeframes(frames)


def config():
    return '''context.properties = {
 core.daemon = true core.name = oa support.dbus = false loop.rt-prio = 0
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
   access.legacy = false access.socket = { oa = unrestricted oa-manager = unrestricted }
 } }
 { name = libpipewire-module-spa-node-factory }
 { name = libpipewire-module-client-node }
 { name = libpipewire-module-link-factory }
 { name = libpipewire-module-adapter }
 { name = libpipewire-module-metadata }
 { name = libpipewire-module-session-manager }
 { name = libpipewire-module-pipe-tunnel args = {
   tunnel.mode = sink tunnel.may-pause = false pipe.filename = "''' + str(FIFO) + '''"
   node.name = oa-sink media.class = Audio/Sink
   node.always-process = true
   audio.format = S16LE audio.rate = 48000
   audio.channels = 2 audio.position = [ FL FR ]
 } }
]
context.objects = [
 { factory = spa-node-factory args = {
   factory.name = support.node.driver node.name = Dummy-Driver
   node.group = pipewire.dummy node.sync-group = sync.dummy
   priority.driver = 200000
 } }
]
'''


def run():
    if not R.is_dir() or not FIFO.is_fifo() or FIFO.parent != R:
        raise RuntimeError('existing app-owned FIFO in private run directory required')
    packages = query('packages', ['/usr/bin/pacman', '-Q', 'pipewire', 'libpipewire',
                                   'pipewire-audio', 'pipewire-pulse', 'wireplumber',
                                   'libpulse'], os.environ.copy())
    expected = {'pipewire 1:1.6.9-1', 'libpipewire 1:1.6.9-1',
                'pipewire-audio 1:1.6.9-1', 'pipewire-pulse 1:1.6.9-1',
                'wireplumber 0.5.18-1'}
    if not expected.issubset(set(packages.splitlines())):
        raise RuntimeError('untested PipeWire/WirePlumber version')
    if hashlib.sha256(WP_CONFIG.read_bytes()).hexdigest() != WP_SHA256:
        raise RuntimeError('WirePlumber 0.5 policy config changed; inspect before running')
    if Path('/etc/wireplumber').exists():
        raise RuntimeError('system WirePlumber override requires inspection')
    for binary in ('pipewire', 'wireplumber', 'pipewire-pulse', 'pw-play', 'paplay',
                   'pw-dump', 'pactl'):
        if not (Path('/usr/bin') / binary).is_file():
            raise RuntimeError('missing /usr/bin/' + binary)
    (R / 'pipewire.conf').write_text(config())
    wav(R / 'tone.wav')
    for name in ('home', 'config', 'cache', 'state'):
        (R / name).mkdir(mode=0o700)
    pulse = R / 'config/pipewire/pipewire-pulse.conf.d'
    pulse.mkdir(parents=True)
    (pulse / 'no-fallback.conf').write_text(
        'pulse.properties = { pulse.cmd.always-sink = false }\n')
    env = os.environ.copy()
    for key in tuple(env):
        if key.startswith(('PIPEWIRE_', 'WIREPLUMBER_', 'PULSE_', 'XDG_')):
            env.pop(key)
    env.update(HOME=str(R / 'home'), XDG_CONFIG_HOME=str(R / 'config'),
               XDG_CACHE_HOME=str(R / 'cache'), XDG_STATE_HOME=str(R / 'state'),
               XDG_RUNTIME_DIR=str(R), PIPEWIRE_RUNTIME_DIR=str(R),
               PIPEWIRE_REMOTE='oa', PULSE_SERVER='unix:' + str(R / 'pulse/native'))
    spawn('pipewire', ['/usr/bin/pipewire', '-c', str(R / 'pipewire.conf')], env)
    wait_for(lambda: (R / 'oa').exists(), 5, 'native socket')
    spawn('wireplumber', ['/usr/bin/wireplumber', '--profile', 'policy'], env)
    spawn('pipewire-pulse', ['/usr/bin/pipewire-pulse'], env)

    def ready():
        if not (R / 'pulse/native').exists():
            return False
        try:
            info = query('pactl-info', ['/usr/bin/pactl', 'info'], env, 2)
            nodes = graph('readiness', env)
            return 'Default Sink: oa-sink' in info and any(
                item['type'].endswith(':Node')
                and item.get('info', {}).get('props', {}).get('node.name') == 'oa-sink'
                for item in nodes)
        except (OSError, subprocess.SubprocessError, ValueError, RuntimeError):
            return False

    wait_for(ready, 8, 'policy/Pulse default sink')
    (R / 'ready').write_text('ready')
    for phase, client in (('native1', 'pw-play'), ('native2', 'pw-play'), ('pulse', 'paplay')):
        if command_line() != 'start ' + phase:
            raise RuntimeError('expected start ' + phase)
        process = spawn(phase, ['/usr/bin/' + client, str(R / 'tone.wav')], env)
        def linked():
            return active_link(graph(phase + '-graph', env), process.pid)
        wait_for(linked, 4, phase + ' active link')
        (R / (phase + '.active')).write_text(str(process.pid))
        try:
            rc = process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            raise RuntimeError(phase + ' client did not complete')
        result = {'phase': phase, 'clientPid': process.pid, 'activeLinkObserved': True,
                  'clientReturnCode': rc}
        RECEIPT['phases'].append(result)
        (R / (phase + '.done.json')).write_text(json.dumps(result))
        if rc:
            raise RuntimeError(phase + ' client rc=' + str(rc))
    if command_line() != 'stop':
        raise RuntimeError('expected stop')


def terminated(signum, frame):
    raise InterruptedError('fixture terminated')


signal.signal(signal.SIGTERM, terminated)
(R / 'helper.json').write_text(json.dumps(
    {'pid': os.getpid(), 'starttime': starttime(os.getpid()),
     'stat': Path('/proc/self/stat').read_text()}))
try:
    run()
except BaseException as error:
    RECEIPT['error'] = repr(error)
finally:
    cleanup_errors = []
    for child, identity, log in reversed(CHILDREN):
        try:
            if child.poll() is None:
                try:
                    if starttime(child.pid) == identity['starttime']:
                        child.terminate()
                except FileNotFoundError:
                    pass
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                try:
                    if starttime(child.pid) == identity['starttime']:
                        child.kill()
                except FileNotFoundError:
                    pass
                child.wait(timeout=3)
            identity['returncode'] = child.returncode
            try:
                identity['identityAbsent'] = starttime(child.pid) != identity['starttime']
            except FileNotFoundError:
                identity['identityAbsent'] = True
        except Exception as error:
            identity['identityAbsent'] = False
            cleanup_errors.append(identity['name'] + ': ' + repr(error))
        log.close()
    RECEIPT['allChildrenReaped'] = all(c['identityAbsent'] for c in RECEIPT['children'])
    RECEIPT['cleanupErrors'] = cleanup_errors
    (R / 'receipt.json').write_text(json.dumps(RECEIPT, indent=2))
    print(json.dumps(RECEIPT), flush=True)
sys.exit(0 if RECEIPT['error'] is None and RECEIPT['allChildrenReaped']
         and not RECEIPT['cleanupErrors'] else 7)
