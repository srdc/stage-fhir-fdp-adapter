import org.apache.jena.rdf.model.{Model, ModelFactory, Resource}
import org.apache.jena.vocabulary.{DCAT, DCTerms, RDF, SKOS, VCARD4, XSD}
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.sparql.vocabulary.FOAF

import java.nio.file.{Files, Paths}
import java.util.UUID

/**
 * Encapsulates statistical information extracted from the dataset.
 *
 * @param recordCount    Total number of records (rows) in the final dataset.
 * @param uniquePatients Count of unique individuals (patients) in the dataset.
 * @param minAge         Minimum age of patients found in the cohort.
 * @param maxAge         Maximum age of patients found in the cohort.
 * @param columns        Array of column name and type pairs, used for CSVW schema generation.
 * @param vocabularies   Map of question/variable IDs to their code-display value pairs (for SKOS).
 */
case class DatasetStats(
                         recordCount: Long,
                         uniquePatients: Long,
                         minAge: Int,
                         maxAge: Int,
                         columns: Array[(String, String)],
                         vocabularies: Map[String, Map[String, String]] = Map.empty
                       )

/**
 * Handles the generation and publication of FAIR Data Point (FDP) metadata.
 *
 * This object is responsible for:
 * 1. Creating RDF models (Catalog, Dataset, Distribution, CSVW, SKOS) using Apache Jena.
 * 2. Orchestrating the publication workflow: Creating a Catalog first, obtaining its ID,
 * and using it to link the subsequent Dataset and Distribution.
 * 3. Persisting the generated Turtle (TTL) files to the local file system.
 */
object MetadataWriter {

  private val VOCAB_BASE = "http://example.org/vocab"
  private val HEALTH_DCAT_NS = "http://healthdataportal.eu/ns/health#"
  private val DCATAP_NS = "http://data.europa.eu/r5r/"
  private val CSVW_NS = "http://www.w3.org/ns/csvw#"
  private val DPV_NS = "https://w3id.org/dpv#"
  private val ADMS_NS = "http://www.w3.org/ns/adms#"
  private val PROV_NS = "http://www.w3.org/ns/prov#"
  private val ELI_NS = "http://data.europa.eu/eli/ontology#"
  private val DQV_NS = "http://www.w3.org/ns/dqv#"

  private val MEDIA_TYPE_EXTENT = "http://purl.org/dc/terms/MediaTypeOrExtent"
  private val LEGAL_RESOURCE = "http://data.europa.eu/eli/ontology#LegalResource"
  private val DATASET_TYPE_STATISTICAL = "http://publications.europa.eu/resource/authority/dataset-type/STATISTICAL"
  private val DATA_THEME_HEALTH = "http://publications.europa.eu/resource/authority/data-theme/HEAL"

  /**
   * Initializes a new Apache Jena RDF Model with standard prefixes pre-registered.
   *
   * @return An empty Jena Model with DCAT, DCTerms, SKOS, HealthDCAT-AP, and other prefixes set.
   */
  private def createModel(): Model = {
    val m = ModelFactory.createDefaultModel()
    m.setNsPrefix("dcat", DCAT.NS)
    m.setNsPrefix("dct", DCTerms.NS)
    m.setNsPrefix("foaf", FOAF.NS)
    m.setNsPrefix("healthdcatap", HEALTH_DCAT_NS)
    m.setNsPrefix("dcatap", DCATAP_NS)
    m.setNsPrefix("csvw", CSVW_NS)
    m.setNsPrefix("dpv", DPV_NS)
    m.setNsPrefix("prov", PROV_NS)
    m.setNsPrefix("skos", SKOS.getURI)
    m.setNsPrefix("xsd", XSD.NS)
    m.setNsPrefix("vcard", VCARD4.NS)
    m.setNsPrefix("adms", ADMS_NS)
    m.setNsPrefix("eli", ELI_NS)
    m.setNsPrefix("dqv", DQV_NS)
    m
  }

