#!/usr/bin/env bash
# Run the emulator check: make an analyzed Ghidra project of an EEC-IV image (8065 language,
# ghidra_scripts/EecLoadBanks.java, auto-analysis, ghidra_scripts/EecFindCode.java), run
# test/ghidra/EecEmulatorCheck.java on it, and compare the emulated lookups with
# test/emulator/ford_function.py. See docs/emulator-check.md.
#
# Usage: test/run-emulator-check.sh [options] [-- CHECK_ARGUMENTS...]
#   --image PATH   The 256 KiB image (four 64 KiB blocks in bank order 0, 1, 8, 9).
#                  Default: $EEC_IMAGE.
#   --name NAME    Prefix of the output files. Default: emulator_check.
#   --out DIR      Output folder. Default: <main clone>/.claude/local-data/emulator.
#   CHECK_ARGUMENTS
#                  key=value arguments for EecEmulatorCheck.java (init=, byte=, word=,
#                  descriptor=, tables=, inputs=, traces=, ram=, regs=, margin=). The script
#                  sets out= and refs=. Default: $EEC_CHECK_ARGS.
#
# Environment:
#   GHIDRA_HOME  Ghidra installation. Default: /opt/homebrew/opt/ghidra/libexec.
#   JAVA_HOME    Java for Ghidra 12.2 (Java 25). Default: the Homebrew openjdk@25.
#   GHIDRA_HEADLESS_JAVA_OPTIONS
#                Passed to Ghidra. Ghidra uses the language of the module in the Extensions
#                folder of its settings folder; to check a changed language from a copy of the
#                module, give -Dapplication.settingsdir=<folder> with a copy there.
#
# If <out>/run-emulator-check.env exists, the script reads it first. Use it to set EEC_IMAGE
# and EEC_CHECK_ARGS on one machine, so that local paths and addresses stay out of git.
#
# Ghidra does not accept a project folder with a path element that starts with '.', so the
# Ghidra project goes in a new temporary folder, which the script deletes when it exits.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMMON_GIT_DIR="$(git -C "$REPO_DIR" rev-parse --path-format=absolute --git-common-dir)"
MAIN_CLONE="$(dirname "$COMMON_GIT_DIR")"

NAME=emulator_check OUT_DIR="$MAIN_CLONE/.claude/local-data/emulator" IMAGE_ARG=""
CHECK_ARGS=()

usage() { sed -n '2,31p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
	case "$1" in
	--image) IMAGE_ARG="$2"; shift 2 ;;
	--name) NAME="$2"; shift 2 ;;
	--out) OUT_DIR="$2"; shift 2 ;;
	--) shift; CHECK_ARGS=("$@"); break ;;
	-h|--help) usage 0 ;;
	*) echo "unknown option: $1" >&2; usage 2 ;;
	esac
done

if [ -f "$OUT_DIR/run-emulator-check.env" ]; then
	# shellcheck disable=SC1091
	. "$OUT_DIR/run-emulator-check.env"
fi
IMAGE="${IMAGE_ARG:-${EEC_IMAGE:-}}"
if [ ${#CHECK_ARGS[@]} -eq 0 ] && [ -n "${EEC_CHECK_ARGS:-}" ]; then
	read -r -a CHECK_ARGS <<<"$EEC_CHECK_ARGS"
fi
GHIDRA_HOME="${GHIDRA_HOME:-/opt/homebrew/opt/ghidra/libexec}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"

[ -n "$IMAGE" ] && [ -f "$IMAGE" ] || { echo "image not found: '$IMAGE' (use --image or EEC_IMAGE)" >&2; exit 2; }
[ ${#CHECK_ARGS[@]} -gt 0 ] || { echo "no check arguments (give them after -- or in EEC_CHECK_ARGS)" >&2; exit 2; }
mkdir -p "$OUT_DIR"

ROWS="$OUT_DIR/$NAME.rows.tsv"
REFS="$OUT_DIR/$NAME.refs.tsv"
LOG="$OUT_DIR/$NAME.log"
COMPARE="$OUT_DIR/$NAME.compare.txt"
rm -f "$ROWS" "$REFS" "$COMPARE"

PROJECT_PARENT="$(mktemp -d "${TMPDIR:-/tmp}/eec806x-emulator.XXXXXX")"
trap 'rm -rf "$PROJECT_PARENT"' EXIT
SCRIPT_PATH="$REPO_DIR/ghidra_scripts;$REPO_DIR/test/ghidra"

# 1. Import bank 8, load the other banks, run the auto-analysis, find the code that flow
#    analysis misses. analyzeHeadless analyzes the changes of the post-script again.
echo "== Ghidra project of $IMAGE"
"$GHIDRA_HOME/support/analyzeHeadless" "$PROJECT_PARENT" eec806x \
	-import "$IMAGE" -loader BinaryLoader -loader-blockName bank8 \
	-loader-baseAddr 0x82000 -loader-fileOffset 0x22000 -loader-length 0xe000 \
	-processor 8065:LE:24:default -scriptPath "$SCRIPT_PATH" \
	-preScript EecLoadBanks.java "image=$IMAGE" \
	-postScript EecFindCode.java >"$LOG" 2>&1 || {
	tail -40 "$LOG" >&2; echo "analyzeHeadless failed, log: $LOG" >&2; exit 1; }

# 2. Run the check on the analyzed program, without changing it.
echo "== EecEmulatorCheck.java -> $ROWS"
"$GHIDRA_HOME/support/analyzeHeadless" "$PROJECT_PARENT" eec806x \
	-process "$(basename "$IMAGE")" -noanalysis -readOnly -scriptPath "$SCRIPT_PATH" \
	-postScript EecEmulatorCheck.java "out=$ROWS" "refs=$REFS" "${CHECK_ARGS[@]}" \
	>>"$LOG" 2>&1 || { tail -40 "$LOG" >&2; echo "analyzeHeadless failed, log: $LOG" >&2; exit 1; }
grep -E 'EecLoadBanks.java>|EecFindCode.java> inline|EecEmulatorCheck.java>|ERROR' "$LOG" || true
if grep -q 'SCRIPT ERROR' "$LOG" || [ ! -s "$ROWS" ]; then
	echo "a Ghidra script failed, log: $LOG" >&2
	exit 1
fi

echo "== comparison with test/emulator/ford_function.py -> $COMPARE"
status=0
python3 "$REPO_DIR/test/emulator/ford_function.py" compare --image "$IMAGE" --rows "$ROWS" \
	>"$COMPARE" || status=$?
cat "$COMPARE"
exit "$status"
