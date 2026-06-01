package srdc.stage.jobs

import io.onfhir.spark.SparkOnFhir
import org.apache.spark.sql.functions.{col, max, min, substring}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.LoggerFactory
import srdc.stage.config.AppConfig
import srdc.stage.rdf.{DatasetStats, MetadataUserInput}
object FullExtraction extends BaseExtraction {

  override protected val logger = LoggerFactory.getLogger(getClass)

  /**
   * Execute the Full Extraction pipeline.
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
    val rawQR = sparkOnFhir.load(s"QuestionnaireResponse?_searchafter${buildDateFilter(appConfig, "authored")}").cache()
    val rawQ = sparkOnFhir.load("Questionnaire?_searchafter").cache()
    val rawPat = loadPatients()

    // Run both
    val observations = ObservationExtraction.extractObservations(rawObs, rawPat)
    val (surveys, surveyVocabularies, _) = SurveyExtraction.extractSurveys(rawQR, rawQ, rawPat)

    // Normalize the Survey schema so the union is column-compatible
    val surveysNormalized = surveys
      .select(
        $"pid",
        $"code",
        $"display",
        $"answer".as("value"),
        $"date"
      )

    val combined = observations.union(surveysNormalized)

    // Build the unified vocabulary set
    val observationVocabRows = observations
      .where($"code".isNotNull && $"display".isNotNull)
      .select("code", "display")
      .distinct()
      .as[(String, String)]
      .collect()

    val questionCodeRows = surveys
      .where($"code".isNotNull && $"display".isNotNull)
      .select("code", "display")
      .distinct()
      .as[(String, String)]
      .collect()

    val observationVocabId = "observation_codes"
    val questionCodesVocabId = "question_codes"

    val vocabularies: Map[String, Map[String, String]] = {
      val base = surveyVocabularies
      val withObs =
        if (observationVocabRows.nonEmpty) base + (observationVocabId -> observationVocabRows.toMap)
        else base
      val withQ =
        if (questionCodeRows.nonEmpty) withObs + (questionCodesVocabId -> questionCodeRows.toMap)
        else withObs
      withQ
    }
    val codeTarget =
      if (observationVocabRows.nonEmpty) Some(observationVocabId)
      else if (questionCodeRows.nonEmpty) Some(questionCodesVocabId)
      else None

    val columnMapping: Map[String, String] = codeTarget match {
      case Some(target) => Map("code" -> target, "display" -> target)
      case None         => Map.empty
    }

    val baseStats = computeDatasetStats(
      finalProfileDf = combined,
      rawPatients = rawPat,
      rawResources = Seq(rawObs, rawQR, rawQ, rawPat),
      vocabularies = vocabularies,
      dateSourceDf = None,
      dateColumn = None,
      patientIdColumn = "pid"
    )

    val dateRows = combined
      .where(col("date").isNotNull)
      .withColumn("dateOnly", substring(col("date"), 1, 10))
      .agg(min("dateOnly").as("startDate"), max("dateOnly").as("endDate"))
      .collect()

    val startDate =
      if (dateRows.isEmpty || dateRows(0).isNullAt(0)) None
      else Some(dateRows(0).getString(0))

    val endDate =
      if (dateRows.isEmpty || dateRows(0).isNullAt(1)) None
      else Some(dateRows(0).getString(1))

    val stats = baseStats.copy(
      columnToVocabId = columnMapping,
      startDate = startDate,
      endDate = endDate
    )

    (combined, stats, "patient_data")
  }
}