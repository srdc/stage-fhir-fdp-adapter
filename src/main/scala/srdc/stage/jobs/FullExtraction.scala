package srdc.stage.jobs

import io.onfhir.spark.SparkOnFhir
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.LoggerFactory
import srdc.stage.config.AppConfig
import srdc.stage.rdf.{DatasetStats, MetadataUserInput}
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
  override def extractDataAndStats(appConfig: AppConfig, staticMetadata: MetadataUserInput)(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): (DataFrame, DatasetStats, String) = {
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

    val observations = ObservationExtraction.extractObservations(rawObs)
    val (surveys, columnOrder, vocabularies, columnMapping) = SurveyExtraction.extractSurveys(rawQR, rawQ)

    val patientProfiles = computePatientProfiles(observations, surveys, columnOrder)

    val baseStats = computeDatasetStats(
      finalProfileDf = patientProfiles,
      rawPatients = rawPat,
      rawResources = Seq(rawObs, rawQR, rawQ, rawPat),
      vocabularies = vocabularies,
      dateSourceDf = Some(rawQR),
      dateColumn = Some("authored")
    )

    val stats = baseStats.copy(columnToVocabId = columnMapping)

    (patientProfiles, stats, "patient_data")
  }
}