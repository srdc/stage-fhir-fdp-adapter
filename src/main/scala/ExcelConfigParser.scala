import java.io.File
import org.apache.poi.ss.usermodel.{DataFormatter, WorkbookFactory}
import scala.jdk.CollectionConverters._

/**
 * Utility to load application configuration from an Excel file.
 *
 * Assumes a simple Key-Value structure in the first sheet:
 * - Column A: Parameter Name (e.g., "catalogTitle")
 * - Column B: Parameter Value (e.g., "My Great Catalog")
 */
object ExcelConfigParser {

  /**
   * Reads the specified Excel file and maps its contents to a Config object.
   *
   * @param filePath Path to the .xlsx file.
   * @param baseConfig The default configuration to use as a fallback.
   * @return A Config object populated with values from the Excel sheet.
   */
  def parse(filePath: String, baseConfig: Config): Config = {
    val file = new File(filePath)
    if (!file.exists()) {
      throw new IllegalArgumentException(s"Excel file not found at: $filePath")
    }

    println(s"Reading configuration from Excel: $filePath")

    // Open the workbook and select the first sheet
    val workbook = WorkbookFactory.create(file)
    val sheet = workbook.getSheetAt(0)
    val formatter = new DataFormatter()

    // Iterate over rows and extract Key-Value pairs
    val data = sheet.asScala.map { row =>
      val keyCell = row.getCell(0)
      val valCell = row.getCell(1)

      // Safely format cells to strings, handling nulls
      val key = if (keyCell != null) formatter.formatCellValue(keyCell).trim else ""
      val value = if (valCell != null) formatter.formatCellValue(valCell).trim else ""

      key -> value
    }.toMap.filter(_._1.nonEmpty) // Remove empty keys

    workbook.close()

    // Apply the extracted map to the Config object
    mapToConfig(data, baseConfig)
  }

  /**
   * Maps the raw string map from Excel to the specific fields of the Config case class.
   *
   * @param data The map of keys and values extracted from Excel.
   * @param defaults The default configuration values.
   * @return The updated Config object.
   */
  private def mapToConfig(data: Map[String, String], defaults: Config): Config = {
    defaults.copy(
      // Catalog Settings
      catalogTitle = data.getOrElse("catalogTitle", defaults.catalogTitle),
      catalogDescription = data.getOrElse("catalogDescription", defaults.catalogDescription),
      catalogUri = data.getOrElse("catalogUri", defaults.catalogUri),
      catalogIsExisting = data.get("catalogIsExisting").exists(_.toBoolean),
      catalogStartDate = data.getOrElse("catalogStartDate", defaults.catalogStartDate),
      catalogEndDate = data.getOrElse("catalogEndDate", defaults.catalogEndDate),

      // Dataset Settings
      datasetTitle = data.getOrElse("datasetTitle", defaults.datasetTitle),
      datasetDescription = data.getOrElse("datasetDescription", defaults.datasetDescription),
      datasetIdentifier = data.getOrElse("datasetIdentifier", defaults.datasetIdentifier),
      datasetVersion = data.getOrElse("datasetVersion", defaults.datasetVersion),
      // Split comma-separated keywords into a sequence
      datasetKeywords = data.get("datasetKeywords").map(_.split(",").map(_.trim).toSeq).getOrElse(defaults.datasetKeywords),
      datasetTheme = data.getOrElse("datasetTheme", defaults.datasetTheme),
      datasetSpatial = data.getOrElse("datasetSpatial", defaults.datasetSpatial),
      datasetProvenance = data.getOrElse("datasetProvenance", defaults.datasetProvenance),

      // Contact Points
      contactEmail = data.getOrElse("contactEmail", defaults.contactEmail),
      contactPage = data.getOrElse("contactPage", defaults.contactPage),
      publisherName = data.getOrElse("publisherName", defaults.publisherName),
      publisherEmail = data.getOrElse("publisherEmail", defaults.publisherEmail),

      // FDP Settings (Optional Override)
      fdpUrl = data.getOrElse("fdpUrl", defaults.fdpUrl),
      fdpEmail = data.getOrElse("fdpEmail", defaults.fdpEmail),
      fdpPassword = data.getOrElse("fdpPassword", defaults.fdpPassword)
    )
  }
}