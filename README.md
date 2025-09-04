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
3. Add the GUIDs of the digital objects to test in the src/main/resources/guids.txt file
4. Build the application using `mvn compile`.
5. Run the application using the following command: `mvn exec:java`.

## Customisation

To use a different QA algorithm configuration spreadsheet, change the value of BENCHMARK_ALGORITHM_URI.
Note this must point to a spreadsheet that is registered in FAIR Champion.
You can register a new Google spreadsheet with [FAIR Champion](https://tools.ostrails.eu/champion/algorithms/new).
You must publish the spreadsheet to the web and use the resulting URL to register it.

## Project Structure

This project uses the standard Maven project structure.

``` text
<ROOT>
├── .mvn                # Maven wrapper.
├── src                 # Contains all source code and assets for the application.
|   ├── main
|   |   ├── java        # Contains release source code of the application.
|   |   └── resources   # Contains release resources assets.
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

## CITING

See the [CITATION](CITATION.cff) file.
