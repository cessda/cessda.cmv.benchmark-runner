# CESSDA CMV Benchmark-runner

[![SQAaaS badge](https://github.com/EOSC-synergy/SQAaaS/raw/master/badges/badges_150x116/badge_software_silver.png)](https://api.eu.badgr.io/public/assertions/rxEEBuR9QoadzMDHXT4PmQ "SQAaaS silver badge achieved")

This repository contains the source code for assessing digital objects in bulk
against the [CESSDA QA algorithm configuration spreadsheet](https://tools.ostrails.eu/champion/algorithms/1Nk0vM4yBpVQTo_UbB62NY_fz93aRZRHBZGh5fG-khOw),
using the [FAIR Champion Benchmark Assessment tool](https://tools.ostrails.eu/champion/assess/algorithms/new).

## Prerequisites

Java 21 or greater is required to build and run this application.

## Quick Start

1. Check prerequisites and install any required software.
2. Clone the repository to your local workspace.
3. Add the GUIDs of the digital objects to test in the `src/main/resources/guids.txt` file.
4. Build the application using `mvn compile`.
5. Run the application using the following command: `mvn exec:java`
   (this uses the QA algorithm configuration spreadsheet specified by `BENCHMARK_ALGORITHM_URI`).
6. The assessment results file for each GUID is written to the `results` directory.

## Fetching Identifiers from OAI-PMH

Instead of (or in addition to) maintaining a `guids.txt` file manually, the application can fetch
lists of identifiers directly from the CESSDA data catalogue OAI-PMH endpoint. Identifiers are
retrieved for the following language sets: `de`, `el`, `en`, `fi`, `fr`, `hr`, `nl`, `sl`,
`sl-SI`, and `sv`.

Each language produces a separate file, for example `guids_de.txt`, written to the
`src/main/resources` directory (or the current working directory when running from a JAR).
These files are then available for processing in the same way as `guids.txt`.

To fetch all identifier lists and immediately process them, run:

```text
mvn exec:java -Dexec.args="-A"
```

or equivalently:

```text
mvn exec:java -Dexec.args="--fetch-and-process"
```

To fetch the identifier lists without processing them:

```text
mvn exec:java -Dexec.args="-F"
```

To fetch the identifier list for a specified language without processing them:

```text
mvn exec:java -Dexec.args="-L en"
```

To process all previously fetched language files without re-fetching:

```text
mvn exec:java -Dexec.args="-P"
```

To process a single named file:

```text
mvn exec:java -Dexec.args="-p guids_de.txt"
```

To process a single GUID:

```text
mvn exec:java -Dexec.args="-g <GUID>"
```

## Dashboard

There is a dashboard for displaying the results.
Before viewing the dashboard, run
`mvn exec:java -Dexec.mainClass="cessda.cmv.benchmark.GenerateManifest"`
to index the results. It should be run locally (as the application is not
structured for Web deployment at present) using `npx serve .` then go to
`http://localhost:3000` view it.

## Customisation

The following options can be combined with any of the modes described above.
All commands should be run from the top-level directory (i.e. where the `pom.xml` file is located).

### Use a different QA algorithm configuration spreadsheet

```text
mvn exec:java -Dexec.args="-s https://tools.ostrails.eu/champion/algorithms/16s2klErdtZck2b6i2Zp_PjrgpBBnnrBKaAvTwrnMB4w"
```

or:

```text
mvn exec:java -Dexec.args="--spreadsheet https://tools.ostrails.eu/champion/algorithms/16s2klErdtZck2b6i2Zp_PjrgpBBnnrBKaAvTwrnMB4w"
```

Note: this must be the URL of a spreadsheet that is registered in FAIR Champion.
You can register a new Google spreadsheet with [FAIR Champion](https://tools.ostrails.eu/champion/algorithms/new).
You must publish the spreadsheet to the web and use the resulting URL to register it.

### Use a different filename for the list of GUIDs

The file must be located in the `src/main/resources` directory or the current working directory.

```text
mvn exec:java -Dexec.args="--filename guids2.txt"
```

or:

```text
mvn exec:java -Dexec.args="-f guids2.txt"
```

### Get help with the command line arguments

```text
mvn exec:java -Dexec.args="--help"
```

or:

```text
mvn exec:java -Dexec.args="-h"
```

## Command Line Reference

The following flags are available:

```text
 -A,--fetch-and-process        Fetch all identifier lists from OAI-PMH then process all
                               resulting files (equivalent to -F -P)
 -F,--fetch-all                Fetch identifier lists for all languages from OAI-PMH and
                               write guids_XX.txt files
 -L,--fetch-language <lang>    Fetch identifier list for specified language from OAI-PMH and
                               write guids_XX.txt file
 -f,--filename <file>          GUIDs filename (default: guids.txt) – used in legacy
                               single-file mode
 -h,--help                     Show help
 -P,--process-all              Process all guids_XX.txt files found in resources or
                               current directory
 -p,--process-file <file>      Process a single named GUID file
 -s,--spreadsheet <uri>        Spreadsheet URI (default: BENCHMARK_ALGORITHM_URI)
```

## Project Structure

This project uses the standard Maven project structure.

```text
<ROOT>
├── .mvn                # Maven wrapper.
├── src                 # Contains all source code and assets for the application.
|   ├── main
|   |   ├── java        # Contains release source code of the application.
|   |   └── resources   # Contains release resource assets.
|   └── test
|       ├── java        # Contains test source code.
|       └── resources   # Contains test resource assets.
└── target              # The output directory for the build.
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
