#!/usr/bin/env bash
# Run the comparison chain: compile the languages, make a Ghidra listing of an EEC-IV
# image with ghidra_scripts/EecLoadBanks.java and test/ghidra/EecListing.java, and compare
# it with a SAD reference listing with test/compare/compare_listings.py.
#
# Usage: test/run-compare.sh [options]
#   --mode single|multi  single: language 806x:LE:16:default, bank 8 at 0x2000-0xffff.
#                        multi:  language 8065:LE:24:default, banks 0, 1, 8 and 9 at
#                                bank * 0x10000. Default: single.
#   --language ID        Ghidra language ID. Default: chosen by --mode.
#   --image PATH         The 256 KiB image (four 64 KiB blocks in bank order 0, 1, 8, 9).
#                        Default: $EEC_IMAGE.
#   --ref PATH           The SAD reference listing. Default: $EEC_REF.
#   --seed               Also start disassembly at every code address of the reference.
#   --seed-only          Decode one instruction at every code address of the reference,
#                        without following jumps and calls from there.
#   --find-code          Also run ghidra_scripts/EecFindCode.java (code tables and
#                        inline arguments) before the listing is written.
#   --bank N             Compare only bank N. Default: 8.
#   --name NAME          Prefix of the output files.
#                        Default: tde1_<mode>[_seeded|_seedonly].
#   --out DIR            Output folder. Default: <main clone>/.claude/local-data/ghidra.
#
# Environment:
#   GHIDRA_HOME  Ghidra installation. Default: /opt/homebrew/opt/ghidra/libexec.
#   JAVA_HOME    Java for Ghidra 12.2 (Java 25). Default: the Homebrew openjdk@25.
#
# If <out>/../run-compare.env exists, the script reads it first. Use it to set EEC_IMAGE
# and EEC_REF on one machine, so that local paths stay out of git.
#
# Ghidra does not accept a project folder with a path element that starts with '.'
# (such as .claude), so the Ghidra project goes in a new temporary folder. The script
# deletes that folder when it exits. Only the listings and reports stay, in --out.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMMON_GIT_DIR="$(git -C "$REPO_DIR" rev-parse --path-format=absolute --git-common-dir)"
MAIN_CLONE="$(dirname "$COMMON_GIT_DIR")"

MODE=single LANGUAGE="" SEED=0 FIND=0 BANK=8 NAME="" OUT_DIR="$MAIN_CLONE/.claude/local-data/ghidra"
IMAGE_ARG="" REF_ARG=""

usage() { sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
	case "$1" in
	--mode) MODE="$2"; shift 2 ;;
	--language) LANGUAGE="$2"; shift 2 ;;
	--image) IMAGE_ARG="$2"; shift 2 ;;
	--ref) REF_ARG="$2"; shift 2 ;;
	--seed) SEED=1; shift ;;
	--seed-only) SEED=2; shift ;;
	--find-code) FIND=1; shift ;;
	--bank) BANK="$2"; shift 2 ;;
	--name) NAME="$2"; shift 2 ;;
	--out) OUT_DIR="$2"; shift 2 ;;
	-h|--help) usage 0 ;;
	*) echo "unknown option: $1" >&2; usage 2 ;;
	esac
done

if [ -f "$OUT_DIR/../run-compare.env" ]; then
	# shellcheck disable=SC1091
	. "$OUT_DIR/../run-compare.env"
fi
IMAGE="${IMAGE_ARG:-${EEC_IMAGE:-}}"
REF="${REF_ARG:-${EEC_REF:-}}"
GHIDRA_HOME="${GHIDRA_HOME:-/opt/homebrew/opt/ghidra/libexec}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"

