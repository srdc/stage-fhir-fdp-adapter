import org.apache.jena.query.QueryExecutionFactory

object PubEU {
  val accessUri         = "http://publications.europa.eu/resource/authority/access-right/"
  val availabilityUri   = "http://publications.europa.eu/resource/authority/planned-availability/"
  val corporateBodyUri  = "http://publications.europa.eu/resource/authority/corporate-body/"
  val countryUri        = "http://publications.europa.eu/resource/authority/country/"
  val datasetTypeUri    = "http://publications.europa.eu/resource/authority/dataset-type/"
  val fileTypeUri       = "http://publications.europa.eu/resource/authority/file-type/"
  val frequencyUri      = "http://publications.europa.eu/resource/authority/frequency/"
  val languageUri       = "http://publications.europa.eu/resource/authority/language/"
  val dataThemeUri      = "http://publications.europa.eu/resource/authority/data-theme/"

  private def getResources(ns: String): List[(String, String)] = {
    val query =
      s"""
         |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
         |SELECT DISTINCT ?uri ?label WHERE {
         |  GRAPH <${ns.dropRight(1)}> {
         |    ?uri skos:inScheme <${ns.dropRight(1)}> ;
         |         skos:prefLabel ?label .
         |  }
         |  FILTER(lang(?label) = "en")
         |}
         |ORDER BY ?label
         |""".stripMargin

    try {
      val qexec = QueryExecutionFactory.sparqlService("https://publications.europa.eu/webapi/rdf/sparql", query)
      val results = qexec.execSelect()
      var list = List.empty[(String, String)]
      while (results.hasNext) {
        val soln = results.nextSolution()
        val uri = soln.getResource("uri").getURI
        val label = soln.getLiteral("label").getString
        list = list :+ (uri, label)
      }
      qexec.close()
      list
    } catch {
      case e: Exception =>
        println(s"Warning: Failed to fetch vocabularies from EU SPARQL endpoint for $ns: ${e.getMessage}")
        List.empty
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