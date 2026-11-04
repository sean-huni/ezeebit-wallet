#!/usr/bin/env zsh
# Prints a lowercase random UUID (v4) and copies it to the clipboard, for pasting into Idempotency-Key.
set -euo pipefail

id=$(uuidgen | tr '[:upper:]' '[:lower:]')
print -r -- "$id"
if command -v pbcopy >/dev/null; then
  print -rn -- "$id" | pbcopy
fi
