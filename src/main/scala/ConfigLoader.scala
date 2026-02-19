import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress
import java.awt.Desktop
import java.net.URI
import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import java.net.URLDecoder
import scala.io.Source
import org.json4s._
import org.json4s.jackson.JsonMethods._

/**
 * Utility object for loading the application configuration.
 * Supports three modes: JSON file, Excel file, or an interactive Browser-based form.
 */
object ConfigLoader {

  val SUCCESS_HTML = """<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>Success</title></head><body><h1>Configuration Received!</h1><p>You can close this tab and return to the terminal.</p><script>setTimeout(() => window.close(), 2000)</script></body></html>"""

  /**
   * Main entry point for loading configuration.
   * Dispatches to the specific loading strategy based on the 'runMode' parameter.
   *
   * @param baseConfig The initial configuration (usually from CLI args).
   * @return A fully populated Config object.
   */
  def load(baseConfig: Config): Config = {
    baseConfig.runMode match {
      case "json" => loadFromJson(baseConfig)
      case "excel" => loadFromExcel(baseConfig)
      case _      => loadFromBrowser(baseConfig)
    }
  }

  /**
   * Loads configuration directly from a JSON file.
   *
   * @param config Configuration containing the JSON path.
   * @return Config object parsed from JSON.
   */
  private def loadFromJson(config: Config): Config = {
    implicit val formats: Formats = DefaultFormats
    val path = config.jsonPath.getOrElse(throw new IllegalArgumentException("JSON mode selected but no jsonPath provided."))
    println(s"Loading configuration from JSON: $path")
    val jsonContent = Source.fromFile(path).mkString
    parse(jsonContent).extract[Config]
  }

  /**
   * Loads configuration from an Excel file using the ExcelConfigParser.
   *
   * @param config Configuration containing the Excel path.
   * @return Config object parsed from Excel.
   */
  private def loadFromExcel(config: Config): Config = {
    val path = config.excelPath.getOrElse(
      throw new IllegalArgumentException("Excel mode selected but no excelPath provided (--excelPath)")
    )
    ExcelConfigParser.parse(path, config)
  }