  /**
   * Helper to create the main Subject resource for an RDF model.
   *
   * If the URI is empty or null, it creates a resource with a relative URI ("<>").
   *
   * @param m   The Jena Model.
   * @param uri The URI string (or empty string).
   * @return The created Resource.
   */
  private def createSubject(m: Model, uri: String): Resource = {
    if (uri == null || uri.trim.isEmpty) m.createResource("")
    else m.createResource(uri)
  }

  /**
   * Main orchestration method for exporting metadata.
   *
   * This method manages the lifecycle of metadata creation:
   * 1.  Catalog: Checks if an existing catalog should be used or a new one created.
   * If creating new on FDP, it POSTs the catalog and retrieves the generated ID.
   * 2.  Dataset: Creates the Dataset metadata linked to the Catalog ID.
   * POSTs to FDP if enabled and retrieves the new Dataset ID.
   * 3.  Distribution: Creates the Distribution metadata linked to the Dataset ID.
   * 4.  Local Files: Generates CSVW schema and SKOS vocabularies locally.
   *
   * All generated models are also saved as Turtle (.ttl) files in the output directory.
   *
   * @param cfg   The configuration object containing metadata fields and FDP settings.
   * @param stats The calculated statistics from the ETL pipeline.
   */
  def exportResults(cfg: Config, stats: DatasetStats): Unit = {
    println(s"Starting Metadata Export...")

    // 1. Sanitize Inputs to determine Mode
    val fdpUrl = Option(cfg.fdpUrl).map(_.trim).getOrElse("")
    val fdpEmail = Option(cfg.fdpEmail).map(_.trim).getOrElse("")
    val isFdpMode = fdpUrl.nonEmpty && fdpEmail.nonEmpty

    if (isFdpMode) {
      println(s"FDP Mode ACTIVE. Target: $fdpUrl")
    } else {
      println("FDP Mode INACTIVE (Missing URL or Email). Switching to LOCAL FILE generation with auto-generated UUIDs.")
    }

    // 2. Ensure Output Directory Exists
    val outDir = Paths.get(cfg.outputDir)
    if (!Files.exists(outDir)) {
      println(s"Creating output directory: ${cfg.outputDir}")
      Files.createDirectories(outDir)
    }

    // CATALOG
    val finalCatalogUri: String = if (isFdpMode) {
      // FDP Mode: Check existing or create new via POST
      val providedUriMatchesServer = cfg.catalogUri.startsWith(fdpUrl)
      if (cfg.catalogIsExisting && providedUriMatchesServer) {
        println(s"Using Existing Valid FDP Catalog URI: ${cfg.catalogUri}")
        val model = createCatalogModel(cfg, cfg.catalogUri, isFdpMode)
        saveLocalFile(cfg, "Catalog", model)
        cfg.catalogUri
      } else {
        println("Creating NEW Catalog on FDP...")
        val model = createCatalogModel(cfg, "", isFdpMode)
        saveLocalFile(cfg, "Catalog", model)
        val loc = postToFdp(cfg, "catalog", model)
        println(s"Catalog confirmed at: $loc")
        loc
      }
    } else {
      // Local Mode: Use existing URI if provided, otherwise mint a UUID
      val uri = if (cfg.catalogIsExisting && cfg.catalogUri.trim.nonEmpty) cfg.catalogUri.trim else s"urn:uuid:${UUID.randomUUID()}"
      println(s"Generating Local Catalog with URI: $uri")
      val model = createCatalogModel(cfg, uri, isFdpMode)
      saveLocalFile(cfg, "Catalog", model)
      uri
    }

    if (finalCatalogUri.isEmpty) {
      throw new RuntimeException("CRITICAL ERROR: Could not determine a valid Catalog URI.")
    }

    // DATASET
    println(s"Preparing Dataset (Linking to Parent Catalog: $finalCatalogUri)...")

    // If local, we need a UUID. If FDP, we use empty string to let FDP mint the ID.
    val initialDatasetUri = if (isFdpMode) "" else s"urn:uuid:${UUID.randomUUID()}"
    val datasetModel = createDatasetModel(cfg, stats, finalCatalogUri, initialDatasetUri)
    saveLocalFile(cfg, "Dataset", datasetModel)

    val finalDatasetUri = if (isFdpMode) {
      val loc = postToFdp(cfg, "dataset", datasetModel)
      println(s"Dataset created at: $loc")
      loc
    } else {
      initialDatasetUri
    }

    // DISTRIBUTION
    println(s"Preparing Distribution (Linking to Parent Dataset: $finalDatasetUri)...")

    val initialDistUri = if (isFdpMode) "" else s"urn:uuid:${UUID.randomUUID()}"
    val distModel = createDistributionModel(cfg, finalDatasetUri, initialDistUri)
    saveLocalFile(cfg, "Distribution", distModel)

    if (isFdpMode) {
      val loc = postToFdp(cfg, "distribution", distModel)
      println(s"Distribution created at: $loc")
    }

    // LOCAL EXTRAS
    val csvwModel = createCsvwModel(cfg, stats)
    saveLocalFile(cfg, "CSVW", csvwModel)

    if (stats.vocabularies.nonEmpty) {
      val vocabModel = createSkosModel(stats)
      saveLocalFile(cfg, "Vocabularies", vocabModel)
    }

    println("Metadata generation completed successfully.")
  }

