package srdc.stage.rdf

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import srdc.stage.config.AppConfig

import java.net.InetSocketAddress
import java.awt.Desktop
import java.net.URI
import java.io.File
import java.nio.charset.StandardCharsets
import java.net.URLDecoder
import scala.io.Source
import org.apache.poi.ss.usermodel.{Cell, DataFormatter, Workbook, WorkbookFactory}
import org.apache.poi.ss.usermodel.Row.MissingCellPolicy
import io.onfhir.feast.parsers.JsonFormatter
import org.slf4j.LoggerFactory
import srdc.stage.vocab.PubEU

import scala.collection.mutable
import scala.util.Try

case class Period(start: Option[String], end: Option[String])
case class CodeValue(scheme: Option[String], notation: String, label: String)
case class ContactPointMetadataUserInput(name: Option[String] = None, page: Option[String] = None, email: Option[String] = None)
case class QualifiedAttribution(name: String, role: Option[String])
case class Checksum(algorithm: String, value: String)

case class CatalogMetadataUserInput(
                                     existing: Option[Boolean] = None,
                                     uri: Option[String] = None,
                                     title: Option[String] = None,
                                     description: Option[String] = None,
                                     applicableLegislation: Option[String] = None,
                                     creatorRef: Option[String] = None,
                                     publisherRef: Option[String] = None,
                                     geographicalCoverage: Option[Seq[String]] = None,
                                     temporalCoverage: Option[Period] = None,
                                     licence: Option[String] = None,
                                     themes: Option[String] = None,
                                     homepage: Option[String] = None,
                                     language: Option[Seq[String]] = None,
                                     modificationDate: Option[String] = None,
                                     releaseDate: Option[String] = None,
                                     rights: Option[String] = None
                                   )

case class DistributionMetadataUserInput(
                                          title: Option[String] = None,
                                          applicableLegislation: Option[String] = None,
                                          accessURL: Option[String] = None,
                                          description: Option[String] = None,
                                          format: Option[String] = None,
                                          license: Option[String] = None,
                                          availability: Option[String] = None,
                                          byteSize: Option[Int] = None,
                                          checksum: Option[Checksum] = None,
                                          mediaType: Option[String] = None,
                                          packagingFormat: Option[String] = None,
                                          releaseDate: Option[String] = None,
                                          rights: Option[String] = None,
                                          spatialResolution: Option[Double] = None,
                                          status: Option[String] = None,
                                          temporalResolution: Option[String] = None,
                                          accessService: Option[String] = None
                                        )

case class DatasetMetadataUserInput(
                                     title: Option[String] = None,
                                     description: Option[String] = None,
                                     identifier: Option[String] = None,
                                     version: Option[String] = None,
                                     populationCoverage: Option[String] = None,
                                     theme: Option[String] = None,
                                     provenance: Option[String] = None,
                                     contactPoint: ContactPointMetadataUserInput = ContactPointMetadataUserInput(),
                                     publisherRef: Option[String] = None,
                                     creatorRef: Option[String] = None,
                                     hdabRef: Option[String] = None,
                                     keyword: Option[Seq[String]] = None,
                                     spatial: Option[Seq[String]] = None,
                                     healthCategory: Option[String] = None,
                                     datasetType: Option[String] = None,
                                     applicableLegislation: Option[String] = None,
                                     accessRights: Option[String] = None,
                                     healthTheme: Option[String] = None,
                                     conformsTo: Option[String] = None,
                                     documentation: Option[String] = None,
                                     frequency: Option[String] = None,
                                     sample: Option[DistributionMetadataUserInput] = None,
                                     analytics: Option[DistributionMetadataUserInput] = None,
                                     alternative: Option[Seq[String]] = None,
                                     codeValues: Option[Seq[CodeValue]] = None,
                                     codingSystems: Option[Seq[String]] = None,
                                     numRecords: Option[Int] = None,
                                     retentionPeriod: Option[Period] = None,
                                     maxAge: Option[Int] = None,
                                     minAge: Option[Int] = None,
                                     numUniqueIndividual: Option[Int] = None,
                                     personalData: Option[Seq[String]] = None,
                                     landingPage: Option[String] = None,
                                     language: Option[String] = None,
                                     modificationDate: Option[String] = None,
                                     releaseDate: Option[String] = None,
                                     otherIdentifier: Option[Seq[String]] = None,
                                     qualifiedAttribution: Option[Seq[QualifiedAttribution]] = None,
                                     spatialResolution: Option[Double] = None,
                                     temporalCoverage: Option[Period] = None,
                                     temporalResolution: Option[String] = None,
                                     versionNotes: Option[String] = None,
                                     wasGeneratedBy: Option[Seq[String]] = None,
                                     purpose: Option[String] = None,
                                     qualityAnnotation: String = ""
                                   )

case class CsvwField(
                      name: String,
                      title: String,
                      datatype: String,
                      description: Option[String] = None,
                      propertyUrl: Option[String] = None,
                      unit: Option[String] = None,
                      study: Option[String] = None,
                      group: Option[String] = None,
                      subpopulation: Option[String] = None,
                      sampleSize: Option[String] = None,
                      dataOwner: Option[String] = None,
                      identifier: Option[String] = None,
                      selection: Option[String] = None,
                      parentGroup: Option[String] = None,
                      responsible: Option[String] = None,
                      note: Option[String] = None,
                      minValue: Option[String] = None,
                      maxValue: Option[String] = None,
                      required: Option[String] = None,
                      conditionalOn: Option[String] = None,
                      repetitionPeriod: Option[String] = None
                    )

case class MetadataUserInput(
                         catalog: CatalogMetadataUserInput,
                         dataset: DatasetMetadataUserInput,
                         distribution: DistributionMetadataUserInput,
                         dataDictionary: Option[List[CsvwField]] = None,
                         dataDictionaryValueSets: Map[String, Map[String, String]] = Map.empty,
                         organizations: Seq[Organization] = Seq.empty
                       )

case class SharedMetadata(
                           catalog: CatalogMetadataUserInput,
                           organizations: Seq[Organization] = Seq.empty
                         )

