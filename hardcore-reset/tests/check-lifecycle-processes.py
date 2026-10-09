"""Run ONLY inside the private mount/PID namespace, with this cwd bound to a temp fixture."""
from pathlib import Path
import os, signal, subprocess, time
root = Path('/home/ubuntu/minecraft-26-2')
assert (root / 'ISOLATED_TEST_ONLY').exists()
os.chdir(root)
world = root / 'world'
world.mkdir(exist_ok=True)
(world / 'keep').write_text('keep')
run = root / 'run.sh'

def invoke():
    return subprocess.Popen(['/bin/bash', './server-wrapper.sh'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

def wait_line(proc, needle):
    lines = []
    while True:
        line = proc.stdout.readline()
        assert line, ''.join(lines)
        lines.append(line)
        if needle in line: return

def finish(proc, code):
    output = proc.communicate(timeout=20)[0]
    print(output, end='')
    assert proc.returncode == code, proc.returncode

run.write_text('#!/bin/sh\nexit 7\n')
finish(invoke(), 1)
assert (world / 'keep').exists()
print('[PROCESS TEST] PASS crash preserves world and exits nonzero for systemd')
run.write_text('#!/bin/sh\nmkdir -p crash-reports\necho crash > crash-reports/test.txt\nexit 0\n')
finish(invoke(), 1)
assert (world / 'keep').exists()
print('[PROCESS TEST] PASS crash report with exit zero is reported as failure')
run.write_text('#!/bin/sh\nexit 0\n')
finish(invoke(), 0)
assert (world / 'keep').exists()
print('[PROCESS TEST] PASS normal exit preserves world')
(root / 'fake-server.py').write_text('''import signal,time
from pathlib import Path
def stop(*args):
 time.sleep(1)
 Path("child-fully-stopped").touch()
 raise SystemExit(0)
signal.signal(signal.SIGTERM,stop)
print("FAKE READY",flush=True)
while True: time.sleep(.1)
''')
run.write_text('#!/bin/sh\npython3 fake-server.py\n')
proc = invoke()
wait_line(proc, 'FAKE READY')
proc.send_signal(signal.SIGTERM)
finish(proc, 0)
assert (root / 'child-fully-stopped').exists()
assert (world / 'keep').exists()
print('[PROCESS TEST] PASS manual stop waits for child and preserves world')
(root / 'child-fully-stopped').unlink()
proc = invoke()
wait_line(proc, 'FAKE READY')
(root / '.reset_world').write_text(str(world) + '\n')
proc.send_signal(signal.SIGTERM)
finish(proc, 0)
assert (world / 'keep').exists() and (root / '.reset_world').exists()
print('[PROCESS TEST] PASS manual stop defers pending reset')
run.write_text('#!/bin/sh\nmkdir world\ntouch world/fresh\nexit 0\n')
finish(invoke(), 0)
assert (world / 'fresh').exists() and not (world / 'keep').exists()
assert not (root / '.reset_world').exists()
print('[PROCESS TEST] PASS pending reset completes before next Forge start')
(root / 'server.properties').write_text('level-name=../world\n')
finish(invoke(), 78)
assert (world / 'fresh').exists()
print('[PROCESS TEST] PASS unsafe config exits 78, preventing restart loop')
