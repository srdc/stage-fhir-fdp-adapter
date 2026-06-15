package srdc.stage.jobs

import io.onfhir.spark.SparkOnFhir
import io.onfhir.spark.SparkOnFhirConversions._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.config.AppConfig
import srdc.stage.rdf.{DatasetStats, MetadataUserInput}

/**
 * Main ETL pipeline for extracting Survey and Observation data.
 * Orchestrates configuration loading, resource extraction, profile generation,
 * metadata calculation, and final export.
 */
object ObservationExtraction extends BaseExtraction {

  override protected val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Extracts Observation resources from the FHIR server.
   * Handles both root-level simple observations and component-based observations.
   *
   * @return A DataFrame with columns [patient_id, metric_name, final_value].
   */
  def extractObservations(rawObs: DataFrame, rawPat: DataFrame)(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): DataFrame = {
    import spark.implicits._
    val patientIdMap = rawPat
      .fcolumn("id", "patient_uuid")
      .fcolumn("identifier[0].value", "patient_identifier")
      .extract()
      .withColumn("pid", coalesce($"patient_identifier", $"patient_uuid"))
      .select($"patient_uuid", $"pid")

    val obsSimple = rawObs
      .fcolumn("subject.getReferenceKey(Patient)", "patient_uuid")
      .fcolumn("effectiveDateTime", "date", "string")
      .fcolumn("code.coding.code.first()", "code")
      .fcolumn("code.coding.display.first()", "display")
      .fcolumn("valueQuantity.value", "v_qty")
      .fcolumn("valueInteger", "v_int")
      .fcolumn("valueString", "v_str")
      .fcolumn("valueCodeableConcept.coding.display.first()", "v_code")
      .fcolumn("valueQuantity.unit", "unit")
      .extract()
      .withColumn("value_raw", coalesce($"v_qty", $"v_int", $"v_str", $"v_code"))
      .withColumn("value",
        when($"unit".isNotNull, concat($"value_raw", lit(" "), $"unit"))
          .otherwise($"value_raw")
      )
      .select(
        $"patient_uuid",
        $"code".cast("string"),
        $"display".cast("string"),
        $"value".cast("string"),
        $"date".cast("string")
      )

    val obsComponents = rawObs
      .where("component IS NOT NULL")
      .fcolumn("subject.getReferenceKey(Patient)", "patient_uuid")
      .fcolumn("effectiveDateTime", "date", "string")
      .fforEach("component", comp =>
        comp
          .fcolumn("code.coding.code.first()", "code")
          .fcolumn("code.coding.display.first()", "display")
          .fcolumn("valueQuantity.value", "c_qty")
          .fcolumn("valueInteger", "c_int")
          .fcolumn("valueString", "c_str")
          .fcolumn("valueBoolean", "c_bool")
          .fcolumn("valueDateTime", "c_dt", "string")
          .fcolumn("valueCodeableConcept.coding.display.first()", "c_code")
          .fcolumn("valueQuantity.unit", "unit")
      )
      .extract()
      .withColumn("val",
        coalesce(
          $"c_qty".cast("string"), $"c_int".cast("string"),
          $"c_str", $"c_bool".cast("string"), $"c_dt", $"c_code"
        )
      )
      .withColumn("value",
        when($"unit".isNotNull, concat($"val", lit(" "), $"unit"))
          .otherwise($"val")
      )
      .select(
        $"patient_uuid",
        $"code".cast("string"),
        $"display".cast("string"),
        $"value".cast("string"),
        $"date".cast("string")
      )

    obsSimple
      .union(obsComponents)
      .where($"value".isNotNull)
      .join(patientIdMap, Seq("patient_uuid"), "left")
      // If no identifier, use FHIR UUID
      .withColumn("pid", coalesce($"pid", $"patient_uuid"))
      .select($"pid", $"code", $"display", $"value", $"date")
  }

  /**
   * Execute the Observation Extraction pipeline.
   *
   * @param appConfig      The base application configuration object passed from the CLI.
   * @param staticMetadata The loaded static metadata configuration (from JSON, Excel, or Browser).
   * @param spark          The active SparkSession.
   * @param sparkOnFhir    The active SparkOnFhir entry point.
   */
  override def extractDataAndStats(appConfig: AppConfig, staticMetadata: MetadataUserInput)(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): (DataFrame, DatasetStats, String) = {
    import spark.implicits._

    // Load the raw FHIR DataFrames ONCE to avoid redundant server calls
    val rawObs = sparkOnFhir.load(s"Observation?_searchafter${buildDateFilter(appConfig, "date")}").cache()
    val rawPat = loadPatients()

    val observations = extractObservations(rawObs, rawPat)

    // Build a SKOS vocabulary
    val vocabRows = observations
      .where($"code".isNotNull && $"display".isNotNull)
      .select("code", "display")
      .distinct()
      .as[(String, String)]
      .collect()

    val observationVocabId = "observation_codes"
    val vocabularies: Map[String, Map[String, String]] =
      if (vocabRows.nonEmpty) Map(observationVocabId -> vocabRows.toMap)
      else Map.empty

    val columnMapping: Map[String, String] =
      if (vocabRows.nonEmpty) Map("code" -> observationVocabId, "display" -> observationVocabId)
      else Map.empty

    val baseStats = computeDatasetStats(
      finalProfileDf = observations,
      rawPatients = rawPat,
      rawResources = Seq(rawObs, rawPat),
      vocabularies = vocabularies,
      dateSourceDf = Some(rawObs),
      dateColumn = Some("effectiveDateTime"),
      patientIdColumn = "pid"
    )

    val stats = baseStats.copy(columnToVocabId = columnMapping)

    (observations, stats, "observation_profiles")
  }
}