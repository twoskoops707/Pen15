#!/usr/bin/env bash
set -euo pipefail

TOPIC="${NTFY_TOPIC:-pen15-builds-twoskoops707}"
TITLE="${NTFY_TITLE:-Pen15 Build Green}"
BODY="${NTFY_BODY:-Pen15 build succeeded}"

curl -fsS \
  -H "Title: ${TITLE}" \
  -d "${BODY}" \
  "https://ntfy.sh/${TOPIC}"