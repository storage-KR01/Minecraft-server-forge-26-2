#!/usr/bin/python3
"""Own Forge's lifecycle. Only a validated death marker authorizes deletion."""
import base64
import fcntl
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import time

SERVER_DIR = Path('/home/ubuntu/minecraft-26-2')
MARKER = '.reset_world'
RESERVED = {'mods', 'libraries', 'config', 'defaultconfigs', 'logs', 'crash-reports',
            'hardcore-reset', 'hardcorereset-src'}


def log(message):
    print(f'[HARDCORE wrapper] {message}', flush=True)


def read_world(root):
    result = subprocess.run(['java', '-cp', str(root / 'hardcore-reset'), 'ReadProperties',
                             str(root / 'server.properties')], check=True,
                            capture_output=True, text=True)
    lines = result.stdout.splitlines()
    if len(lines) != 2:
        raise ValueError('Invalid properties reader output')
    name, seed = (base64.b64decode(line, validate=True).decode('utf-8') for line in lines)
    # A single ordinary directory name deliberately excludes all traversal, globs,
    # separators, hidden control paths, Unicode tricks and administrative directories.
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9 _.-]{0,127}', name) or name in RESERVED:
        raise ValueError(f'Unsafe or reserved level-name: {name!r}')
    if name.endswith((' ', '.')):
        raise ValueError('level-name may not end in a space or dot')
    target = root / name
    if target.is_symlink() or target.resolve().parent != root or target.resolve() == root:
        raise ValueError(f'World must be a direct, non-symlink child: {target}')
    if target.exists() and not target.is_dir():
        raise ValueError(f'World is not a directory: {target}')
    if seed:
        raise ValueError('level-seed must be empty for random-world resets; refusing startup/wipe')
    return target


def assert_no_java(root):
    # Includes a manually launched run.sh outside our systemd cgroup.
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            if (proc / 'comm').read_text().strip() == 'java' and (proc / 'cwd').resolve() == root:
                raise RuntimeError(f'Java process {proc.name} still uses {root}; refusing deletion/start')
        except (FileNotFoundError, ProcessLookupError):
            pass
        except PermissionError:
            # Other users' processes cannot normally open this user's world.
            continue


def reset_world(root, expected=None):
    target = read_world(root)
    if expected is not None and target != expected:
        raise ValueError('level-name changed while Forge ran; refusing deletion')
    marker = root / MARKER
    with os.fdopen(os.open(marker, os.O_RDONLY | os.O_NOFOLLOW), 'r') as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError('Reset marker must be a regular file')
        content = stream.read(4097)
    if content != str(target) + '\n':
        raise ValueError('Marker does not match the configured world; refusing deletion')
    assert_no_java(root)
    lock_fd = None
    try:
        if target.exists():
            # Refuse mounts, nested mounts and symlinks (even inside the world).
            # shutil.rmtree uses fd-relative operations and does not follow symlinks.
            for current, dirs, files in os.walk(target, followlinks=False):
                if os.path.ismount(current):
                    raise ValueError(f'Mount inside world: {current}')
                for name in dirs + files:
                    if Path(current, name).is_symlink():
                        raise ValueError(f'Symlink inside world: {Path(current, name)}')
            lock_fd = os.open(target / 'session.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
            # Java FileChannel locks use POSIX record locks on Linux, not flock.
            fcntl.lockf(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if not shutil.rmtree.avoids_symlink_attacks:
                raise RuntimeError('Platform lacks safe fd-relative rmtree')
            log(f'Permanently deleting {target} (no backup)')
            shutil.rmtree(target)
        else:
            log(f'World already absent: {target}')
        if target.exists() or target.is_symlink():
            raise RuntimeError('World deletion incomplete; marker retained')
        marker.unlink()
        log('Reset complete; marker removed')
    finally:
        if lock_fd is not None:
            os.close(lock_fd)


def crash_state(root):
    return {str(p): p.stat().st_mtime_ns for p in (root / 'crash-reports').glob('*.txt')}


def group_alive(pgid):
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            # comm may contain spaces or parentheses.
            fields = (proc / 'stat').read_text().rsplit(')', 1)[1].split()
            if int(fields[2]) == pgid and fields[0] != 'Z':
                return True
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            pass
    return False


def main():
    root = SERVER_DIR
    if root != Path('/home/ubuntu/minecraft-26-2') or root.resolve() != root or Path.cwd() != root:
        raise ValueError('SERVER_DIR/cwd does not equal the approved server directory')
    with os.fdopen(os.open(root / '.hardcorereset-wrapper.lock',
                          os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600), 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        stopping = False
        child = None

        def stop(signum, frame):
            nonlocal stopping
            stopping = True
            log('Administrator stop requested; no world deletion during stop')
            if child is not None:
                try:
                    os.killpg(child.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass

        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)

        def pause():
            end = time.monotonic() + 4
            while not stopping and time.monotonic() < end:
                time.sleep(0.1)

        assert_no_java(root)
        # A pending death request survives power loss or systemctl stop. Finish it
        # on the next explicit/service start, before ever reopening that world.
        if os.path.lexists(root / MARKER) and not stopping:
            reset_world(root)
            pause()
        while not stopping:
            target = read_world(root)
            assert_no_java(root)
            previous_crashes = crash_state(root)
            log(f'Starting existing run.sh; world={target}')
            child = subprocess.Popen(['/bin/sh', str(root / 'run.sh'), 'nogui'],
                                     cwd=root, start_new_session=True)
            # A stop arriving during Popen must also reach the newly created child.
            if stopping:
                os.killpg(child.pid, signal.SIGTERM)
            code = child.wait()
            while group_alive(child.pid):
                time.sleep(0.1)
            child = None
            if stopping:
                return 0
            if os.path.lexists(root / MARKER):
                reset_world(root, target)
                pause()
            else:
                if code == 0 and crash_state(root) != previous_crashes:
                    log('Forge wrote a crash report despite exit code 0; reporting failure to systemd')
                    code = 1
                log(f'Forge exited ({code}); no death marker, world preserved')
                return 0 if code == 0 else 1  # systemd owns crash restarts
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        log(f'REFUSING to continue: {error}; pending marker retained for inspection')
        sys.exit(78)  # RestartPreventExitStatus: never spin on unsafe paths/deletion failures
