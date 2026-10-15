#!/usr/bin/env bash
# Commit the staged sports/EPG work with the prepared message. (not part of the app)
set -eu
cd /root/Lumora
git commit -F .slim/commit-msg.txt
git log -1 --format=%h
git status -sb | head -3
