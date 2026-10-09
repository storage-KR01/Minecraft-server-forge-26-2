from pathlib import Path
import os, shutil, tempfile
root = Path('/home/ubuntu/minecraft-26-2')
fixture = Path(tempfile.mkdtemp(prefix='hardcore-forge-test-'))
for name in ['run.sh', 'server-wrapper.sh', 'forge-26.2-65.1.3-shim.jar']:
    shutil.copy2(root / name, fixture / name)
shutil.copytree(root / 'libraries', fixture / 'libraries', copy_function=os.link)
shutil.copytree(root / 'hardcore-reset', fixture / 'hardcore-reset')
(fixture / 'mods').mkdir()
shutil.copy2(root / 'hardcorereset-src/build/libs/hardcorereset-1.1.0.jar', fixture / 'mods')
shutil.copy2(root / 'hardcore-reset/tests/hardcoreresettest.jar', fixture / 'mods')
(fixture / 'user_jvm_args.txt').write_text('-Xms512M\n-Xmx2G\n')
(fixture / 'eula.txt').write_text('eula=true\n')
(fixture / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25567\nlevel-name=world\nlevel-seed=\nonline-mode=false\nhardcore=true\ndifficulty=hard\ngamemode=survival\nforce-gamemode=true\nview-distance=2\nsimulation-distance=2\npause-when-empty-seconds=0\n')
(fixture / 'ISOLATED_TEST_ONLY').touch()
(root / 'hardcore-reset/tests/fixture-path.txt').write_text(str(fixture))
print(fixture)
