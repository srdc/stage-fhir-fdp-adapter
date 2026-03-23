package srdc.stage.vocab

import org.apache.jena.rdf.model.{ModelFactory, Property, Resource}

object ADMS {
  private final val m = ModelFactory.createDefaultModel()
  final val NS = "http://www.w3.org/ns/adms#"
  final val Identifier: Resource = m.createResource(NS + "Identifier")
  final val status: Property = m.createProperty(NS + "status")
  final val sample: Property = m.createProperty(NS + "sample")
  final val identifier: Property = m.createProperty(NS + "identifier")
  final val versionNotes: Property = m.createProperty(NS + "versionNotes")

}