case class JobMetadata(
                        dataset: DatasetMetadataUserInput,
                        distribution: DistributionMetadataUserInput,
                        dataDictionary: Option[List[CsvwField]] = None,
                        dataDictionaryValueSets: Map[String, Map[String, String]] = Map.empty
                      )

case class MultiJobMetadataInput(
                                  catalog: CatalogMetadataUserInput,
                                  jobs: Map[String, JobMetadata],
                                  organizations: Option[Seq[Organization]] = None
                                )

/**
 * Utility object for loading the application configuration.
 * Supports three modes: JSON file, Excel file, or an interactive Browser-based form.
 */
object MetadataUserInput {

  private val logger = LoggerFactory.getLogger(getClass)

  val SUCCESS_HTML = """<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>Success</title></head><body style="font-family: sans-serif; text-align: center; margin-top: 50px;"><h1>Configuration Received!</h1><p>You can close this tab and return to your terminal.</p><script>setTimeout(() => window.close(), 2000)</script></body></html>"""

  /**
   * Main entry point for loading configuration.
   * Dispatches to the specific loading strategy based on the 'runMode' parameter.
   *
   * @param appConfig The initial application configuration (usually from CLI args).
   * @return A fully populated ConfigLoader object.
   */
  def load(appConfig: AppConfig, jobName: String = "", shared: Option[SharedMetadata] = None): MetadataUserInput = {
    appConfig.runMode.toLowerCase match {
      case "json" => loadFromJson(appConfig, jobName)
      case "excel" => loadFromExcel(appConfig, jobName, shared)
      case "browser" => loadFromBrowser(appConfig, jobName, shared)
      case _      => throw new IllegalArgumentException(s"Unknown runMode: ${appConfig.runMode}")
    }
  }

  /**
   * Loads configuration directly from a JSON file.
   *
   * @param appConfig Configuration containing the JSON path.
   * @return ConfigLoader object parsed from JSON.
   */
  private def loadFromJson(appConfig: AppConfig, jobName: String): MetadataUserInput = {
    logger.info("Loading static metadata from JSON for job: {}", jobName)
    val source = Source.fromFile(appConfig.jsonPath)
    val jsonContent = source.mkString
    source.close()

    val multiJob = Try(JsonFormatter.fromJson[MultiJobMetadataInput](jsonContent)) match {
      case scala.util.Success(parsed) => parsed
      case scala.util.Failure(cause) =>
        // Surface the underlying parse error: an unexpected/renamed key is otherwise indistinguishable
        // from a malformed file.
        logger.error("Failed to parse JSON metadata file: {}", cause.getMessage)
        throw new IllegalArgumentException(
          s"Invalid JSON metadata file! Expected multi-job format with 'catalog' and 'jobs' keys (optionally 'organizations'). Cause: ${cause.getMessage}",
          cause
        )
    }

    val jobMeta = multiJob.jobs.getOrElse(jobName,
      throw new IllegalArgumentException(
        s"No metadata found for job '$jobName' in config JSON. Available jobs: ${multiJob.jobs.keys.mkString(", ")}"
      ))

    // Organizations are declared once at the top level, next to the catalog, and shared by every job.
    val organizations = multiJob.organizations.getOrElse(Seq.empty)
    OrganizationRegistry.validate(organizations)
    if (organizations.nonEmpty) {
      logger.info("Loaded {} organisation(s) from JSON: {}", organizations.size, organizations.map(_.id).mkString(", "))
    }

    MetadataUserInput(
      catalog = multiJob.catalog,
      dataset = jobMeta.dataset,
      distribution = jobMeta.distribution,
      dataDictionary = jobMeta.dataDictionary,
      dataDictionaryValueSets = jobMeta.dataDictionaryValueSets,
      organizations = organizations
    )
  }

  /**
   * Loads configuration from an Excel file using Apache POI.
   *
   * @param appConfig Configuration containing the Excel path.
   * @return ConfigLoader object parsed from Excel.
   */
  private def loadFromExcel(appConfig: AppConfig, jobName: String = "", shared: Option[SharedMetadata] = None): MetadataUserInput = {
    val jobSuffix = if (jobName.nonEmpty) s" (job: $jobName)" else ""
    logger.info("Loading static metadata from Excel: {}{}", appConfig.excelPath, jobSuffix)
    val file = new File(appConfig.excelPath)
    val wb = WorkbookFactory.create(file)
    val metadata = fromExcel(wb, jobName, shared)
    wb.close()
    metadata
  }

