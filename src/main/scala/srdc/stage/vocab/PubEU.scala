package srdc.stage.vocab

import srdc.stage.client.SparqlClient
import org.apache.jena.rdf.model.ModelFactory

object PubEU {
  private final val m = ModelFactory.createDefaultModel()
  val accessUri         = "http://publications.europa.eu/resource/authority/access-right/"
  val availabilityUri   = "http://publications.europa.eu/resource/authority/planned-availability/"
  val corporateBodyUri  = "http://publications.europa.eu/resource/authority/corporate-body/"
  val countryUri        = "http://publications.europa.eu/resource/authority/country/"
  val datasetTypeUri    = "http://publications.europa.eu/resource/authority/dataset-type/"
  val fileTypeUri       = "http://publications.europa.eu/resource/authority/file-type/"
  val frequencyUri      = "http://publications.europa.eu/resource/authority/frequency/"
  val languageUri       = "http://publications.europa.eu/resource/authority/language/"
  val dataThemeUri          = "http://publications.europa.eu/resource/authority/data-theme/"

  private final val client = new SparqlClient("https://publications.europa.eu/webapi/rdf/sparql")

  private def getResources(ns: String) = {
    val query =
      s"""
         |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
         |
         |SELECT DISTINCT ?uri ?label WHERE {
         |  GRAPH <${ns.dropRight(1)}> {
         |    ?uri skos:inScheme <${ns.dropRight(1)}> ;
         |             skos:prefLabel ?label .
         |  }
         |  FILTER(lang(?label) = "en")
         |}
         |ORDER BY ?label
         |""".stripMargin

    val rows = client.select(query)

    rows.filter(row => row.contains("uri") && row.contains("label")).map { row =>
      (row("uri"), row("label").dropRight(3))
    }
  }

  lazy val queryCountryNames: List[(String, String)] = getResources(countryUri)
  lazy val queryDataThemes: List[(String, String)] = getResources(dataThemeUri)
  lazy val queryCorporateBodyTypes: List[(String, String)] = getResources(corporateBodyUri)
  lazy val queryDatasetTypes: List[(String, String)] = getResources(datasetTypeUri)
  lazy val queryFileTypes: List[(String, String)] = getResources(fileTypeUri)
  lazy val queryAccessRights: List[(String, String)] = getResources(accessUri)
  lazy val queryFrequencies: List[(String, String)] = getResources(frequencyUri)

}
