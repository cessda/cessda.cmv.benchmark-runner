# Installation and usage guide

This guide explains how to install, configure and run the CESSDA CMV
Benchmark Runner application, and how to process a set of GUIDs through
the FAIR assessment workflow.

> Note: the application is now multi-tenanted. There is currently no
> way to trigger an assessment run via the command line, so this guide
> describes running the application as a Spring Boot service and
> driving the assessment through the browser-based UI instead.

## Prerequisites

Before you start, make sure you have the following installed:

- Java (a version compatible with the project's Spring Boot version)
- Apache Maven
- Git (to obtain the latest source)
- A modern web browser

## 1. Get the latest software version

Make sure you are working with the latest version of the code before
starting a run. If you already have a local clone, pull the latest
changes; otherwise clone the
[repository](https://github.com/cessda/cessda.cmv.benchmark-runner.git).

```bash
cd /path/to/your/workspace/cessda.cmv.benchmark-runner
git pull
```

## 2. Prepare your tenant and GUID files

Each tenant has its own subdirectory under `guids/`, named after the
tenant's identifier. For example, a tenant called `cessda` would use
`guids/cessda`.

1. Copy your GUIDs file into the appropriate tenant directory, for
   example `guids/cessda`.

2. Delete any other GUID files already present in that directory that
   you do not want included in this run.

3. Name each GUIDs file using the pattern `guids_xxx.txt`, where `xxx`
   is any identifying suffix. This naming pattern must be followed
   even if you only have one file, for example `guids_1.txt`.

If you need to split a large batch of GUIDs into several files (for
example, to run them in separate batches), each file must still
follow the `guids_xxx.txt` naming pattern, for example:

```text
guids_1.txt
guids_2.txt
guids_3.txt
guids_4.txt
```

> Tip: GUID files are working data rather than source code, so
> consider excluding the contents of the `guids/` directories from
> version control (for example, via `.gitignore`), keeping only a
> placeholder or `README` in each tenant folder.

## 3. Build the application

Open a terminal and change to the root of the code directory, for
example:

```bash
cd /path/to/your/workspace/cessda.cmv.benchmark-runner
```

Build the JAR file:

```bash
mvn clean package
```

## 4. Run the application

Start the application with:

```bash
mvn spring-boot:run
```

Once the application has started, open a browser and go to:

```text
http://localhost:8080
```

## 5. Authenticate for your tenant

You should see a key-entry challenge. Enter your tenant's API key
using the following format:

```text
key-<tenant-id>
```

For example, a tenant called `cessda` would use `key-cessda`.

![Enter key for tennant](src/main/resources/static/images/Figure_1.jpg)

Figure 1: Enter key for tennant

## 6. Check the run configuration

Click the **Run assessment** button, near the top right of the page,
and check that the following are set correctly for this run:

- Algorithm URI
- Runner URI

![Check algorithm and runner URIs](src/main/resources/static/images/Figure_2.jpg)

Figure 2: Check algorithm and runner URIs

If either value is incorrect, you have two options:

- **For this run only** — change the value directly in the UI.
- **For every future run** — edit the corresponding
  `config:<tenant-id>` section of `application.yaml`, located under
  `src/main/resources/static`.

> If you edit `application.yaml`, you must stop the application,
> rebuild the JAR file (`mvn clean package`), and restart the
> application (`mvn spring-boot:run`) before your changes take
> effect.

## 7. Run the assessment

1. The name of your GUIDs file(s) should already be selected in the UI.

![All GUID files are selected by default](src/main/resources/static/images/Figure_3.jpg)

Figure 3: All GUID files are selected by default

1. Click **Run assessment**.

1. Wait for the run to complete. Larger GUID sets can take some time
   to process, so this is a good point to take a break.

When the run finishes, the dashboard should update to show the
results for that GUID set.

## 8. Generate the manifest

If results are not shown automatically once the run finishes, click
**Generate manifest**. This aggregates results across all GUID sets
that have been run for the tenant, and the dashboard should then
display them.

### Running GUID sets incrementally

You do not need to run all of a tenant's GUID sets in one go, and you
do not need to run them in any particular order.
You can select a subset of GUID files:

![Select a subset of GUID files](src/main/resources/static/images/Figure_4.jpg)

Figure 4: Select a subset of GUID files

1. Run one subsetset at a time.
2. Click **Generate manifest** at any point to see the results
   accumulated so far.
3. Run further GUID subsets later, and click **Generate manifest** again
   to bring the dashboard up to date with the full set.

For example, for a tenant with multiple GUID sets:

- After running two of the sets and generating the manifest, the
  dashboard shows results for those sets (for example, four records
  across two sets).

![Partial results from two sets](src/main/resources/static/images/Figure_5.jpg)

Figure 5: Partial results from two sets

- After running another set and generating the manifest again, the
  dashboard updates to reflect those sets (for example, 625 records
  across three sets).

![Select another subset of GUID files](src/main/resources/static/images/Figure_6.jpg)

Figure 6: Select another subset of GUID files

![Partial results from three sets](src/main/resources/static/images/Figure_7.jpg)

Figure 7: Partial results from three sets

- After running the remaining sets of GUID files, a complete picture of the results is available:

![Full results from all ten sets](src/main/resources/static/images/Figure_8.jpg)

Figure 8: Full results from all ten sets

## Adding and Configuring Tenants

This section explains how to add a new tenant to the CESSDA CMV Benchmark
Runner and configure its various options.

### Overview

The application supports multi-tenancy, with each tenant having its own
Benchmark Assessment Algorithm, FAIR Champion runner instance, and
dataset-specific configuration. Tenants are configured in the
`application.yaml` file under the `tenants` section.

### Enabling Multi-Tenancy

To enable multi-tenancy, set the `enabled` flag in `application.yaml`:

```yaml
tenants:
  enabled: true
```

When enabled, each tenant operates independently with its own data,
results, and configuration.

### Adding a New Tenant

#### Step 1: Create a Tenant Key Mapping

In the `tenants.keys` section, map an API key to a tenant identifier:

```yaml
tenants:
  keys:
    "key-your-tenant": "your-tenant"
```

- **Key (left side)**: The API key used to identify the tenant in requests
- **Value (right side)**: The tenant ID used internally in configuration

Example:

```yaml
tenants:
  keys:
    "key-one": "one"
    "key-two": "two"
    "key-example": "example"
```

#### Step 2: Configure the Tenant

Under `tenants.config`, create a new section with the tenant ID:

```yaml
tenants:
  config:
    your-tenant:
      algorithm: <URL to Benchmark Assessment Algorithm>
      runner: <URL to FAIR Champion runner instance>
      title: <Dashboard title for this tenant>
      footer: <Footer text for this tenant>
      set-names:
        # Language codes or dataset identifiers mapped to display names
      fair-map:
        # Assessment indicators mapped to FAIR categories
      maturity-levels:
        # Definition of maturity levels for this tenant
```

### Configuration Options

#### Basic Properties

##### `algorithm`

The URL of the Benchmark Assessment Algorithm spreadsheet or resource.

```yaml
one:
  algorithm: https://docs.google.com/spreadsheets/d/<ADDRESS>
```

##### `runner`

The URL of the FAIR Champion runner instance where assessments are
performed.

```yaml
one:
  runner: https://tools.ostrails.eu/champion/assess/algorithm
```

##### `title`

The title displayed on the dashboard for this tenant.

```yaml
one:
  title: Tennant One · Assessment Results
```

##### `footer`

Footer text displayed on the dashboard for this tenant.

```yaml
one:
  footer: CESSDA FAIR Benchmark Dashboard
```

#### Set Names

The `set-names` section maps identifiers (typically language codes or
dataset names) to human-readable display names. These are used to label
datasets or language variants in the dashboard.

```yaml
one:
   bhf: British Heart Foundation
   epsrc: Engineering and Physical Sciences Research Council
   mrc: Medical Research Council
   nerc: Natural Environment Research Council
   wellcome: Wellcome Trust
```

Each entry consists of:

- **Key**: An identifier (language code, dataset code, etc.)
- **Value**: The human-readable name to display in the dashboard

#### FAIR Map

The `fair-map` section maps assessment indicators to FAIR categories
(Findable, Accessible, Interoperable, Reusable).

```yaml
one:
  fair-map:
    F1_PID: F              # Persistent Identifier → Findable
    F1_GUID: F             # GUID → Findable
    F2A: F                 # Findable metric 2A
    A1_1: A                # Authentication → Accessible
    I1_A: I                # Semantic interoperability → Interoperable
    R1_2_CPI: R            # Reusability metric
```

Each entry consists of:

- **Key**: An assessment indicator code (defined in your algorithm)
- **Value**: The FAIR category letter: `F`, `A`, `I`, or `R`

This mapping is used to aggregate assessment results by FAIR category
in the dashboard.

#### Maturity Levels

The `maturity-levels` section defines which indicators must be met to
achieve each maturity level. This creates a progression path from basic
to advanced FAIR compliance.

```yaml
one:
  maturity-levels:
    level1:
      - F1_GUID
      - F2B
      - F4
      - A1_1
    level2:
      - F1_GUID
      - F2B
      - F4
      - A1_1
      - F2A
      - I1_A
      - R1_2_CPI
    level3:
      - F1_GUID
      - F2B
      - F4
      - A1_1
      - F2A
      - I1_A
      - R1_2_CPI
      - F1_PID
      - I2_A
      - R1_3_CEK
      - R1_3_CTV
      - R1_3_DMOCV
      - R1_3_DAUV
      - R1_3_DTMV
      - R1_3_DSPV
```

Each maturity level contains:

- **Key**: The level identifier (e.g., `level1`, `level2`, `level3`)
- **Value**: A list of assessment indicators that must be met to achieve
  that level

Typically, higher maturity levels include all indicators from lower
levels plus additional requirements.

### Complete Example

Here is a complete example of adding a new tenant called `my-org`:

```yaml
tenants:
  enabled: true
  keys:
    "key-one": "one"
    "key-two": "two"
    "key-myorg": "my-org"
  config:
    my-org:
      algorithm: https://example.com/my-org-algorithm
      runner: https://champion.example.com/assess/algorithm
      title: My Organisation · Assessment Results
      footer: My Organisation FAIR Benchmark Dashboard
      set-names:
        dataset-a: Dataset A
        dataset-b: Dataset B
        dataset-c: Dataset C
      fair-map:
        ORG_F1: F
        ORG_F2: F
        ORG_A1: A
        ORG_I1: I
        ORG_R1: R
      maturity-levels:
        level1:
          - ORG_F1
          - ORG_A1
        level2:
          - ORG_F1
          - ORG_F2
          - ORG_A1
          - ORG_I1
        level3:
          - ORG_F1
          - ORG_F2
          - ORG_A1
          - ORG_I1
          - ORG_R1
```

### Runtime Behaviour

Once configured, tenants operate independently:

- Each tenant's data is stored in separate directories:
  `{data-dir}/{tenant-id}/` and `{results-dir}/{tenant-id}/`
- Assessment results are computed using the tenant's specific algorithm
  and runner
- The dashboard displays information using the tenant's title, footer,
  and display names
- Maturity level assessments are calculated based on the tenant's
  definitions

### API Access

When using the REST API, specify the tenant via the API key header:

```bash
curl -H "Authorization: key-myorg" \
  https://your-instance.com/api/assess
```

The application matches the API key to the corresponding tenant ID and
applies that tenant's configuration.

### Validation

When you start the application, it validates the configuration:

- All required properties (`algorithm`, `runner`, `title`, `footer`,
  `set-names`, `fair-map`, `maturity-levels`) must be present
- All entries in `fair-map` must have valid FAIR category values
- All entries in `maturity-levels` must have non-empty indicator lists
- All indicators referenced in `maturity-levels` should be defined in
  `fair-map`

Missing or invalid configuration will cause a startup error with details
about which property is missing.

## Troubleshooting

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| Results not shown after a run | Manifest not yet regenerated | Click **Generate manifest** |
| Wrong Algorithm/Runner URI | Tenant config out of date | Update `config:<tenant-id>` in `application.yaml` and rebuild |
| Key not accepted | Wrong tenant key format | Use `key-<tenant-id>`, matching your tenant folder name |
| Config changes not visible | Application not rebuilt/restarted | Rebuild the JAR and restart `mvn spring-boot:run` |

## Known limitation

Assessment runs currently cannot be triggered from the command line;
the multi-tenant refactor removed this path. All runs must currently
be started through the web UI as described above. This guide will be
updated if command-line support is reintroduced.
