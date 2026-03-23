package srdc.stage.jobs

import io.onfhir.spark.SparkOnFhir
import io.onfhir.spark.SparkOnFhirConversions._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.LoggerFactory
import srdc.stage.config.AppConfig
import srdc.stage.rdf.{DatasetStats, MetadataUserInput, MetadataWriter}
import srdc.stage.util.FileUtils

/**
 * Main ETL pipeline for extracting Survey and Observation data.
 * Orchestrates configuration loading, resource extraction, profile generation,
 * metadata calculation, and final export.
 */
object FullExtraction extends BaseExtraction {

  override protected val logger = LoggerFactory.getLogger(getClass)

  /**
   * Execute the Survey Extraction pipeline.
   *
   * @param appConfig      The base application configuration object passed from the CLI.
   * @param staticMetadata The loaded static metadata configuration (from JSON, Excel, or Browser).
   * @param spark          The active SparkSession.
   * @param sparkOnFhir    The active SparkOnFhir entry point.
   */
  override def run(appConfig: AppConfig, staticMetadata: MetadataUserInput)(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): Unit = {
    import spark.implicits._

    // Load the raw FHIR DataFrames ONCE to avoid redundant server calls
    val rawObs = sparkOnFhir.load("Observation?_searchafter").cache()
    val rawQR = sparkOnFhir.load("QuestionnaireResponse?_searchafter").cache()
    val rawQ = sparkOnFhir.load("Questionnaire?_searchafter").cache()
    val rawPat = sparkOnFhir.load("Patient?_searchafter").cache()

    /**
     * Pivots the long-format data into a wide patient profile.
     *
     * @param obsDf      DataFrame containing Observation data.
     * @param surveysDf  DataFrame containing Survey data.
     * @param surveyCols Sequence of survey column names to force specific ordering.
     * @return Wide-format DataFrame with one row per patient.
     */
    def computePatientProfiles(obsDf: DataFrame, surveysDf: DataFrame, surveyCols: Seq[String]): DataFrame = {
      val obsCols = obsDf.select("metric_name").distinct().as[String].collect().sorted.toSeq
      val allPivotCols = obsCols ++ surveyCols
      val combinedData = obsDf.union(surveysDf).where($"final_value".isNotNull)

      combinedData
        .groupBy("patient_id")
        .pivot("metric_name", allPivotCols)
        .agg(first("final_value"))
    }

    /**
     * Calculates statistical metadata for the Dataset description.
     * Extracts age aggregates, row counts, temporal coverages, and coding systems directly from the FHIR resources.
     *
     * @param finalProfileDf The processed patient profile DataFrame.
     * @param vocabularies   The extracted SKOS vocabularies.
     * @return A DatasetStats object containing counts, age ranges, dates, and dynamically extracted coding systems.
     */
    def computeDatasetStats(finalProfileDf: DataFrame, vocabularies: Map[String, Map[String, String]]): DatasetStats = {
      // 1. Patient Age Aggregation
      val patients = rawPat
        .fcolumn("id", "patient_id")
        .fcolumn("birthDate", "birthDate")
        .extract()
        .select("patient_id", "birthDate")

      val currentYear = java.time.LocalDate.now().getYear

      val ageStats = patients
        .withColumn("birthYear", substring(col("birthDate"), 0, 4).cast("int"))
        .where(col("birthYear").isNotNull)
        .withColumn("age", lit(currentYear) - col("birthYear"))
        .agg(min("age").as("minAge"), max("age").as("maxAge"))
        .collect()(0)

      val minAge = if (ageStats.isNullAt(0)) 0 else ageStats.getInt(0)
      val maxAge = if (ageStats.isNullAt(1)) 0 else ageStats.getInt(1)

      // 2. Profile Coverage
      val recordCount = finalProfileDf.count()
      val uniquePatients = finalProfileDf.select("patient_id").distinct().count()
      val columnInfo = finalProfileDf.schema.fields.map(f => (f.name, f.dataType.toString))

      // 3. Temporal Coverage (QuestionnaireResponse authored dates)
      val qrDates = rawQR
        .fcolumn("authored", "authored", "string")
        .extract()
        .where(col("authored").isNotNull)
        .withColumn("dateOnly", substring(col("authored"), 1, 10))
        .agg(min("dateOnly").as("startDate"), max("dateOnly").as("endDate"))
        .collect()

      val startDate = if (qrDates.isEmpty || qrDates(0).isNullAt(0)) None else Some(qrDates(0).getString(0))
      val endDate = if (qrDates.isEmpty || qrDates(0).isNullAt(1)) None else Some(qrDates(0).getString(1))

      // 4. Extract Unique Coding Systems across all levels of the FHIR resources
      // Uses a UDF to stringify the row into JSON and regex extract all "system" values
      val extractSystemsUDF = udf((jsonStr: String) => {
        if (jsonStr == null) Seq.empty[String]
        else {
          val pattern = """"system"\s*:\s*"([^"]+)"""".r
          pattern.findAllMatchIn(jsonStr).map(_.group(1)).toSeq
        }
      })

      val allRawDfs = Seq(rawObs, rawQR, rawQ, rawPat)
      val extractedSystems = allRawDfs.map { df =>
          df.select(explode(extractSystemsUDF(to_json(struct(col("*"))))).as("system"))
        }.reduce(_ union _)
        .distinct()
        .where($"system".isNotNull && $"system" =!= "")
        .as[String]
        .collect()
        .toSeq

      DatasetStats(recordCount, uniquePatients, minAge, maxAge, columnInfo, vocabularies, startDate, endDate, extractedSystems)
    }

    val observations = ObservationExtraction.extractObservations(rawObs)
    val (surveys, columnOrder, vocabularies) = SurveyExtraction.extractSurveys(rawQR, rawQ)

    val patientProfiles = computePatientProfiles(observations, surveys, columnOrder)
    val stats = computeDatasetStats(patientProfiles, vocabularies)

    exportResults(appConfig, staticMetadata, patientProfiles, stats, "patient_data")
  }
}