  /**
   * Posts an RDF Model to the configured FAIR Data Point.
   *
   * @param cfg    Config containing FDP URL and credentials.
   * @param prefix The resource prefix (e.g., "catalog", "dataset").
   * @param model  The Jena Model to post.
   * @return The Location URI returned by the FDP server.
   */
  private def postToFdp(cfg: Config, prefix: String, model: Model): String = {
    println(s"Posting to $prefix...")
    val result = FdpClient.postResource(cfg.fdpUrl, prefix, model, cfg.fdpEmail, cfg.fdpPassword)
    result.location.getOrElse(throw new RuntimeException(s"FDP did not return location header for $prefix")).toString
  }

  /**
   * Writes the given Jena Model to a Turtle (.ttl) file in the output directory.
   *
   * @param cfg   Configuration containing output directory path.
   * @param name  Name of the file (e.g., "Dataset").
   * @param model The Jena Model to write.
   */
  private def saveLocalFile(cfg: Config, name: String, model: Model): Unit = {
    val path = s"${cfg.outputDir}/$name.ttl"
    println(s"Writing local file: $path")
    RdfUtils.writeTurtle(path, model)
  }

  // MODEL FACTORIES

  /**
   * Creates the RDF Model for the Data Catalog.
   *
   * @param config     Configuration object.
   * @param subjectUri The subject URI for the Catalog (or empty string for relative).
   * @param isFdpMode  Boolean indicating if FDP mode is active to add isPartOf links.
   * @return A populated Jena Model.
   */
  private def createCatalogModel(config: Config, subjectUri: String, isFdpMode: Boolean): Model = {
    val m = createModel()
    val catalog = createSubject(m, subjectUri)
      .addProperty(RDF.`type`, DCAT.Catalog)
      .addProperty(DCTerms.title, config.catalogTitle)
      .addProperty(DCTerms.description, config.catalogDescription)

    // Optional fields
    if(config.issuedDate.nonEmpty) catalog.addProperty(DCTerms.issued, m.createTypedLiteral(config.issuedDate, XSDDatatype.XSDdate))
    if(config.modificationDate.nonEmpty) catalog.addProperty(DCTerms.modified, m.createTypedLiteral(config.modificationDate, XSDDatatype.XSDdate))

    val temporal = m.createResource()
      .addProperty(RDF.`type`, DCTerms.PeriodOfTime)

    if(config.catalogStartDate.nonEmpty) temporal.addProperty(DCAT.startDate, m.createTypedLiteral(config.catalogStartDate, XSDDatatype.XSDdate))
    if(config.catalogEndDate.nonEmpty) temporal.addProperty(DCAT.endDate, m.createTypedLiteral(config.catalogEndDate, XSDDatatype.XSDdate))

    // Only add temporal if it has properties
    if(config.catalogStartDate.nonEmpty || config.catalogEndDate.nonEmpty) catalog.addProperty(DCTerms.temporal, temporal)

    val legislation = m.createResource(config.catalogApplicableLegislation)
    legislation.addProperty(RDF.`type`, m.createResource(LEGAL_RESOURCE))
    catalog.addProperty(m.createProperty(DCATAP_NS, "applicableLegislation"), legislation)

    val language = m.createResource("http://publications.europa.eu/resource/authority/language/ENG")
    language.addProperty(RDF.`type`, DCTerms.LinguisticSystem)
    catalog.addProperty(DCTerms.language, language)

    if (config.catalogSpatial.nonEmpty) {
      val spatial = m.createResource(config.catalogSpatial)
      spatial.addProperty(RDF.`type`, DCTerms.Location)
      catalog.addProperty(DCTerms.spatial, spatial)
    }

    val publisher = m.createResource()
      .addProperty(RDF.`type`, FOAF.Agent)
      .addProperty(FOAF.name, config.publisherName)
      .addLiteral(m.createProperty(HEALTH_DCAT_NS, "trustedDataHolder"), true)
    catalog.addProperty(DCTerms.publisher, publisher)

    // Only link to FDP if we are actually in FDP mode and have a valid URL
    if (isFdpMode && config.fdpUrl.nonEmpty) {
      catalog.addProperty(DCTerms.isPartOf, m.createResource(config.fdpUrl))
    }
    m
  }

