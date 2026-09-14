#!/usr/bin/env bash
#
# Merges raw per-GUID result files from paced chunk folders
# (results/guids_<code>1/, guids_<code>2/, ... created by running
# split guids_<code>N.txt files through an assessment) back into a
# single results/guids_<code>/ folder, so GenerateManifest treats
# them as one set again instead of many fragments.
#
# Deliberately does NOT touch each chunk folder's own pages/
# subdirectory or .manifest-cache.json -- those are artefacts of
# whatever GenerateManifest run (if any) was made against that
# individual chunk folder, and are not valid input for the merged
# set. Nothing is ever deleted; matching *.json files are moved
# (not copied) out of each chunk folder, and a genuine filename
# collision (the same identifier appearing in two chunks) is left
# in place and reported rather than silently overwritten.
#
# Usage:
#   ./merge-guids-results.sh <results-dir> <set-code>
#
# Example:
#   ./merge-guids-results.sh results en
#     merges results/guids_en1, guids_en2, ... guids_en50
#     into results/guids_en
#
# After merging, re-run Generate Manifest against results/guids_en
# to build a fresh, unified pages/ and summary.json covering all
# 27,046 records as one "en" set. The now-empty chunk folders
# (guids_en1, guids_en2, ...) are left in place -- delete them.

set -euo pipefail

results_dir="${1:?Usage: $0 <results-dir> <set-code>}"
code="${2:?Usage: $0 <results-dir> <set-code>}"

if [[ ! -d "$results_dir" ]]; then
  echo "No such directory: $results_dir" >&2
  exit 1
fi

target_dir="$results_dir/guids_$code"
mkdir -p "$target_dir"

shopt -s nullglob

# Find chunk folders named exactly "guids_<code><digits>" -- not the
# target itself, and not some unrelated folder that merely starts
# with the same prefix (e.g. guids_english).
chunk_dirs=()
for d in "$results_dir"/guids_"$code"[0-9]*; do
  [[ -d "$d" ]] || continue
  base=$(basename "$d")
  if [[ "$base" =~ ^guids_${code}[0-9]+$ ]]; then
    chunk_dirs+=("$d")
  fi
done

if [[ ${#chunk_dirs[@]} -eq 0 ]]; then
  echo "No chunk folders matching guids_${code}<N> found under $results_dir" >&2
  exit 1
fi

# Sort by the trailing chunk number (portable -- avoids relying on
# GNU sort's -V, which macOS's built-in /usr/bin/sort doesn't have).
sort_input=$(mktemp)
for d in "${chunk_dirs[@]}"; do
  base=$(basename "$d")
  num="${base#guids_$code}"
  printf '%s\t%s\n' "$num" "$d" >> "$sort_input"
done
chunk_dirs=()
while IFS=$'\t' read -r _ d; do
  chunk_dirs+=("$d")
done < <(sort -n -k1,1 "$sort_input")
rm -f "$sort_input"

echo "Target:         $target_dir"
echo "Chunk folders:  ${#chunk_dirs[@]}"
echo

moved=0
collisions=()

for d in "${chunk_dirs[@]}"; do
  base=$(basename "$d")
  count=0
  for f in "$d"/*.json; do
    [[ -e "$f" ]] || continue
    name=$(basename "$f")
    dest="$target_dir/$name"
    if [[ -e "$dest" ]]; then
      collisions+=("$name (left in $base)")
      continue
    fi
    mv "$f" "$dest"
    count=$((count + 1))
  done
  moved=$((moved + count))
  remaining=$(find "$d" -maxdepth 1 -name '*.json' | wc -l | tr -d ' ')
  echo "  $base: moved $count file(s), $remaining left behind (pages/, .manifest-cache.json untouched)"
done

echo
echo "Moved $moved result file(s) into $target_dir."

if [[ ${#collisions[@]} -gt 0 ]]; then
  echo
  echo "WARNING: ${#collisions[@]} filename collision(s) were left in place," \
       "not overwritten -- the same identifier appears to have been" \
       "processed in more than one chunk. Resolve these manually:" >&2
  for c in "${collisions[@]}"; do
    echo "  - $c" >&2
  done
fi

echo
echo "Each chunk folder's pages/ subdirectory and .manifest-cache.json"
echo "were left untouched -- they belong to that individual chunk and"
echo "are not valid raw input for the merged set."
echo
echo "Next: re-run Generate Manifest against $target_dir to build a"
echo "fresh, unified pages/ and summary.json for the whole set. Once"
echo "you're happy with the result, the now-empty guids_${code}<N>"
echo "chunk folders can be deleted -- nothing here deletes them for you."
