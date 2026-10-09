"""Validate using generated disposable worlds, never production world data."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import signal
import subprocess
import tempfile

ROOT = Path('/home/ubuntu/minecraft-26-2')
os.chdir(ROOT)
with (ROOT / 'hardcore-reset/safety-tests-1.1.0.log').open('w') as output:
    subprocess.run(['python3', 'hardcore-reset/test_lifecycle.py'], stdout=output, stderr=subprocess.STDOUT, check=True)
fixture = Path(tempfile.mkdtemp(prefix='hardcore-process-test-'))
try:
    shutil.copytree(ROOT / 'hardcore-reset', fixture / 'hardcore-reset')
    shutil.copy(ROOT / 'server-wrapper.sh', fixture / 'server-wrapper.sh')
    (fixture / 'server.properties').write_text('level-name=world\nlevel-seed=\n')
    (fixture / 'ISOLATED_TEST_ONLY').touch()
    with (ROOT / 'hardcore-reset/process-tests-1.1.0.log').open('w') as output:
        subprocess.run(['sudo', '-n', 'unshare', '--mount', '--pid', '--fork', '--mount-proc',
                        '/bin/bash', str(ROOT / 'hardcore-reset/tests/run-process-tests.sh'), str(fixture)],
                       stdout=output, stderr=subprocess.STDOUT, timeout=45, check=True)
finally:
    shutil.rmtree(fixture)
subprocess.run(['python3', 'hardcore-reset/tests/prepare-integration.py'], check=True)
fixture = Path((ROOT / 'hardcore-reset/tests/fixture-path.txt').read_text())
with (ROOT / 'hardcore-reset/integration-tests-1.1.0.log').open('w') as output:
    command = ['sudo', '-n', 'unshare', '--mount', '--pid', '--fork', '--mount-proc',
               '/bin/bash', str(ROOT / 'hardcore-reset/tests/run-isolated.sh'), str(fixture)]
    process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        code = process.wait(timeout=180)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=20)
        raise
assert code == 0 and (fixture / 'TEST_PASSED').exists(), f'Integration failed: exit={code}, fixture={fixture}'
log = (ROOT / 'hardcore-reset/integration-tests-1.1.0.log').read_text()
assert log.count('Requesting Minecraft server shutdown (first death: FirstDeath)') == 1
assert log.count('Permanently deleting /home/ubuntu/minecraft-26-2/world (no backup)') == 3
assert not (fixture / '.reset_world').exists()
assert (fixture / 'world/level.dat').exists(), 'Normal final stop must preserve generated world'
assert (fixture / 'COUNTDOWN_PASSED').exists() and (fixture / 'CRASH_DURING_COUNTDOWN').exists()
seeds = (fixture / 'seeds.txt').read_text().splitlines()
assert len(seeds) == 4 and len(set(seeds)) == 4
result = {
    'safety_tests': 13, 'process_scenarios': 7,
    'countdown_timing': (fixture / 'COUNTDOWN_PASSED').read_text(),
    'distinct_random_seeds': seeds,
    'shutdown_requests_normal_countdown': 1,
    'crash_during_countdown_recovered': True,
    'normal_stop_preserved_world': True,
    'jar_sha256': hashlib.sha256((ROOT / 'hardcorereset-src/build/libs/hardcorereset-1.1.0.jar').read_bytes()).hexdigest(),
    'connection_coverage': 'Server-side packet capture via simulated connections; no human client',
}
(ROOT / 'hardcore-reset/validation-1.1.0.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2), flush=True)
# Delete generated test worlds, not backups or copies of the production world.
assert fixture.name.startswith('hardcore-forge-test-') and (fixture / 'ISOLATED_TEST_ONLY').is_file()
shutil.rmtree(fixture)
(ROOT / 'hardcore-reset/tests/fixture-path.txt').unlink()
