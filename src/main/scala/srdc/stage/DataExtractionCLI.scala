package srdc.stage

import io.onfhir.spark.{FhirApiSource, FhirPagination, FhirPaginationMethods, SparkOnFhir}
import org.apache.spark.sql.SparkSession
import srdc.stage.config.{AppConfig, CommandLineArgumentParser}
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.jobs.{BaseExtraction, BundleExtraction, FullExtraction, ObservationExtraction, SurveyExtraction}
import srdc.stage.rdf.{CatalogMetadataUserInput, DatasetStats, MetadataUserInput}

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

    val (bundleJobs, sparkJobs) = jobs.partition(_ == "bundle")
    if (bundleJobs.nonEmpty) {
      if (config.fhirServer == null || config.fhirServer.trim.isEmpty) {
        logger.error("The 'bundle' job requires a FHIR server. Set --server or fhirServer in application.conf.")
        System.exit(1)
      }
      bundleJobs.foreach { _ =>
        val path = BundleExtraction.run(config)
        logger.info(s"--- Bundle extraction complete: $path ---")
      }
      if (sparkJobs.isEmpty) System.exit(0)
    }

    // No spark initialization when no FHIR server is provided
    if (config.fhirServer == null || config.fhirServer.trim.isEmpty) {
      runJobsWithoutFhir(config, sparkJobs)
      System.exit(0)
    }

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

    var sharedCatalog: Option[CatalogMetadataUserInput] = None
    val extractions = sparkJobs.flatMap { jobName =>
      logger.info(s"Extracting features from FHIR for module: $jobName")
      val jobMetadata = MetadataUserInput.load(config, jobName, sharedCatalog)
      if (sharedCatalog.isEmpty) sharedCatalog = Some(jobMetadata.catalog)
      jobName match {
        case "survey" =>
          Some((SurveyExtraction, SurveyExtraction.extractDataAndStats(config, jobMetadata), jobMetadata))
        case "observation" =>
          Some((ObservationExtraction, ObservationExtraction.extractDataAndStats(config, jobMetadata), jobMetadata))
        case "full" =>
          Some((FullExtraction, FullExtraction.extractDataAndStats(config, jobMetadata), jobMetadata))
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

    extractions.foreach { case (jobObject, (dataDf, jobStats, subFolder), jobMetadata) =>
      logger.info(s"--- Finalizing and Validating Module: $subFolder ---")

      val catalogUri = jobObject.exportResultsWithState(
        config,
        jobMetadata,
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

  /**
   * Build RDF contents only form the resource excel when no FHIR server is present.
   *
   * @param config Application configuration
   * @param jobs The parsed list of job names
   */
  private def runJobsWithoutFhir(config: AppConfig, jobs: Array[String]): Unit = {
    logger.info("No FHIR server configured - running metadata-only pipeline (CSVW will be built from the Excel Data Dictionary).")

    var sharedCatalog: Option[CatalogMetadataUserInput] = None
    var currentCatalogUri: Option[String] = None

    jobs.foreach { jobName =>
      logger.info(s"--- Finalizing and Validating Module (no-FHIR): $jobName ---")
      val jobMetadata = MetadataUserInput.load(config, jobName, sharedCatalog)
      if (sharedCatalog.isEmpty) sharedCatalog = Some(jobMetadata.catalog)

      val jobObject: BaseExtraction = jobName match {
        case "survey"      => SurveyExtraction
        case "observation" => ObservationExtraction
        case "full"        => FullExtraction
        case unknown =>
          logger.warn(s"Unknown module skipped: $unknown")
          null
      }
      if (jobObject != null) {
        val catalogUri = jobObject.exportResultsNoFhir(config, jobMetadata, jobName, currentCatalogUri)
        currentCatalogUri = Some(catalogUri)
      }
    }
  }
}