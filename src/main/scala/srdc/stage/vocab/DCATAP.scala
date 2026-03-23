package srdc.stage.vocab

import org.apache.jena.rdf.model.{ModelFactory, Property, Resource}

object DCATAP {
  private final val m = ModelFactory.createDefaultModel()
  final val NS = "http://data.europa.eu/r5r/"
  final val applicableLegislation: Property = m.createProperty(NS + "applicableLegislation")
  final val availability: Property = m.createProperty(NS + "availability")

}
