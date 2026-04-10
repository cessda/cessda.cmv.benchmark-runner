# GenerateManifest

Pre-processes FAIR benchmark results into two artefacts consumed by
the HTML dashboard.

## Overview

`GenerateManifest` scans a `results/` directory for per-language
subdirectories (each named `guids_<lang>/`) and produces:

- `results/summary.json` — fully aggregated statistics for every
  language and overall totals. Loaded once by both `index.html` and
  `language.html`; no individual record files are fetched by the
  browser.
- `results/guids_<lang>/pages/page-NNN.json` — slim, paginated slices
  of the record list (200 records per page). Only the current page is
  fetched when the user browses the records table.

Each page file contains an array of compact record objects with the
fields the browser needs: `identifier`, `testedguid`, `test_results`,
`narratives`, `guidances`, and a pre-computed `netScore`.

## Expected input layout

```text
results/
  guids_de/
    <hash>.json
    <hash>.json
    ...
  guids_en/
    <hash>.json
    ...
```

Files whose names begin with `error_` are skipped automatically.

## Output layout

```text
results/
  summary.json
  guids_de/
    pages/
      page-001.json
      page-002.json
      ...
  guids_en/
    pages/
      page-001.json
      ...
```

Any existing `page-*.json` files are deleted before new ones are
written, so stale pages from a previous run are never left behind.

## Usage

Run with Maven (from the project root):

```bash
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.GenerateManifest"
```

Run from a JAR, passing an optional path to the results directory:

```bash
java -cp <jar> cessda.cmv.benchmark.GenerateManifest [resultsDir]
```

If `resultsDir` is omitted it defaults to `./results`.

## summary.json structure

```json
{
  "generated": "2026-01-01T00:00:00Z",
  "overall": {
    "records": 1200,
    "pass": 8400,
    "fail": 2100,
    "indet": 300,
    "fair": {
      "F": { "pass": 3000, "total": 4000 },
      "A": { "pass": 1200, "total": 1500 },
      "I": { "pass":  600, "total":  800 },
      "R": { "pass": 3600, "total": 4500 }
    },
    "tests": {
      "F1-GUID": { "pass": 900, "fail": 200, "indet": 100 }
    }
  },
  "languages": {
    "de": {
      "records": 120,
      "pageCount": 1,
      "pass": 840,
      "fail": 210,
      "indet": 30,
      "fair": { ... },
      "tests": { ... }
    }
  }
}
```

The `overall` block aggregates across all languages. Each entry in
`languages` contains the same fields plus `pageCount` (the number of
page files written for that language).

## page-NNN.json structure

Each page file is a JSON array of up to 200 record objects:

```json
[
  {
    "identifier":   "abc123",
    "testedguid":   "https://…?verb=GetRecord&…&identifier=abc123",
    "netScore":     12.5,
    "test_results": { "F1-GUID": { "result": "pass", "weight": 1.0 } },
    "narratives":   [ "…" ],
    "guidances":    [ "…" ]
  }
]
```

The `identifier` field is extracted from the `identifier=` query
parameter of the `testedguid` URL.

## FAIR category mapping

Test identifiers are mapped to FAIR categories as follows:

| Test ID      | Category |
|--------------|----------|
| F1-PID       | F        |
| F1-GUID      | F        |
| F2A          | F        |
| F2B          | F        |
| F4           | F        |
| A1-1         | A        |
| A1-2         | A        |
| I1-A         | I        |
| I2-A         | I        |
| R1-2-CPI     | R        |
| R1-3-CEK     | R        |
| R1-3-CTV     | R        |
| R1-3-DMOCV   | R        |
| R1-3-DAUV    | R        |
| R1-3-DTMV    | R        |
| R1-3-DSPV    | R        |

Any test identifier not in the table above is mapped by its first
character if that character is one of `F`, `A`, `I`, or `R`.
Identifiers that cannot be mapped are excluded from FAIR category
counts but still appear in the per-test breakdown.

## How it works

1. The results directory is scanned for subdirectories whose names
   begin with `guids_`.
2. For each language directory, all `*.json` files that do not begin
   with `error_` are collected and sorted.
3. Each file is parsed; `test_results` fields are aggregated into pass,
   fail, and indeterminate counts, broken down by test ID and FAIR
   category.
4. A slim record object is built for each file and buffered. When the
   buffer reaches 200 records it is flushed to the next page file.
5. After all languages are processed, `summary.json` is written with
   the per-language and overall aggregated statistics.

## Dependencies

- Java standard library (`java.nio.file`)
- Jackson Databind (JSON reading and writing)

## Contributing

Please read [CONTRIBUTING](CONTRIBUTING.md) for details on our code of conduct,
and the process for submitting pull requests to us.

## Versioning

See [Semantic Versioning](https://semver.org/) for guidance.

## Contributors

You can find the list of contributors in the [CONTRIBUTORS](CONTRIBUTORS.md)
file.

## License

See the [LICENSE](LICENSE.txt) file.

## Citing

See the [CITATION](CITATION.cff) file.
