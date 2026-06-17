# Spark-FHIR Healthy Aging Dataset Extractor

This project leverages Apache Spark and the `spark-on-fhir` toolkit to flatten complex clinical data (Observations, QuestionnaireResponses) into analytics-ready CSVs. Simultaneously, it generates rich metadata (DCAT, CSVW, SKOS) and publishes it to a FAIR Data Point (FDP).

---

## Project Overview

The pipeline performs the following key operations:

1. Extracts raw FHIR resources (`Patient`, `Observation`, `QuestionnaireResponse`, `Questionnaire`).
2. Resolves terminology by joining patient answers with full Questionnaire definitions.
3. Flattens the data into a long-format CSV - one row per reading/answer - with the schema:
   * **Observation job** -> `pid, code, display, value, date`
   * **Survey job** -> `pid, code, display, answer, date`
   * **Full Extraction job** (combined) -> `pid, code, display, value, date` (Survey rows have their `answer` column renamed to `value` at the union)

   The `pid` is taken from `Patient.identifier[0].value` (falling back to the FHIR resource id when no identifier exists). Each row carries the timestamp of the underlying reading/response, so multiple readings of the same code for the same patient remain as distinct rows.
4. Optionally restricts the extraction to a configurable time window (`--date-from`/`--date-to` or `--reference`/`--window`).
5. Optionally produces a raw FHIR transaction `bundle.json` mirroring exactly what would be extracted (the `bundle` job).
6. Generates FAIR metadata:
   * **DCAT** - Catalog, Dataset, and Distribution descriptions.
   * **CSVW** - schema definition for the five output columns, with `code`/`display` linked to a per-job SKOS concept scheme.
   * **SKOS** - concept schemes recording the unique observation codes and questionnaire item codes seen in the data, plus per-question answer-option value sets.
7. Publishes metadata to a FAIR Data Point (FDP) and/or saves locally as Turtle (`.ttl`) files.

---

## Prerequisites

Before running the application, ensure you have the following environment set up:

* **Java 11+**
* **Apache Spark 3.5.x**
* **FHIR Server:** A running R4 FHIR Server (e.g., OnFhir) containing your source data.
* **FDP Server (Optional):** A running FAIR Data Point instance if you intend to publish metadata remotely.
* **Dependencies:**
* `spark-on-fhir-sdk` (Ensure this is available in your Maven repo)
* `onfhir-feast` (Used for metadata component extraction)



---

## Building the Project

Clone the repository and build the "fat JAR" using Maven. This will bundle all necessary Scala dependencies.

```bash
cd stage-fhir-fdp-adapter
mvn -DskipTests clean package

```

---

## Configuration

The project strictly separates technical application configuration from domain-specific metadata to allow for seamless environment setups.

### 1. Application Configuration (`application.conf`)

Technical settings—such as server connections, credentials, and file paths—are provided via the `application.conf` file (typically located in `src/main/resources/application.conf`).

| Parameter | Description |
| --- | --- |
| `fhirUrl` | Base URL of the source FHIR server. |
| `fdpUrl` | (Optional) URL of the target FAIR Data Point. |
| `fdpEmail` | (Optional) Email for FDP authentication. |
| `fdpPassword` | (Optional) Password for FDP authentication. |
| `outputDir` | Local directory to save the extracted CSV and generated `.ttl` files. |
| `jsonPath` | Path to the metadata JSON file (used if `runMode=json`). |
| `excelPath` | Path to the metadata Excel file (used if `runMode=excel`). |

### 2. Configure Run Mode (Metadata)

Descriptive FAIR metadata (Catalog details, Publisher, Access Rights, etc.) is ingested based on the selected `runMode`. Dynamic statistics (like patient age ranges and temporal coverage) are automatically extracted from FHIR unless overridden by the static configuration.

* **`browser`** (Default): Launches a local web server and opens a UI form in your browser to input metadata dynamically.
* **`json`**: Automatically loads metadata from the configured `config.json` file.
* **`excel`**: Automatically loads metadata from the configured `config.xlsx` file.

---

## Usage

Use the provided shell script to submit the Spark job. You can customize the job type, output format, and configuration mode via CLI arguments.

```bash
./run-cli.sh (optional CLI arguments can be seen below)

```
### CLI Arguments

You can customize the job type, output format, configuration mode, and directly override any `application.conf` settings via CLI arguments.

| Argument | Default | Description |
| --- | --- | --- |
| `--job` | `observation,survey` | The ETL pipeline to run. Supports: `survey`, `observation`, `full` (combined), or `bundle` (raw FHIR Bundle export, bypasses Spark). Multiple jobs can be comma-separated. |
| `--format` | `csv` | The output format for the patient data (`csv` or `parquet`). |
| `--runMode` | `browser` | How the app loads metadata configuration (`json`, `excel`, or `browser`). |
| `--fhirUrl` | *(from conf)* | Override the source FHIR server URL. |
| `--fdpUrl` | *(from conf)* | Override the target FAIR Data Point URL. |
| `--fdpEmail` | *(from conf)* | Override the FDP authentication email. |
| `--fdpPassword`| *(from conf)* | Override the FDP authentication password. |
| `--outputDir` | *(from conf)* | Override the output directory for generated files. |
| `--jsonPath` | *(from conf)* | Override the path to the `config.json` file. |
| `--excelPath` | *(from conf)* | Override the path to the `config.xlsx` file. |
| `--date-from` | *(none)* | Lower bound (inclusive) for the FHIR search window. Accepts ISO date (`2026-03-01`) or date-time (`2026-03-01T00:00:00Z`). |
| `--date-to` | *(none)* | Upper bound (inclusive) for the FHIR search window. Same accepted formats. |
| `--reference` | *(none)* | Anchor date for a centered window. Must be paired with `--window`. |
| `--window` | *(none)* | Half-width around `--reference`, given as an ISO-8601 duration: `P30D`, `P6M`, `P1Y`, `PT12H`, etc. Resolved into `[reference - window, reference + window]`. |
| `--vocab-base` | `http://stage-healthyageing.eu/fdp/vocab` | Base URI for SKOS scheme/concept URIs and CSVW `propertyUrl` values emitted in `CSVW.ttl`. Trailing slashes are stripped. |

