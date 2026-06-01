package srdc.stage.config

import scopt.OParser

import java.time.{Duration, LocalDate, OffsetDateTime, Period, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.util.Try

object CommandLineArgumentParser {

  /**
   * Internal staging case class. Collects the CLI inputs for time-window
   */
  private case class DateWindowInputs(
                                       explicitFrom: Option[String] = None,
                                       explicitTo: Option[String] = None,
                                       reference: Option[String] = None,
                                       window: Option[String] = None
                                     )

  def parse(baseConfig: AppConfig, args: Array[String]): Option[AppConfig] = {

    // Initialize the command-line argument parser builder for AppConfig
    val builder = OParser.builder[AppConfig]

    val dateInputs = scala.collection.mutable.Map(
      "from" -> Option.empty[String],
      "to" -> Option.empty[String],
      "reference" -> Option.empty[String],
      "window" -> Option.empty[String]
    )

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
          .text("Password for FDP authentication"),

        // Time-window filter (optional)
        opt[String]("date-from")
          .action { (x, c) => dateInputs("from") = Some(x.trim); c }
          .text("Filter resources whose date is >= this ISO date/datetime (inclusive). " +
            "Cannot be combined with --reference/--window."),

        opt[String]("date-to")
          .action { (x, c) => dateInputs("to") = Some(x.trim); c }
          .text("Filter resources whose date is <= this ISO date/datetime (inclusive). " +
            "Cannot be combined with --reference/--window."),

        opt[String]("reference")
          .action { (x, c) => dateInputs("reference") = Some(x.trim); c }
          .text("Anchor date for a centered window. Used together with --window. " +
            "Cannot be combined with --date-from/--date-to."),

        opt[String]("window")
          .action { (x, c) => dateInputs("window") = Some(x.trim); c }
          .text("Half-width around --reference, as an ISO-8601 duration (e.g. P30D, P6M, P1Y). " +
            "Resolved into [reference - window, reference + window].")
      )
    }

    OParser.parse(parser1, args, baseConfig).flatMap { cfg =>
      val inputs = DateWindowInputs(
        explicitFrom = dateInputs("from"),
        explicitTo = dateInputs("to"),
        reference = dateInputs("reference"),
        window = dateInputs("window")
      )
      resolveDateWindow(inputs) match {
        case Right((from, to)) => Some(cfg.copy(dateFrom = from, dateTo = to))
        case Left(message) =>
          Console.err.println(s"Error: $message")
          None
      }
    }
  }

  private def resolveDateWindow(in: DateWindowInputs): Either[String, (Option[String], Option[String])] = {
    val hasExplicit = in.explicitFrom.isDefined || in.explicitTo.isDefined
    val hasReference = in.reference.isDefined || in.window.isDefined

    if (hasExplicit && hasReference) {
      Left(
        "--date-from/--date-to cannot be combined with --reference/--window. " +
          "Use either an explicit range or a centered window, not both.")
    } else if (!hasExplicit && !hasReference) {
      Right((None, None))
    } else if (hasReference) {
      resolveReferenceWindow(in)
    } else {
      resolveExplicitRange(in)
    }
  }

  private def resolveReferenceWindow(in: DateWindowInputs): Either[String, (Option[String], Option[String])] = {
    (in.reference, in.window) match {
      case (None, _) => Left("--window requires --reference.")
      case (_, None) => Left("--reference requires --window.")
      case (Some(ref), Some(win)) =>
        parseIsoDateOrDateTime(ref)
          .toRight(s"--reference value '$ref' is not a valid ISO date or date-time.")
          .flatMap { refInstant =>
            parseIsoDuration(win)
              .toRight(
                s"--window value '$win' is not a valid ISO-8601 duration " +
                  s"(expected forms include P30D, P6M, P1Y, PT12H).")
              .map { winAmount =>
                val from = winAmount.subtractFrom(refInstant)
                val to = winAmount.addTo(refInstant)
                (Some(formatIso(from)), Some(formatIso(to)))
              }
          }
    }
  }

  private def resolveExplicitRange(in: DateWindowInputs): Either[String, (Option[String], Option[String])] = {
    val fromParsed = in.explicitFrom
      .map(v => parseIsoDateOrDateTime(v).toRight(s"--date-from value '$v' is not a valid ISO date or date-time."))
    val toParsed = in.explicitTo
      .map(v => parseIsoDateOrDateTime(v).toRight(s"--date-to value '$v' is not a valid ISO date or date-time."))

    val fromError = fromParsed.collectFirst { case Left(msg) => msg }
    val toError = toParsed.collectFirst { case Left(msg) => msg }

    (fromError, toError) match {
      case (Some(msg), _) => Left(msg)
      case (_, Some(msg)) => Left(msg)
      case _ =>
        val orderingError = for {
          fr <- fromParsed.flatMap(_.toOption)
          to <- toParsed.flatMap(_.toOption)
          if fr.isAfter(to)
        } yield s"--date-from (${in.explicitFrom.get}) must be on or before --date-to (${in.explicitTo.get})."

        orderingError match {
          case Some(msg) => Left(msg)
          case None      => Right((in.explicitFrom, in.explicitTo))
        }
    }
  }

  /**
   * Parse `YYYY-MM-DD` or `YYYY-MM-DDT00:00:00Z` into a UTC OffsetDateTime.
   */
  private def parseIsoDateOrDateTime(s: String): Option[OffsetDateTime] = {
    Try(OffsetDateTime.parse(s)).toOption
      .orElse(Try(LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime).toOption)
  }

  /**
   * Parse an ISO-8601 duration
   */
  private trait DurationLike {
    def addTo(dt: OffsetDateTime): OffsetDateTime
    def subtractFrom(dt: OffsetDateTime): OffsetDateTime
  }

  private def parseIsoDuration(s: String): Option[DurationLike] = {
    Try(Period.parse(s)).toOption.map { p =>
      new DurationLike {
        def addTo(dt: OffsetDateTime): OffsetDateTime = dt.plus(p)
        def subtractFrom(dt: OffsetDateTime): OffsetDateTime = dt.minus(p)
      }
    }.orElse(Try(Duration.parse(s)).toOption.map { d =>
      new DurationLike {
        def addTo(dt: OffsetDateTime): OffsetDateTime = dt.plus(d)
        def subtractFrom(dt: OffsetDateTime): OffsetDateTime = dt.minus(d)
      }
    })
  }

  /**
   * Emit a date-only form when the time is exactly midnight UTC
   */
  private def formatIso(dt: OffsetDateTime): String = {
    val isMidnightUtc =
      dt.getHour == 0 && dt.getMinute == 0 && dt.getSecond == 0 &&
        dt.getNano == 0 && dt.getOffset == ZoneOffset.UTC
    if (isMidnightUtc) dt.toLocalDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
    else dt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
  }
}
