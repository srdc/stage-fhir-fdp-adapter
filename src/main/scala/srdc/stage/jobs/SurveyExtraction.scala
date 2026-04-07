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
    val rawQR = sparkOnFhir.load("QuestionnaireResponse?_searchafter").cache()
    val rawQ = sparkOnFhir.load("Questionnaire?_searchafter").cache()
    val rawPat = loadPatients()

    val (surveys, surveyCols, vocabularies, columnMapping) = extractSurveys(rawQR, rawQ)

    val patientProfiles = computePatientProfiles(surveys, surveyCols)

    val baseStats = computeDatasetStats(
      finalProfileDf = patientProfiles,
      rawPatients = rawPat,
      rawResources = Seq(rawQR, rawQ, rawPat),
      vocabularies = vocabularies,
      dateSourceDf = Some(rawQR),
      dateColumn = Some("authored")
    )

    val stats = baseStats.copy(columnToVocabId = columnMapping)

    (patientProfiles, stats, "survey_profiles")
  }

  def extractSurveys(
                              rawQR: DataFrame,
                              rawQ: DataFrame
                            )(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): (DataFrame, Seq[String], Map[String, Map[String, String]], Map[String, String]) = {
    import spark.implicits._

    val rawResponses = rawQR
      .fcolumn("subject.getReferenceKey(Patient)", "patient_id")
      .fcolumn("questionnaire", "questionnaire_ref")
      .frepeat(
        _
          .fcolumn("linkId", "question_id")
          .fcolumn("text", "text_resp")
          .fcolumn("answer[0].valueCoding.code", "val_code")
          .fcolumn("answer[0].valueString", "val_string")
          .fcolumn("answer[0].valueCoding.display", "val_disp_resp"),
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

    val vocabularies = vocabRows
      .groupBy(_.getString(0))
      .map { case (qid, rows) =>
        qid -> rows.map(r => r.getString(1) -> r.getString(2)).toMap
      }

    val defText = flatDefinitions.select("question_id", "text_def").distinct()
    val respText = rawResponses.select("question_id", "text_resp").distinct()

      val allQuestions = defText.join(respText, Seq("question_id"), "outer")
        .withColumn("final_text", coalesce($"text_def", $"text_resp"))
        .where($"final_text".isNotNull)
        .select("question_id", "final_text")
        .distinct()
        .as[(String, String)]
        .collect()
        .sortBy(_._1)

    val orderedColumns = allQuestions.map(_._2).toSeq

    val allQuestionsDf = spark.createDataFrame(allQuestions).toDF("question_id", "final_text")

    val columnMapping = allQuestions.map { case (qid, text) => text -> qid }.toMap

      val codes = rawResponses
        .where($"val_code".isNotNull && $"question_id".isNotNull)
        .select($"patient_id", $"question_id".as("metric_name"), $"val_code".as("final_value"))

      val defOptions = flatDefinitions
        .where($"opt_code".isNotNull)
        .select($"question_id", $"opt_code".as("val_code"), $"opt_display")
        .distinct()

      val displays = rawResponses
        .join(defOptions, Seq("question_id", "val_code"), "left")
        .join(allQuestionsDf, Seq("question_id"), "left")
        .withColumn("resolved_value", coalesce($"opt_display", $"val_disp_resp", $"val_string"))
        .where($"final_text".isNotNull && $"resolved_value".isNotNull)
        .select(
          $"patient_id",
          $"final_text".as("metric_name"),
          $"resolved_value".as("final_value")
        )

    (codes.union(displays), orderedColumns, vocabularies, columnMapping)
  }
}