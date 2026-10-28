#!/bin/bash
cd /root/Lumora
for f in app/build/test-results/testDebugUnitTest/*.xml; do
  if grep -q 'failures="[1-9]' "$f"; then
    echo "FAILED SUITE: $f"
  fi
done
