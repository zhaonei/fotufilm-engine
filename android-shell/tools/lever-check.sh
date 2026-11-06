#!/bin/bash
# Asks the engine, with numbers, whether each film lever moves a pixel.
#
# Renders the same frame once with the defaults and once with one lever pushed to its
# extreme, then uses the engine's own A/B tool (`fotufilm <a> <b> --diff`) to print how
# far apart the two renders are. A lever that reports sum 0 is not wired up; a lever
# that reports a large sum works, and what the phone showed was the film's own small
# value on a frame with nothing to work on.
#
#   tools/lever-check.sh <path-to-fotufilm-cli> <source-image> <work-dir>
#
# Only the engine's own flags are used, so this measures the same code the app runs.
set -uo pipefail
BIN="${1:?the fotufilm command-line engine}"
SRC="${2:?a source image}"
WORK="${3:-/tmp/lever-check}"
mkdir -p "$WORK"

render() { # render <out> [flags...]
  local out="$1"; shift
  "$BIN" "$SRC" "$out" "$@" >"$WORK/last.log" 2>&1 || return 1
  [ -f "$out" ]
}

diff_of() { # diff_of <a> <b> -> prints the numbers, returns 0
  "$BIN" "$1" "$2" --diff "$WORK/diff.png" 2>&1 |
    grep -iE "peak|sum|rms|channel|identical|same" | tail -6
}

BASE="$WORK/base.tif"
if ! render "$BASE"; then
  echo "the baseline render failed:"; tail -5 "$WORK/last.log"; exit 1
fi
echo "baseline rendered: $BASE"

# name | flags for the variant. One lever at a time against the baseline.
CHECKS=(
  "halation return 1%|--halation-return 1"
  "halation return 80%|--halation-return 80"
  "grain model film|--grain-model film"
  "grain mottle 90%|--mottle 0.9"
  "push +1 stop|--push 1"
  "adjacency screened-diffusion|--adjacency-model screened-diffusion"
  "diffusion grade|--diffusion-grade 1"
  "diffusion|--diffusion 1"
  "lens filter Pro-Mist 1|--filter promist-1"
  "print light tungsten|--print-light tungsten"
)

printf '\n%-32s %s\n' "LEVER" "RESULT"
for entry in "${CHECKS[@]}"; do
  name="${entry%%|*}"
  args="${entry#*|}"
  out="$WORK/variant.tif"
  rm -f "$out"
  # shellcheck disable=SC2086  # the flags are a list on purpose
  if ! render "$out" $args; then
    printf '%-32s SKIPPED (the engine rejected: %s)\n' "$name" "$args"
    continue
  fi
  numbers="$(diff_of "$BASE" "$out")"
  if [ -z "$numbers" ]; then
    printf '%-32s NO OUTPUT from --diff\n' "$name"
  else
    printf '%-32s %s\n' "$name" "$(echo "$numbers" | tr '\n' ' ')"
  fi
done

echo
echo "A lever whose difference is zero is not reaching the pixels; one with a large"
echo "difference works, and the phone was showing the film's own value on a frame"
echo "with nothing for it to act on."
