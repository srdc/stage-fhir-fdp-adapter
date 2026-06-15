package srdc.stage.client

import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.slf4j.LoggerFactory
import srdc.stage.config.AppConfig

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.util.matching.Regex

/**
 * HTTP-level FHIR client used by the bundle-export job
 */
object FhirBundleClient {

  private val logger = LoggerFactory.getLogger(getClass)
  private val httpClient: HttpClient = HttpClient.newHttpClient()
  private val mapper = new ObjectMapper()

  /** Result of an authenticated fetch, if required */
  private case class Auth(headerValue: Option[String])

  /**
   * Fetch every resource matching `searchPath` from the FHIR server
   *
   * @param appConfig  Holds the FHIR server base URL and the auth configuration.
   * @param searchPath Path + query string relative to the FHIR base URL
   * @return All "entry.resource" JSON nodes
   */
  def fetchAll(appConfig: AppConfig, searchPath: String): Seq[JsonNode] = {
    val auth = resolveAuth(appConfig)
    val firstUrl = joinUrl(appConfig.fhirServer, searchPath)
    logger.info(s"Fetching FHIR bundle pages starting at: $firstUrl")

    val collected = mutable.ArrayBuffer.empty[JsonNode]
    var nextUrl: Option[String] = Some(firstUrl)
    var page = 0

    while (nextUrl.isDefined) {
      page += 1
      val url = nextUrl.get
      val bundle = getJson(url, auth)
      val entries = Option(bundle.get("entry"))
        .filter(_.isArray)
        .map(_.asInstanceOf[ArrayNode])
        .getOrElse(mapper.createArrayNode())

      var addedThisPage = 0
      val it = entries.elements()
      while (it.hasNext) {
        val entry = it.next()
        val resource = entry.get("resource")
        if (resource != null) {
          collected += resource
          addedThisPage += 1
        }
      }
      logger.info(s"  page $page: $addedThisPage resources (running total: ${collected.size})")
      nextUrl = nextLinkOf(bundle)
    }

    collected.toSeq
  }

  /** Pulls the next link out of a Bundle, returning None when the chain ends. */
  private def nextLinkOf(bundle: JsonNode): Option[String] = {
    val links = bundle.get("link")
    if (links == null || !links.isArray) return None
    val it = links.elements()
    while (it.hasNext) {
      val l = it.next()
      val relation = Option(l.get("relation")).map(_.asText()).getOrElse("")
      if (relation == "next") {
        val url = Option(l.get("url")).map(_.asText()).getOrElse("")
        if (url.nonEmpty) return Some(url)
      }
    }
    None
  }

  /** GET a JSON document with the resolved Authorization header, if required. */
  private def getJson(url: String, auth: Auth): JsonNode = {
    val builder = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .header("Accept", "application/fhir+json, application/json")
      .GET()
    auth.headerValue.foreach(v => builder.header("Authorization", v))
    val request = builder.build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if (response.statusCode() / 100 != 2) {
      throw new RuntimeException(
        s"FHIR fetch failed for $url. Status: ${response.statusCode()}. Body: ${response.body().take(500)}")
    }
    mapper.readTree(response.body())
  }

  /**
   * Build the Authorization header value from the app config
   */
  private def resolveAuth(appConfig: AppConfig): Auth = {
    if (!appConfig.authEnabled) return Auth(None)

    appConfig.token match {
      case Some(t) if t.trim.nonEmpty =>
        Auth(Some(s"Bearer ${t.trim}"))
      case _ =>
        (appConfig.tokenEndpoint, appConfig.clientId, appConfig.clientSecret) match {
          case (Some(endpoint), Some(clientId), Some(clientSecret)) =>
            val token = fetchOAuthToken(endpoint, clientId, clientSecret, appConfig.scope.getOrElse(List.empty))
            Auth(Some(s"Bearer $token"))
          case _ =>
            logger.warn("Auth is enabled but no token or token-endpoint credentials are configured. Falling back to anonymous fetch.")
            Auth(None)
        }
    }
  }

  /** Minimal OAuth2 client_credentials grant. Returns the access_token. */
  private def fetchOAuthToken(endpoint: String, clientId: String, clientSecret: String, scopes: List[String]): String = {
    val form = {
      val sb = new StringBuilder("grant_type=client_credentials")
      sb.append("&client_id=").append(urlEncode(clientId))
      sb.append("&client_secret=").append(urlEncode(clientSecret))
      if (scopes.nonEmpty) sb.append("&scope=").append(urlEncode(scopes.mkString(" ")))
      sb.toString
    }

    val request = HttpRequest.newBuilder()
      .uri(URI.create(endpoint))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .header("Accept", "application/json")
      .POST(BodyPublishers.ofString(form, StandardCharsets.UTF_8))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if (response.statusCode() / 100 != 2) {
      throw new RuntimeException(
        s"OAuth token fetch failed at $endpoint. Status: ${response.statusCode()}. Body: ${response.body().take(500)}")
    }
    extractAccessTokenFromJson(response.body())
      .getOrElse(throw new RuntimeException(s"OAuth response did not contain access_token. Body: ${response.body().take(500)}"))
  }

  private val AccessTokenPattern: Regex = """"access_token"\s*:\s*"([^"]+)"""".r
  private def extractAccessTokenFromJson(json: String): Option[String] =
    AccessTokenPattern.findFirstMatchIn(json).map(_.group(1))

  private def urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)

  private def joinUrl(baseUrl: String, path: String): String = {
    val baseTrim = if (baseUrl.endsWith("/")) baseUrl.dropRight(1) else baseUrl
    val pathTrim = if (path.startsWith("/")) path.drop(1) else path
    s"$baseTrim/$pathTrim"
  }

  /** Exposed for the bundle assembler */
  def objectMapper: ObjectMapper = mapper

  /** build a node we can add fields to. */
  def newObject(): ObjectNode = mapper.createObjectNode()

  /** build an empty array we can append into. */
  def newArray(): ArrayNode = mapper.createArrayNode()
}
