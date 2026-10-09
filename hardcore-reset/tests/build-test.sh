#!/bin/bash
set -euo pipefail
cd /home/ubuntu/minecraft-26-2
python3 - <<'PY'
from pathlib import Path
import subprocess
r=Path.cwd()
cp=[r/'libraries/net/minecraftforge/forge/26.2-65.1.3/forge-26.2-65.1.3-server.jar']+list((r/'libraries').rglob('*.jar'))
subprocess.run(['javac','-proc:none','-cp',':'.join(map(str,cp)),'-d','hardcore-reset/tests/classes','hardcore-reset/tests/java/local/hardcoreresettest/ResetIntegration.java'],check=True)
PY
jar --create --file hardcore-reset/tests/hardcoreresettest.jar -C hardcore-reset/tests/classes . -C hardcore-reset/tests/resources .
