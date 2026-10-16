"""Test-only private graph; no persistent service or policy installation."""
import ctypes, json, os, select, signal, subprocess, sys, time
from pathlib import Path
R = Path(sys.argv[1])
(R / 'helper.json').write_text(json.dumps({'pid': os.getpid(), 'stat': Path('/proc/self/stat').read_text()}))
config = """context.properties = {
 core.daemon = true core.name = oa support.dbus = false loop.rt-prio = 0
 mem.allow-mlock = false default.clock.rate = 48000
 default.clock.allowed-rates = [ 48000 ] default.clock.quantum = 1024
 default.clock.min-quantum = 1024 default.clock.max-quantum = 1024
}
context.spa-libs = {
 audio.convert.* = audioconvert/libspa-audioconvert
 support.* = support/libspa-support
 audiotestsrc = audiotestsrc/libspa-audiotestsrc
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
 { name = libpipewire-module-pipe-tunnel args = {
   tunnel.mode = sink tunnel.may-pause = false pipe.filename = "FIFO"
   node.name = oa-sink audio.format = S16LE audio.rate = 48000
   audio.channels = 2 audio.position = [ FL FR ]
 } }
]
context.objects = [
 { factory = adapter args = {
   factory.name = audiotestsrc node.name = oa-tone media.class = Audio/Source
   audio.rate = 48000 audio.channels = 2 audio.position = [ FL FR ]
   node.param.Props = { live = true volume = 0.25 }
 } }
]
""".replace('FIFO', str(R / 'out.pcm'))
(R / 'pipewire.conf').write_text(config)
env = os.environ.copy()
for key in ('PIPEWIRE_CONFIG_DIR', 'PIPEWIRE_CONFIG_NAME', 'PIPEWIRE_CONFIG_PREFIX'):
    env.pop(key, None)
env.update(XDG_RUNTIME_DIR=str(R), PIPEWIRE_RUNTIME_DIR=str(R), PIPEWIRE_REMOTE='oa', PIPEWIRE_DEBUG='3')
receipt = {'error': None, 'commands': [], 'serverReaped': False, 'requestedFailure': False}
server = None
# This fixture has no Python threads. Arm only its one foreground server child;
# fork clears PDEATHSIG, while the runtime's --exec-child does not arm it itself.
libc = ctypes.CDLL(None, use_errno=True)
libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]
libc.prctl.restype = ctypes.c_int
parent_pid = os.getpid()

def bind_server_to_parent():
    if libc.prctl(1, signal.SIGKILL, 0, 0, 0) != 0:  # PR_SET_PDEATHSIG
        os.write(2, ('PR_SET_PDEATHSIG failed errno=' + str(ctypes.get_errno()) + '\n').encode())
        os._exit(125)
    # Parent may have died before arming; after this check the kernel owns the race.
    if os.getppid() != parent_pid:
        os._exit(125)

def command(label, argv):
    p = subprocess.run(argv, env=env, capture_output=True, text=True, timeout=3)
    (R / (label + '.stdout')).write_text(p.stdout)
    (R / (label + '.stderr')).write_text(p.stderr)
    receipt['commands'].append({'argv': argv, 'rc': p.returncode})
    if p.returncode:
        raise RuntimeError(label + ': ' + p.stderr[-500:])
    return p.stdout

def terminated(signum, frame):
    raise InterruptedError('fixture terminated')

signal.signal(signal.SIGTERM, terminated)
try:
    command('inventory', ['/usr/bin/pacman', '-Q', 'pipewire', 'libpipewire'])
    command('version', ['/usr/bin/pipewire', '--version'])
    with (R / 'pipewire.log').open('wb') as log:
        server = subprocess.Popen(['/usr/bin/pipewire', '-c', str(R / 'pipewire.conf')],
                                  env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                  preexec_fn=bind_server_to_parent)
    receipt['serverPid'] = server.pid
    (R / 'server.json').write_text(json.dumps({'pid': server.pid, 'stat': Path('/proc/' + str(server.pid) + '/stat').read_text()}))
    end = time.monotonic() + 4
    while not (R / 'oa').exists() and server.poll() is None and time.monotonic() < end:
        time.sleep(.02)
    if server.poll() is not None or not (R / 'oa').exists():
        raise RuntimeError('server socket unavailable')
    graph = json.loads(command('graph-before', ['/usr/bin/pw-dump']))
    nodes = {v['info']['props'].get('node.name'): v['id'] for v in graph if v['type'].endswith(':Node')}
    for node, direction in [('oa-tone', 'Output'), ('oa-sink', 'Input')]:
        command('configure-' + node, ['/usr/bin/pw-cli', 'set-param', str(nodes[node]), 'PortConfig',
                '{ direction = ' + direction + ' mode = dsp format = { mediaType = audio mediaSubtype = raw format = F32P rate = 48000 channels = 2 position = [ FL FR ] } }'])
    graph = json.loads(command('graph-configured', ['/usr/bin/pw-dump']))
    def ports(name, direction):
        return sorted([v for v in graph if v['type'].endswith(':Port')
                       and str(v['info']['props'].get('node.id')) == str(nodes[name])
                       and v['info']['props'].get('port.direction') == direction],
                      key=lambda v: int(v['info']['props'].get('port.id', 0)))
    outputs, inputs = ports('oa-tone', 'out'), ports('oa-sink', 'in')
    if len(outputs) != 2 or len(inputs) != 2:
        raise RuntimeError('expected two observed ports at each node')
    for i, (output, input_) in enumerate(zip(outputs, inputs)):
        command('link-' + str(i), ['/usr/bin/pw-link', str(output['id']), str(input_['id'])])
    command('modules', ['/usr/bin/pw-cli', 'ls', 'Module'])
    command('links', ['/usr/bin/pw-link', '-l'])
    graph = json.loads(command('graph-running', ['/usr/bin/pw-dump']))
    (R / 'ready').write_text('ready')
    # stdin EOF also requests normal shutdown; never leave a durable daemon.
    if not select.select([sys.stdin], [], [], 12)[0]:
        raise TimeoutError('fixture control deadline')
    action = sys.stdin.readline().strip()
    if action == 'fail':
        receipt['requestedFailure'] = True
        raise RuntimeError('injected failure after real graph startup')
    if action not in ('stop', ''):
        raise ValueError('unexpected fixture control')
except BaseException as error:
    receipt['error'] = str(error)
finally:
    if server is not None:
        if server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=3)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=3)
        receipt['serverReturnCode'] = server.returncode
        receipt['serverReaped'] = server.poll() is not None
    (R / 'receipt.json').write_text(json.dumps(receipt, indent=2))
    print(json.dumps(receipt), flush=True)
sys.exit(7 if receipt['error'] else 0)
