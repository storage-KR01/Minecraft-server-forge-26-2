#!/bin/bash
set -euo pipefail
readonly SERVER_DIR=/home/ubuntu/minecraft-26-2
cd -- "$SERVER_DIR"
exec /usr/bin/python3 "$SERVER_DIR/hardcore-reset/lifecycle.py"
