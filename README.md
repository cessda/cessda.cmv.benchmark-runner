# CESSDA CMV Benchmark-runner

[![SQAaaS badge shields.io](https://img.shields.io/badge/sqaaas%20software-silver-lightgrey)](https://api.eu.badgr.io/public/assertions/rxEEBuR9QoadzMDHXT4PmQ "SQAaaS silver badge achieved")

This repository contains the source code for assessing digital objects
in bulk against a Benchmark Assessment Algorithm spreadsheet, using
the FAIR Champion Benchmark Assessment tool. The benchmark algorithm
and runner URIs are configurable via `application.yaml` — see
[RunBenchmarkAssessment_README.md](RunBenchmarkAssessment_README.md)
for details.

The application is multi-tenanted: a single deployment can serve
results for several organisations at once, with each organisation's
data kept completely separate on disk and reachable only via its own
API key. See
[Multi-tenancy](#multi-tenancy) below for how this works and how to
add a new tenant.

## Prerequisites

Java 21 or greater is required to build and run this application.

## Dependencies

- Java standard library (`java.net.http`, `java.nio.file`,
  `javax.xml.parsers`, `java.util.concurrent`)
- Apache Commons CLI — command-line argument parsing
- Jackson Databind — JSON reading and writing
- Spring Boot, including Spring Security — web layer, REST API, and
  per-tenant authentication

## Overview

A three-stage Java pipeline that fetches OAI-PMH record identifiers,
submits them to a FAIR Champion benchmark assessment API, and
pre-processes the results into a form ready for an HTML dashboard.
Each stage, and the dashboard itself, is exposed as an authenticated
REST endpoint and operates on a single tenant's data at a time.

## Pipeline overview

```text
OAI-PMH endpoint
      │
      ▼
GetOaiPmhIdentifiers   →   data/<tenant>/guids_<set>.txt
      │
      ▼
RunBenchmarkAssessment →   results/<tenant>/guids_<set>/<identifier>.json
      │
      ▼
GenerateManifest       →   results/<tenant>/summary.json
                           results/<tenant>/guids_<set>/pages/page-NNN.json
```

The three stages are intended to be run in order, once per tenant. The
output of each stage is the input to the next. Every path is rooted
under that tenant's own subdirectory, so running the pipeline for one
tenant never reads or writes another tenant's files.

## Classes

### GetOaiPmhIdentifiers

Queries an OAI-PMH endpoint using the `ListIdentifiers` verb,
following resumption tokens until all pages have been retrieved. For
each set it writes a `guids_<set>.txt` file, under the
calling tenant's data directory, in which every non-comment line is a
complete OAI-PMH `GetRecord` URL ready for the next stage. By default
it targets the CESSDA Data Catalogue endpoint and processes ten
sets (`de`, `el`, `en`, `fi`, `fr`, `hr`, `nl`, `sl`,
`sl-SI`, `sv`).

It can also query `verb=ListSets` to discover, live, exactly which
sets an endpoint currently offers — used by the dashboard's "Fetch
identifiers" page (see [HTTP API overview](#http-api-overview)) so
the set list shown to an operator can never drift out of sync with
the real catalogue, unlike a hand-maintained list.

See [GetOaiPmhIdentifiers_README.md](GetOaiPmhIdentifiers_README.md)
for full usage and options.

### RunBenchmarkAssessment

Reads the `guids_<set>.txt` files produced by the previous stage and
POSTs each `GetRecord` URL to a configurable FAIR Champion championUri
endpoint, with the configured benchmark algorithm URI included in the
request payload.
Results are saved as JSON files under the calling tenant's results
directory, in `guids_<set>/`. Each GUID runs on its own virtual
thread, paced with a short backoff between submissions and capped at
2 concurrent requests to Champion, and a transient failure (a
timeout, an HTTP 500/502/504, or Champion silently overloaded on a
200 response) is retried with backoff before giving up. Only once
retries are exhausted is a failure captured, in a separate
`error_*.json` file, so that it does not interrupt the rest of the
batch.

See [RunBenchmarkAssessment_README.md](RunBenchmarkAssessment_README.md)
for full usage and options.

## HTTP API overview

All `/api/**` endpoints require an `X-API-Key` header. The key selects
the current tenant before any data or configuration is read.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/config` | Tenant's `setNames` and `fairMap` |
| `GET` | `/api/tenant/branding` | Tenant's dashboard title and footer |
| `GET` | `/api/fetch-identifiers/defaults` | Tenant's default OAI-PMH base URL |
| `GET` | `/api/fetch-identifiers/sets` | Live set list from an OAI-PMH endpoint |
| `POST` | `/api/fetch-identifiers` | Fetch OAI-PMH identifiers |
| `GET` | `/api/run-assessment/defaults` | Tenant's default algorithm and runner |
| `GET` | `/api/run-assessment/guid-files` | List available `guids_*.txt` files |
| `POST` | `/api/run-assessment` | Run FAIR Champion assessment |
| `POST` | `/api/generate-manifest` | Generate dashboard manifest |
| `GET` | `/api/results/summary.json` | Tenant's summary manifest |
| `GET` | `/api/results/guids_{set}/pages/{page}` | Paginated record data |

## Multi-tenancy

Each organisation using this deployment is a tenant. A tenant is
identified by an API key, configured centrally in `application.yaml`,
which maps to a tenant ID used to namespace that organisation's files
on disk.

### How a request is authenticated

A request to any `/api/**` endpoint must include an `X-API-Key`
header. A `TenantAuthFilter` resolves the key to a tenant ID before
the request reaches a controller; requests with a missing or unknown
key are rejected with `401 Unauthorized`. The static dashboard pages
(`index.html`, `detail.html`, `fetch-identifiers.html`) are served
without a key, but the data they fetch via `/api/**` is not — the
dashboard prompts for a key on first load and stores it for the
browser session only, and every other page reuses that stored key
for its own `fetch()` calls.

### How tenant data is separated on disk

Every file written or read by the pipeline is rooted under a
per-tenant subdirectory of the configured data and results
directories:

```text
data/
  <tenant-id>/
    guids_<set>.txt

results/
  <tenant-id>/
    summary.json
    guids_<set>/
      <identifier>.json
      pages/
        page-NNN.json
```

A tenant's API key determines its tenant ID for the lifetime of a
request, so one tenant can never read or write another tenant's
files, even if the same Spring Boot instance is serving both.

### Adding a new tenant

Adding a tenant requires three steps: registering its API key,
configuring its properties, and creating its directories on disk.

1. Add a new entry under `tenants.keys` in `application.yaml`:

   ```yaml
   tenants:
     enabled: true
     keys:
       "key-org-alpha": "org-alpha"
       "key-org-beta":  "org-beta"
       "key-new-org":   "new-org"
   ```

   The map key is the secret the tenant will send in the `X-API-Key`
   header; the map value is the tenant ID used to namespace its files.
   Choose a strong, unique key for each tenant and keep it
   confidential.

2. Add a matching `tenants.config.<tenant-id>` block with at least:

   - `algorithm` — FAIR Champion algorithm URI
   - `runner` — FAIR Champion runner URI
   - `oai-pmh-base-url` — this tenant's OAI-PMH endpoint (optional;
     falls back to the CESSDA Data Catalogue if unset — see
     [GetOaiPmhIdentifiers_README.md](GetOaiPmhIdentifiers_README.md))
   - `title` — dashboard page title
   - `footer` — dashboard footer
   - `set-names` — friendly set names
   - `fair-map` — test ID to FAIR category mapping
   - `maturity-levels` — tests required for each maturity level

3. Create matching subdirectories under the configured data and
   results directories:

   ```bash
   mkdir -p data/new-org results/new-org
   ```

   This step is optional in practice, since `fetch-identifiers` and
   `run-assessment` create their tenant subdirectory automatically if
   it does not already exist. It is shown here for clarity and is
   useful when pre-seeding a tenant's results manually.

4. Restart the application so the new `application.yaml` entry is
   loaded.

No code changes are required to add a tenant.

### Running the pipeline for a tenant

With the application running, call the three pipeline stages in order
using that tenant's key:

```bash
curl -s -X POST http://localhost:8080/api/fetch-identifiers \
  -H "X-API-Key: key-new-org"

curl -s -X POST http://localhost:8080/api/run-assessment \
  -H "X-API-Key: key-new-org" \
  -d "processAll=true"

curl -s -X POST http://localhost:8080/api/generate-manifest \
  -H "X-API-Key: key-new-org"
```

The dashboard can also trigger the final step directly: once signed
in with a tenant's key, the "Generate manifest" button in the header
re-runs `GenerateManifest` for that tenant and refreshes the page with
the result.

## Quick start

Running each stage directly from the command line (bypassing the REST
API) is still supported for local development, using Maven from the
project root:

```bash
# 1. Fetch identifiers for all default sets
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

Run this way, `GetOaiPmhIdentifiers` writes under `guids/cessda/`
(its default tenant, overridable with `-t`/`--tenant`; see
[GetOaiPmhIdentifiers_README.md](GetOaiPmhIdentifiers_README.md)).
`RunBenchmarkAssessment` uses its own non-tenant-scoped `data/`/
`results/` defaults unless it is also given `-t`/`--tenant
<tenant-id>`, in which case it resolves that tenant's algorithm,
runner, and `{data,results}-dir/<tenant-id>/` directories from
`tenants.config.<tenant-id>` — see
[RunBenchmarkAssessment_README.md](RunBenchmarkAssessment_README.md#tenant-aware-runs).
`GenerateManifest` has no such flag and always uses its own
non-tenant-scoped defaults. Without passing `-t` to
`RunBenchmarkAssessment`, the three CLI stages above are not wired
together via the same tenant directory. This mode is intended for
local testing of the pipeline classes in isolation, or for driving an
individual stage from an external pipeline; it is not a replacement
for the multi-tenanted deployment described above, which still
requires `GenerateManifest` (and, for identifier fetching plus
manifest generation, the REST API or web UI) to complete a tenant's
results.

## Project Structure

This project uses the standard Maven project structure. Various
non-functional files have been omitted.

```text
<ROOT>
├── README.md           # This file
├── data                # Per-tenant guids_<set>.txt files
├── results             # Per-tenant outputs from RunBenchmarkAssessment
├── src                 # Contains all source code and assets for the
│                          application
│   ├── main
│   │   ├── java        # Contains release source code of the
│   │   │                 application
│   │   ├── resources   # Contains release resource assets, including
│   │   │                 application.yaml, index.html, detail.html, and
│   │   │                 fetch-identifiers.html
│   │   └── webapp
│   └── test
│       ├── java        # Contains test source code
│       └── resources   # Contains test resource assets
└── target               # The output directory for the build
```

Interaction diagram:

```text
Browser                    Spring Boot
  │                            │
  │  GET /                     │
  │ ─────────────────────────► │  serves static index.html
  │ ◄───────────────────────── │
  │                            │
  │  GET /api/results/         │
  │    summary.json            │
  │    X-API-Key: key-abc      │
  │ ─────────────────────────► │  TenantAuthFilter resolves "org-cessda"
  │                            │  TenantContext.tenantId = "org-cessda"
  │                            │  DashboardController reads
  │                            │    results/org-cessda/summary.json
  │ ◄───────────────────────── │  200 OK + JSON
  │                            │
  │  GET /api/results/         │
  │    guids_de/pages/         │
  │    page-001.json           │
  │    X-API-Key: key-abc      │
  │ ─────────────────────────► │  same filter + controller
  │                            │  reads results/org-cessda/guids_de/
  │                            │       pages/page-001.json
  │ ◄───────────────────────── │  200 OK + JSON
```

## Contributing

Please read [CONTRIBUTING](CONTRIBUTING.md) for details on our code
of conduct, and the process for submitting pull requests to us.

## Versioning

See [Semantic Versioning](https://semver.org/) for guidance.

## Contributors

You can find the list of contributors in the
[CONTRIBUTORS](CONTRIBUTORS.md) file.

## License

See the [LICENSE](LICENSE.txt) file.

## Citing

See the [CITATION](CITATION.cff) file.
