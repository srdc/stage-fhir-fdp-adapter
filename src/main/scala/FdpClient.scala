import org.apache.jena.rdf.model.Model
import org.apache.jena.riot.{Lang, RDFDataMgr}
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpRequest.BodyPublishers
import java.nio.charset.StandardCharsets
import scala.util.matching.Regex

/**
 * Provides a specialized HTTP client for interacting with a FAIR Data Point (FDP) server.
 *
 * This object handles authentication (JWT retrieval) and the publication of RDF resources
 * (Catalog, Dataset, Distribution) to the FDP API. It abstracts away the serialization
 * of Jena Models to Turtle format and the management of HTTP headers.
 */
object FdpClient {

  /**
   * Represents the result of an HTTP POST operation to the FDP.
   *
   * @param status     The HTTP status code returned by the server (e.g., 201 Created).
   * @param location   The URI of the newly created resource, extracted from the 'Location' header.
   * @param bodyTurtle The response body returned by the server, typically containing the metadata of the created resource in Turtle format.
   */
  final case class PostResult(
                               status: Int,
                               location: Option[URI],
                               bodyTurtle: String
                             )

  private val client: HttpClient = HttpClient.newHttpClient()

  /**
   * Serializes an Apache Jena RDF Model into a Turtle-formatted string.
   *
   * @param model The RDF Model to serialize.
   * @return A String containing the RDF data in Turtle (TTL) syntax, encoded in UTF-8.
   */
  private def serializeModelAsTurtle(model: Model): String = {
    val out = new ByteArrayOutputStream()
    RDFDataMgr.write(out, model, Lang.TURTLE)
    out.toString(StandardCharsets.UTF_8)
  }

  /**
   * Authenticates against the FDP server to retrieve a JWT access token.
   *
   * Sends a POST request to the '/tokens' endpoint with the provided credentials.
   *
   * @param baseUrl  The root URL of the FDP server (e.g., "http://localhost:8080").
   * @param email    The email address for FDP authentication.
   * @param password The password for FDP authentication.
   * @return The JWT token string extracted from the JSON response.
   * @throws IllegalArgumentException If the server returns a non-success status code or the token cannot be parsed.
   */
  private def getToken(baseUrl: String, email: String, password: String): String = {
    val json = s"""{"email":"$email","password":"$password"}"""

    val request = HttpRequest.newBuilder()
      .uri(URI.create(stripTrailingSlash(baseUrl) + "/tokens"))
      .header("Accept", "application/json")
      .header("Content-Type", "application/json")
      .POST(BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

    if (response.statusCode() / 100 != 2) {
      throw new IllegalArgumentException(s"Failed to retrieve token. Status: ${response.statusCode()}, Body: ${response.body()}")
    }

    extractTokenFromJson(response.body())
      .getOrElse(throw new IllegalArgumentException("Token not found in response"))
  }

  /**
   * Publishes an RDF Model to a specific FDP endpoint.
   *
   * This method performs the following actions:
   * 1. Acquires an authentication token.
   * 2. Serializes the Jena Model to Turtle format.
   * 3. Sends a POST request to the specified prefix (e.g., 'catalog', 'dataset').
   *
   * @param baseUrl  The root URL of the FDP server.
   * @param prefix   The API endpoint prefix (e.g., "catalog", "dataset", "distribution").
   * @param model    The Jena Model containing the metadata to publish.
   * @param email    The email address for authentication.
   * @param password The password for authentication.
   * @return A PostResult containing the status, location header, and response body.
   * @throws RuntimeException If the server returns a non-success status code.
   */
  def postResource(baseUrl: String, prefix: String, model: Model, email: String, password: String): PostResult = {
    val token = getToken(baseUrl, email, password)
    val turtle = serializeModelAsTurtle(model)

    println(s"Posting to FDP endpoint: $prefix")

    val targetUri = URI.create(s"${stripTrailingSlash(baseUrl)}/$prefix")

    val request = HttpRequest.newBuilder()
      .uri(targetUri)
      .header("Authorization", s"Bearer $token")
      .header("Content-Type", "text/turtle")
      .POST(BodyPublishers.ofString(turtle, StandardCharsets.UTF_8))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

    if (response.statusCode() / 100 != 2) {
      throw new RuntimeException(s"FDP Post Failed for $prefix. Status: ${response.statusCode()}. Body: ${response.body()}")
    }

    val location = response.headers().firstValue("Location").orElse(null) match {
      case null => None
      case s if s.isBlank => None
      case s => Some(URI.create(s))
    }

    PostResult(response.statusCode(), location, response.body())
  }

  /**
   * Extracts the value of the "token" field from a simple JSON string.
   *
   * @param json The JSON string returned by the authentication endpoint.
   * @return An Option containing the token string if found, or None otherwise.
   */
  private def extractTokenFromJson(json: String): Option[String] = {
    val TokenPattern: Regex = """"token"\s*:\s*"([^"]+)"""".r
    TokenPattern.findFirstMatchIn(json).map(_.group(1))
  }

  /**
   * Normalizes a URL string by removing the trailing slash if present.
   *
   * @param url The URL string to normalize.
   * @return The URL string without a trailing slash.
   */
  private def stripTrailingSlash(url: String): String =
    if (url.endsWith("/")) url.dropRight(1) else url
}