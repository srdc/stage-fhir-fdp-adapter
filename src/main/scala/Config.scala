/**
 * Configuration case class holding command-line arguments, static metadata defaults,
 * and dynamic form fields.
 *
 * This class serves as the single source of truth for the application's runtime behavior
 * and metadata generation logic. It consolidates inputs from:
 * 1. CLI arguments (e.g., --job, --format).
 * 2. Static defaults (e.g., license URLs, legislative links).
 * 3. User inputs from the Browser Form, JSON, or Excel.
 */
case class Config(
                   // Runtime Config
                   // Basic settings for connecting to source data and defining output behavior
                   fhirServer: String = "http://localhost:8080/fhir",
                   outputDir: String = "./out",
                   jobType: String = "survey",
                   format: String = "csv",
                   runMode: String = "json", // Controls how config is loaded: "browser", "excel" or "json"
                   jsonPath: Option[String] = Option("src/main/resources/config.json"),
                   htmlTemplatePath: String = "src/main/resources/form.html",
                   excelPath: Option[String] = Option("src/main/resources/config.xlsx"),

                   // FDP Connection Config
                   // Required for automating metadata publication to a FAIR Data Point
                   fdpUrl: String = "",
                   fdpEmail: String = "",
                   fdpPassword: String = "",

                   // Catalog Metadata
                   // Describes the high-level collection (Catalog) that holds the datasets
                   catalogIsExisting: Boolean = false,
                   catalogUri: String = "",
                   catalogTitle: String = "STAGE Data Catalog",
                   catalogDescription: String = "FAIRful metadata catalog...",
                   catalogApplicableLegislation: String = "http://eur-lex.europa.eu/eli/reg/2025/327/oj",
                   catalogSpatial: String = "http://publications.europa.eu/resource/authority/country/FIN",
                   catalogStartDate: String = "1986-01-01",
                   catalogEndDate: String = "2026-01-01",

                   // Dataset Metadata
                   // Describes the specific dataset being extracted (e.g., "Core Cohort")
                   datasetTitle: String = "Core",
                   datasetDescription: String = "Dataset description...",
                   datasetIdentifier: String = "http://oulu.fi/NFBC1986/core",
                   datasetTheme: String = "http://publications.europa.eu/resource/authority/data-theme/HEAL",
                   datasetKeywords: Seq[String] = Seq("finland", "birth cohort", "prenatal"),
                   datasetSpatial: String = "http://publications.europa.eu/resource/authority/country/FIN",
                   datasetPopulationCoverage: String = "Prenatal, Infant, Child...",
                   datasetTemporalStart: String = "1968-01-01",
                   datasetTemporalEnd: String = "2025-01-01",
                   datasetQualityAnnotation: String = "",
                   datasetLegislation: String = "http://eur-lex.europa.eu/eli/reg/2025/327/oj",
                   datasetAccessRights: String = "http://publications.europa.eu/resource/authority/access-right/PUBLIC",
                   datasetType: String = "http://publications.europa.eu/resource/authority/dataset-type/RELEASE",
                   datasetProvenance: String = "Original data collected via FHIR QuestionnaireResponses.",
                   datasetFrequency: String = "http://publications.europa.eu/resource/authority/frequency/BIMONTHLY",
                   datasetVersion: String = "1.0.0",

                   // Static Metadata: General
                   metadataVersion: String = "1.0.0",
                   metadataVersionNotes: String = "PLACEHOLDER_VERSION_NOTES",
                   metadataLanguage: String = "Finnish",

                   // Static Metadata: Dates & Resolution
                   modificationDate: String = "2025-10-01",
                   issuedDate: String = "2016-01-01",
                   temporalResolution: String = "P1Y",
                   temporalCoverageStart: String = "1970-01-01",
                   temporalCoverageEnd: String = "2025-01-01",
                   spatialResolution: String = "PLACEHOLDER_SPATIAL_RES",

                   // Static Metadata: Categorization
                   healthCategory: String = "http://13.81.34.152:1101/resource/authority/healthcategories/EHRS",
                   healthTheme: String = "http://13.81.34.152:1101/resource/authority/health-theme/LIFECOURSE_HEALTH",
                   otherIdentifier: String = "http://placeholder.url/other-id",
                   wasGeneratedBy: String = "http://placeholder.url/generator-activity",

                   // Static Metadata: Legal & Compliance
                   conformsTo: String = "https://www.wikidata.org/entity/Q19597236",
                   legalBasis: String = "PLACEHOLDER_LEGAL_BASIS",
                   purpose: String = "PLACEHOLDER_PURPOSE",
                   retentionStart: String = "2015-01-01",
                   retentionEnd: String = "2030-01-01",
                   personalData: Seq[String] = Seq("Gender", "Age", "BirthDate"),

                   // Static Metadata: Contact & Provenance
                   contactPage: String = "",
                   contactEmail: String = "john.doe@oulu.fi",
                   contactName: String = "John Doe",
                   contactUrl: String = "https://example.com",

                   creatorName: String = "PLACEHOLDER_CREATOR_NAME",
                   creatorEmail: String = "creator@example.com",

                   attributionName: String = "PLACEHOLDER_SPONSOR_NAME",
                   attributionRole: String = "sponsor",

                   // Health Data Access Body (HDAB)
                   // The organization responsible for granting access to the data
                   hdabName: String = "OULU",
                   hdabType: String = "http://publications.europa.eu/resource/authority/corporate-body-type/EUN_BOD",
                   hdabContactPage: String = "https://oulu.fi",
                   hdabContactEmail: String = "access@oulu.fi",
                   hdabNote: String = "",
                   hdabTrusted: Boolean = true,

                   // Static Metadata: Publisher
                   // The organization that publishes and maintains the metadata catalog
                   publisherName: String = "OULU",
                   publisherType: String = "European University Institute",
                   publisherPage: String = "https://oulu.fi",
                   publisherEmail: String = "info@oulu.fi",
                   publisherNote: String = "PLACEHOLDER_PUBLISHER_NOTE",
                   publisherTrusted: Boolean = true,
                   publisherUrl: String = "https://oulu.fi",

                   // Static Metadata: Documentation
                   landingPage: String = "https://example.com/landing-page",
                   documentationUrl: String = "https://example.com/documentation",
                   frequency: String = "http://publications.europa.eu/resource/authority/frequency/BIMONTHLY",
                   alternativeName: String = "PLACEHOLDER_ALT_NAME",
                   qualityAnnotation: String = "PLACEHOLDER_QUALITY_NOTE",

                   // Metadata: Distributions
                   // Details about the actual downloadable files (CSVs)
                   distributionAccessUrl: String = "https://oulu.fi/data",
                   distributionLegislation: String = "http://eur-lex.europa.eu/eli/reg/2025/327/oj",
                   distributionFormat: String = "http://publications.europa.eu/resource/authority/file-type/CSV",
                   distributionDescription: String = "Distribution description",
                   distributionLicense: String = "http://creativecommons.org/licenses/by/4.0/",

                   // Sample Data
                   // Details about a small sample distribution for preview purposes
                   sampleAccessUrl: String = "https://oulu.fi/nfbc1986/sample.csv",
                   sampleLegislation: String = "http://eur-lex.europa.eu/eli/reg/2025/327/oj",
                   sampleTitle: String = "NFBC1986 Samples",
                   sampleFormat: String = "http://publications.europa.eu/resource/authority/file-type/CSV",

                   // Analytics
                   analyticsUrl: String = "https://example.com/analytics.pdf",
                   analyticsFormat: String = "PDF",
                   analyticsTitle: String = "PLACEHOLDER_ANALYTICS_TITLE",

                   // Static Metadata: Coding Systems & File
                   codingSystems: Seq[String] = Seq("http://loinc.org", "http://snomed.info/sct"),
                   csvwFileName: String = "patient_profiles.csv"
                 )