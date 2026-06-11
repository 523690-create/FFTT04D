#!/usr/bin/env bash
# One-time mass-unpack of all Coswara split tar.gz archives so the desktop app
# never has to extract at load time again. Idempotent: skips date dirs already
# extracted. Each <date>.tar.gz.a{a,b,c,d} -> <date>/<date>-extracted/<date>/<pid>/...
set -u
root="/h/Coswara-Data-master"
done=0; skipped=0; failed=0
for d in "$root"/[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]; do
  [ -d "$d" ] || continue
  name="$(basename "$d")"
  ext="$d/${name}-extracted"
  # Already extracted (has the inner <date> dir with content)? skip.
  if [ -d "$ext/$name" ] && [ -n "$(ls -A "$ext/$name" 2>/dev/null)" ]; then
    skipped=$((skipped+1)); echo "skip   $name (already extracted)"; continue
  fi
  parts=( "$d/${name}.tar.gz.a"? )
  if [ ! -e "${parts[0]}" ]; then
    echo "no-parts $name"; continue
  fi
  mkdir -p "$ext"
  if cat "$d/${name}.tar.gz.a"? | tar -xz -C "$ext" 2>/dev/null; then
    done=$((done+1)); echo "ok     $name ($(ls "$ext/$name" 2>/dev/null | wc -l) participants)"
  else
    failed=$((failed+1)); echo "FAIL   $name"
  fi
done
echo "=== mass-unpack complete: extracted=$done skipped=$skipped failed=$failed ==="
