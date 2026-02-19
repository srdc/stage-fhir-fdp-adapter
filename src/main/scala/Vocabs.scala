import org.apache.jena.rdf.model.{Property, Resource, ResourceFactory}

/**
 * Singleton object defining RDF vocabulary constants used across the application.
 * Centralizes namespace definitions and factory methods for Jena properties and resources.
 */
object Vocabs {
  // Namespace definitions for various ontologies used in HealthDCAT-AP metadata
  val NS_HEALTH = "http://healthdataportal.eu/ns/health#"
  val NS_CSVW   = "http://www.w3.org/ns/csvw#"
  val NS_DPV    = "https://w3id.org/dpv#"
  val NS_PROV   = "http://www.w3.org/ns/prov#"
  val NS_DCATAP = "http://data.europa.eu/r5r/"

  /** Helper to create a Jena Property from a namespace and local name. */
  def prop(ns: String, local: String): Property = ResourceFactory.createProperty(ns + local)

  /** Helper to create a Jena Resource from a namespace and local name. */
  def res(ns: String, local: String): Resource = ResourceFactory.createResource(ns + local)

  /**
   * Terms specific to the HealthDCAT-AP profile.
   */
  object Health {
    val healthCategory     = prop(NS_HEALTH, "healthCategory")
    val healthTheme        = prop(NS_HEALTH, "healthTheme")
    val hdab               = prop(NS_HEALTH, "hdab") // Health Data Access Body
    val hasCodeValues      = prop(NS_HEALTH, "hasCodeValues")
    val hasCodingSystem    = prop(NS_HEALTH, "hasCodingSystem")
    val minTypicalAge      = prop(NS_HEALTH, "minTypicalAge")
    val maxTypicalAge      = prop(NS_HEALTH, "maxTypicalAge")
    val numberOfRecords    = prop(NS_HEALTH, "numberOfRecords")
    val numberOfUniqueIndividuals = prop(NS_HEALTH, "numberOfUniqueIndividuals")
    val populationCoverage = prop(NS_HEALTH, "populationCoverage")
    val retentionPeriod    = prop(NS_HEALTH, "retentionPeriod")
    val trustedDataHolder  = prop(NS_HEALTH, "trustedDataHolder")
  }

  /**
   * Terms from the CSV on the Web (CSVW) vocabulary.
   * Used for describing the schema and structure of tabular output files.
   */
  object Csvw {
    val TableGroup = res(NS_CSVW, "TableGroup")
    val Table      = res(NS_CSVW, "Table")
    val Column     = res(NS_CSVW, "Column")
    val columnProp = prop(NS_CSVW, "column")
    val tableProp  = prop(NS_CSVW, "table")
    val name       = prop(NS_CSVW, "name")
    val titles     = prop(NS_CSVW, "titles")
    val datatype   = prop(NS_CSVW, "datatype")
    val valueUrl   = prop(NS_CSVW, "valueUrl")
  }

  /**
   * Terms from the Data Privacy Vocabulary (DPV).
   * Used for tagging personal data categories (e.g., Age, Gender).
   */
  object Dpv {
    val hasPersonalData = prop(NS_DPV, "hasPersonalData")
    val hasPurpose      = prop(NS_DPV, "hasPurpose")
  }

  /**
   * Terms from the PROV-O ontology.
   * Used for lineage tracking (e.g., which activity generated a dataset).
   */
  object Prov {
    val wasGeneratedBy = prop(NS_PROV, "wasGeneratedBy")
  }

  /**
   * Terms from the DCAT-AP application profile.
   */
  object DcatAp {
    val applicableLegislation = prop(NS_DCATAP, "applicableLegislation")
  }
}