  /**
   * Creates the RDF Model for the Dataset.
   *
   * This method incorporates both static configuration (Title, License) and dynamic
   * statistics (Record Count, Age Range) derived from the data.
   *
   * @param config     Configuration object.
   * @param stats      Dynamic statistics (counts, age min/max).
   * @param catalogUri The URI of the parent Catalog (required for dct:isPartOf).
   * @param subjectUri The subject URI for the Dataset.
   * @return A populated Jena Model.
   */
  private def createDatasetModel(config: Config, stats: DatasetStats, catalogUri: String, subjectUri: String): Model = {
    val m = createModel()
    val dataset = createSubject(m, subjectUri)
      .addProperty(RDF.`type`, DCAT.Dataset)
      .addProperty(DCTerms.isPartOf, m.createResource(catalogUri))
      .addProperty(DCTerms.title, config.datasetTitle)
      .addProperty(DCTerms.description, config.datasetDescription)
      .addProperty(DCTerms.identifier, m.createTypedLiteral(config.datasetIdentifier, XSDDatatype.XSDanyURI))
      .addProperty(DCTerms.provenance, config.datasetProvenance)
      .addProperty(DCTerms.`type`, m.createResource(DATASET_TYPE_STATISTICAL))
      .addProperty(DCAT.theme, m.createResource(DATA_THEME_HEALTH))

    if (config.datasetVersion.nonEmpty) dataset.addProperty(m.createProperty("http://www.w3.org/ns/dcat#version"), config.datasetVersion)

    config.datasetKeywords.foreach { kw => dataset.addProperty(DCAT.keyword, kw) }

    dataset.addProperty(DCTerms.spatial, m.createResource(config.datasetSpatial).addProperty(RDF.`type`, DCTerms.Location))
    dataset.addProperty(DCTerms.accessRights, m.createResource(config.datasetAccessRights).addProperty(RDF.`type`, DCTerms.RightsStatement))
    dataset.addProperty(m.createProperty(DCATAP_NS, "applicableLegislation"), m.createResource(config.datasetLegislation).addProperty(RDF.`type`, m.createResource(LEGAL_RESOURCE)))

    if(config.datasetFrequency.nonEmpty) {
      dataset.addProperty(DCTerms.accrualPeriodicity, m.createResource(config.datasetFrequency).addProperty(RDF.`type`, DCTerms.Frequency))
    }

    // Temporal Coverage
    if (config.datasetTemporalStart.nonEmpty || config.datasetTemporalEnd.nonEmpty) {
      val temporal = m.createResource().addProperty(RDF.`type`, DCTerms.PeriodOfTime)
      if (config.datasetTemporalStart.nonEmpty) temporal.addProperty(DCAT.startDate, m.createTypedLiteral(config.datasetTemporalStart, XSDDatatype.XSDdate))
      if (config.datasetTemporalEnd.nonEmpty) temporal.addProperty(DCAT.endDate, m.createTypedLiteral(config.datasetTemporalEnd, XSDDatatype.XSDdate))
      dataset.addProperty(DCTerms.temporal, temporal)
    }

    // Contact Point
    val contact = m.createResource().addProperty(RDF.`type`, VCARD4.Kind)
    if(config.contactEmail.nonEmpty) contact.addProperty(VCARD4.hasEmail, m.createResource(s"mailto:${config.contactEmail}"))
    if (config.contactPage.nonEmpty) contact.addProperty(VCARD4.hasURL, m.createResource(config.contactPage))
    dataset.addProperty(DCAT.contactPoint, contact)

    // Health Metadata
    val hCat = m.createResource(config.healthCategory).addProperty(RDF.`type`, SKOS.Concept).addProperty(SKOS.prefLabel, m.createLiteral("Electronic Health Records", "en"))
    val hTheme = m.createResource(config.healthTheme).addProperty(RDF.`type`, SKOS.Concept).addProperty(SKOS.prefLabel, m.createLiteral("Lifecourse Health", "en"))
    dataset.addProperty(m.createProperty(HEALTH_DCAT_NS, "healthCategory"), hCat)
    dataset.addProperty(m.createProperty(HEALTH_DCAT_NS, "healthTheme"), hTheme)

    dataset.addLiteral(m.createProperty(HEALTH_DCAT_NS, "numberOfRecords"), m.createTypedLiteral(stats.recordCount, XSDDatatype.XSDnonNegativeInteger))
    dataset.addLiteral(m.createProperty(HEALTH_DCAT_NS, "numberOfUniqueIndividuals"), m.createTypedLiteral(stats.uniquePatients, XSDDatatype.XSDnonNegativeInteger))
    dataset.addLiteral(m.createProperty(HEALTH_DCAT_NS, "minTypicalAge"), m.createTypedLiteral(stats.minAge, XSDDatatype.XSDnonNegativeInteger))
    dataset.addLiteral(m.createProperty(HEALTH_DCAT_NS, "maxTypicalAge"), m.createTypedLiteral(stats.maxAge, XSDDatatype.XSDnonNegativeInteger))
    if(config.datasetPopulationCoverage.nonEmpty) dataset.addLiteral(m.createProperty(HEALTH_DCAT_NS, "populationCoverage"), config.datasetPopulationCoverage)

    // Publisher
    val publisher = m.createResource()
      .addProperty(RDF.`type`, FOAF.Agent)
      .addProperty(FOAF.name, config.publisherName)
      .addLiteral(m.createProperty(HEALTH_DCAT_NS, "trustedDataHolder"), config.publisherTrusted)

    val pubType = if (config.publisherType.startsWith("http")) config.publisherType else "http://publications.europa.eu/resource/authority/corporate-body-type/RES_BODY"
    publisher.addProperty(DCTerms.`type`, m.createResource(pubType))

    val pubContact = m.createResource().addProperty(RDF.`type`, VCARD4.Kind)
    if(config.publisherEmail.nonEmpty) pubContact.addProperty(VCARD4.hasEmail, m.createResource(s"mailto:${config.publisherEmail}"))
    if (config.publisherPage.nonEmpty) pubContact.addProperty(VCARD4.hasURL, m.createResource(config.publisherPage))
    publisher.addProperty(DCAT.contactPoint, pubContact)

    dataset.addProperty(DCTerms.publisher, publisher)

    // HDAB
    val hdab = m.createResource()
      .addProperty(RDF.`type`, FOAF.Agent)
      .addProperty(FOAF.name, config.hdabName)
      .addLiteral(m.createProperty(HEALTH_DCAT_NS, "trustedDataHolder"), config.hdabTrusted)

    val hdabType = if (config.hdabType.startsWith("http")) config.hdabType else "http://publications.europa.eu/resource/authority/corporate-body-type/EUN_BOD"
    hdab.addProperty(DCTerms.`type`, m.createResource(hdabType))

    val hdabContact = m.createResource().addProperty(RDF.`type`, VCARD4.Kind)
    if(config.hdabContactEmail.nonEmpty) hdabContact.addProperty(VCARD4.hasEmail, m.createResource(s"mailto:${config.hdabContactEmail}"))
    if (config.hdabContactPage.nonEmpty) hdabContact.addProperty(VCARD4.hasURL, m.createResource(config.hdabContactPage))
    hdab.addProperty(DCAT.contactPoint, hdabContact)

    dataset.addProperty(m.createProperty(HEALTH_DCAT_NS, "hdab"), hdab)

    if (config.datasetQualityAnnotation.nonEmpty) {
      val qualityAnn = m.createResource()
        .addProperty(RDF.`type`, m.createResource("http://www.w3.org/ns/dqv#QualityAnnotation"))
        .addProperty(DCTerms.description, config.datasetQualityAnnotation)
      dataset.addProperty(m.createProperty(DQV_NS, "hasQualityAnnotation"), qualityAnn)
    }

    // DPV Tags
    dataset.addProperty(m.createProperty(DPV_NS, "hasPersonalData"), m.createResource("https://w3c.github.io/dpv/2.0/pd#Age"))
    dataset.addProperty(m.createProperty(DPV_NS, "hasPersonalData"), m.createResource("https://w3c.github.io/dpv/2.0/pd#Gender"))

    // Sample Distribution
    if (config.sampleAccessUrl.nonEmpty) {
      val sample = m.createResource()
        .addProperty(RDF.`type`, DCAT.Distribution)
        .addProperty(DCTerms.title, config.sampleTitle)
        .addProperty(DCAT.accessURL, m.createResource(config.sampleAccessUrl))
        .addProperty(m.createProperty(DCATAP_NS, "applicableLegislation"), m.createResource(config.datasetLegislation)) // Fallback to dataset legislation if specific sample legislation missing

      if (config.sampleFormat.nonEmpty) {
        val fmt = m.createResource(config.sampleFormat)
        fmt.addProperty(RDF.`type`, m.createResource(MEDIA_TYPE_EXTENT))
        sample.addProperty(DCTerms.format, fmt)
      }
      dataset.addProperty(m.createProperty(ADMS_NS, "sample"), sample)
    }
    m
  }

