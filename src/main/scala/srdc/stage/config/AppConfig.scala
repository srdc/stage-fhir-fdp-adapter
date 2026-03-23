package srdc.stage.config

import com.typesafe.config.ConfigFactory

case class AppConfig(
                      fhirServer: String,
                      fhirVersion: String,
                      outputDir: String,
                      jobType: String,
                      format: String,
                      runMode: String,
                      jsonPath: String,
                      excelPath: String,
                      htmlTemplatePath: String,
                      fdpUrl: Option[String],
                      fdpEmail: Option[String],
                      fdpPassword: Option[String],
                      authEnabled: Boolean,
                      tokenEndpoint: Option[String],
                      clientId: Option[String],
                      clientSecret: Option[String],
                      scope: Option[List[String]],
                      token: Option[String]
                    )

object AppConfig {
  def load(): AppConfig = {
    val conf = ConfigFactory.load().getConfig("app")
    val authEnabled = conf.hasPath("auth.enabled") && conf.getBoolean("auth.enabled").booleanValue()

    AppConfig(
      fhirServer = conf.getString("fhirServer"),
      fhirVersion = conf.getString("fhirVersion"),
      outputDir = conf.getString("outputDir"),
      jobType = conf.getString("jobType"),
      format = conf.getString("format"),
      runMode = conf.getString("runMode"),
      jsonPath = conf.getString("paths.json"),
      excelPath = conf.getString("paths.excel"),
      htmlTemplatePath = conf.getString("paths.htmlTemplate"),

      fdpUrl = if (conf.hasPath("fdp.url") && conf.getString("fdp.url").nonEmpty) Some(conf.getString("fdp.url")) else None,
      fdpEmail = if (conf.hasPath("fdp.email") && conf.getString("fdp.email").nonEmpty) Some(conf.getString("fdp.email")) else None,
      fdpPassword = if (conf.hasPath("fdp.password") && conf.getString("fdp.password").nonEmpty) Some(conf.getString("fdp.password")) else None,

      authEnabled = authEnabled,
      tokenEndpoint = if (authEnabled && conf.hasPath("auth.tokenEndpoint")) Some(conf.getString("auth.tokenEndpoint")) else None,
      clientId = if (authEnabled && conf.hasPath("auth.clientId")) Some(conf.getString("auth.clientId")) else None,
      clientSecret = if (authEnabled && conf.hasPath("auth.clientSecret")) Some(conf.getString("auth.clientSecret")) else None,
      scope = if (authEnabled && conf.hasPath("auth.scope")) Some(conf.getString("auth.scope").split(" ").toList) else None,
      token = if (authEnabled && conf.hasPath("auth.token")) Some(conf.getString("auth.token")) else None
    )
  }
}