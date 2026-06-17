package srdc.stage.jobs

import io.onfhir.spark.SparkOnFhir
import io.onfhir.spark.SparkOnFhirConversions._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.Logger
import srdc.stage.config.AppConfig
import srdc.stage.rdf.{DatasetStats, MetadataUserInput, MetadataWriter}
import srdc.stage.util.FileUtils

abstract class BaseExtraction {

  protected val logger: Logger

  def extractDataAndStats(appConfig: AppConfig, staticMetadata: MetadataUserInput)(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): (DataFrame, DatasetStats, String)

  protected def loadPatients()(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): DataFrame =
    sparkOnFhir.load("Patient?_searchafter").cache()

  /**
   * Date Time filter for fhir extraction
   */
  protected def buildDateFilter(appConfig: AppConfig, paramName: String): String = {
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

  protected def computePatientProfiles(
                                        dataDf: DataFrame,
                                        orderedCols: Seq[String]
                                      )(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): DataFrame = {
    dataDf
      .where(col("final_value").isNotNull)
      .groupBy("patient_id")
      .pivot("metric_name", orderedCols)
      .agg(first("final_value"))
  }

  protected def computeDatasetStats(
                                     finalProfileDf: DataFrame,
                                     rawPatients: DataFrame,
                                     rawResources: Seq[DataFrame],
                                     vocabularies: Map[String, Map[String, String]],
                                     dateSourceDf: Option[DataFrame] = None,
                                     dateColumn: Option[String] = None,
                                     patientIdColumn: String = "patient_id"
                                   )(implicit spark: SparkSession, sparkOnFhir: SparkOnFhir): DatasetStats = {
    import spark.implicits._

    val patients = rawPatients
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

    val recordCount = finalProfileDf.count()
    val uniquePatients = finalProfileDf.select(patientIdColumn).distinct().count()
    val columnInfo = finalProfileDf.schema.fields.map(f => (f.name, f.dataType.toString))

    val (startDate, endDate) =
      (dateSourceDf, dateColumn) match {
        case (Some(df), Some(dc)) =>
          val rows = df
            .fcolumn(dc, dc, "string")
            .extract()
            .where(col(dc).isNotNull)
            .withColumn("dateOnly", substring(col(dc), 1, 10))
            .agg(min("dateOnly").as("startDate"), max("dateOnly").as("endDate"))
            .collect()

          val start = if (rows.isEmpty || rows(0).isNullAt(0)) None else Some(rows(0).getString(0))
          val end = if (rows.isEmpty || rows(0).isNullAt(1)) None else Some(rows(0).getString(1))
          (start, end)

        case _ => (None, None)
      }

    val extractSystemsUDF = udf((jsonStr: String) => {
      if (jsonStr == null) Seq.empty[String]
      else {
        val pattern = """"system"\s*:\s*"([^"]+)"""".r
        pattern.findAllMatchIn(jsonStr).map(_.group(1)).toSeq
      }
    })

    val extractedSystems = rawResources.map { df =>
        df.select(explode(extractSystemsUDF(to_json(struct(col("*"))))).as("system"))
      }.reduce(_ union _)
      .distinct()
      .where(col("system").isNotNull && col("system") =!= "")
      .as[String]
      .collect()
      .toSeq

    DatasetStats(
      recordCount,
      uniquePatients,
      minAge,
      maxAge,
      columnInfo,
      vocabularies,
      Map.empty[String, String],
      startDate,
      endDate,
      extractedSystems
    )
  }

  def exportResultsWithState(
                              cfg: AppConfig,
                              meta: MetadataUserInput,
                              dataDf: DataFrame,
                              jobStats: DatasetStats,
                              globalStats: DatasetStats,
                              subFolder: String,
                              catalogUri: Option[String]
                            ): String = {

    FileUtils.saveData(dataDf, s"${cfg.outputDir}/$subFolder", "csv")
    logger.info("Saved patient data to: {}/{}", cfg.outputDir, subFolder)

    MetadataWriter.exportResults(
      outputDir = s"${cfg.outputDir}/$subFolder/rdf",
      fdpUrl = cfg.fdpUrl.getOrElse(""),
      fdpEmail = cfg.fdpEmail.getOrElse(""),
      fdpPassword = cfg.fdpPassword.getOrElse(""),
      meta = meta,
      jobStats = jobStats,
      globalStats = globalStats,
      runMode = cfg.runMode,
      sharedCatalogUri = catalogUri,
      isFhirConfigured = true,
      vocabBase = cfg.vocabBase
    )
  }

  /**
   * Export used when no FHIR server is configured.
   */
  def exportResultsNoFhir(
                           cfg: AppConfig,
                           meta: MetadataUserInput,
                           subFolder: String,
                           catalogUri: Option[String]
                         ): String = {
    val emptyStats = DatasetStats(0, 0, 0, 0, Array.empty)
    MetadataWriter.exportResults(
      outputDir = s"${cfg.outputDir}/$subFolder/rdf",
      fdpUrl = cfg.fdpUrl.getOrElse(""),
      fdpEmail = cfg.fdpEmail.getOrElse(""),
      fdpPassword = cfg.fdpPassword.getOrElse(""),
      meta = meta,
      jobStats = emptyStats,
      globalStats = emptyStats,
      runMode = cfg.runMode,
      sharedCatalogUri = catalogUri,
      isFhirConfigured = false,
      vocabBase = cfg.vocabBase
    )
  }
}