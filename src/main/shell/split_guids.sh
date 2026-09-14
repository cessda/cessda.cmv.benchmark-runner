#!/usr/bin/env bash
#
# Splits a guids_*.txt file (a fixed-line header followed by one
# identifier per line) into fixed-size chunks, skipping the header
# and numbering the output files sequentially with no zero-padding
# (guids_en1.txt, guids_en2.txt, ... guids_en50.txt).
#
# Usage:
#   ./split_guids.sh <input-file> <chunk-size> [header-lines]
#
# Example (your case: 27046 identifiers, 3-line header, 541 per file):
#   ./split_guids.sh guids_en.txt 541 3
#
# Output files are written alongside the input file, using the
# input's basename (minus ".txt") as the prefix — so guids_en.txt
# produces guids_en1.txt, guids_en2.txt, etc. in the same directory.

set -euo pipefail

infile="${1:?Usage: $0 <input-file> <chunk-size> [header-lines]}"
chunk_size="${2:?Usage: $0 <input-file> <chunk-size> [header-lines]}"
header_lines="${3:-3}"

if [[ ! -f "$infile" ]]; then
  echo "No such file: $infile" >&2
  exit 1
fi

dir=$(dirname "$infile")
base=$(basename "$infile" .txt)
prefix="$dir/$base"

total_identifiers=$(( $(wc -l < "$infile") - header_lines ))

echo "Input:            $infile"
echo "Header lines:      $header_lines"
echo "Identifiers:        $total_identifiers"
echo "Chunk size:         $chunk_size"
echo

tail -n "+$((header_lines + 1))" "$infile" | awk -v n="$chunk_size" -v prefix="$prefix" '
  {
    if ((NR - 1) % n == 0) {
      if (out) close(out)
      file_num++
      out = prefix file_num ".txt"
    }
    print > out
  }
  END {
    for (i = 1; i <= file_num; i++) {
      f = prefix i ".txt"
      close(f)
    }
    print file_num > "/dev/stderr"
  }
' 2> /tmp/split_file_count.$$

file_count=$(cat /tmp/split_file_count.$$)
rm -f /tmp/split_file_count.$$

echo "Wrote $file_count file(s):"
for i in $(seq 1 "$file_count"); do
  f="${prefix}${i}.txt"
  printf '  %-40s %6d identifiers\n' "$(basename "$f")" "$(wc -l < "$f")"
done

# Sanity check: every identifier accounted for, none duplicated or lost.
written=0
for i in $(seq 1 "$file_count"); do
  written=$(( written + $(wc -l < "${prefix}${i}.txt") ))
done
echo
if [[ "$written" -eq "$total_identifiers" ]]; then
  echo "OK: $written identifiers written across $file_count files (matches source)."
else
  echo "MISMATCH: wrote $written but source had $total_identifiers — check output." >&2
  exit 1
fi