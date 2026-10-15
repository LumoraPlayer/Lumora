#!/usr/bin/env bash
# Push main to all three homes via origin's pushurls. (not part of the app)
set -eu
cd /root/Lumora
git push
echo "--- local vs github:"
git ls-remote origin refs/heads/main
git rev-parse HEAD
