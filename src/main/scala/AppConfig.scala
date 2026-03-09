import com.typesafe.config.ConfigFactory

case class AppConfig(
                      fhirServer: String,
                      outputDir: String,
                      jobType: String,
                      format: String,
                      runMode: String,
                      jsonPath: String,
                      excelPath: String,
                      htmlTemplatePath: String,
                      fdpUrl: Option[String],
                      fdpEmail: Option[String],
                      fdpPassword: Option[String]
                    )

object AppConfig {
  def load(): AppConfig = {
    val conf = ConfigFactory.load().getConfig("app")

    AppConfig(
      fhirServer = conf.getString("fhirServer"),
      outputDir = conf.getString("outputDir"),
      jobType = conf.getString("jobType"),
      format = conf.getString("format"),
      runMode = conf.getString("runMode"),
      jsonPath = conf.getString("paths.json"),
      excelPath = conf.getString("paths.excel"),
      htmlTemplatePath = conf.getString("paths.htmlTemplate"),

      fdpUrl = if (conf.hasPath("fdp.url") && conf.getString("fdp.url").nonEmpty) Some(conf.getString("fdp.url")) else None,
      fdpEmail = if (conf.hasPath("fdp.email") && conf.getString("fdp.email").nonEmpty) Some(conf.getString("fdp.email")) else None,
      fdpPassword = if (conf.hasPath("fdp.password") && conf.getString("fdp.password").nonEmpty) Some(conf.getString("fdp.password")) else None
    )
  }
}