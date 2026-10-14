package srdc.stage.vocab

import org.apache.jena.rdf.model.{ModelFactory, Property, Resource}

/**
 * Core Public Organisation Vocabulary, published under the EU's m8g namespace
 */
object CPOV {
  private final val m = ModelFactory.createDefaultModel()
  final val NS = "http://data.europa.eu/m8g/"
  final val ContactPoint: Resource = m.createResource(NS + "ContactPoint")
  final val contactPoint: Property = m.createProperty(NS + "contactPoint")
  final val email: Property = m.createProperty(NS + "email")
  final val contactPage: Property = m.createProperty(NS + "contactPage")
  final val telephone: Property = m.createProperty(NS + "telephone")
}
