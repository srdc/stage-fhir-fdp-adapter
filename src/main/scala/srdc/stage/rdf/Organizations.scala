package srdc.stage.rdf

import org.slf4j.LoggerFactory

/**
 * A single organisation recorded once in the metadata input and referenced from every agent field
 *
 * Excel: one row of the "Organizations" sheet.
 * JSON:  one entry of the top-level `organizations` array.
 *
 * @param id           Unique key used in the reference cells (e.g. "OULU"). Case-insensitive.
 * @param name         Human readable name, emitted as foaf:name.
 * @param iri          Canonical IRI (ROR / Wikidata / homepage). When empty an IRI is minted
 *                     deterministically from the vocabulary base - see [[OrganizationRegistry.iriFor]].
 * @param type         Agent type URI (typically an EU corporate-body authority code).
 * @param contactPoint Contact page and/or email, emitted as a single vcard:Kind node.
 * @param homepage     Organisation homepage, emitted as foaf:homepage.
 * @param note         Free text description, emitted as dct:description.
 * @param trusted      Whether the organisation is a trusted data holder.
 * @param identifiers  Additional persistent identifiers (ROR, VAT, Wikidata, ...).
 */
case class Organization(
                         id: String,
                         name: String,
                         iri: Option[String] = None,
                         `type`: Option[String] = None,
                         contactPoint: ContactPointMetadataUserInput = ContactPointMetadataUserInput(),
                         homepage: Option[String] = None,
                         note: Option[String] = None,
                         trusted: Option[Boolean] = None,
                         identifiers: Seq[String] = Seq.empty
                       )

/**
 * The flattened view of an agent that MetadataWriter actually emits.
 *
 * `iri = Some(...)` produces a named node shared by every reference within a graph.
 * `iri = None` produces a blank node, reproducing the pre-Organizations output for inputs that never mention an organisation.
 */
case class AgentDescription(
                             iri: Option[String],
                             name: String,
                             `type`: Option[String] = None,
                             contactPoint: ContactPointMetadataUserInput = ContactPointMetadataUserInput(),
                             homepage: Option[String] = None,
                             note: Option[String] = None,
                             trusted: Option[Boolean] = None,
                             identifiers: Seq[String] = Seq.empty
                           )

object AgentDescription {

  /** A named agent with no registry entry - used only for data dictionary owner/responsible cells. */
  def literal(name: String, iri: Option[String] = None): AgentDescription =
    AgentDescription(iri = iri, name = name)
}

/**
 * Case-insensitive lookup of organisations by id, then by name.
 */
class OrganizationIndex(val organizations: Seq[Organization]) {

  private val byId: Map[String, Organization] =
    organizations.map(o => OrganizationIndex.normalize(o.id) -> o).toMap

  private val byName: Map[String, Organization] =
    organizations.map(o => OrganizationIndex.normalize(o.name) -> o).toMap

  def isEmpty: Boolean = organizations.isEmpty

  def nonEmpty: Boolean = organizations.nonEmpty

  /** Resolves a reference against the ids first, then the names. */
  def lookup(ref: String): Option[Organization] = {
    val key = OrganizationIndex.normalize(ref)
    if (key.isEmpty) None else byId.get(key).orElse(byName.get(key))
  }

  def lookup(ref: Option[String]): Option[Organization] = ref.flatMap(lookup)

  def contains(ref: String): Boolean = lookup(ref).isDefined

  def ids: Seq[String] = organizations.map(_.id)
}

object OrganizationIndex {

  /** Trim, collapse internal whitespace and case-fold, so "  Oulu " matches "OULU". */
  def normalize(value: String): String =
    if (value == null) "" else value.trim.replaceAll("\\s+", " ").toLowerCase
}

/**
 * Resolves organisation references into AgentDescriptions carrying a stable IRI.
 *
 * @param index     The parsed organisations.
 * @param vocabBase Base URI used when an organisation has no canonical IRI of its own.
 */
class OrganizationRegistry(val index: OrganizationIndex, val vocabBase: String) {

  private val logger = LoggerFactory.getLogger(getClass)

  def isEmpty: Boolean = index.isEmpty

  def nonEmpty: Boolean = index.nonEmpty

  def ids: Seq[String] = index.ids

  /**
   * Canonical IRI when supplied, otherwise a deterministic IRI minted from the id.
   */
  def iriFor(org: Organization): String =
    org.iri.map(_.trim).filter(_.nonEmpty).getOrElse {
      s"${OrganizationRegistry.stripTrailingSlash(vocabBase)}/organization/${OrganizationRegistry.slug(org.id)}"
    }

  /** Resolves a reference (id or name) into an agent with a stable IRI. */
  def resolve(ref: String): Option[AgentDescription] = index.lookup(ref).map(toAgent)

  def resolve(ref: Option[String]): Option[AgentDescription] = ref.flatMap(resolve)

  /** The IRI a reference would resolve to, if any. Used to name otherwise-blank legacy agents. */
  def iriOf(ref: String): Option[String] = index.lookup(ref).map(iriFor)

  def iriOf(ref: Option[String]): Option[String] = ref.flatMap(iriOf)

  private def toAgent(org: Organization): AgentDescription = {
    val iri = iriFor(org)
    logger.debug("Resolved organisation '{}' -> {}", org.id, iri)
    AgentDescription(
      iri = Some(iri),
      name = org.name,
      `type` = org.`type`,
      contactPoint = org.contactPoint,
      homepage = org.homepage,
      note = org.note,
      trusted = org.trusted,
      identifiers = org.identifiers
    )
  }
}

object OrganizationRegistry {

  def apply(organizations: Seq[Organization], vocabBase: String): OrganizationRegistry =
    new OrganizationRegistry(new OrganizationIndex(organizations), vocabBase)

  def empty(vocabBase: String): OrganizationRegistry =
    new OrganizationRegistry(new OrganizationIndex(Seq.empty), vocabBase)

  /** URI-safe, lower-case slug used when minting an organisation IRI. */
  def slug(value: String): String =
    value.trim.toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .replaceAll("^-+|-+$", "")

  def stripTrailingSlash(url: String): String =
    if (url != null && url.endsWith("/")) url.dropRight(1) else url

  /**
   * Input-level validation shared by every run mode.
   */
  def validate(organizations: Seq[Organization]): Unit = {
    organizations.foreach { org =>
      if (org.id == null || org.id.trim.isEmpty) {
        throw new IllegalArgumentException("Organizations: every organisation requires a non-empty 'Organization ID'.")
      }
      if (org.name == null || org.name.trim.isEmpty) {
        throw new IllegalArgumentException(s"Organizations: organisation '${org.id}' requires a non-empty 'Name'.")
      }
      if (org.contactPoint.page.isEmpty && org.contactPoint.email.isEmpty) {
        LoggerFactory.getLogger(getClass).warn(
          "Organizations: '{}' has neither 'Contact Page' nor 'Contact Email'. That is fine for a " +
            "creator or attribution agent, but a publisher or HDAB will get an empty contact point.",
          org.id
        )
      }
    }

    val duplicates = organizations
      .groupBy(o => OrganizationIndex.normalize(o.id))
      .collect { case (_, group) if group.size > 1 => group.head.id }

    if (duplicates.nonEmpty) {
      throw new IllegalArgumentException(
        s"Organizations: duplicate Organization ID(s): ${duplicates.mkString(", ")}. Ids must be unique (case-insensitive)."
      )
    }
  }
}