  /**
   * Creates the RDF Model for the Primary Distribution.
   * This represents the actual downloadable CSV file containing the cohort data.
   *
   * @param config     Configuration object.
   * @param datasetUri The URI of the parent Dataset.
   * @param subjectUri The subject URI for the Distribution.
   * @return A populated Jena Model.
   */
  private def createDistributionModel(config: Config, datasetUri: String, subjectUri: String): Model = {
    val m = createModel()
    val dist = createSubject(m, subjectUri)
      .addProperty(RDF.`type`, DCAT.Distribution)
      .addProperty(DCTerms.isPartOf, m.createResource(datasetUri))
      .addProperty(DCTerms.title, "Distribution")
      .addProperty(DCAT.accessURL, m.createResource(config.distributionAccessUrl))

    val fmt = m.createResource(config.distributionFormat)
    fmt.addProperty(RDF.`type`, m.createResource(MEDIA_TYPE_EXTENT))
    dist.addProperty(DCTerms.format, fmt)

    if (config.distributionLicense.nonEmpty) {
      val lic = m.createResource(config.distributionLicense)
      lic.addProperty(RDF.`type`, DCTerms.LicenseDocument)
      dist.addProperty(DCTerms.license, lic)
    }

    dist.addProperty(m.createProperty(DCATAP_NS, "applicableLegislation"), m.createResource(config.distributionLegislation).addProperty(RDF.`type`, m.createResource(LEGAL_RESOURCE)))
    if (config.distributionDescription.nonEmpty) dist.addProperty(DCTerms.description, config.distributionDescription)
    m
  }