  /**
   * Launches a local HTTP server to serve an HTML form.
   * Allows the user to interactively edit the configuration in their browser.
   *
   * @param appConfig Default application configuration values to pre-fill the form (e.g., FDP URL, Output Dir).
   * @return The updated ConfigLoader object submitted by the user.
   */
  private def loadFromBrowser(appConfig: AppConfig, jobName: String = "", shared: Option[SharedMetadata] = None): MetadataUserInput = {
    logger.info("Fetching EU Vocabularies. This might take a few seconds...")

    val healthCategories = Map(
      "http://13.81.34.152:1101/resource/authority/healthcategories/NRPE" -> "Aggregated data on healthcare needs, resources allocated to healthcare, the provision of and access to healthcare, healthcare expenditure and financing",
      "http://13.81.34.152:1101/resource/authority/healthcategories/EHCT" -> "Data from clinical trials, clinical studies, clinical investigations and performance studies subject to Regulation (EU) No 536/2014, Regulation (EU) 2024/1938 of the European Parliament and of the Council34, Regulation (EU) 2017/745 and Regulation (EU) 2017/746",
      "http://13.81.34.152:1101/resource/authority/healthcategories/MRMR" -> "Data from medical registries and mortality registries",
      "http://13.81.34.152:1101/resource/authority/healthcategories/PHDR" -> "Data from population-based health data registries such as public health registries",
      "http://13.81.34.152:1101/resource/authority/healthcategories/RMMD" -> "Data from registries for medicinal products and medical devices",
      "http://13.81.34.152:1101/resource/authority/healthcategories/RQSH" -> "Data from research cohorts, questionnaires and surveys related to health, after the first publication of the related results",
      "http://13.81.34.152:1101/resource/authority/healthcategories/WELA" -> "Data from wellness applications",
      "http://13.81.34.152:1101/resource/authority/healthcategories/DIOH" -> "Data on factors impacting on health, including socio-economic, environmental and behavioural determinants of health",
      "http://13.81.34.152:1101/resource/authority/healthcategories/RPDG" -> "Data on pathogens that impact human health",
      "http://13.81.34.152:1101/resource/authority/healthcategories/IDHP" -> "Data on professional status, and on the specialisation and institution of health professionals involved in the treatment of a natural person",
      "http://13.81.34.152:1101/resource/authority/healthcategories/EHRS" -> "Electronic Health Data from EHRs",
      "http://13.81.34.152:1101/resource/authority/healthcategories/EINS" -> "Health data from biobanks and associated databases",
      "http://13.81.34.152:1101/resource/authority/healthcategories/HRAD" -> "Healthcare-related administrative data, including on dispensations, reimbursement claims and reimbursements",
      "http://13.81.34.152:1101/resource/authority/healthcategories/HGPD" -> "Human genetic, epigenomic and genomic data",
      "http://13.81.34.152:1101/resource/authority/healthcategories/EMRD" -> "Other health data from medical devices",
      "http://13.81.34.152:1101/resource/authority/healthcategories/HPML" -> "Other human molecular data such as proteomic, transcriptomic, metabolomic, lipidomic and other omic data",
      "http://13.81.34.152:1101/resource/authority/healthcategories/PGEH" -> "Personal electronic health data automatically generated through medical devices"
    )

    val healthThemes = Map(
      "http://13.81.34.152:1101/resource/authority/health-theme/ANTIMICROBIAL_CONTROL" -> "Antimicrobial resistance infection control",
      "http://13.81.34.152:1101/resource/authority/health-theme/BLOOD_INFECTIONS" -> "Blood-borne sexually transmitted infections",
      "http://13.81.34.152:1101/resource/authority/health-theme/CANCER_DISEASE" -> "Cancer",
      "http://13.81.34.152:1101/resource/authority/health-theme/CLIMATE_HEALTH" -> "Climate planetary health",
      "http://13.81.34.152:1101/resource/authority/health-theme/EMERGENCY_SETTINGS" -> "Emergencies, disasters, travel humanitarian settings",
      "http://13.81.34.152:1101/resource/authority/health-theme/ENTERIC_INFECTIONS" -> "Enteric, water- food-borne infections",
      "http://13.81.34.152:1101/resource/authority/health-theme/ENVIRONMENTAL_HEALTH" -> "Environmental, occupational radiation health (incl. WASH urban)",
      "http://13.81.34.152:1101/resource/authority/health-theme/HEALTH_PRODUCTS" -> "Health products, technologies, data research",
      "http://13.81.34.152:1101/resource/authority/health-theme/HEALTH_SYSTEMS" -> "Health systems, quality, care models determinants",
      "http://13.81.34.152:1101/resource/authority/health-theme/IMMUNIZATION_DISEASES" -> "Immunization vaccine-preventable diseases",
      "http://13.81.34.152:1101/resource/authority/health-theme/INJURY_PREVENTION" -> "Injuries, envenoming drowning",
      "http://13.81.34.152:1101/resource/authority/health-theme/LIFECOURSE_HEALTH" -> "Life-course health: maternal, newborn, child, adolescent ageing",
      "http://13.81.34.152:1101/resource/authority/health-theme/MENTAL_HEALTH" -> "Mental, neurological substance use",
      "http://13.81.34.152:1101/resource/authority/health-theme/TROPICAL_DISEASES" -> "Neglected tropical, parasitic fungal skin diseases",
      "http://13.81.34.152:1101/resource/authority/health-theme/NONCOMMUNICABLE_DISEASES" -> "Noncommunicable diseases – metabolic cardiopulmonary",
      "http://13.81.34.152:1101/resource/authority/health-theme/NUTRITION_SECURITY" -> "Nutrition food security",
      "http://13.81.34.152:1101/resource/authority/health-theme/SENSORY_HEALTH" -> "Oral, eye sensory health",
      "http://13.81.34.152:1101/resource/authority/health-theme/RESPIRATORY_DISEASES" -> "Respiratory infectious diseases",
      "http://13.81.34.152:1101/resource/authority/health-theme/REPRODUCTIVE_HEALTH" -> "Sexual reproductive health and rights",
      "http://13.81.34.152:1101/resource/authority/health-theme/VECTOR_DISEASES" -> "Vector-borne zoonotic viral diseases"
    )

    def toHtmlOption(pair: (String, String)) = s"""<option value="${pair._1}">${pair._2}</option>"""

    val stream = getClass.getClassLoader.getResourceAsStream("form.html")
    if (stream == null) {
      throw new IllegalStateException("form.html not found in resources folder! Make sure it is placed in src/main/resources/form.html")
    }
    val htmlTemplate = Source.fromInputStream(stream)(StandardCharsets.UTF_8).mkString

    var htmlContent = htmlTemplate
      .replace("{{countryOptions}}", PubEU.queryCountryNames.map(toHtmlOption).mkString(""))
      .replace("{{themeOptions}}", PubEU.queryDataThemes.map(toHtmlOption).mkString(""))
      .replace("{{hdabTypeOptions}}", PubEU.queryCorporateBodyTypes.map(toHtmlOption).mkString(""))
      .replace("{{typeOptions}}", PubEU.queryDatasetTypes.map(toHtmlOption).mkString(""))
      .replace("{{fileTypeOptions}}", PubEU.queryFileTypes.map(toHtmlOption).mkString(""))
      .replace("{{accessRightsOptions}}", PubEU.queryAccessRights.map(toHtmlOption).mkString(""))
      .replace("{{frequencyOptions}}", PubEU.queryFrequencies.map(toHtmlOption).mkString(""))
      .replace("{{healthCategoryOptions}}", healthCategories.map(toHtmlOption).mkString(""))
      .replace("{{healthThemeOptions}}", healthThemes.map(toHtmlOption).mkString(""))
      .replace("{{catalog-uri}}", appConfig.fdpUrl.map(_ + "/catalog/").getOrElse(""))
      .replace("{{catalog-title}}", "STAGE Data Catalog")
      .replace("{{catalog-description}}", "FAIRful metadata catalog for the datasets collected in the scope of STAGE project")
      .replace("{{catalog-applicable-legislation}}", "https://eur-lex.europa.eu/eli/reg/2025/327/oj")
      .replace("{{title}}", "Core")
      .replace("{{dataset-version}}", "1.0.0")
      .replace("{{description}}", "NFBC1986 is a longitudinal one-year birth cohort study from an unselected population.")
      .replace("{{dataset-quality-annotation}}", "Data validated against STAGE quality assurance protocols.")
      .replace("{{provenance}}", "The original data have been supplemented by data collected with postal questionnaires at the ages of 7, 8 and 16 years.")
      .replace("{{keyword}}", "finland, lapland, birth cohort")
      .replace("{{identifier}}", "http://oulu.fi/NFBC1986/core")
      .replace("{{population-coverage}}", "Prenatal, Infant (0-23 months), Child (2-12 years), Adolescent (13-17 years), Young adult (18-24 years), Adult (25-44 years)")
      .replace("{{contact-page}}", "https://example.com")
      .replace("{{contact-email}}", "john.doe@oulu.fi")
      .replace("{{publisher-name}}", "OULU")
      .replace("{{publisher-page}}", "https://oulu.fi")
      .replace("{{publisher-email}}", "info@oulu.edu.fi")
      .replace("{{hdab-name}}", "OULU")
      .replace("{{hdab-contact-page}}", "https://oulu.fi")
      .replace("{{hdab-contact-email}}", "info@oulu.edu.fi")
      .replace("{{hdab-note}}", "The publisher provides this dataset metadata without warranties of any kind.")
      .replace("{{applicable-legislation}}", "https://eur-lex.europa.eu/eli/reg/2025/327/oj")
      .replace("{{distribution-access-url}}", "https://www.oulu.fi/nfbc")
      .replace("{{distribution-license}}", "http://creativecommons.org/licenses/by/4.0/")
      .replace("{{distribution-applicable-legislation}}", "https://eur-lex.europa.eu/eli/reg/2025/327/oj")
      .replace("{{distribution-description}}", "The primary dataset distribution.")
      .replace("{{sample-title}}", "NFBC1986 Samples")
      .replace("{{sample-access-url}}", "https://oulu.fi/nfbc1986/sample.csv")
      .replace("{{sample-legislation}}", "http://eur-lex.europa.eu/eli/reg/2025/327/oj")

    // Job header and catalog visibility in multi-job browser run
    val jobLabel = if (jobName.nonEmpty) jobName else "default"
    val jobBannerHtml = if (jobName.nonEmpty) {
      s"""<div class="job-banner">Configuring metadata for job: <span class="job-name">$jobLabel</span>${if (shared.isDefined) " (Catalog details carried over from the first job)" else ""}</div>"""
    } else ""
    val sharedOrganizations = shared.map(_.organizations).getOrElse(Seq.empty)
    def jsonStr(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val sharedOrgsJson = sharedOrganizations
      .map(o => s"""{"id":${jsonStr(o.id)},"name":${jsonStr(o.name)}}""")
      .mkString("[", ",", "]")

    htmlContent = htmlContent
      .replace("{{job-banner}}", jobBannerHtml)
      .replace("{{catalog-section-style}}", if (shared.isDefined) """style="display:none"""" else "")
      .replace("{{organizations-section-style}}", if (shared.isDefined) """style="display:none"""" else "")
      .replace("{{shared-organizations}}", sharedOrgsJson)

    // Scrub all unused placeholders
    htmlContent = htmlContent.replaceAll("\\{\\{[^}]+\\}\\}", "")

    var resultConfig: Option[MetadataUserInput] = None
    val server = HttpServer.create(new InetSocketAddress(0), 0)

    server.createContext("/", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        if (exchange.getRequestMethod == "GET") {
          val bytes = htmlContent.getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(200, bytes.length)
          exchange.getResponseBody.write(bytes)
          exchange.getResponseBody.close()
        } else if (exchange.getRequestMethod == "POST" && exchange.getRequestURI.getPath == "/form") {
          val is = exchange.getRequestBody
          val body = new String(is.readAllBytes(), StandardCharsets.UTF_8)

          val formData = body.split("&").map { kv =>
            val split = kv.split("=")
            val key = URLDecoder.decode(split(0), "UTF-8")
            val value = if (split.length > 1) URLDecoder.decode(split(1), "UTF-8") else ""
            key -> value
          }.toMap

          val formResult = fromFormData(formData)
          // Shared catalog from the first job
          resultConfig = shared match {
            case Some(s) => Some(formResult.copy(catalog = s.catalog, organizations = s.organizations))
            case None => Some(formResult)
          }

          val resp = SUCCESS_HTML.getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(200, resp.length)
          exchange.getResponseBody.write(resp)
          exchange.getResponseBody.close()
        }
      }
    })

    server.setExecutor(null)
    server.start()
    val url = s"http://localhost:${server.getAddress.getPort}"
    val catalogNote = if (shared.isDefined) " (catalog shared from first job)" else ""
    logger.info("Waiting for browser input for job '{}'{} — form launched at: {}", jobLabel, catalogNote, url)

    if (Desktop.isDesktopSupported) {
      Desktop.getDesktop.browse(new URI(url))
    }

    while(resultConfig.isEmpty) {
      Thread.sleep(500)
    }

    server.stop(0)
    logger.info("Browser form successfully mapped!")
    resultConfig.get
  }

  /**
   * Maps the raw form data Map to a new ConfigLoader object.
   * Overrides default values with user-provided inputs where available.
   *
   * @param formData Map containing form field names and values.
   * @return A new ConfigLoader instance reflecting the form data.
   */
  def fromFormData(formData: Map[String, String]): MetadataUserInput = {
    def getOpt(key: String): Option[String] = formData.get(key).filter(_.trim.nonEmpty)
    def getBool(key: String): Option[Boolean] = formData.get(key).map(_ == "on")
    def reqStr(key: String, name: String): String = getOpt(key).getOrElse(throw new IllegalArgumentException(s"$name is required"))

    val organizations: Seq[Organization] = {
      val pattern = """^org\.(\d+)\.(.+)$""".r
      val grouped = mutable.LinkedHashMap.empty[Int, mutable.Map[String, String]]
      formData.foreach {
        case (key, value) => key match {
          case pattern(idx, field) =>
            grouped.getOrElseUpdate(idx.toInt, mutable.Map.empty)(field) = value
          case _ => ()
        }
      }
      val parsed = grouped.toSeq.sortBy(_._1).flatMap { case (_, fields) =>
        def field(name: String): Option[String] = fields.get(name).map(_.trim).filter(_.nonEmpty)
        field("id").map { id =>
          Organization(
            id = id,
            name = field("name").getOrElse(""),
            iri = field("iri"),
            `type` = field("type"),
            contactPoint = ContactPointMetadataUserInput(page = field("page"), email = field("email")),
            homepage = field("homepage"),
            note = field("note"),
            trusted = fields.get("trusted").map(_ == "on"),
            identifiers = field("identifiers")
              .map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq).getOrElse(Seq.empty)
          )
        }
      }
      OrganizationRegistry.validate(parsed)
      if (parsed.nonEmpty) {
        logger.info("Received {} organisation(s) from the form: {}", parsed.size, parsed.map(_.id).mkString(", "))
      }
      parsed
    }

    val orgIndex = new OrganizationIndex(organizations)

    /** Validates a reference submitted by one of the form's organisation selects. */
    def orgRef(key: String, field: String): Option[String] = getOpt(key).map { ref =>
      orgIndex.lookup(ref).map(_.id).getOrElse(
        throw new IllegalArgumentException(
          s"$field references organisation '$ref', which is not defined in the Organizations section" +
            (if (orgIndex.nonEmpty) s" (known ids: ${orgIndex.ids.mkString(", ")})." else ".")
        )
      )
    }

    val hdabRef = orgRef("datasetHdabOrg", "Dataset HDAB").getOrElse(
      throw new IllegalArgumentException(
        "Health Data Access Body is required: pick an organisation from the Organizations section."
      )
    )

    MetadataUserInput(
      organizations = organizations,
      catalog = CatalogMetadataUserInput(
        existing = getBool("existingCatalog"),
        uri = getOpt("catalogUri"),
        title = getOpt("catalogTitle"),
        description = getOpt("catalogDescription"),
        applicableLegislation = getOpt("catalogApplicableLegislation"),
        geographicalCoverage = getOpt("catalogSpatial").map(Seq(_)),
        temporalCoverage = None, // Handled dynamically via FHIR stats
        publisherRef = orgRef("catalogPublisherOrg", "Catalog publisher"),
        creatorRef = orgRef("catalogCreatorOrg", "Catalog creator")
      ),
      dataset = DatasetMetadataUserInput(
        title = getOpt("title"),
        description = getOpt("description"),
        identifier = getOpt("identifier"),
        version = getOpt("datasetVersion"),
        populationCoverage = getOpt("populationCoverage"),
        theme = getOpt("theme"),
        provenance = getOpt("provenance"),
        contactPoint = ContactPointMetadataUserInput(page = getOpt("contactPage"), email = getOpt("contactEmail")),
        publisherRef = orgRef("datasetPublisherOrg", "Dataset publisher"),
        creatorRef = orgRef("datasetCreatorOrg", "Dataset creator"),
        hdabRef = Some(hdabRef),
        keyword = getOpt("keyword").map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq),
        spatial = getOpt("datasetSpatial").map(Seq(_)),
        healthCategory = getOpt("healthCategory"),
        datasetType = getOpt("type"),
        applicableLegislation = getOpt("applicableLegislation"),
        accessRights = getOpt("accessRights"),
        healthTheme = getOpt("healthTheme"),
        qualityAnnotation = reqStr("datasetQualityAnnotation", "Quality Annotation"),
        frequency = getOpt("datasetFrequency"),
        temporalCoverage = None, // Handled dynamically via FHIR stats
        sample = getOpt("sampleAccessUrl").map { url =>
          DistributionMetadataUserInput(
            title = getOpt("sampleTitle"),
            accessURL = Some(url),
            format = getOpt("sampleFormat"),
            applicableLegislation = getOpt("sampleLegislation")
          )
        }
      ),
      distribution = DistributionMetadataUserInput(
        accessURL = getOpt("distributionAccessUrl"),
        applicableLegislation = getOpt("distributionApplicableLegislation"),
        format = getOpt("distributionFormat"),
        license = getOpt("distributionLicense"),
        description = getOpt("distributionDescription")
      ),
      dataDictionary = None
    )
  }

  // --- EXCEL LOGIC ---
  /**
   * Parses the "Organizations" sheet into Organization entries.
   *
   * Unlike the vertical Catalog/Dataset/Distribution sheets, this one is a plain table:
   * row 0 holds the headers and each subsequent row is one organisation. Columns are located
   * **by header name**, so authors may reorder or append columns without breaking the parser.
   *
   * Absent sheet -> empty registry, and every agent field falls back to its inline definition.
   *
   * @param wb        The active workbook.
   * @param formatter Formatter used to render cell values (handles inline strings and numerics).
   * @param evaluator Formula evaluator, so computed cells (e.g. authority-code lookups) resolve.
   */
  def parseOrganizations(
                          wb: Workbook,
                          formatter: DataFormatter,
                          evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator
                        ): Seq[Organization] = {
    val sheet = wb.getSheet("Organizations")
    if (sheet == null) {
      logger.info("No 'Organizations' sheet found - agent fields will use their inline definitions.")
      return Seq.empty
    }

    def cellText(row: org.apache.poi.ss.usermodel.Row, colIdx: Int): String = {
      if (colIdx < 0) "" else {
        Option(row.getCell(colIdx)).map { c =>
          evaluator.evaluate(c)
          formatter.formatCellValue(c, evaluator).trim
        }.getOrElse("")
      }
    }

    def opt(row: org.apache.poi.ss.usermodel.Row, colIdx: Int): Option[String] =
      cellText(row, colIdx).trim match {
        case s if s.isEmpty => None
        case s => Some(s)
      }

    val headerRow = Option(sheet.getRow(0)).getOrElse(
      throw new IllegalArgumentException("The 'Organizations' sheet is present but has no header row.")
    )

    // Header label (normalised) -> column index
    val headers: Map[String, Int] = (0 until headerRow.getLastCellNum.toInt).flatMap { idx =>
      val label = OrganizationIndex.normalize(cellText(headerRow, idx))
      if (label.isEmpty) None else Some(label -> idx)
    }.toMap

    def col(names: String*): Int =
      names.map(OrganizationIndex.normalize).flatMap(headers.get).headOption.getOrElse(-1)

    val idCol = col("organization id", "organisation id", "id")
    val nameCol = col("name", "organization name")
    val iriCol = col("canonical iri", "iri", "uri")
    val typeCol = col("type")
    val pageCol = col("contact page", "page")
    val emailCol = col("contact email", "email")
    val homepageCol = col("homepage", "home page")
    val descriptionCol = col("description", "note")
    val trustedCol = col("trusted data holder", "trusted")
    val identifiersCol = col("other identifiers", "identifiers")

    if (idCol < 0 || nameCol < 0) {
      throw new IllegalArgumentException(
        "The 'Organizations' sheet must have at least 'Organization ID' and 'Name' header columns."
      )
    }

    val organizations = (1 to sheet.getLastRowNum).flatMap { rowIdx =>
      Option(sheet.getRow(rowIdx)).flatMap { row =>
        val id = cellText(row, idCol).trim
        // Silently skip fully blank filler rows; a row with an id must be complete (validated below).
        if (id.isEmpty) None
        else Some(Organization(
          id = id,
          name = cellText(row, nameCol).trim,
          iri = opt(row, iriCol),
          `type` = opt(row, typeCol),
          contactPoint = ContactPointMetadataUserInput(
            page = opt(row, pageCol),
            email = opt(row, emailCol)
          ),
          homepage = opt(row, homepageCol),
          note = opt(row, descriptionCol),
          trusted = opt(row, trustedCol).map(_.trim.toLowerCase).flatMap {
            case "true" | "yes" | "y" | "1" => Some(true)
            case "false" | "no" | "n" | "0" => Some(false)
            case other =>
              throw new IllegalArgumentException(
                s"Organizations: invalid 'Trusted Data Holder' value '$other' for organisation '$id'. Use true or false."
              )
          },
          identifiers = opt(row, identifiersCol)
            .map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq)
            .getOrElse(Seq.empty)
        ))
      }
    }.toList

    OrganizationRegistry.validate(organizations)
    logger.info("Loaded {} organisation(s) from Excel: {}", organizations.size, organizations.map(_.id).mkString(", "))
    organizations
  }

  /**
   * Parses the loaded Excel Workbook into a MetadataUserInput object.
   * Extracts data from Organizations, Catalog, Dataset, Distribution, and Data Dictionary sheets.
   *
   * @param wb The active Excel Workbook instance.
   * @param shared Catalog + organisations carried over from the first job of a multi-job run.
   * @return A fully populated MetadataUserInput instance based on Excel inputs.
   */
  def fromExcel(wb: Workbook, jobName: String = "", shared: Option[SharedMetadata] = None): MetadataUserInput = {
    def toStringOption(str: String) = str.trim match {
      case s if s.isEmpty => None
      case s => Some(s)
    }
    def toBooleanOption(str: String, path: String = "") = str.trim match {
      case "true" => Some(true)
      case "false" => Some(false)
      case s if s.trim.isEmpty => None
      case _ => throw new IllegalArgumentException(s"Incorrect value for boolean: $str ($path)")
    }
    def ensureNotEmpty(str: String, field: String) = {
      if (str.trim.isEmpty) {
        throw new IllegalArgumentException(s"The '$field' field cannot be empty.")
      }
      str.trim
    }

    // Resolve sheet names: try job-specific (e.g. "Dataset-SURVEY") first, fall back to base name (e.g. "Dataset").
    // Catalog is always shared — it uses the base "Catalog" sheet regardless of job name.
    def resolveSheet(baseName: String): org.apache.poi.ss.usermodel.Sheet = {
      if (jobName.nonEmpty) {
        val jobSpecific = s"$baseName-${jobName.toUpperCase}"
        val sheet = wb.getSheet(jobSpecific)
        if (sheet != null) {
          logger.info("Using job-specific sheet: {}", jobSpecific)
          return sheet
        }
      }
      wb.getSheet(baseName)
    }

    wb.setMissingCellPolicy(MissingCellPolicy.CREATE_NULL_AS_BLANK)
    val catalogSheet = wb.getSheet("Catalog")
    val datasetSheet = resolveSheet("Dataset")
    val distSheet = resolveSheet("Distribution")
    val dataDictionarySheet = resolveSheet("Data Dictionary")

    val formatter = new DataFormatter()
    val evaluator = wb.getCreationHelper.createFormulaEvaluator()

    def getCellStr(sheet: org.apache.poi.ss.usermodel.Sheet, rowIdx: Int, colIdx: Int = 6): String = {
      Option(sheet.getRow(rowIdx)).flatMap(r => Option(r.getCell(colIdx))).map { c =>
        evaluator.evaluate(c)
        formatter.formatCellValue(c, evaluator).trim
      }.getOrElse("")
    }

    def getFormattedOption(sheet: org.apache.poi.ss.usermodel.Sheet, rowIdx: Int) = toStringOption(getCellStr(sheet, rowIdx))

    // Organisations are entered once and shared by every job of a multi-job run, like the Catalog.
    val organizations: Seq[Organization] =
      shared.map(_.organizations).getOrElse(parseOrganizations(wb, formatter, evaluator))
    val orgIndex = new OrganizationIndex(organizations)

    /**
     * Resolves an agent cell against the Organizations sheet. Agent details live there and nowhere
     * else, so a value that matches no entry is a configuration error rather than a literal name.
     */
    def resolveOrgRef(value: Option[String], field: String): Option[String] = value.map { ref =>
      val known = if (orgIndex.nonEmpty) s" Known ids: ${orgIndex.ids.mkString(", ")}." else " The 'Organizations' sheet is empty or missing."
      val org = orgIndex.lookup(ref).getOrElse(
        throw new IllegalArgumentException(
          s"$field refers to '$ref', which is not defined on the 'Organizations' sheet.$known"
        )
      )
      logger.info("{} -> organisation '{}'", field, org.id)
      org.id
    }

    // Validations
    if (datasetSheet == null) {
      val sheetName = if (jobName.nonEmpty) s"Dataset-${jobName.toUpperCase} or Dataset" else "Dataset"
      throw new IllegalArgumentException(s"Required sheet '$sheetName' not found in Excel workbook.")
    }
    if (distSheet == null) {
      val sheetName = if (jobName.nonEmpty) s"Distribution-${jobName.toUpperCase} or Distribution" else "Distribution"
      throw new IllegalArgumentException(s"Required sheet '$sheetName' not found in Excel workbook.")
    }

    // Reuse sharedCatalog for subsequent ones
    val catalog: CatalogMetadataUserInput = shared.map(_.catalog).getOrElse {
      if (catalogSheet == null) throw new IllegalArgumentException("Required sheet 'Catalog' not found in Excel workbook.")

      val existingVal = toBooleanOption(getCellStr(catalogSheet, 1), "Catalog.existing")
      val uriVal = getFormattedOption(catalogSheet, 2)
      if (existingVal.contains(true) && uriVal.isEmpty) throw new IllegalArgumentException("Catalog.uri is REQUIRED when existing is true.")

      // Rows 6 (creator) and 16 (publisher) hold an Organization ID from the 'Organizations' sheet.
      val catalogCreatorRef = resolveOrgRef(getFormattedOption(catalogSheet, 6), "Catalog.creator")
      val catalogPublisherName = getFormattedOption(catalogSheet, 16)
      val catalogPublisherRef = resolveOrgRef(catalogPublisherName, "Catalog.publisher")

      CatalogMetadataUserInput(
        creatorRef = catalogCreatorRef,
        publisherRef = catalogPublisherRef,
        existing = existingVal,
        uri = uriVal,
        title = getFormattedOption(catalogSheet, 3),
        description = getFormattedOption(catalogSheet, 4),
        applicableLegislation = getFormattedOption(catalogSheet, 5),
        geographicalCoverage = getFormattedOption(catalogSheet, 8).map(_.split(",").toSeq),
        temporalCoverage = (
          getFormattedOption(catalogSheet, 9),
          getFormattedOption(catalogSheet, 10)
        ) match {
          case (None, None) => None
          case (start, end) => Some(Period(start, end))
        },
        licence = getFormattedOption(catalogSheet, 11),
        themes = getFormattedOption(catalogSheet, 12),
        homepage = getFormattedOption(catalogSheet, 13),
        language = getFormattedOption(catalogSheet, 14).map(_.split(",").toSeq),
        modificationDate = getFormattedOption(catalogSheet, 15),
        releaseDate = getFormattedOption(catalogSheet, 22),
        rights = getFormattedOption(catalogSheet, 23)
      )
    }
    // Validation dataset sheet
    val dsPage = getFormattedOption(datasetSheet, 8)
    val dsEmail = getFormattedOption(datasetSheet, 9)
    if (dsPage.isEmpty && dsEmail.isEmpty) throw new IllegalArgumentException("Dataset Contact Point requires at least one of page or email.")

    // Rows 10 (HDAB), 14 (publisher) and 28 (creator) hold an Organization ID.
    val datasetHdabRef = resolveOrgRef(getFormattedOption(datasetSheet, 10), "Dataset.hdab")
    val datasetPublisherRef = resolveOrgRef(getFormattedOption(datasetSheet, 14), "Dataset.publisher")
    val datasetCreatorRef = resolveOrgRef(getFormattedOption(datasetSheet, 28), "Dataset.creator")

    if (datasetHdabRef.isEmpty) {
      throw new IllegalArgumentException(
        "Dataset 'health data access body' is REQUIRED: put an Organization ID in its name cell."
      )
    }

    MetadataUserInput(
      catalog = catalog,
      organizations = organizations,
      dataset = DatasetMetadataUserInput(
        publisherRef = datasetPublisherRef,
        creatorRef = datasetCreatorRef,
        hdabRef = datasetHdabRef,
        title = getFormattedOption(datasetSheet, 1),
        description = getFormattedOption(datasetSheet, 2),
        identifier = getFormattedOption(datasetSheet, 3),
        version = getFormattedOption(datasetSheet, 4),
        populationCoverage = getFormattedOption(datasetSheet, 5),
        theme = getFormattedOption(datasetSheet, 6),
        provenance = getFormattedOption(datasetSheet, 7),
        contactPoint = ContactPointMetadataUserInput(
          page = getFormattedOption(datasetSheet, 8),
          email = getFormattedOption(datasetSheet, 9)
        ),
        keyword = getFormattedOption(datasetSheet, 20).map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq),
        spatial = getFormattedOption(datasetSheet, 21).map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq),
        healthCategory = getFormattedOption(datasetSheet, 22),
        datasetType = getFormattedOption(datasetSheet, 23),
        applicableLegislation = getFormattedOption(datasetSheet, 24),
        accessRights = getFormattedOption(datasetSheet, 25),
        healthTheme = getFormattedOption(datasetSheet, 26),
        conformsTo = getFormattedOption(datasetSheet, 27),
        documentation = getFormattedOption(datasetSheet, 30),
        frequency = getFormattedOption(datasetSheet, 31),
        sample = None,
        analytics = None,
        alternative = getFormattedOption(datasetSheet, 40).map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq),
        codeValues = Try(Seq(
          getCellStr(datasetSheet, 41).split(",", -1),
          getCellStr(datasetSheet, 42).split(",", -1),
          getCellStr(datasetSheet, 43).split(",", -1)
        ).transpose.filter(value => value.size >= 3 && value(1).nonEmpty && value(2).nonEmpty).map {
          case Seq(scheme, notation, label) => CodeValue(toStringOption(scheme), notation, label)
        }).toOption,
        codingSystems = getFormattedOption(datasetSheet, 44).map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq),
        numRecords = Try(getCellStr(datasetSheet, 45).toDouble.toInt).toOption,
        retentionPeriod = (
          getFormattedOption(datasetSheet, 46),
          getFormattedOption(datasetSheet, 47)
        ) match {
          case (None, None) => None
          case (start, end) => Some(Period(start, end))
        },
        maxAge = Try(getCellStr(datasetSheet, 48).toDouble.toInt).toOption,
        minAge = Try(getCellStr(datasetSheet, 49).toDouble.toInt).toOption,
        numUniqueIndividual = Try(getCellStr(datasetSheet, 50).toDouble.toInt).toOption,
        personalData = getFormattedOption(datasetSheet, 51).map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq
          .map(pd => s"https://w3c.github.io/dpv/2.0/pd#$pd")),
        landingPage = getFormattedOption(datasetSheet, 52),
        language = getFormattedOption(datasetSheet, 53),
        modificationDate = getFormattedOption(datasetSheet, 54),
        releaseDate = getFormattedOption(datasetSheet, 55),
        otherIdentifier = getFormattedOption(datasetSheet, 56).map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq),
        qualifiedAttribution = Try(Seq(
          getCellStr(datasetSheet, 57).split(",", -1),
          getCellStr(datasetSheet, 58).split(",", -1)
        ).transpose.filter(value => value.head.nonEmpty).map {
          case Seq(name, role) => QualifiedAttribution(name, toStringOption(role).map(r => s"https://standards.iso.org/iso/19115/resources/Codelists/gml/CI_RoleCode.xml#$r"))
        }).toOption,
        spatialResolution = Try(getCellStr(datasetSheet, 59).toDouble).toOption,
        temporalCoverage = (
          getFormattedOption(datasetSheet, 60),
          getFormattedOption(datasetSheet, 61)
        ) match {
          case (None, None) => None
          case (start, end) => Some(Period(start, end))
        },
        temporalResolution = getFormattedOption(datasetSheet, 62),
        versionNotes = getFormattedOption(datasetSheet, 63),
        wasGeneratedBy = getFormattedOption(datasetSheet, 64).map(_.split(",").map(_.trim).filter(_.nonEmpty).toSeq),
        purpose = getFormattedOption(datasetSheet, 65)
      ),
      distribution = DistributionMetadataUserInput(
        title = getFormattedOption(distSheet, 1),
        applicableLegislation = getFormattedOption(distSheet, 2),
        accessURL = getFormattedOption(distSheet, 3),
        availability = getFormattedOption(distSheet, 4),
        byteSize = Try(getCellStr(distSheet, 5).toDouble.toInt).toOption,
        checksum = {
          val alg = getFormattedOption(distSheet, 6)
          val v = getFormattedOption(distSheet, 7)
          if(alg.isDefined && v.isDefined) Some(Checksum(alg.get, v.get)) else None
        },
        description = getFormattedOption(distSheet, 8),
        format = getFormattedOption(distSheet, 9),
        license = getFormattedOption(distSheet, 10),
        mediaType = getFormattedOption(distSheet, 11),
        packagingFormat = getFormattedOption(distSheet, 12),
        releaseDate = getFormattedOption(distSheet, 13),
        rights = getFormattedOption(distSheet, 14),
        spatialResolution = Try(getCellStr(distSheet, 15).toDouble).toOption,
        status = getFormattedOption(distSheet, 16),
        temporalResolution = getFormattedOption(distSheet, 17),
        accessService = getFormattedOption(distSheet, 18)
      ),
      dataDictionary = if (dataDictionarySheet != null && dataDictionarySheet.getLastRowNum > 1)
        Some((1 until dataDictionarySheet.getLastRowNum).flatMap(row => {
          val r = dataDictionarySheet.getRow(row)
          if (r == null) None
          else {
            val nameVal = r.getCell(0).getStringCellValue.trim
            if (nameVal.isEmpty) None
            else Some(CsvwField(
              name = ensureNotEmpty(r.getCell(0).getStringCellValue, s"Data Dictionary:$row:name"),
              title = ensureNotEmpty(r.getCell(1).getStringCellValue, s"Data Dictionary:$row:title"),
              description = toStringOption(r.getCell(2).getStringCellValue),
              datatype = ensureNotEmpty(r.getCell(3).getStringCellValue, s"Data Dictionary:$row:datatype"),
              propertyUrl = toStringOption(r.getCell(4).getStringCellValue),
              unit = toStringOption(r.getCell(5).getStringCellValue),
              study = toStringOption(r.getCell(6).getStringCellValue),
              group = toStringOption(r.getCell(7).getStringCellValue),
              subpopulation = toStringOption(r.getCell(8).getStringCellValue),
              sampleSize = toStringOption(r.getCell(9).getStringCellValue),
              dataOwner = toStringOption(r.getCell(10).getStringCellValue),
              identifier = toStringOption(r.getCell(11).getStringCellValue),
              selection = toStringOption(r.getCell(12).getStringCellValue),
              parentGroup = toStringOption(r.getCell(13).getStringCellValue),
              responsible = toStringOption(r.getCell(14).getStringCellValue),
              note = toStringOption(r.getCell(15).getStringCellValue),
              minValue = toStringOption(r.getCell(16).getStringCellValue),
              maxValue = toStringOption(r.getCell(17).getStringCellValue),
              required = toStringOption(r.getCell(18).getStringCellValue),
              conditionalOn = toStringOption(r.getCell(19).getStringCellValue),
              // Optional column 21: ISO-8601 duration for variables re-recorded at a
              // fixed interval (e.g. P6M). Absent cells resolve to blank (backward compatible).
              repetitionPeriod = toStringOption(r.getCell(20).getStringCellValue)
            ))
          }
        }).toList)
      else None,
      dataDictionaryValueSets = {
        val valueSetsSheet = resolveSheet("ValueSet")
        if (valueSetsSheet == null) Map.empty[String, Map[String, String]]
        else {
          val builder = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, String]]
          for (rowIdx <- 1 to valueSetsSheet.getLastRowNum) {
            val r = valueSetsSheet.getRow(rowIdx)
            if (r != null) {
              val variable = r.getCell(0).getStringCellValue.trim
              val code = r.getCell(1).getStringCellValue.trim
              val display = r.getCell(2).getStringCellValue.trim
              if (variable.nonEmpty && code.nonEmpty) {
                builder.getOrElseUpdate(variable, mutable.LinkedHashMap.empty)(code) = display
              }
            }
          }
          builder.map { case (k, v) => k -> v.toMap }.toMap
        }
      }
    )
  }
}