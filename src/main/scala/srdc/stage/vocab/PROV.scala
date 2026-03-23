package srdc.stage.vocab

import org.apache.jena.rdf.model.{ModelFactory, Property, Resource}

object PROV {
  private final val m = ModelFactory.createDefaultModel()
  final val NS = "http://www.w3.org/ns/prov#"
  final val Attribution: Resource = m.createResource(NS + "Attribution")
  final val Activity: Resource = m.createResource(NS + "Activity")
  final val qualifiedAttribution: Property = m.createProperty(NS + "qualifiedAttribution")
  final val wasGeneratedBy: Property = m.createProperty(NS + "wasGeneratedBy")
  final val agent: Property = m.createProperty(NS + "agent")
}
