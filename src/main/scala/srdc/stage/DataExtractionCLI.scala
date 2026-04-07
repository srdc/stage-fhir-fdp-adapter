package srdc.stage

import io.onfhir.spark.{FhirApiSource, FhirPagination, FhirPaginationMethods, SparkOnFhir}
import org.apache.spark.sql.SparkSession
import srdc.stage.config.{AppConfig, CommandLineArgumentParser}
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.jobs.{FullExtraction, ObservationExtraction, SurveyExtraction}
import srdc.stage.rdf.{DatasetStats, MetadataUserInput}

/**
 * Factory object responsible for bootstrapping the application.
 *
 * It handles:
 * 1. CLI Argument Parsing (using scopt).
 * 2. Spark Session Initialization.
 * 3. FHIR Connector Setup.
 * 4. Multi-Job Orchestration (Two-Pass extraction and FDP state sharing).
 */
object DataExtractionCLI {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * The main entry point for the Healthy Aging Analytics Tool.
   *
   * @param args Command line arguments passed to the application.
   */
  def main(args: Array[String]): Unit = {
    CommandLineArgumentParser.parse(AppConfig.load(), args) match {
      case Some(config) => runJob(config)
      case _ => System.exit(1)
    }
  }

  /**
   * Sets up the Spark environment and executes the requested ETL jobs.
   *
   * @param config The fully populated application configuration object.
   */
  private def runJob(config: AppConfig): Unit = {
    val jobs = config.jobType.split(",").map(_.trim.toLowerCase)
    logger.info(s"Initiating extraction pipelines for ${jobs.length} module(s): ${jobs.mkString(", ").toUpperCase}")

    val staticMetadata = MetadataUserInput.load(config)

    implicit val spark: SparkSession = SparkSession.builder()
      .appName(s"healthy-aging-${config.jobType.replace(",", "-")}")
      .master("local[*]")
      .getOrCreate()

    val fhirApiSource =
      FhirApiSource(config.fhirServer, pagination = Some(FhirPagination(FhirPaginationMethods.CURSOR_BASED, "_searchafter")))
        .withNumOfRecordsPerPage(1000)

    logger.info("Initializing with FHIR server: {} ({})", config.fhirServer, config.fhirVersion)

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

    val extractions = jobs.flatMap { jobName =>
      logger.info(s"Extracting features from FHIR for module: $jobName")
      jobName match {
        case "survey" =>
          Some((SurveyExtraction, SurveyExtraction.extractDataAndStats(config, staticMetadata)))
        case "observation" =>
          Some((ObservationExtraction, ObservationExtraction.extractDataAndStats(config, staticMetadata)))
        case "full" =>
          Some((FullExtraction, FullExtraction.extractDataAndStats(config, staticMetadata)))
        case unknown =>
          logger.warn(s"Unknown module skipped: $unknown")
          None
      }
    }

    if (extractions.isEmpty) {
      logger.error("No valid modules to execute. Exiting.")
      spark.stop()
      System.exit(1)
    }

    val allJobStats = extractions.map(_._2._2).toSeq
    val globalStats = DatasetStats.mergeGlobal(allJobStats)

    logger.info("Proceeding to metadata generation and FDP validation...")
    var currentCatalogUri: Option[String] = None

    extractions.foreach { case (jobObject, (dataDf, jobStats, subFolder)) =>
      logger.info(s"--- Finalizing and Validating Module: $subFolder ---")

      val catalogUri = jobObject.exportResultsWithState(
        config,
        staticMetadata,
        dataDf,
        jobStats,
        globalStats,
        subFolder,
        currentCatalogUri
      )

      currentCatalogUri = Some(catalogUri)
    }

    spark.stop()
    System.exit(0)
  }
}