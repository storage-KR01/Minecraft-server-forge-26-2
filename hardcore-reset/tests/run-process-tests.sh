#!/bin/bash
set -euo pipefail
fixture=$1
mount --make-rprivate /
mount --bind "$fixture" /home/ubuntu/minecraft-26-2
cd /home/ubuntu/minecraft-26-2
exec runuser -u ubuntu -- python3 hardcore-reset/tests/check-lifecycle-processes.py
