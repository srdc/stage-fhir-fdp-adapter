package srdc.stage.jobs

import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.usermodel.Row.MissingCellPolicy
import org.slf4j.LoggerFactory
import srdc.stage.config.AppConfig
import srdc.stage.rdf.{CsvwField, MetadataWriter}
import srdc.stage.util.RdfUtils

import java.io.File
import java.nio.file.{Files, Paths}
import scala.collection.mutable

/**
 * Standalone job for generating a Dictionary.ttl from Excel.
 */
object DictionaryGeneration {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Runs the dictionary generation pipeline.
   *
   * @param config The application configuration
   */
  def run(config: AppConfig): Unit = {
    logger.info("Starting Dictionary Generation job...")
    logger.info("Reading from Excel: {}", config.excelPath)

    val file = new File(config.excelPath)
    if (!file.exists()) {
      throw new IllegalArgumentException(s"Excel file not found: ${config.excelPath}")
    }

    val wb = WorkbookFactory.create(file)
    wb.setMissingCellPolicy(MissingCellPolicy.CREATE_NULL_AS_BLANK)

    def toStringOption(str: String): Option[String] = str.trim match {
      case s if s.isEmpty => None
      case s => Some(s)
    }
    def ensureNotEmpty(str: String, field: String): String = {
      if (str.trim.isEmpty) throw new IllegalArgumentException(s"The '$field' field cannot be empty.")
      str.trim
    }

    // Data Dictionary
    val dataDictionarySheet = wb.getSheet("Data Dictionary")
    if (dataDictionarySheet == null) {
      wb.close()
      throw new IllegalArgumentException("Required sheet 'Data Dictionary' not found in Excel workbook.")
    }

    val lastRow = dataDictionarySheet.getLastRowNum
    if (lastRow < 1) {
      wb.close()
      throw new IllegalArgumentException("Data Dictionary sheet has no data rows.")
    }

    val fields: List[CsvwField] = (1 until lastRow).flatMap { row =>
      val r = dataDictionarySheet.getRow(row)
      if (r == null) None
      else {
        val nameVal = r.getCell(0).getStringCellValue.trim
        if (nameVal.isEmpty) None
        else Some(CsvwField(
          name = ensureNotEmpty(r.getCell(0).getStringCellValue, s"Data Dictionary:$row:name"),
          title = ensureNotEmpty(r.getCell(1).getStringCellValue, s"Data Dictionary:$row:title"),
          description = toStringOption(r.getCell(2).getStringCellValue),
          datatype = ensureNotEmpty(r.getCell(3).getStringCellValue, s"Data Dictionary:$row:datatype"),
          propertyUrl = toStringOption(r.getCell(4).getStringCellValue),
          unit = toStringOption(r.getCell(5).getStringCellValue)
        ))
      }
    }.toList

    logger.info("Parsed {} variable definitions from Data Dictionary sheet.", fields.size)

    // Value Sets (optional)
    val vocabularies: Map[String, Map[String, String]] = {
      val valueSetsSheet = wb.getSheet("Value Sets")
      if (valueSetsSheet == null) {
        logger.info("No 'Value Sets' sheet found — skipping vocabulary generation.")
        Map.empty
      } else {
        val vsLastRow = valueSetsSheet.getLastRowNum
        val vocabBuilder = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, String]]

        for (rowIdx <- 1 to vsLastRow) {
          val row = valueSetsSheet.getRow(rowIdx)
          if (row != null) {
            val variable = row.getCell(0).getStringCellValue.trim
            val code = row.getCell(1).getStringCellValue.trim
            val display = row.getCell(2).getStringCellValue.trim
            if (variable.nonEmpty && code.nonEmpty) {
              vocabBuilder.getOrElseUpdate(variable, mutable.LinkedHashMap.empty)(code) = display
            }
          }
        }

        val result = vocabBuilder.map { case (k, v) => k -> v.toMap }.toMap
        logger.info("Parsed {} value set entries across {} variables.", result.values.map(_.size).sum, result.size)
        result
      }
    }

    wb.close()

    if (fields.isEmpty) {
      throw new IllegalArgumentException("No valid entries found in the Data Dictionary sheet.")
    }

    // Generate the Dictionary RDF model
    val model = MetadataWriter.createDictionaryModel(fields, vocabularies)

    // Ensure output directory exists
    val rdfDir = Paths.get(config.outputDir, "rdf")
    if (!Files.exists(rdfDir)) {
      logger.info("Creating output directory: {}", rdfDir)
      Files.createDirectories(rdfDir)
    }

    val outputPath = rdfDir.resolve("Dictionary.ttl").toString
    logger.info("Writing Dictionary.ttl to: {}", outputPath)
    RdfUtils.writeTurtle(outputPath, model)

    logger.info("Dictionary Generation completed successfully. Output: {}", outputPath)
  }
}