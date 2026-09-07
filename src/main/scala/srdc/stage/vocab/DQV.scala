package srdc.stage.vocab

import org.apache.jena.rdf.model.{ModelFactory, Property, Resource}

/**
 * Data Quality Vocabulary, used by HealthDCAT-AP for dqv:hasQualityAnnotation.
 */
object DQV {
  private final val m = ModelFactory.createDefaultModel()
  final val NS = "http://www.w3.org/ns/dqv#"
  final val QualityCertificate: Resource = m.createResource(NS + "QualityCertificate")
  final val qualityAssessment: Resource = m.createResource(NS + "qualityAssessment")
  final val hasQualityAnnotation: Property = m.createProperty(NS + "hasQualityAnnotation")
}

/**
 * Web Annotation vocabulary. A dqv:QualityCertificate is an oa:Annotation, so it carries its
 * target, body and motivation with these properties.
 */
object OA {
  private final val m = ModelFactory.createDefaultModel()
  final val NS = "http://www.w3.org/ns/oa#"
  final val hasTarget: Property = m.createProperty(NS + "hasTarget")
  final val hasBody: Property = m.createProperty(NS + "hasBody")
  final val motivatedBy: Property = m.createProperty(NS + "motivatedBy")
}

/**
 * GeoDCAT-AP, used by HealthDCAT-AP for the custodian role.
 */
object GEODCATAP {
  private final val m = ModelFactory.createDefaultModel()
  final val NS = "http://data.europa.eu/930/"
  final val custodian: Property = m.createProperty(NS + "custodian")
}
