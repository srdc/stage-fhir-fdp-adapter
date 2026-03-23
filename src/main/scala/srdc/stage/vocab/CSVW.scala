package srdc.stage.vocab

import org.apache.jena.rdf.model.ModelFactory

object CSVW {
  private final val m = ModelFactory.createDefaultModel()
  final val NS = "http://www.w3.org/ns/csvw#"
  final val TableGroup = m.createResource(NS + "TableGroup")
  final val Table = m.createResource(NS + "Table")
  final val Column = m.createResource(NS + "Column")
  final val name = m.createProperty(NS + "name")
  final val titles = m.createProperty(NS + "titles")
  final val datatype = m.createProperty(NS + "datatype")
  final val separator = m.createProperty(NS + "separator")
  final val propertyURL = m.createProperty(NS + "propertyURL")
  final val table = m.createProperty(NS + "table")
  final val column = m.createProperty(NS + "column")
  final val primaryKey = m.createProperty(NS + "primaryKey")
}
