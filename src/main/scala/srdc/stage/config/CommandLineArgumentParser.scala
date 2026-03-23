package srdc.stage.config

import scopt.OParser

object CommandLineArgumentParser {
  def parse(baseConfig: AppConfig, args: Array[String]) = {

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

        opt[String]('v', "fhirVersion")
          .action((x, c) => c.copy(fhirVersion = x))
          .text("FHIR standard version (R4, R4B, R5)"),

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

        opt[String]("jsonPath")
          .action((x, c) => c.copy(jsonPath = x))
          .text("Path to an optional JSON configuration file"),

        opt[String]("token")
          .action((x, c) => c.copy(token = Some(x)))
          .text("Path to an optional authorization token"),

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
    OParser.parse(parser1, args, baseConfig)
  }
}
