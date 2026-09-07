package srdc.stage.vocab

import org.apache.jena.rdf.model.{ModelFactory, Property, Resource}

object DPV {
  private final val m = ModelFactory.createDefaultModel()
  final val NS = "https://w3id.org/dpv#"
  final val LegalBasis: Resource = m.createResource(NS + "LegalBasis")
  final val Purpose: Resource = m.createResource(NS + "Purpose")
  final val PersonalData: Resource = m.createResource(NS + "PersonalData")
  final val hasPersonalData: Property = m.createProperty(NS + "hasPersonalData")
  final val hasPurpose: Property = m.createProperty(NS + "hasPurpose")
  final val hasLegalBasis: Property = m.createProperty(NS + "hasLegalBasis")
}
