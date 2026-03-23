package srdc.stage.vocab

import org.apache.jena.rdf.model.{ModelFactory, Property}

object HealthDCATAP {
  private final val m = ModelFactory.createDefaultModel()
  final val NS = "http://healthdataportal.eu/ns/health#"
  final val trustedDataHolder = m.createProperty(NS + "trustedDataHolder")
  final val hdab = m.createProperty(NS + "hdab")
  final val healthTheme = m.createProperty(NS + "healthTheme")
  final val healthCategory = m.createProperty(NS + "healthCategory")
  final val populationCoverage = m.createProperty(NS + "populationCoverage")
  final val hasCodeValues = m.createProperty(NS + "hasCodeValues")
  final val hasCodingSystem = m.createProperty(NS + "hasCodingSystem")
  final val numberOfRecords: Property = m.createProperty(NS + "numberOfRecords")
  final val retentionPeriod: Property = m.createProperty(NS + "retentionPeriod")
  final val maxTypicalAge: Property = m.createProperty(NS + "maxTypicalAge")
  final val minTypicalAge: Property = m.createProperty(NS + "minTypicalAge")
  final val numberOfUniqueIndividuals: Property = m.createProperty(NS + "numberOfUniqueIndividuals")
}
