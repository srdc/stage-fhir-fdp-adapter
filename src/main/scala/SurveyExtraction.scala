import io.onfhir.spark.SparkOnFhir
import io.onfhir.spark.SparkOnFhirConversions._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

/**
 * Main ETL pipeline for extracting Survey and Observation data.
 * Orchestrates configuration loading, resource extraction, profile generation,
 * metadata calculation, and final export.
 */
object SurveyExtraction {

  /**
   * Execute the Survey Extraction pipeline.
   *
   * @param appConfig      The base application configuration object passed from the CLI.
   * @param staticMetadata The loaded static metadata configuration (from JSON, Excel, or Browser).
   * @param spark          The active SparkSession.
   * @param sparkOnFhir    The active SparkOnFhir entry point.
   */
  def run(appConfig: AppConfig, staticMetadata: ConfigLoader)(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): Unit = {
    import spark.implicits._

    // Load the raw FHIR DataFrames ONCE to avoid redundant server calls
    val rawObs = sparkOnFhir.load("Observation").cache()
    val rawQR = sparkOnFhir.load("QuestionnaireResponse").cache()
    val rawQ = sparkOnFhir.load("Questionnaire").cache()
    val rawPat = sparkOnFhir.load("Patient").cache()

    /**
     * Extracts Observation resources from the FHIR server.
     * Handles both root-level simple observations and component-based observations.
     *
     * @return A DataFrame with columns [patient_id, metric_name, final_value].
     */
    def extractObservations(): DataFrame = {
      val obsSimple = rawObs
        .fcolumn("subject.getReferenceKey(Patient)", "patient_id")
        .fcolumn("effectiveDateTime", "timestamp", "string")
        .fcolumn("code.coding.display.first()", "metric_name")
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
            .fcolumn("code.coding.display.first()", "metric_name")
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
     * Extracts survey data by joining QuestionnaireResponses with their defining Questionnaires.
     * Builds vocabularies by looking up answer options in the Questionnaire definition.
     *
     * @return A tuple containing the survey DataFrame, column order sequence, and extracted vocabularies.
     */
    def extractSurveys(): (DataFrame, Seq[String], Map[String, Map[String, String]]) = {
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

      val vocabularies: Map[String, Map[String, String]] = vocabRows
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

      val orderedColumns = allQuestions.flatMap { case (id, text) => Seq(id, text) }

      val codes = rawResponses
        .where($"val_code".isNotNull && $"question_id".isNotNull)
        .select($"patient_id", $"question_id".as("metric_name"), $"val_code".as("final_value"))

      val defOptions = flatDefinitions
        .where($"opt_code".isNotNull)
        .select($"question_id", $"opt_code".as("val_code"), $"opt_display")
        .distinct()

      val allQuestionsDf = spark.createDataFrame(allQuestions).toDF("question_id", "final_text")

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

      (codes.union(displays), orderedColumns, vocabularies)
    }

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

    /**
     * Exports the results to disk and triggers metadata generation.
     *
     * @param cfg    The application configuration containing export and FDP settings.
     * @param meta   The static metadata configuration loader.
     * @param dataDf The finalized patient profile DataFrame to save as CSV.
     * @param stats  The dynamically extracted Dataset statistics.
     */
    def exportResults(cfg: AppConfig, meta: ConfigLoader, dataDf: DataFrame, stats: DatasetStats): Unit = {
      FileUtils.saveData(dataDf, s"${cfg.outputDir}/patient_profiles", "csv")
      println(s"Saved patient data to: ${cfg.outputDir}/patient_profiles")

      MetadataWriter.exportResults(
        outputDir = cfg.outputDir,
        fdpUrl = cfg.fdpUrl.getOrElse(""),
        fdpEmail = cfg.fdpEmail.getOrElse(""),
        fdpPassword = cfg.fdpPassword.getOrElse(""),
        meta = meta,
        stats = stats,
        runMode = cfg.runMode
      )
    }

    val observations = extractObservations()
    val (surveys, columnOrder, vocabularies) = extractSurveys()

    val patientProfiles = computePatientProfiles(observations, surveys, columnOrder)
    val stats = computeDatasetStats(patientProfiles, vocabularies)

    exportResults(appConfig, staticMetadata, patientProfiles, stats)
  }
}