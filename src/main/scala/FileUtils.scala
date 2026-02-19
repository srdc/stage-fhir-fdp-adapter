import org.apache.spark.sql.{DataFrame, SaveMode}

object FileUtils {

  /**
   * Persists a DataFrame to the specified path in the requested format.
   *
   * @param df     The Spark DataFrame to save.
   * @param path   The target file system path.
   * @param format The format of the output file (e.g., "csv", "parquet").
   */
  def saveData(df: DataFrame, path: String, format: String): Unit = {
    val writer = df.write.mode(SaveMode.Overwrite)
    if (format == "csv") writer.option("header", "true")
    writer.format(format).save(path)
    println(s"Data saved to: $path")
  }
}