# API Usage Examples

This document provides worked examples for each of the three benchmark
pipeline endpoints. All examples use `curl` against a locally running
instance. Replace `http://localhost:8080` with your server address if
the application is running elsewhere.

Interactive documentation is also available via Swagger UI at
`http://localhost:8080/api-docs/ui`.

## Contents

- [1. Fetch OAI-PMH Identifiers](#1-fetch-oai-pmh-identifiers)
- [2. Run Benchmark Assessment](#2-run-benchmark-assessment)
- [3. Generate Dashboard Manifest](#3-generate-dashboard-manifest)
- [Response format](#response-format)
- [Running the full pipeline](#running-the-full-pipeline)

## Response format

All three endpoints return a JSON object with two fields.

On success:

```json
{
  "status": "ok",
  "message": "Human-readable description of what was done"
}
```

On failure:

```json
{
  "status": "error",
  "message": "Description of what went wrong"
}
```

A successful call always returns HTTP 200. An error always returns
HTTP 500.

## 1. Fetch OAI-PMH Identifiers

`POST /api/fetch-identifiers`

Fetches record identifiers from an OAI-PMH endpoint and writes one
`guids_<set>.txt` file per language set to the `benchmark-data`
volume. All parameters are optional.

### Fetch all default language sets

Fetches identifiers for all ten default sets: `de`, `el`, `en`, `fi`,
`fr`, `hr`, `nl`, `sl`, `sl-SI`, `sv`.

```bash
curl -X POST http://localhost:8080/api/fetch-identifiers
```

Expected response:

```json
{
  "status": "ok",
  "message": "Fetched identifiers for 10 set(s) -> /data"
}
```

### Fetch a single language set

Use the `fetchSet` parameter to fetch one set only. The `sets`
parameter is ignored when `fetchSet` is present.

```bash
curl -X POST \
  "http://localhost:8080/api/fetch-identifiers?fetchSet=en"
```

Expected response:

```json
{
  "status": "ok",
  "message": "Fetched identifiers for set: en -> /data/guids_en.txt"
}
```

### Fetch a custom list of language sets

Use the `sets` parameter with a comma-separated list of set names.

```bash
curl -X POST \
  "http://localhost:8080/api/fetch-identifiers?sets=de,en,fr"
```

Expected response:

```json
{
  "status": "ok",
  "message": "Fetched identifiers for 3 set(s) -> /data"
}
```

### Use a custom OAI-PMH endpoint

Override the base URL, verb, and metadata prefix to target a different
repository.

```bash
curl -X POST \
  "http://localhost:8080/api/fetch-identifiers\
?baseUrl=https://example.org/oai-pmh\
&verb=ListIdentifiers\
&metadataPrefix=oai_dc\
&sets=en"
```

### All Fetch parameters

| Parameter        | Default value                                          |
|------------------|--------------------------------------------------------|
| `baseUrl`        | `https://datacatalogue.cessda.eu/oai-pmh/v0/oai`      |
| `verb`           | `ListIdentifiers`                                      |
| `metadataPrefix` | `oai_ddi25`                                            |
| `sets`           | `de,el,en,fi,fr,hr,nl,sl,sl-SI,sv`                    |
| `fetchSet`       | *(none — fetches all sets when absent)*                |

## 2. Run Benchmark Assessment

`POST /api/run-assessment`

Reads `guids_*.txt` files from the `benchmark-data` volume, posts
each GetRecord URL to the FAIR Champion API, and writes one JSON
result file per identifier to the `benchmark-results` volume. All
parameters are optional.

This step can take a significant amount of time depending on the
number of identifiers in the input files.

### Process all default language sets

```bash
curl -X POST \
  "http://localhost:8080/api/run-assessment?processAll=true"
```

Expected response:

```json
{
  "status": "ok",
  "message": "Processed all default set files from /data -> results written to /results"
}
```

### Process a single language file

```bash
curl -X POST \
  "http://localhost:8080/api/run-assessment?guidFile=guids_en.txt"
```

Expected response:

```json
{
  "status": "ok",
  "message": "Processed file: /data/guids_en.txt -> results written to /results"
}
```

### Assess a single GetRecord URL

Use the `guid` parameter to assess one record directly without
reading from a file. The URL must be a complete OAI-PMH GetRecord
URL. The `guid` parameter takes priority over `guidFile` and
`processAll` when all three are supplied.

```bash
curl -X POST \
  "http://localhost:8080/api/run-assessment\
?guid=https%3A%2F%2Fdatacatalogue.cessda.eu%2Foai-pmh%2Fv0%2Foai\
%3Fverb%3DGetRecord%26metadataPrefix%3Doai_ddi25%26identifier%3Dabc123"
```

Decoded, the `guid` value is:

```text
https://datacatalogue.cessda.eu/oai-pmh/v0/oai
  ?verb=GetRecord
  &metadataPrefix=oai_ddi25
  &identifier=abc123
```

Expected response:

```json
{
  "status": "ok",
  "message": "Processed single GUID: https://datacatalogue.cessda.eu/... -> results written to /results"
}
```

### Use a custom Champion API URI

```bash
curl -X POST \
  "http://localhost:8080/api/run-assessment\
?processAll=true\
&spreadsheetUri=https%3A%2F%2Ftools.ostrails.eu%2Fchampion%2Fassess%2Falgorithm%2Fd%2FYourAlgorithmId"
```

### All Run parameters

| Parameter        | Default value                                            |
|------------------|----------------------------------------------------------|
| `spreadsheetUri` | The default CESSDA Champion algorithm URI                |
| `guidFile`       | `guids_hr.txt` *(when no mode parameter is supplied)*    |
| `guid`           | *(none)*                                                 |
| `processAll`     | `false`                                                  |

Parameter priority when multiple are supplied:

1. `guid` — single URL, processed immediately
2. `guidFile` — single named file
3. `processAll=true` — all default set files
4. *(none)* — default file (`guids_hr.txt`)

## 3. Generate Dashboard Manifest

`POST /api/generate-manifest`

Reads the JSON result files from the `benchmark-results` volume and
produces two artefacts consumed by the HTML dashboard:

- `results/summary.json` — aggregated pass, fail, and indeterminate
  counts broken down by language, test ID, and FAIR category
- `results/guids_<lang>/pages/page-NNN.json` — paginated slices of
  the record list (200 records per page)

This endpoint is the API equivalent of the first command in the
original `start-dashboard.sh` script. It must be called after
`/api/run-assessment` has written result files.

### Generate the manifest using the default results directory

```bash
curl -X POST http://localhost:8080/api/generate-manifest
```

Expected response:

```json
{
  "status": "ok",
  "message": "Manifest generated in: /results"
}
```

### Generate the manifest from a custom directory

Use the `resultsDir` parameter to override the default results volume
path. This is useful when testing with a local results directory.

```bash
curl -X POST \
  "http://localhost:8080/api/generate-manifest\
?resultsDir=%2Ftmp%2Fmy-results"
```

Decoded, `resultsDir` is `/tmp/my-results`.

Expected response:

```json
{
  "status": "ok",
  "message": "Manifest generated in: /tmp/my-results"
}
```

### Error: results directory not found

If the results directory does not exist the endpoint returns HTTP 500.

```json
{
  "status": "error",
  "message": "Results directory not found: /results"
}
```

Ensure `/api/run-assessment` has been called at least once before
calling `/api/generate-manifest`.

### All Dashboard parameters

| Parameter    | Default value                               |
|--------------|---------------------------------------------|
| `resultsDir` | The configured `benchmark.results-dir` path |

## Running the full pipeline

The following sequence runs all three stages in order using the
default settings. Run each command and wait for a success response
before proceeding to the next.

```bash
# Stage 1 - fetch identifiers for all default language sets
curl -X POST http://localhost:8080/api/fetch-identifiers

# Stage 2 - run the FAIR benchmark assessment for all sets
# (this step contacts an external API and may take several minutes)
curl -X POST \
  "http://localhost:8080/api/run-assessment?processAll=true"

# Stage 3 - generate the dashboard manifest
curl -X POST http://localhost:8080/api/generate-manifest
```

Once all three stages complete successfully, open the dashboard at:

```text
http://localhost:8080
```
