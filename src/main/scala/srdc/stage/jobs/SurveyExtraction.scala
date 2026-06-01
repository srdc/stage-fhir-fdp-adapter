package srdc.stage.jobs

import io.onfhir.spark.SparkOnFhir
import io.onfhir.spark.SparkOnFhirConversions._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.config.AppConfig
import srdc.stage.rdf.{DatasetStats, MetadataUserInput}

object SurveyExtraction extends BaseExtraction {

  override protected val logger: Logger = LoggerFactory.getLogger(getClass)

  override def extractDataAndStats(appConfig: AppConfig, staticMetadata: MetadataUserInput)(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): (DataFrame, DatasetStats, String) = {
    import spark.implicits._
    val rawQR = sparkOnFhir.load(s"QuestionnaireResponse?_searchafter${buildDateFilter(appConfig, "authored")}").cache()
    val rawQ = sparkOnFhir.load("Questionnaire?_searchafter").cache()
    val rawPat = loadPatients()

    val (surveys, vocabularies, _) = extractSurveys(rawQR, rawQ, rawPat)
    val questionCodeRows = surveys
      .where($"code".isNotNull && $"display".isNotNull)
      .select("code", "display")
      .distinct()
      .as[(String, String)]
      .collect()

    val questionCodesVocabId = "question_codes"
    val mergedVocabularies: Map[String, Map[String, String]] =
      if (questionCodeRows.nonEmpty) vocabularies + (questionCodesVocabId -> questionCodeRows.toMap)
      else vocabularies

    val columnMapping: Map[String, String] =
      if (questionCodeRows.nonEmpty) Map("code" -> questionCodesVocabId, "display" -> questionCodesVocabId)
      else Map.empty

    val baseStats = computeDatasetStats(
      finalProfileDf = surveys,
      rawPatients = rawPat,
      rawResources = Seq(rawQR, rawQ, rawPat),
      vocabularies = mergedVocabularies,
      dateSourceDf = Some(rawQR),
      dateColumn = Some("authored"),
      patientIdColumn = "pid"
    )

    val stats = baseStats.copy(columnToVocabId = columnMapping)

    (surveys, stats, "survey_profiles")
  }

  def extractSurveys(
                              rawQR: DataFrame,
                              rawQ: DataFrame,
                              rawPat: DataFrame
                            )(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): (DataFrame, Map[String, Map[String, String]], Map[String, String]) = {
    import spark.implicits._

    val patientIdMap = rawPat
      .fcolumn("id", "patient_uuid")
      .fcolumn("identifier[0].value", "patient_identifier")
      .extract()
      .withColumn("pid", coalesce($"patient_identifier", $"patient_uuid"))
      .select($"patient_uuid", $"pid")

    val rawResponses = rawQR
      .fcolumn("subject.getReferenceKey(Patient)", "patient_uuid")
      .fcolumn("authored", "date", "string")
      .fcolumn("questionnaire", "questionnaire_ref")
      .frepeat(
        _
          .fcolumn("linkId", "question_id")
          .fcolumn("text", "text_resp")
          .fcolumn("answer[0].valueCoding.code", "val_code")
          .fcolumn("answer[0].valueCoding.display", "val_disp_resp")
          .fcolumn("answer[0].valueString", "val_string")
          .fcolumn("answer[0].valueInteger", "val_integer")
          .fcolumn("answer[0].valueDecimal", "val_decimal")
          .fcolumn("answer[0].valueBoolean", "val_boolean")
          .fcolumn("answer[0].valueDate", "val_date", "string")
          .fcolumn("answer[0].valueDateTime", "val_datetime", "string")
          .fcolumn("answer[0].valueTime", "val_time", "string")
          .fcolumn("answer[0].valueUri", "val_uri")
          .fcolumn("answer[0].valueQuantity.value", "val_qty")
          .fcolumn("answer[0].valueQuantity.unit", "val_qty_unit"),
        "item",
        "answer.item"
      )
      .extract()

    val usedQuestionnaireIds = rawResponses
      .select(element_at(split($"questionnaire_ref", "/"), -1).as("q_id"))
      .where($"q_id".isNotNull)
      .distinct()
      .as[String]
      .collect()

    val rawDefinitions = rawQ
      .where($"id".isInCollection(usedQuestionnaireIds))
      .frepeat(
        _
          .fcolumn("linkId", "question_id")
          .fcolumn("text", "text_def")
          .fcolumn("answerOption.valueCoding.code", "opt_codes")
          .fcolumn("answerOption.valueCoding.display", "opt_disps")
          .fcolumn("answerValueSet", "value_set_url"),
        "item",
        "item"
      )
      .extract()

    val flatDefinitions = rawDefinitions
      .select(
        $"question_id",
        $"text_def",
        $"value_set_url",
        posexplode_outer($"opt_codes").as(Seq("pos", "opt_code")),
        $"opt_disps"
      )
      .withColumn("opt_display", expr("opt_disps[pos]"))
      .select("question_id", "text_def", "value_set_url", "opt_code", "opt_display")
      .distinct()
      .cache()

    val vocabRows = flatDefinitions
      .where($"question_id".isNotNull && $"opt_code".isNotNull && $"opt_display".isNotNull)
      .select("question_id", "opt_code", "opt_display")
      .distinct()
      .collect()

    val vocabularies: Map[String, Map[String, String]] = vocabRows
      .groupBy(_.getString(0))
      .map { case (qid, rows) =>
        qid -> rows.map(r => r.getString(1) -> r.getString(2)).toMap
      }

    val defOptions = flatDefinitions
      .where($"opt_code".isNotNull)
      .select($"question_id", $"opt_code".as("val_code"), $"opt_display")
      .distinct()

    val defText = flatDefinitions
      .select("question_id", "text_def")
      .where($"text_def".isNotNull)
      .distinct()

    val codedAnswer = coalesce($"opt_display", $"val_disp_resp")
    val primitiveAnswer = coalesce(
      $"val_string",
      $"val_integer".cast("string"),
      $"val_decimal".cast("string"),
      $"val_boolean".cast("string"),
      $"val_date",
      $"val_datetime",
      $"val_time",
      $"val_uri"
    )
    val quantityAnswer =
      when($"val_qty".isNotNull && $"val_qty_unit".isNotNull,
        concat($"val_qty".cast("string"), lit(" "), $"val_qty_unit"))
        .when($"val_qty".isNotNull, $"val_qty".cast("string"))
        .otherwise(lit(null).cast("string"))

    val resolved = rawResponses
      .where($"question_id".isNotNull)
      .join(defOptions, Seq("question_id", "val_code"), "left")
      .join(defText, Seq("question_id"), "left")
      .withColumn("display", coalesce($"text_def", $"text_resp"))
      .withColumn("answer", coalesce(codedAnswer, primitiveAnswer, quantityAnswer, $"val_code"))
      .where($"answer".isNotNull && $"display".isNotNull)
      .select(
        $"patient_uuid",
        $"question_id".as("code"),
        $"display".cast("string"),
        $"answer".cast("string"),
        $"date".cast("string")
      )

    val surveys = resolved
      .join(patientIdMap, Seq("patient_uuid"), "left")
      .withColumn("pid", coalesce($"pid", $"patient_uuid"))
      .select($"pid", $"code", $"display", $"answer", $"date")

    (surveys, vocabularies, Map.empty[String, String])
  }
}