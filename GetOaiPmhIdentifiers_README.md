# GetOaiPmhIdentifiers

Fetches identifier lists from an OAI-PMH endpoint and writes them as
full `GetRecord` URLs to `guids/<tenant>/guids_<set>.txt` files.

Each output line is a complete, ready-to-use OAI-PMH `GetRecord` URL,
for example:

```text
https://datacatalogue.cessda.eu/oai-pmh/v0/oai?verb=GetRecord
  &metadataPrefix=oai_ddi25&identifier=abc123
```

## Overview

`GetOaiPmhIdentifiers` queries an OAI-PMH endpoint using the
`ListIdentifiers` verb (or a configurable alternative), collecting all
record identifiers for one or more named sets. Pagination via
resumption tokens is handled automatically. The resulting identifiers
are converted into `GetRecord` URLs and written to plain-text files,
one file per set, under a tenant-scoped output directory — see
[Output files](#output-files).

## Default values

| Parameter       | Default value                                                 |
|-----------------|---------------------------------------------------------------|
| Base URL        | `https://datacatalogue.cessda.eu/oai-pmh/v0/oai`              |
| Verb            | `ListIdentifiers`                                             |
| Metadata prefix | `oai_ddi25`                                                   |
| Sets            | `de`, `el`, `en`, `fi`, `fr`, `hr`, `nl`, `sl`, `sl-SI`, `sv` |
| Tenant          | `cessda`                                                       |

## Command-line options

```text
-b, --oai-pmh-base-url <url>     OAI-PMH base URL
                                   (default: https://datacatalogue
                                   .cessda.eu/oai-pmh/v0/oai)
-v, --verb <verb>                 OAI-PMH verb used when listing
                                   identifiers (default: ListIdentifiers)
-m, --metadata-prefix <prefix>   Metadata prefix embedded in output
                                   GetRecord URLs (default: oai_ddi25)
-S, --sets <set1,set2,...>        Comma-separated list of sets to fetch
                                   (default: de,el,en,fi,fr,hr,nl,sl,
                                   sl-SI,sv)
-F, --fetch-all-sets              Fetch identifiers for all sets
                                   (default behaviour)
-s, --fetch-set <set>             Fetch identifiers for a single set only
-t, --tenant <tenant>             Tenant name; output is written to
                                   guids/<tenant>/ (default: cessda)
-h, --help                        Show the help message
```

## Usage

Run with Maven (from the project root):

```bash
mvn exec:java \
  -Dexec.mainClass="cessda.cmv.benchmark.GetOaiPmhIdentifiers" \
  -Dexec.args="--fetch-set en"
```

Run from a JAR:

```bash
java -cp <jar> cessda.cmv.benchmark.GetOaiPmhIdentifiers \
  --oai-pmh-base-url https://example.org/oai \
  --metadata-prefix oai_ddi25 \
  --fetch-set de
```

### Fetch all sets (default)

```bash
java -cp <jar> cessda.cmv.benchmark.GetOaiPmhIdentifiers
```

Or

```bash
mvn exec:java -Dexec.mainClass="cessda.cmv.benchmark.GetOaiPmhIdentifiers" \
  -Dexec.args="-F"
```

### Fetch a custom list of sets

```bash
java -cp <jar> cessda.cmv.benchmark.GetOaiPmhIdentifiers \
  --sets de,en,fr
```

### Fetch a single set

```bash
java -cp <jar> cessda.cmv.benchmark.GetOaiPmhIdentifiers \
  --fetch-set hr
```

Or

```bash
mvn exec:java -Dexec.mainClass="cessda.cmv.benchmark.GetOaiPmhIdentifiers" \
-Dexec.args="-s <set_name>"
```

### Fetch for a specific tenant

```bash
java -cp <jar> cessda.cmv.benchmark.GetOaiPmhIdentifiers \
  --tenant new-org \
  --fetch-set de
```

Or

```bash
mvn exec:java -Dexec.mainClass="cessda.cmv.benchmark.GetOaiPmhIdentifiers" \
-Dexec.args="-t new-org -s de"
```

Output is written to `guids/new-org/guids_de.txt` instead of the
default `guids/cessda/guids_de.txt`. See
[Output files](#output-files).

## Output files

For each set processed, a file named `guids_<set>.txt` is written to
`guids/<tenant>/`, where `<tenant>` is the value of `-t` /
`--tenant` (default: `cessda`). The directory is created automatically
if it does not already exist — e.g. running with the default tenant
writes to `guids/cessda/guids_de.txt`, `guids/cessda/guids_en.txt`,
and so on.

This mirrors how the web/REST pipeline resolves each tenant's data
directory (`benchmark.data-dir`, default `./guids`, resolved as
`{data-dir}/{tenant-id}/`), so a standalone CLI run for a given tenant
and a REST-triggered run for that same tenant land in the same place.

Each file begins with three comment lines:

```text
# Identifiers for set: de
# Fetched: 2026-01-01T00:00:00Z
# Count: 42
```

The remaining lines are full `GetRecord` URLs, one per identifier.

## How it works

1. A `ListIdentifiers` request is built from the base URL, verb,
   metadata prefix, and set name. This repository's sets are keyed by
   language, so the set name is sent as `set=language:<code>` (e.g.
   `language:hr`), confirmed against `verb=ListSets` on the live
   endpoint — the bare code alone (`set=hr`) is not a valid setSpec.
2. The XML response is checked for a top-level `<error>` element (see
   [HTTP behaviour](#http-behaviour)) and then parsed for `<identifier>`
   elements.
3. If a `<resumptionToken>` is present, the next page is fetched and
   the process repeats until all pages are exhausted.
4. Each raw identifier is combined with the base URL and metadata
   prefix to produce a `GetRecord` URL.
5. All URLs are written to `guids_<set>.txt`.

## Discovering available sets (ListSets)

Besides fetching identifiers, `GetOaiPmhIdentifiers` can call
`verb=ListSets` to discover exactly which sets an endpoint currently
offers, returning each set's `setSpec` (the value to pass back as a
set name) alongside its own human-readable `setName` — there is no
static or hand-maintained mapping to keep in sync, so this always
reflects the live catalogue. Every OAI-PMH repository that supports
selective harvesting at all is required to support `ListSets`, so
this works for any tenant's endpoint regardless of its own setSpec
naming scheme (CESSDA's own `language:<code>` scheme is not assumed).
A repository with no set hierarchy returns an empty list — OAI-PMH's
`noSetHierarchy` response — rather than an error.

This is not currently exposed as its own CLI flag; it backs the
dashboard's "Fetch identifiers" page and the `GET
/api/fetch-identifiers/sets` REST endpoint. See
[API_Usage.md](API_Usage.md#discover-available-sets) for the REST
form.

## HTTP behaviour

- Connection timeout: 30 seconds.
- Request timeout: 60 seconds.
- Non-2xx responses raise an `IOException`.
- OAI-PMH reports protocol-level failures (`badVerb`, `badArgument`,
  `noRecordsMatch`, etc.) as an `<error>` element inside an ordinary
  HTTP 200 response, not as a non-2xx status. Every response is checked
  for this before its identifiers are counted, and an `<error>` element
  raises an `IOException` naming the OAI-PMH error code and message —
  a failed set is never silently written out as an empty
  `guids_<set>.txt` file.
- XML external entities and external parameter entities are disabled to
  guard against XXE attacks.

## Dependencies

- Java standard library (`java.net.http`, `javax.xml.parsers`)
- Apache Commons CLI (argument parsing)

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