  /**
   * Generates a CSV on the Web (CSVW) schema model.
   * This describes the structure of the CSV file, including column names and data types.
   *
   * @param config Configuration object.
   * @param stats  DatasetStats containing the column definitions extracted from the dataframe.
   * @return A populated Jena Model representing the CSVW schema.
   */
  private def createCsvwModel(config: Config, stats: DatasetStats): Model = {
    val m = createModel()
    val tableGroup = m.createResource("urn:uuid:" + UUID.randomUUID())
      .addProperty(RDF.`type`, m.createResource(CSVW_NS + "TableGroup"))
    val table = m.createResource("urn:uuid:" + UUID.randomUUID())
      .addProperty(RDF.`type`, m.createResource(CSVW_NS + "Table"))
      .addProperty(DCTerms.title, "Tabular data schema")
    tableGroup.addProperty(m.createProperty(CSVW_NS, "table"), table)
    stats.columns.foreach { case (colName, colType) =>
      val col = m.createResource("urn:uuid:" + UUID.randomUUID())
        .addProperty(RDF.`type`, m.createResource(CSVW_NS + "Column"))
        .addProperty(m.createProperty(CSVW_NS, "name"), colName)
        .addProperty(m.createProperty(CSVW_NS, "titles"), colName)
      val dt = colType.toLowerCase match {
        case t if t.contains("int") || t.contains("long") => "integer"
        case t if t.contains("double") || t.contains("float") => "double"
        case t if t.contains("binary") => "binary"
        case _ => "string"
      }
      col.addProperty(m.createProperty(CSVW_NS, "datatype"), dt)
      table.addProperty(m.createProperty(CSVW_NS, "column"), col)
    }
    m
  }

  /**
   * Generates SKOS vocabularies for categorical columns in the dataset.
   * This maps raw codes (from FHIR Questionnaires) to human-readable display labels.
   *
   * @param stats DatasetStats containing the extracted vocabularies map.
   * @return A populated Jena Model containing SKOS Concept Schemes.
   */
  private def createSkosModel(stats: DatasetStats): Model = {
    val m = createModel()
    stats.vocabularies.foreach { case (colName, options) =>
      val schemeUri = s"$VOCAB_BASE/$colName"
      val scheme = m.createResource(schemeUri)
        .addProperty(RDF.`type`, SKOS.ConceptScheme)
        .addProperty(DCTerms.title, s"Vocabulary for $colName")
      options.foreach { case (code, display) =>
        val concept = m.createResource(s"$schemeUri/$code")
          .addProperty(RDF.`type`, SKOS.Concept)
          .addProperty(SKOS.inScheme, scheme)
          .addProperty(SKOS.notation, code)
          .addProperty(SKOS.prefLabel, m.createLiteral(display, "en"))
        scheme.addProperty(SKOS.hasTopConcept, concept)
      }
    }
    m
  }
}