  /**
   * Launches a local HTTP server to serve an HTML form.
   * Allows the user to interactively edit the configuration in their browser.
   *
   * @param defaultConfig Default configuration values to pre-fill the form.
   * @return The updated Config object submitted by the user.
   */
  private def loadFromBrowser(defaultConfig: Config): Config = {
    val templateFile = new File(defaultConfig.htmlTemplatePath)
    if (!templateFile.exists()) {
      throw new java.io.FileNotFoundException(s"Could not find HTML template at: ${templateFile.getAbsolutePath}")
    }
    val htmlTemplate = Source.fromFile(templateFile, "UTF-8").mkString

    // Setup local server on port 9999
    val port = 9999
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    var capturedConfig: Option[Config] = None
    val lock = new Object()

    println(s"Starting configuration server at http://localhost:$port")

    // Handler for serving the HTML form (GET request)
    server.createContext("/", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        if (exchange.getRequestMethod == "GET") {
          val response = populateForm(htmlTemplate, defaultConfig)
          val responseBytes = response.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.set("Content-Type", "text/html; charset=UTF-8")
          exchange.sendResponseHeaders(200, responseBytes.length)
          val os = exchange.getResponseBody
          os.write(responseBytes)
          os.close()
        }
      }
    })

    // Handler for processing the form submission (POST request)
    server.createContext("/form", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        if (exchange.getRequestMethod == "POST") {
          // Read the request body
          val is = exchange.getRequestBody
          val os = new ByteArrayOutputStream()
          val buffer = new Array[Byte](1024)
          var len = is.read(buffer)
          while (len != -1) { os.write(buffer, 0, len); len = is.read(buffer) }
          val body = new String(os.toByteArray, StandardCharsets.UTF_8)

          // Parse form data and update config
          val formData = parseFormData(body)
          capturedConfig = Some(mapFormToConfig(formData, defaultConfig))

          // Send success response
          exchange.sendResponseHeaders(200, SUCCESS_HTML.length)
          val out = exchange.getResponseBody
          out.write(SUCCESS_HTML.getBytes)
          out.close()

          // Notify the main thread that config is captured
          lock.synchronized { lock.notify() }
        }
      }
    })

    server.setExecutor(null)
    server.start()

    // Open browser automatically if supported
    if (Desktop.isDesktopSupported) {
      Desktop.getDesktop.browse(new URI(s"http://localhost:$port"))
    } else {
      println(s"Please open http://localhost:$port in your browser.")
    }

    // Wait until configuration is captured
    lock.synchronized {
      while (capturedConfig.isEmpty) { lock.wait() }
    }

    server.stop(0)
    println("Configuration received. Server stopped.")
    capturedConfig.get
  }

  /**
   * Injects default configuration values into the HTML template placeholders.
   *
   * @param html The raw HTML template string.
   * @param c    The current configuration object.
   * @return The HTML string with all placeholders replaced by values.
   */
  private def populateForm(html: String, c: Config): String = {
    // Helper to generate HTML select options
    def opt(value: String, label: String) = s"""<option value="$value">$label</option>"""

    // Define Option Lists
    val themes = opt("http://publications.europa.eu/resource/authority/data-theme/HEAL", "Health") + opt("http://publications.europa.eu/resource/authority/data-theme/SOCI", "Social")
    val countries = opt("http://publications.europa.eu/resource/authority/country/FIN", "Finland") + opt("http://publications.europa.eu/resource/authority/country/USA", "USA")
    val corpTypes = opt("http://publications.europa.eu/resource/authority/corporate-body-type/EUN_BOD", "EU Body") + opt("http://publications.europa.eu/resource/authority/corporate-body-type/RES_INST", "Research Institute")
    val rights = opt("http://publications.europa.eu/resource/authority/access-right/PUBLIC", "Public") + opt("http://publications.europa.eu/resource/authority/access-right/RESTRICTED", "Restricted")
    val types = opt("http://publications.europa.eu/resource/authority/dataset-type/RELEASE", "Release")
    val fileTypes = opt("http://publications.europa.eu/resource/authority/file-type/CSV", "CSV") + opt("http://publications.europa.eu/resource/authority/file-type/PDF", "PDF")
    val healthCats = opt("http://13.81.34.152:1101/resource/authority/healthcategories/EHRS", "EHRs")
    val healthThemes = opt("http://13.81.34.152:1101/resource/authority/health-theme/LIFECOURSE_HEALTH", "Lifecourse Health")
    val frequencies = opt("http://publications.europa.eu/resource/authority/frequency/BIMONTHLY", "Bimonthly") + opt("http://publications.europa.eu/resource/authority/frequency/ANNUAL", "Annual")

    html
      // Technical & FDP
      .replace("{{fdp-url}}", c.fdpUrl)
      .replace("{{fdp-email}}", c.fdpEmail)
      .replace("{{fdp-password}}", c.fdpPassword)
      .replace("{{output-dir}}", c.outputDir)

      // Catalog metadata
      .replace("{{catalog-uri}}", c.catalogUri)
      .replace("{{catalog-title}}", c.catalogTitle)
      .replace("{{catalog-description}}", c.catalogDescription)
      .replace("{{catalog-applicable-legislation}}", c.catalogApplicableLegislation)
      .replace("{{catalog-start-date}}", c.catalogStartDate)
      .replace("{{catalog-end-date}}", c.catalogEndDate)

      // Dataset metadata
      .replace("{{title}}", c.datasetTitle)
      .replace("{{dataset-version}}", c.datasetVersion)
      .replace("{{identifier}}", c.datasetIdentifier)
      .replace("{{description}}", c.datasetDescription)
      .replace("{{dataset-quality-annotation}}", c.datasetQualityAnnotation)
      .replace("{{provenance}}", c.datasetProvenance)
      .replace("{{keyword}}", c.datasetKeywords.mkString(","))
      .replace("{{dataset-temporal-start}}", c.datasetTemporalStart)
      .replace("{{dataset-temporal-end}}", c.datasetTemporalEnd)
      .replace("{{population-coverage}}", c.datasetPopulationCoverage)
      .replace("{{contact-page}}", c.contactPage)
      .replace("{{contact-email}}", c.contactEmail)
      .replace("{{applicable-legislation}}", c.datasetLegislation)

      // Publisher (Data Holder)
      .replace("{{publisher-name}}", c.publisherName)
      .replace("{{publisher-page}}", c.publisherPage)
      .replace("{{publisher-email}}", c.publisherEmail)
      .replace("{{publisherTrustedChecked}}", if(c.publisherTrusted) "checked" else "")

      // Health Data Access Body (HDAB)
      .replace("{{hdab-name}}", c.hdabName)
      .replace("{{hdab-contact-page}}", c.hdabContactPage)
      .replace("{{hdab-contact-email}}", c.hdabContactEmail)
      .replace("{{hdab-note}}", c.hdabNote)
      .replace("{{hdabTrustedChecked}}", if(c.hdabTrusted) "checked" else "")

      // Distribution metadata
      .replace("{{distribution-access-url}}", c.distributionAccessUrl)
      .replace("{{distribution-applicable-legislation}}", c.distributionLegislation)
      .replace("{{distribution-license}}", c.distributionLicense)
      .replace("{{distribution-description}}", c.distributionDescription)

      // Sample Distribution metadata
      .replace("{{sample-access-url}}", c.sampleAccessUrl)
      .replace("{{sample-title}}", c.sampleTitle)
      .replace("{{sample-legislation}}", c.sampleLegislation)

      // Dropdown Options
      .replace("{{themeOptions}}", themes)
      .replace("{{countryOptions}}", countries)
      .replace("{{hdabTypeOptions}}", corpTypes) // Reused for Publisher Type
      .replace("{{accessRightsOptions}}", rights)
      .replace("{{typeOptions}}", types)
      .replace("{{healthCategoryOptions}}", healthCats)
      .replace("{{healthThemeOptions}}", healthThemes)
      .replace("{{fileTypeOptions}}", fileTypes)
      .replace("{{frequencyOptions}}", frequencies)

      // Cleanup remaining placeholders
      .replaceAll("\\{\\{.*?\\}\\}", "")
  }

  /**
   * Parses the raw x-www-form-urlencoded body string into a Map.
   *
   * @param body Raw body string from the POST request.
   * @return A map of field names to decoded values.
   */
  private def parseFormData(body: String): Map[String, String] = {
    body.split("&").map { pair =>
      val parts = pair.split("=")
      if (parts.length == 2) {
        val key = URLDecoder.decode(parts(0), StandardCharsets.UTF_8.name())
        val value = URLDecoder.decode(parts(1), StandardCharsets.UTF_8.name())
        (key, value)
      } else { (parts(0), "") }
    }.toMap
  }

  /**
   * Maps the raw form data Map to a new Config object.
   * Overrides default values with user-provided inputs where available.
   *
   * @param data     Map containing form field names and values.
   * @param defaults The default configuration to fallback on.
   * @return A new Config instance reflecting the form data.
   */
  private def mapFormToConfig(data: Map[String, String], defaults: Config): Config = {
    defaults.copy(
      // Technical / FDP
      fdpUrl = data.getOrElse("fdpUrl", defaults.fdpUrl),
      fdpEmail = data.getOrElse("fdpEmail", defaults.fdpEmail),
      fdpPassword = data.getOrElse("fdpPassword", defaults.fdpPassword),
      outputDir = data.getOrElse("outputDir", defaults.outputDir),

      // Catalog metadata
      catalogIsExisting = data.contains("existingCatalog"),
      catalogUri = data.getOrElse("catalogUri", defaults.catalogUri),
      catalogTitle = data.getOrElse("catalogTitle", defaults.catalogTitle),
      catalogDescription = data.getOrElse("catalogDescription", defaults.catalogDescription),
      catalogApplicableLegislation = data.getOrElse("catalogApplicableLegislation", defaults.catalogApplicableLegislation),
      catalogSpatial = data.getOrElse("catalogSpatial", defaults.catalogSpatial),
      catalogStartDate = data.getOrElse("catalogStartDate", defaults.catalogStartDate),
      catalogEndDate = data.getOrElse("catalogEndDate", defaults.catalogEndDate),

      // Dataset metadata
      datasetTitle = data.getOrElse("title", defaults.datasetTitle),
      datasetDescription = data.getOrElse("description", defaults.datasetDescription),
      datasetVersion = data.getOrElse("datasetVersion", defaults.datasetVersion),
      datasetIdentifier = data.getOrElse("identifier", defaults.datasetIdentifier),
      datasetQualityAnnotation = data.getOrElse("datasetQualityAnnotation", defaults.datasetQualityAnnotation),
      datasetTheme = data.getOrElse("theme", defaults.datasetTheme),
      datasetProvenance = data.getOrElse("provenance", defaults.datasetProvenance),
      datasetKeywords = data.get("keyword").map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq).getOrElse(defaults.datasetKeywords),
      datasetSpatial = data.getOrElse("datasetSpatial", defaults.datasetSpatial),
      datasetFrequency = data.getOrElse("datasetFrequency", defaults.datasetFrequency),
      datasetTemporalStart = data.getOrElse("datasetTemporalStart", defaults.datasetTemporalStart),
      datasetTemporalEnd = data.getOrElse("datasetTemporalEnd", defaults.datasetTemporalEnd),
      datasetPopulationCoverage = data.getOrElse("populationCoverage", defaults.datasetPopulationCoverage),
      datasetLegislation = data.getOrElse("applicableLegislation", defaults.datasetLegislation),
      datasetAccessRights = data.getOrElse("accessRights", defaults.datasetAccessRights),
      datasetType = data.getOrElse("type", defaults.datasetType),

      // Health Specifics
      healthCategory = data.getOrElse("healthCategory", defaults.healthCategory),
      healthTheme = data.getOrElse("healthTheme", defaults.healthTheme),

      // Contact Points
      contactPage = data.getOrElse("contactPage", defaults.contactPage),
      contactEmail = data.getOrElse("contactEmail", defaults.contactEmail),

      // Publisher (Data Holder)
      publisherName = data.getOrElse("publisherName", defaults.publisherName),
      publisherType = data.getOrElse("publisherType", defaults.publisherType),
      publisherPage = data.getOrElse("publisherPage", defaults.publisherPage),
      publisherEmail = data.getOrElse("publisherEmail", defaults.publisherEmail),
      publisherTrusted = data.contains("publisherTrusted"),

      // Health Data Access Body (HDAB)
      hdabName = data.getOrElse("hdabName", defaults.hdabName),
      hdabType = data.getOrElse("hdabType", defaults.hdabType),
      hdabContactPage = data.getOrElse("hdabContactPage", defaults.hdabContactPage),
      hdabContactEmail = data.getOrElse("hdabContactEmail", defaults.hdabContactEmail),
      hdabNote = data.getOrElse("hdabNote", defaults.hdabNote),
      hdabTrusted = data.contains("hdabTrusted"),

      // Distribution metadata
      distributionAccessUrl = data.getOrElse("distributionAccessUrl", defaults.distributionAccessUrl),
      distributionLegislation = data.getOrElse("distributionApplicableLegislation", defaults.distributionLegislation),
      distributionLicense = data.getOrElse("distributionLicense", defaults.distributionLicense),
      distributionFormat = data.getOrElse("distributionFormat", defaults.distributionFormat),
      distributionDescription = data.getOrElse("distributionDescription", defaults.distributionDescription),

      // Sample Distribution metadata
      sampleAccessUrl = data.getOrElse("sampleAccessUrl", defaults.sampleAccessUrl),
      sampleTitle = data.getOrElse("sampleTitle", defaults.sampleTitle),
      sampleFormat = data.getOrElse("sampleFormat", defaults.sampleFormat),
      sampleLegislation = data.getOrElse("sampleLegislation", defaults.sampleLegislation)
    )
  }
}