### Time-window filter

When any date flag is supplied, the filter is appended to the FHIR search URL - `Observation` is filtered by `date`, `QuestionnaireResponse` by `authored`. `Patient` and `Questionnaire` definitions are always loaded unfiltered so subjects and answer-option vocabularies remain resolvable.

The explicit (`--date-from`/`--date-to`) and centered (`--reference`/`--window`) forms are mutually exclusive. Examples:

```bash
# Explicit, one-sided window - everything from March 2026 onward.
./run-cli.sh --date-from 2026-03-01

# Explicit two-sided window.
./run-cli.sh --date-from 2026-03-01 --date-to 2026-04-30

# Centered window: 6 months on either side of the reference date.
./run-cli.sh --reference 2026-04-01 --window P6M
```

Resources missing the indexed date field are excluded when a filter is active.

### Bundle extraction (raw FHIR export)

The `bundle` job is a non-analytic counterpart to the extraction pipelines. It hits the FHIR server directly (no Spark), pages through `Patient`, `Observation`, `QuestionnaireResponse`, and `Questionnaire` searches, and writes a single FHIR transaction Bundle to:

```
<outputDir>/bundle_extraction/bundle.json
```

Each entry carries a `request.method` of `PUT` (when the resource has an `id` — upsert semantics) or `POST` (when it doesn't), so the file is self-contained and can be POSTed to another FHIR server to recreate the dataset.

The `--date-from` / `--date-to` / `--reference` / `--window` flags apply here as well: `Observation` is filtered by `date`, `QuestionnaireResponse` by `authored`. `Patient` and `Questionnaire` definitions are always pulled un-filtered (subjects + value-set lookups must remain resolvable).

```bash
# Plain export of everything currently on the server.
./run-cli.sh --job bundle

# Filtered export covering March-April 2026.
./run-cli.sh --job bundle --date-from 2026-03-01 --date-to 2026-04-30

# Bundle export AND a flat Observation extraction in the same run.
./run-cli.sh --job bundle,observation
```

The bundle job produces neither CSV nor RDF — its only output is `bundle.json`.

---

## Generating cohort dictionary excel files

For cohorts whose dictionary lives outside FHIR (KORA, NFBC), two Node scripts at the project root turn the cohort's source into a single self-contained Excel file that the extraction app can consume directly.

```bash
# KORA-AGE1
node generate-dict-kora.js --input VarDef_AGE1_20250121_V2.xlsx --template src/main/resources/config.xlsx --output  kora_dictionary_integrated.xlsx --vocab-base http://stage-healthyageing.eu/fdp/vocab

# NFBC1966
node generate-dict-nfbc.js --input NFBC196660vKerys_DataDictionary_2026-02-12.csv --template src/main/resources/config.xlsx --output  nfbc_dictionary_integrated.xlsx --vocab-base http://stage-healthyageing.eu/fdp/vocab
```

Notes:
- The `--vocab-base` value is written into the `Property URL (ontology)` column of every coded variable, so it must match the `--vocab-base` passed to the extraction app, otherwise the CSVW `propertyUrl` values and the SKOS scheme URIs will be different.
- `--template` defaults to `src/main/resources/config_<cohort>.xlsx`; `--output` defaults to `<cohort>_dictionary_integrated.xlsx` in the current directory.

---

## Output

After a successful run, the `outputDir` contains one subfolder per executed job. Each analytic-job subfolder holds a single coalesced CSV (`part-*.csv`) plus an `rdf/` directory with the FAIR metadata:

| Subfolder | Produced by | Contents |
| --- | --- | --- |
| `observation_profiles/` | `--job observation` | Flat Observation CSV (`pid, code, display, value, date`) + `rdf/` |
| `survey_profiles/`      | `--job survey`      | Flat QuestionnaireResponse CSV (`pid, code, display, answer, date`) + `rdf/` |
| `patient_data/`         | `--job full`        | Unified flat CSV combining both (`pid, code, display, value, date`) + `rdf/` |
| `bundle_extraction/`    | `--job bundle`      | A single FHIR transaction `bundle.json` mirroring the FHIR server contents — no Spark, no RDF |

Inside every `rdf/` directory:

1. **`Catalog.ttl`** - DCAT description of the Data Catalog.
2. **`Dataset.ttl`** - DCAT Dataset, linked to the Catalog. Carries dynamic stats (record count, unique individuals, min/max age, temporal coverage) derived from the actual extracted data.
3. **`Distribution.ttl`** - DCAT Distribution describing the produced CSV.
4. **`CSVW.ttl`** - W3C CSV-on-the-Web schema describing the five columns. The `code` and `display` columns reference a SKOS concept scheme (`observation_codes` for Observation jobs, `question_codes` for Survey jobs). For Survey runs, additional per-question SKOS schemes record the answer-option value sets pulled from the Questionnaire definitions. All SKOS content lives in the same `CSVW.ttl` — there is no separate `Vocabularies.ttl`.

The bundle job produces only `bundle.json` (no CSV, no RDF). Each entry has a `request.method` of `PUT` (when the resource carries an `id`) or `POST`, so the file can be POSTed to another FHIR server to recreate the dataset.
