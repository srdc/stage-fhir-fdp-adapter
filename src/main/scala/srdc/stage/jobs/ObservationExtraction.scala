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
  def extractObservations(rawObs: DataFrame)(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): DataFrame = {
    import spark.implicits._
    val obsSimple = rawObs
      .fcolumn("subject.getReferenceKey(Patient)", "patient_id")
      .fcolumn("effectiveDateTime", "timestamp", "string")
      .fcolumn("code.coding.display.first().lower()", "metric_name")
      .fcolumn("valueQuantity.value", "v_qty")
      .fcolumn("valueInteger", "v_int")
      .fcolumn("valueString", "v_str")
      .fcolumn("valueCodeableConcept.coding.display.first()", "v_code")
      .fcolumn("valueQuantity.unit", "unit")
      .extract()
      .withColumn("value_raw", coalesce($"v_qty", $"v_int", $"v_str", $"v_code"))
      .withColumn("final_value",
        when($"unit".isNotNull, concat($"value_raw", lit(" "), $"unit"))
          .otherwise($"value_raw")
      )
      .select($"patient_id", $"metric_name".cast("string"), $"final_value".cast("string"))

    val obsComponents = rawObs
      .where("component IS NOT NULL")
      .fcolumn("subject.getReferenceKey(Patient)", "patient_id")
      .fforEach("component", comp =>
        comp
          .fcolumn("code.coding.display.first().lower()", "metric_name")
          .fcolumn("valueQuantity.value", "c_qty")
          .fcolumn("valueInteger", "c_int")
          .fcolumn("valueString", "c_str")
          .fcolumn("valueQuantity.unit", "unit")
      )
      .extract()
      .withColumn("val", coalesce($"c_qty", $"c_int", $"c_str"))
      .withColumn("final_value",
        when($"unit".isNotNull, concat($"val", lit(" "), $"unit"))
          .otherwise($"val")
      )
      .select($"patient_id", $"metric_name".cast("string"), $"final_value".cast("string"))

    obsSimple.union(obsComponents)
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
    val rawObs = sparkOnFhir.load("Observation?_searchafter").cache()
    val rawPat = loadPatients()

    val observations = extractObservations(rawObs)

    val obsCols = observations.select("metric_name").distinct().as[String].collect().sorted.toSeq
    val patientProfiles = computePatientProfiles(observations, obsCols)

    val stats = computeDatasetStats(
      finalProfileDf = patientProfiles,
      rawPatients = rawPat,
      rawResources = Seq(rawObs, rawPat),
      vocabularies = Map.empty,
      dateSourceDf = Some(rawObs),
      dateColumn = Some("effectiveDateTime")
    )

    (patientProfiles, stats, "observation_profiles")
  }
}