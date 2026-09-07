package srdc.stage.vocab

/**
 * HealthData@EU controlled vocabularies introduced in HealthDCAT-AP Release 7 (changelog §2.2),
 * plus the mapping from the canonical FHIR system URIs the extraction actually encounters.
 *
 * The concept lists below were taken from the published schemes on 2026-09-06:
 *   https://hdeu-dcat.acceptance.data.health.europa.eu/resource/authority/coding-system
 *   https://hdeu-dcat.acceptance.data.health.europa.eu/resource/authority/standard
 *
 * A value with no counterpart in the list is left untouched rather than dropped or forced onto a
 * near-match: the specification's own guidance is to report the gap to the vocabulary maintainer,
 * and silently discarding a coding system the data really uses would be worse than a warning.
 */
object HealthDataEuNal {

  final val AUTHORITY_BASE = "https://hdeu-dcat.acceptance.data.health.europa.eu/resource/authority"
  final val CODING_SYSTEM_SCHEME = s"$AUTHORITY_BASE/coding-system"
  final val STANDARD_SCHEME = s"$AUTHORITY_BASE/standard"

  /** Concepts published in the coding-system scheme. */
  final val CODING_SYSTEM_CONCEPTS: Set[String] = Set(
    "ATC", "EDQM", "GMDN", "HGNC", "ICD-9-CM", "ICD-10", "ICD-11", "ICD-O-3", "ICF", "ICHI",
    "ICPC-2", "ICPC-3", "LOINC", "MEDDRA", "MESH", "OPS", "ORPHACODE", "RXNORM", "SNOMED-CT",
    "UCUM", "UMLS"
  )

  /** Concepts published in the standard scheme. */
  final val STANDARD_CONCEPTS: Set[String] = Set(
    "CDA", "CDISC-SDTM", "DICOM", "FHIR", "HL7-CCOW", "HL7-V2", "HL7-V3", "IEEE-11073-20702",
    "IHE-XDS.b", "ISO-13606-1", "ISO-13940", "OMOP-CDM", "OPENEHR"
  )

  /**
   * Canonical FHIR `Coding.system` URIs to their coding-system concept. Matching is on a
   * lowercased prefix so versioned variants (e.g. .../icd-10-cm) resolve to the same concept.
   */
  private final val CODING_SYSTEM_BY_PREFIX: Seq[(String, String)] = Seq(
    "http://snomed.info/sct" -> "SNOMED-CT",
    "http://loinc.org" -> "LOINC",
    "http://unitsofmeasure.org" -> "UCUM",
    "http://hl7.org/fhir/sid/icd-10" -> "ICD-10",
    "http://hl7.org/fhir/sid/icd-9" -> "ICD-9-CM",
    "http://id.who.int/icd/release/11" -> "ICD-11",
    "http://hl7.org/fhir/sid/icpc-2" -> "ICPC-2",
    "http://hl7.org/fhir/sid/icpc-3" -> "ICPC-3",
    "http://terminology.hl7.org/codesystem/icd-o-3" -> "ICD-O-3",
    "http://www.whocc.no/atc" -> "ATC",
    "http://www.nlm.nih.gov/research/umls/rxnorm" -> "RXNORM",
    "http://www.nlm.nih.gov/research/umls" -> "UMLS",
    "http://id.nlm.nih.gov/mesh" -> "MESH",
    "http://terminology.hl7.org/codesystem/mdr" -> "MEDDRA",
    "http://www.orpha.net" -> "ORPHACODE",
    "http://www.genenames.org" -> "HGNC",
    "https://www.genenames.org" -> "HGNC",
    "http://standardterms.edqm.eu" -> "EDQM",
    "http://fhir.de/codesystem/bfarm/ops" -> "OPS"
  )

  /** Canonical URIs and identifiers for the standards used with dct:conformsTo. */
  private final val STANDARD_BY_PREFIX: Seq[(String, String)] = Seq(
    "http://hl7.org/fhir" -> "FHIR",
    "https://hl7.org/fhir" -> "FHIR",
    "https://www.wikidata.org/entity/q19597236" -> "FHIR", // Wikidata's entity for HL7 FHIR
    "http://dicom.nema.org" -> "DICOM",
    "https://www.dicomstandard.org" -> "DICOM",
    "http://openehr.org" -> "OPENEHR",
    "https://openehr.org" -> "OPENEHR",
    "https://ohdsi.github.io/commondatamodel" -> "OMOP-CDM"
  )

  private def lookup(table: Seq[(String, String)], scheme: String, value: String): Option[String] = {
    val normalised = value.trim.toLowerCase.stripSuffix("/")
    table.collectFirst { case (prefix, concept) if normalised.startsWith(prefix) => s"$scheme/$concept" }
  }

  /** Already-conforming values are returned unchanged so a hand-curated configuration is respected. */
  private def alreadyInScheme(scheme: String, value: String): Boolean =
    value.trim.startsWith(scheme + "/")

  def codingSystem(value: String): Option[String] =
    if (alreadyInScheme(CODING_SYSTEM_SCHEME, value)) Some(value.trim)
    else lookup(CODING_SYSTEM_BY_PREFIX, CODING_SYSTEM_SCHEME, value)

  def standard(value: String): Option[String] =
    if (alreadyInScheme(STANDARD_SCHEME, value)) Some(value.trim)
    else lookup(STANDARD_BY_PREFIX, STANDARD_SCHEME, value)
}
