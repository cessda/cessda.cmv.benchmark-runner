# GetOaiPmhIdentifiers

Fetches identifier lists from an OAI-PMH endpoint and writes them as
full `GetRecord` URLs to `resources/guids_<set>.txt` files.

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
one file per set.

## Default values

| Parameter       | Default value                                              |
|-----------------|------------------------------------------------------------|
| Base URL        | `https://datacatalogue.cessda.eu/oai-pmh/v0/oai`          |
| Verb            | `ListIdentifiers`                                          |
| Metadata prefix | `oai_ddi25`                                                |
| Sets            | `de`, `el`, `en`, `fi`, `fr`, `hr`, `nl`, `sl`, `sl-SI`, `sv` |

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
mvn exec:java -Dexec.mainClass="cessda.cmv.benchmark.GetOaiPmhIdentifiers -Dexec.args="-F"
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

## Output files

For each set processed, a file named `guids_<set>.txt` is written.

- When `src/main/resources/` exists (i.e. when running from source),
  the file is placed there.
- Otherwise it is written to the current working directory.

Each file begins with three comment lines:

```text
# Identifiers for set: de
# Fetched: 2026-01-01T00:00:00Z
# Count: 42
```

The remaining lines are full `GetRecord` URLs, one per identifier.

## How it works

1. A `ListIdentifiers` request is built from the base URL, verb,
   metadata prefix, and set name.
2. The XML response is parsed for `<identifier>` elements.
3. If a `<resumptionToken>` is present, the next page is fetched and
   the process repeats until all pages are exhausted.
4. Each raw identifier is combined with the base URL and metadata
   prefix to produce a `GetRecord` URL.
5. All URLs are written to `guids_<set>.txt`.

## HTTP behaviour

- Connection timeout: 30 seconds.
- Request timeout: 60 seconds.
- Non-2xx responses raise an `IOException`.
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
