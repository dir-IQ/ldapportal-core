#!/usr/bin/env bash
# Asserts that every .java file under addons/ carries the
# `SPDX-License-Identifier: Apache-2.0` header. The addons/
# namespace is the home for open-source optional modules; mixing
# in a proprietary header would silently re-license the file and
# defeat the boundary the namespace exists to enforce.
#
# Runs as a step in the Backend job of .github/workflows/ci.yml.
# Cheap (one grep over a small tree); no-op when addons/ has no
# child modules yet.
#
# Run from the repo root:  ./scripts/check-addons-license-headers.sh
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ADDONS_DIR="$ROOT/addons"
EXPECTED='SPDX-License-Identifier: Apache-2.0'

if [ ! -d "$ADDONS_DIR" ]; then
  echo "addons/ directory missing — skipping (this is fine before P0 lands)."
  exit 0
fi

violations=0
checked=0
while IFS= read -r -d '' file; do
  checked=$((checked + 1))
  # Read just the first 5 lines — SPDX header is always near the top.
  # Files that don't include it on line 1 (package-info.java with a Javadoc
  # comment first, etc.) still must declare it in the file header block.
  if ! head -n 5 "$file" | grep -qF "$EXPECTED"; then
    echo "::error file=${file#$ROOT/}::Missing or wrong SPDX header (expected '$EXPECTED')"
    violations=$((violations + 1))
  fi
done < <(find "$ADDONS_DIR" -name '*.java' -type f -print0 2>/dev/null)

if [ "$violations" -gt 0 ]; then
  echo
  echo "$violations Java file(s) under addons/ are missing the '$EXPECTED' header."
  echo "addons/ ships under Apache 2.0 by convention; mixing in a"
  echo "proprietary-licensed file would silently re-license the open"
  echo "distribution. Fix the header(s) above and re-run."
  exit 1
fi

echo "OK — $checked Java file(s) under addons/ all carry the Apache-2.0 SPDX header."
