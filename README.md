# CESSDA CMV Benchmark-runner

[![SQAaaS badge](https://github.com/EOSC-synergy/SQAaaS/raw/master/badges/badges_150x116/badge_software_silver.png)](https://api.eu.badgr.io/public/assertions/rxEEBuR9QoadzMDHXT4PmQ "SQAaaS silver badge achieved")

This repository contains the source code for assessing digital objects in bulk
against a  [CESSDA QA algorithm configuration spreadsheet](https://tools.ostrails.eu/champion/algorithms/1Nk0vM4yBpVQTo_UbB62NY_fz93aRZRHBZGh5fG-khOw),
using the [FAIR Champion Benchmark Assessment tool](https://tools.ostrails.eu/champion/assess/algorithms/new).

## Prerequisites

Java 21 or greater is required to build and run this application.

## Dependencies

- Java standard library (`java.net.http`, `java.nio.file`,
  `javax.xml.parsers`, `java.util.concurrent`)
- Apache Commons CLI — command-line argument parsing
- Jackson Databind — JSON reading and writing

## Overview

A three-class Java pipeline that fetches OAI-PMH record identifiers,
submits them to a FAIR Champion benchmark assessment API, and
pre-processes the results into a form ready for an HTML dashboard.

## Pipeline overview

```text
OAI-PMH endpoint
      │
      ▼
GetOaiPmhIdentifiers   →   guids_<lang>.txt  (one per language set)
      │
      ▼
RunBenchmarkAssessment →   results/guids_<lang>/<identifier>.json
      │
      ▼
GenerateManifest       →   results/summary.json
                           results/guids_<lang>/pages/page-NNN.json
```

The three classes are intended to be run in order. The output of each
stage is the input to the next.

## Classes

### GetOaiPmhIdentifiers

Queries an OAI-PMH endpoint using the `ListIdentifiers` verb,
following resumption tokens until all pages have been retrieved. For
each language set it writes a `guids_<lang>.txt` file in which every
non-comment line is a complete OAI-PMH `GetRecord` URL ready for the
next stage. By default it targets the CESSDA Data Catalogue endpoint
and processes ten language sets (`de`, `el`, `en`, `fi`, `fr`, `hr`,
`nl`, `sl`, `sl-SI`, `sv`).

See [GetOaiPmhIdentifiers_README.md](GetOaiPmhIdentifiers_README.md)
for full usage and options.

### RunBenchmarkAssessment

Reads the `guids_<lang>.txt` files produced by the previous stage and
POSTs each `GetRecord` URL to the FAIR Champion assessment API. Results
are saved as JSON files under `results/guids_<lang>/`. Processing is
parallelised across five threads. Errors are captured in separate
`error_*.json` files so that a single failure does not interrupt the
rest of the batch.

See [RunBenchmarkAssessment_README.md](RunBenchmarkAssessment_README.md)
for full usage and options.

### GenerateManifest

Scans the `results/` directory and pre-processes the per-record JSON
files into two artefacts used by the HTML dashboard. It writes a
single `results/summary.json` containing aggregated pass, fail, and
indeterminate counts broken down by language, test ID, and FAIR
category (F, A, I, R). It also writes paginated
`results/guids_<lang>/pages/page-NNN.json` files (200 records per
page) containing only the fields the browser needs, keeping page
loads small.

See [GenerateManifest_README.md](GenerateManifest_README.md) for full
details of the output formats.

## Quick start

Run each stage in turn, using Maven from the project root:

```bash
# 1. Fetch identifiers for all default language sets
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.GetOaiPmhIdentifiers"

# 2. Submit all identifiers to the benchmark API
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.RunBenchmarkAssessment" \
  -Dexec.args="--process-all"

# 3. Pre-process results for the dashboard
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.GenerateManifest"
```

## Project Structure

This project uses the standard Maven project structure.
Various non-functional files have been omitted.

```text
<ROOT>
├── README.md           # This file
├── detail.html         # Drill down detail page of the dashboard
├── index.html          # Landing page of the dashboard
├── results             # Outputs from running RunBenchmarkAssessment
├── src                 # Contains all source code and assets for the application.
|   ├── main
|   |   ├── java        # Contains release source code of the application.
|   |── ├── resources   # Contains release resource assets.
|   └── test
|       ├── java        # Contains test source code.
|       └── resources   # Contains test resource assets.
├── target              # The output directory for the build.
└── start-dashboard.sh  # A script that runs GenerateManifest and starts a web server
```

## Contributing

Please read [CONTRIBUTING](CONTRIBUTING.md) for details on our code of conduct, and the process for submitting pull requests to us.

## Versioning

See [Semantic Versioning](https://semver.org/) for guidance.

## Contributors

You can find the list of contributors in the [CONTRIBUTORS](CONTRIBUTORS.md) file.

## License

See the [LICENSE](LICENSE.txt) file.

## Citing

See the [CITATION](CITATION.cff) file.
