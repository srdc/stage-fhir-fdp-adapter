package srdc.stage.client

import org.apache.jena.query.{QueryExecutionFactory, QueryFactory, ResultSet}
import org.apache.jena.rdf.model.Model
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * Simple SPARQL helper for querying remote RDF servers.
 *
 * Example endpoints:
 *   - https://publications.europa.eu/webapi/rdf/sparql
 */
class SparqlClient(val endpoint: String) {

  /**
   * Execute a SPARQL SELECT query and return each row as Map[varName -> valueString].
   *
   * @param queryStr the SPARQL SELECT query
   */
  def select(queryStr: String): List[Map[String, String]] = {
    val query = QueryFactory.create(queryStr)

    val qe = QueryExecutionHTTP.service(endpoint).query(query).build()
    try {
      val rs: ResultSet = qe.execSelect()
      val varNames = rs.getResultVars.asScala.toList

      val buf = ListBuffer.empty[Map[String, String]]

      while (rs.hasNext) {
        val qs = rs.next()
        val row = varNames.map { v =>
          val node = Option(qs.get(v))
          val valueStr = node.map(_.toString).getOrElse("")
          v -> valueStr
        }.toMap
        buf += row
      }

      buf.toList
    } finally {
      qe.close()
    }
  }

  /**
   * Execute a SPARQL CONSTRUCT query and return a Jena Model.
   */
  def construct(queryStr: String): Model = {
    val query = QueryFactory.create(queryStr)
    val qe = QueryExecutionFactory.sparqlService(endpoint, query)
    try {
      qe.execConstruct()
    } finally {
      qe.close()
    }
  }

  /**
   * Execute a SPARQL ASK query and return a boolean.
   */
  def ask(queryStr: String): Boolean = {
    val query = QueryFactory.create(queryStr)
    val qe = QueryExecutionFactory.sparqlService(endpoint, query)
    try {
      qe.execAsk()
    } finally {
      qe.close()
    }
  }
}
