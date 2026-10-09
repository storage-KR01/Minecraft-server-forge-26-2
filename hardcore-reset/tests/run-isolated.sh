#!/bin/bash
# Root only inside private mount + PID namespaces. The actual world is never mounted here.
set -euo pipefail
fixture=$1
mount --make-rprivate /
mount --bind "$fixture" /home/ubuntu/minecraft-26-2
cd /home/ubuntu/minecraft-26-2
exec runuser -u ubuntu -- /bin/bash ./server-wrapper.sh
