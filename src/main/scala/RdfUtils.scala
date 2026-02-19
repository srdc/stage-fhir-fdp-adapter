import org.apache.jena.rdf.model.{Model}
import org.apache.jena.riot.{Lang, RDFDataMgr}

import java.io.FileOutputStream
import scala.util.Using

/**
 * Utility object for handling RDF serialization and file output operations.
 */
object RdfUtils {

  /**
   * Writes the given Jena RDF Model to a file in Turtle (TTL) format.
   * Uses automatic resource management to ensure the output stream is safely closed.
   *
   * @param path  The output file path (e.g., "output/Catalog.ttl")
   * @param model The Jena Model containing the RDF graph to be written
   */
  def writeTurtle(path: String, model: Model): Unit =
    Using.resource(new FileOutputStream(path)) { out =>
      RDFDataMgr.write(out, model, Lang.TURTLE)
    }
}