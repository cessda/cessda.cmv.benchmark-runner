#!/bin/bash

# Run in a directory containing JSON files with Champion test results.
# Generates a CSV file with the non-pass results.
{
  echo "file,test_name,result"
  for f in *.json; do
    jq -r --arg file "$f" '
      .test_results
      | to_entries[]
      | select(.value.result == "fail" or .value.result == "indeterminate")
      | [$file, .key, .value.result]
      | @csv
    ' "$f"
  done
} > results.csv