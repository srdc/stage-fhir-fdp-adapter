import io.onfhir.spark.SparkOnFhir
import org.apache.spark.sql.SparkSession
import scopt.OParser

/**
 * Factory object responsible for bootstrapping the application.
 *
 * It handles:
 * 1. CLI Argument Parsing (using scopt).
 * 2. Spark Session Initialization.
 * 3. FHIR Connector Setup.
 * 4. Job Dispatching (routing to specific ETL pipelines like SurveyExtraction).
 */
object StageDatasetFactory {

  /**
   * The main entry point for the Healthy Aging Analytics Tool.
   *
   * @param args Command line arguments passed to the application.
   */
  def main(args: Array[String]): Unit = {
    // Load baseline defaults from application.conf
    val baseConfig = AppConfig.load()

    // Initialize the command-line argument parser builder for AppConfig
    val builder = OParser.builder[AppConfig]

    // Define the CLI parser structure and available flags
    val parser1 = {
      import builder._
      OParser.sequence(
        programName("healthy-aging-cli"),
        head("Healthy Aging Analytics Tool", "1.5"),

        // Connection Settings
        opt[String]('s', "server")
          .action((x, c) => c.copy(fhirServer = x))
          .text("Base URL of the source FHIR server"),

        opt[String]('o', "output")
          .action((x, c) => c.copy(outputDir = x))
          .text("Directory to save output files (CSV, TTL)"),

        // Job Control
        opt[String]('j', "job")
          .action((x, c) => c.copy(jobType = x))
          .text("Job type to run: 'survey' (Healthy Aging) or 'enrich'"),

        opt[String]('f', "format")
          .action((x, c) => c.copy(format = x))
          .text("Output format: 'csv' or 'parquet'"),

        // Configuration Loading Strategy
        opt[String]("excelPath")
          .action((x, c) => c.copy(excelPath = x))
          .text("Path to an optional Excel configuration file"),

        opt[String]('m', "runMode")
          .action((x, c) => c.copy(runMode = x))
          .text("Configuration mode: 'json' (load file), 'browser' (interactive), or 'excel'"),

        // FAIR Data Point (FDP) Automation Settings
        opt[String]("fdpUrl")
          .action((x, c) => c.copy(fdpUrl = Some(x)))
          .text("Target FAIR Data Point URL (e.g. http://localhost:8081)"),

        opt[String]("fdpEmail")
          .action((x, c) => c.copy(fdpEmail = Some(x)))
          .text("Email address for FDP authentication"),

        opt[String]("fdpPassword")
          .action((x, c) => c.copy(fdpPassword = Some(x)))
          .text("Password for FDP authentication")
      )
    }

    // execute the parsing using baseConfig; if successful run the job, otherwise exit with error code
    OParser.parse(parser1, args, baseConfig) match {
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
    println(s"--- Starting Job: ${config.jobType.toUpperCase} ---")

    // Load static metadata
    val staticMetadata = ConfigLoader.load(config)

    // Initialize the Spark Session (using local[*] for standalone execution)
    implicit val spark: SparkSession = SparkSession.builder()
      .appName(s"healthy-aging-${config.jobType}")
      .master("local[*]")
      .getOrCreate()

    // Initialize the FHIR Connector for R4 standard
    implicit val sparkOnFhir: SparkOnFhir =
      SparkOnFhir("R4").fromFhirServer(config.fhirServer)

    // Route execution to the specific extraction pipeline, passing both configs
    config.jobType match {
      case "survey" => SurveyExtraction.run(config, staticMetadata)
      case _ => println(s"Error: Unknown job type '${config.jobType}'")
    }

    // Clean up resources
    spark.stop()
  }
}