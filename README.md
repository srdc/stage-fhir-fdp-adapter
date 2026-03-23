# Spark-FHIR Healthy Aging Dataset Extractor

This project leverages Apache Spark and the `spark-on-fhir` toolkit to flatten complex clinical data (Observations, QuestionnaireResponses) into analytics-ready CSVs. Simultaneously, it generates rich metadata (DCAT, CSVW, SKOS) and publishes it to a FAIR Data Point (FDP).

---

## Project Overview

The pipeline performs the following key operations:

1. Extracts raw FHIR resources (`Patient`, `Observation`, `QuestionnaireResponse`, `Questionnaire`).
2. Resolves terminology by joining patient answers with full Questionnaire definitions.
3. Transforms data into a wide-format patient profile with one row per patient.
4. Generates FAIR metadata:
* DCAT: Catalog, Dataset, and Distribution descriptions.
* CSVW: Schema definitions for the output data.
* SKOS: Concept schemes mapping survey codes to human-readable displays.


5. **Publishes metadata directly to a FAIR Data Point (FDP) and/or saves locally as Turtle (`.ttl`) files.

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

### 2. Configure Run Mode

The application supports different ways to load these configurations:

* **`browser`** (Default): Loads a web form to fill in the config data in runtime..
* **`json`**: Automatically loads a standard `config.json` from the classpath/working dir.
* **`excel`**: Automatically loads a standard `config.xlsx` from the classpath/working dir.

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
| `--job` | `survey` | The ETL pipeline to run. Supports: `survey`, `observation` or `full` extraction (Can be extended for cohorts) |
| `--format` | `csv` | The output format for the patient data (`csv` or `parquet`). |
| `--runMode` | `browser` | How the app loads metadata configuration (`json`, `excel`, or `browser`). |
| `--fhirUrl` | *(from conf)* | Override the source FHIR server URL. |
| `--fdpUrl` | *(from conf)* | Override the target FAIR Data Point URL. |
| `--fdpEmail` | *(from conf)* | Override the FDP authentication email. |
| `--fdpPassword`| *(from conf)* | Override the FDP authentication password. |
| `--outputDir` | *(from conf)* | Override the output directory for generated files. |
| `--jsonPath` | *(from conf)* | Override the path to the `config.json` file. |
| `--excelPath` | *(from conf)* | Override the path to the `config.xlsx` file. |

---

## Output

After a successful run, the `outputDir` will contain:

1. **`patient_profiles/`**: A folder containing the extracted data in CSV format (one row per patient, columns for every Observation and Survey Question).
2. **`Catalog.ttl`**: RDF description of the Data Catalog.
3. **`Dataset.ttl`**: RDF description of the Dataset, linked to the Catalog.
4. **`Distribution.ttl`**: RDF description of the CSV file, linked to the Dataset.
5. **`CSVW.ttl`**: W3C CSV-on-the-Web schema describing columns and data types.
6. **`Vocabularies.ttl`**: SKOS concepts defining the questions and answer options found in the survey.
