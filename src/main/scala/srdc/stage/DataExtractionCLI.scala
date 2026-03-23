package srdc.stage

import io.onfhir.spark.{FhirApiSource, FhirPagination, FhirPaginationMethods, SparkOnFhir}
import org.apache.spark.sql.SparkSession
import scopt.OParser
import srdc.stage.config.{AppConfig, CommandLineArgumentParser}
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.jobs.{FullExtraction, ObservationExtraction, SurveyExtraction}
import srdc.stage.rdf.MetadataUserInput

/**
 * Factory object responsible for bootstrapping the application.
 *
 * It handles:
 * 1. CLI Argument Parsing (using scopt).
 * 2. Spark Session Initialization.
 * 3. FHIR Connector Setup.
 * 4. Job Dispatching (routing to specific ETL pipelines like SurveyExtraction).
 */
object DataExtractionCLI {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * The main entry point for the Healthy Aging Analytics Tool.
   *
   * @param args Command line arguments passed to the application.
   */
  def main(args: Array[String]): Unit = {
    // parse the args and override base AppConfig; if successful run the job, otherwise exit with error code
    CommandLineArgumentParser.parse(AppConfig.load(), args) match {
      case Some(config) => runJob(config)
      case _ => System.exit(1)
    }
  }

  /**
   * Sets up the Spark environment and executes the requested ETL job.
   *
   * @param config The fully populated application configuration object.
   */
  private def runJob(config: AppConfig): Unit = {
    logger.info("Starting Job: {}", config.jobType.toUpperCase)

    // Load static metadata
    val staticMetadata = MetadataUserInput.load(config)

    // Initialize the Spark Session (using local[*] for standalone execution)
    implicit val spark: SparkSession = SparkSession.builder()
      .appName(s"healthy-aging-${config.jobType}")
      .master("local[*]")
      .getOrCreate()

    val fhirApiSource =
      FhirApiSource(config.fhirServer, pagination = Some(FhirPagination(FhirPaginationMethods.CURSOR_BASED, "_searchafter")))
        .withNumOfRecordsPerPage(1000)

    logger.info("Initializing with FHIR server: {} ({})", config.fhirServer, config.fhirVersion)

    // Initialize the FHIR Connector with configured standard version and authentication
    implicit val sparkOnFhir: SparkOnFhir =
      SparkOnFhir(config.fhirVersion).fromFhirServer(
        if (config.authEnabled) {
          if (config.token.nonEmpty) {
            logger.info("Initializing fixed token authentication...")
            fhirApiSource.withFixedBearerTokenAuthentication(config.token.get)
          } else if (config.tokenEndpoint.nonEmpty) {
            logger.info("Initializing bearer token authentication for client {}...", config.clientId.get)
            fhirApiSource.withBearerTokenAuthentication(
              config.tokenEndpoint.get,
              config.clientId.getOrElse(throw new Error("Token endpoint should be configured.")),
              config.clientSecret.getOrElse(throw new Error("Token endpoint should be configured.")),
              config.scope.getOrElse(List.empty):_*
            )
          } else {
            throw new Error("Either token or tokenEndpoint (with clientId, clientSecret) should be provided.")
          }
        } else fhirApiSource
      )

    // Route execution to the specific extraction pipeline, passing both configs
    config.jobType.split(",") foreach {
      case "survey" => SurveyExtraction.run(config, staticMetadata)
      case "observation" => ObservationExtraction.run(config, staticMetadata)
      case "full" => FullExtraction.run(config, staticMetadata)
      case _ => logger.error(s"Unknown job type '${config.jobType}'")
    }

    // Clean up resources
    spark.stop()
    System.exit(0)
  }
}