case "$MODE" in
single) LANGUAGE="${LANGUAGE:-806x:LE:16:default}"; BASE=0x2000 ;;
multi) LANGUAGE="${LANGUAGE:-8065:LE:24:default}"; BASE=0x82000 ;;
*) echo "--mode must be single or multi" >&2; exit 2 ;;
esac
[ -n "$IMAGE" ] && [ -f "$IMAGE" ] || { echo "image not found: '$IMAGE' (use --image or EEC_IMAGE)" >&2; exit 2; }
[ -n "$REF" ] && [ -f "$REF" ] || { echo "reference not found: '$REF' (use --ref or EEC_REF)" >&2; exit 2; }
if [ -z "$NAME" ]; then
	NAME="tde1_${MODE}$([ "$FIND" = 1 ] && echo _findcode || true)"
	case "$SEED" in 1) NAME="${NAME}_seeded" ;; 2) NAME="${NAME}_seedonly" ;; esac
fi
mkdir -p "$OUT_DIR"

LISTING="$OUT_DIR/$NAME.tsv"
LOG="$OUT_DIR/$NAME.log"
REPORT="$OUT_DIR/$NAME.report.md"
DETAILS="$OUT_DIR/$NAME.details.tsv"

echo "== compiling languages in $REPO_DIR/data/languages"
"$GHIDRA_HOME/support/sleigh" -a "$REPO_DIR/data/languages" >"$OUT_DIR/sleigh-compile.log" 2>&1 || {
	cat "$OUT_DIR/sleigh-compile.log" >&2; echo "sleigh compile failed" >&2; exit 1; }
grep -E 'WARN|ERROR' "$OUT_DIR/sleigh-compile.log" | grep -v 'Use -[nu] switch' || true

PROJECT_PARENT="$(mktemp -d "${TMPDIR:-/tmp}/eec806x-ghidra.XXXXXX")"
trap 'rm -rf "$PROJECT_PARENT"' EXIT

LISTING_ARGS=("out=$LISTING")
FIND_ARGS=()
[ "$FIND" = 1 ] && FIND_ARGS=(-postScript EecFindCode.java)
[ "$SEED" = 1 ] && LISTING_ARGS+=("seeds=$REF")
[ "$SEED" = 2 ] && LISTING_ARGS+=("seeds=$REF" "seedflow=false")

# BinaryLoader loads bank 8; EecLoadBanks.java adds the other banks (8065 language only) and
# disassembles from the reset entry and the interrupt vectors; EecListing.java writes the
# listing.
echo "== Ghidra listing: $LANGUAGE, mode $MODE -> $LISTING"
rm -f "$LISTING" "$REPORT" "$DETAILS"
"$GHIDRA_HOME/support/analyzeHeadless" "$PROJECT_PARENT" eec806x \
	-import "$IMAGE" -loader BinaryLoader -loader-blockName bank8 \
	-loader-baseAddr "$BASE" -loader-fileOffset 0x22000 -loader-length 0xe000 \
	-processor "$LANGUAGE" -noanalysis \
	-scriptPath "$REPO_DIR/ghidra_scripts;$REPO_DIR/test/ghidra" \
	-postScript EecLoadBanks.java "image=$IMAGE" \
	"${FIND_ARGS[@]}" \
	-postScript EecListing.java "${LISTING_ARGS[@]}" \
	-deleteProject >"$LOG" 2>&1 || { tail -40 "$LOG" >&2; echo "analyzeHeadless failed, log: $LOG" >&2; exit 1; }
grep -E 'EecLoadBanks.java>|EecFindCode.java>|EecListing.java>|ERROR' "$LOG" || true
if grep -q 'SCRIPT ERROR' "$LOG" || [ ! -s "$LISTING" ]; then
	echo "a Ghidra script failed, log: $LOG" >&2
	exit 1
fi

echo "== comparison (bank $BANK) -> $REPORT"
python3 "$REPO_DIR/test/compare/compare_listings.py" --sad "$REF" --ghidra "$LISTING" \
	--bank "$BANK" --title "$NAME ($LANGUAGE) compared with $(basename "$REF")" \
	--report "$REPORT" --details "$DETAILS"
