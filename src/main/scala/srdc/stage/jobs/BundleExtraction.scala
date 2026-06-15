package srdc.stage.jobs

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import srdc.stage.client.FhirBundleClient
import srdc.stage.config.AppConfig

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Bundle extraction job.
 */
object BundleExtraction {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Resource types */
  private val PatientType = "Patient"
  private val ObservationType = "Observation"
  private val QuestionnaireResponseType = "QuestionnaireResponse"
  private val QuestionnaireType = "Questionnaire"

  /** Default page size */
  private val DefaultPageSize = 1000

  /**
   * Execute the bundle export.
   *
   * @param appConfig Application configuration
   * @return The absolute path to the written file
   */
  def run(appConfig: AppConfig): String = {
    require(
      appConfig.fhirServer != null && appConfig.fhirServer.trim.nonEmpty,
      "Bundle extraction requires a FHIR server. Set --server or fhirServer in application.conf."
    )

    val outDir = Paths.get(appConfig.outputDir, "bundle_extraction")
    if (!Files.exists(outDir)) Files.createDirectories(outDir)
    val outFile = outDir.resolve("bundle.json")

    logger.info(s"Starting bundle extraction. FHIR server: ${appConfig.fhirServer}")

    val dateFilter = buildDateFilter(appConfig, "date")
    val authoredFilter = buildDateFilter(appConfig, "authored")

    // The four resource fetches
    val patients = FhirBundleClient.fetchAll(appConfig, s"$PatientType?_count=$DefaultPageSize")
    val observations = FhirBundleClient.fetchAll(appConfig, s"$ObservationType?_count=$DefaultPageSize$dateFilter")
    val questionnaireResponses = FhirBundleClient.fetchAll(appConfig, s"$QuestionnaireResponseType?_count=$DefaultPageSize$authoredFilter")
    val questionnaires = FhirBundleClient.fetchAll(appConfig, s"$QuestionnaireType?_count=$DefaultPageSize")

    val resourceGroups = Seq(
      PatientType -> patients,
      ObservationType -> observations,
      QuestionnaireResponseType -> questionnaireResponses,
      QuestionnaireType -> questionnaires
    )

    val totalResources = resourceGroups.map(_._2.size).sum
    logger.info(
      s"Fetched: $totalResources resources total (" +
        resourceGroups.map { case (t, rs) => s"$t=${rs.size}" }.mkString(", ") + ")"
    )

    val bundle = assembleTransactionBundle(resourceGroups)

    val mapper = FhirBundleClient.objectMapper
    val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle)

    Files.write(outFile, json.getBytes(StandardCharsets.UTF_8))
    logger.info(s"Bundle written to: ${outFile.toAbsolutePath}")
    outFile.toAbsolutePath.toString
  }

  /**
   * Wrap the collected resources as a single FHIR Bundle of type `transaction`
   */
  private def assembleTransactionBundle(groups: Seq[(String, Seq[JsonNode])]): JsonNode = {
    val bundle = FhirBundleClient.newObject()
    bundle.put("resourceType", "Bundle")
    bundle.put("type", "transaction")
    bundle.put("timestamp", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))

    val entries = FhirBundleClient.newArray()
    groups.foreach { case (resourceType, resources) =>
      resources.foreach { resource =>
        val entry = FhirBundleClient.newObject()
        entry.set[JsonNode]("resource", resource)

        val request = FhirBundleClient.newObject()
        val idNode = resource.get("id")
        val hasId = idNode != null && !idNode.isNull && idNode.asText("").nonEmpty
        if (hasId) {
          request.put("method", "PUT")
          request.put("url", s"$resourceType/${idNode.asText()}")
        } else {
          request.put("method", "POST")
          request.put("url", resourceType)
        }
        entry.set[JsonNode]("request", request)

        entries.add(entry)
      }
    }
    bundle.set[JsonNode]("entry", entries)
    bundle
  }

  /**
   * BaseExtraction.buildDateFilter without Spark connection
   */
  private def buildDateFilter(appConfig: AppConfig, paramName: String): String = {
    val fromPart = appConfig.dateFrom.map(v => s"&$paramName=ge$v").getOrElse("")
    val toPart = appConfig.dateTo.map(v => s"&$paramName=le$v").getOrElse("")
    val combined = fromPart + toPart
    if (combined.nonEmpty) {
      val fromStr = appConfig.dateFrom.getOrElse("(open)")
      val toStr = appConfig.dateTo.getOrElse("(open)")
      logger.info(s"Applying FHIR date filter on $paramName: from=$fromStr to=$toStr")
    }
    combined
  }
}
