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
 * @param startDate      Dynamically extracted earliest date from the dataset entries.
 * @param endDate        Dynamically extracted latest date from the dataset entries.
 * @param codingSystems  Dynamically extracted list of unique coding systems found in the dataset.
 */
case class DatasetStats(
                         recordCount: Long,
                         uniquePatients: Long,
                         minAge: Int,
                         maxAge: Int,
                         columns: Array[(String, String)],
                         vocabularies: Map[String, Map[String, String]] = Map.empty,
                         startDate: Option[String] = None,
                         endDate: Option[String] = None,
                         codingSystems: Seq[String] = Seq.empty
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
  private val MEDIA_TYPE = "http://purl.org/dc/terms/MediaType"
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
   * If the URI is empty or null, it creates a resource with a relative URI ("<>").
   *
   * @param m   The Jena Model.
   * @param uri The URI string (or empty string).
   * @return The created Resource.
   */
  private def createSubject(m: Model, uri: String): Resource = {
    if (uri == null || uri.trim.isEmpty) m.createResource("") else m.createResource(uri)
  }

  /** * Prevents invalid URI characters (like spaces) from breaking the Turtle Parser.
   * * @param m      The Jena Model.
   * @param uriStr The raw URI string to sanitize.
   * @return The sanitized Jena Resource.
   */
  private def safeRes(m: Model, uriStr: String): Resource = {
    val cleaned = uriStr.trim.replaceAll("\\s+", "%20").replaceAll("[<>\"{}|\\\\^`]", "")
    m.createResource(cleaned)
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
   * @param outputDir   The local directory path to output the TTL files.
   * @param fdpUrl      The FAIR Data Point URL.
   * @param fdpEmail    The authentication email for the FAIR Data Point.
   * @param fdpPassword The authentication password for the FAIR Data Point.
   * @param meta        The configuration object containing metadata fields.
   * @param stats       The calculated statistics from the ETL pipeline.
   * @param runMode     The mode the application is running in (JSON, Excel, or Browser).
   */
  def exportResults(outputDir: String, fdpUrl: String, fdpEmail: String, fdpPassword: String, meta: ConfigLoader, stats: DatasetStats, runMode: String): Unit = {
    println(s"Starting Metadata Export...")

    val isFdpMode = fdpUrl.trim.nonEmpty && fdpEmail.trim.nonEmpty
    val outDir = Paths.get(outputDir)

    if (isFdpMode) {
      println(s"FDP Mode ACTIVE. Target: $fdpUrl")
    } else {
      println("FDP Mode INACTIVE (Missing URL or Email). Switching to LOCAL FILE generation with auto-generated UUIDs.")
    }

    if (!Files.exists(outDir)) {
      println(s"Creating output directory: $outputDir")
      Files.createDirectories(outDir)
    }

    // 1. Catalog
    val configuredCatalogUri = meta.catalog.uri.getOrElse("")
    val finalCatalogUri: String = if (isFdpMode && meta.catalog.existing.contains(true) && configuredCatalogUri.startsWith(fdpUrl)) {
      println(s"Using Existing Valid FDP Catalog URI: $configuredCatalogUri")
      val model = createCatalogModel(meta, stats, configuredCatalogUri, isFdpMode, fdpUrl, runMode)
      saveLocalFile(outputDir, "Catalog", model)
      configuredCatalogUri
    } else if (isFdpMode) {
      println("Creating NEW Catalog on FDP...")
      val model = createCatalogModel(meta, stats, "", isFdpMode, fdpUrl, runMode)
      saveLocalFile(outputDir, "Catalog", model)
      val loc = postToFdp(fdpUrl, fdpEmail, fdpPassword, "catalog", model)
      println(s"Catalog confirmed at: $loc")
      loc
    } else {
      val uri = if (meta.catalog.existing.contains(true) && configuredCatalogUri.trim.nonEmpty) configuredCatalogUri.trim else s"urn:uuid:${UUID.randomUUID()}"
      println(s"Generating Local Catalog with URI: $uri")
      val model = createCatalogModel(meta, stats, uri, isFdpMode, fdpUrl, runMode)
      saveLocalFile(outputDir, "Catalog", model)
      uri
    }

    // 2. Dataset
    println(s"Preparing Dataset (Linking to Parent Catalog: $finalCatalogUri)...")
    val initialDatasetUri = if (isFdpMode) "" else s"urn:uuid:${UUID.randomUUID()}"
    val datasetModel = createDatasetModel(meta, stats, finalCatalogUri, initialDatasetUri, runMode)
    saveLocalFile(outputDir, "Dataset", datasetModel)

    val finalDatasetUri = if (isFdpMode) {
      val loc = postToFdp(fdpUrl, fdpEmail, fdpPassword, "dataset", datasetModel)
      println(s"Dataset created at: $loc")
      loc
    } else {
      initialDatasetUri
    }

    // 3. Main Distribution
    println(s"Preparing Distribution (Linking to Parent Dataset: $finalDatasetUri)...")
    val initialDistUri = if (isFdpMode) "" else s"urn:uuid:${UUID.randomUUID()}"
    val distModel = createDistributionModel(meta.distribution, finalDatasetUri, initialDistUri)
    saveLocalFile(outputDir, "Distribution", distModel)

    if (isFdpMode) {
      val loc = postToFdp(fdpUrl, fdpEmail, fdpPassword, "distribution", distModel)
      println(s"Distribution created at: $loc")
    }

    // 4. Extras
    saveLocalFile(outputDir, "CSVW", createCsvwModel(stats))
    if (stats.vocabularies.nonEmpty) {
      saveLocalFile(outputDir, "Vocabularies", createSkosModel(stats))
    }

    println("Metadata generation completed successfully.")
  }

  /**
   * Posts an RDF Model to the configured FAIR Data Point.
   *
   * @param fdpUrl      FDP base URL.
   * @param fdpEmail    FDP auth email.
   * @param fdpPassword FDP auth password.
   * @param prefix      The resource prefix (e.g., "catalog", "dataset").
   * @param model       The Jena Model to post.
   * @return The Location URI returned by the FDP server.
   */
  private def postToFdp(fdpUrl: String, fdpEmail: String, fdpPassword: String, prefix: String, model: Model): String = {
    println(s"Posting to $prefix...")
    val result = FdpClient.postResource(fdpUrl, prefix, model, fdpEmail, fdpPassword)
    result.location.getOrElse(throw new RuntimeException(s"FDP did not return location header for $prefix")).toString
  }

  /**
   * Writes the given Jena Model to a Turtle (.ttl) file in the output directory.
   *
   * @param outputDir The destination folder string.
   * @param name      Name of the file (e.g., "Dataset").
   * @param model     The Jena Model to write.
   */
  private def saveLocalFile(outputDir: String, name: String, model: Model): Unit = {
    val path = s"$outputDir/$name.ttl"
    println(s"Writing local file: $path")
    RdfUtils.writeTurtle(path, model)
  }

  // --- MODEL FACTORIES ---

  /**
   * Creates the RDF Model for the Data Catalog.
   *
   * @param meta       Configuration object.
   * @param stats      Dynamic statistics (for temporal overrides).
   * @param subjectUri The subject URI for the Catalog (or empty string for relative).
   * @param isFdpMode  Boolean indicating if FDP mode is active to add isPartOf links.
   * @param fdpUrl     The FDP server base URL.
   * @param runMode    The current application run mode.
   * @return A populated Jena Model.
   */
  private def createCatalogModel(meta: ConfigLoader, stats: DatasetStats, subjectUri: String, isFdpMode: Boolean, fdpUrl: String, runMode: String): Model = {
    val m = createModel()
    val catalog = createSubject(m, subjectUri).addProperty(RDF.`type`, DCAT.Catalog)

    if (meta.catalog.existing.contains(true)) return m

    val isExcel = runMode.toLowerCase == "excel"

    meta.catalog.title.foreach(catalog.addProperty(DCTerms.title, _))
    meta.catalog.description.foreach(catalog.addProperty(DCTerms.description, _))
    meta.catalog.applicableLegislation.foreach(al => catalog.addProperty(m.createProperty(DCATAP_NS, "applicableLegislation"), safeRes(m, al).addProperty(RDF.`type`, m.createResource(LEGAL_RESOURCE))))

    val catStart = if (!isExcel && stats.startDate.isDefined) stats.startDate else meta.catalog.temporalCoverage.flatMap(_.start)
    val catEnd = if (!isExcel && stats.endDate.isDefined) stats.endDate else meta.catalog.temporalCoverage.flatMap(_.end)

    if (catStart.isDefined || catEnd.isDefined) {
      val temporal = m.createResource().addProperty(RDF.`type`, DCTerms.PeriodOfTime)
      catStart.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(s => temporal.addProperty(DCAT.startDate, m.createTypedLiteral(s, XSDDatatype.XSDdate)))
      catEnd.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(e => temporal.addProperty(DCAT.endDate, m.createTypedLiteral(e, XSDDatatype.XSDdate)))
      catalog.addProperty(DCTerms.temporal, temporal)
    }

    meta.catalog.geographicalCoverage.foreach(_.foreach(sp => catalog.addProperty(DCTerms.spatial, safeRes(m, sp).addProperty(RDF.`type`, DCTerms.Location))))
    meta.catalog.themes.foreach(t => catalog.addProperty(DCAT.theme, safeRes(m, t)))

    meta.catalog.releaseDate.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(d => catalog.addProperty(DCTerms.issued, m.createTypedLiteral(d, XSDDatatype.XSDdate)))
    meta.catalog.modificationDate.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(d => catalog.addProperty(DCTerms.modified, m.createTypedLiteral(d, XSDDatatype.XSDdate)))
    meta.catalog.licence.foreach(l => catalog.addProperty(DCTerms.license, safeRes(m, l).addProperty(RDF.`type`, DCTerms.LicenseDocument)))
    meta.catalog.homepage.foreach(h => catalog.addProperty(FOAF.homepage, safeRes(m, h).addProperty(RDF.`type`, FOAF.Document)))
    meta.catalog.rights.foreach(r => catalog.addProperty(DCTerms.rights, m.createResource().addProperty(RDF.`type`, DCTerms.RightsStatement).addProperty(DCTerms.description, r)))
    meta.catalog.language.foreach(langs => langs.foreach(l => catalog.addProperty(DCTerms.language, safeRes(m, l).addProperty(RDF.`type`, DCTerms.LinguisticSystem))))

    meta.catalog.creator.foreach { c =>
      val creator = m.createResource().addProperty(RDF.`type`, FOAF.Agent)
      creator.addProperty(FOAF.name, c.name)
      c.`type`.foreach(t => creator.addProperty(DCTerms.`type`, safeRes(m, t)))
      catalog.addProperty(DCTerms.creator, creator)
    }

    meta.catalog.publisher.foreach { p =>
      val publisher = m.createResource().addProperty(RDF.`type`, FOAF.Agent)
      publisher.addProperty(FOAF.name, p.name)
      p.trusted.foreach(t => publisher.addLiteral(m.createProperty(HEALTH_DCAT_NS, "trustedDataHolder"), t))
      p.`type`.foreach(t => publisher.addProperty(DCTerms.`type`, safeRes(m, t)))

      val contact = m.createResource().addProperty(RDF.`type`, VCARD4.Kind)
      p.contactPoint.email.foreach(e => contact.addProperty(VCARD4.hasEmail, safeRes(m, s"mailto:$e")))
      p.contactPoint.page.foreach(page => contact.addProperty(VCARD4.hasURL, safeRes(m, page)))
      publisher.addProperty(DCAT.contactPoint, contact)

      catalog.addProperty(DCTerms.publisher, publisher)
    }

    if (isFdpMode && fdpUrl.nonEmpty) catalog.addProperty(DCTerms.isPartOf, safeRes(m, fdpUrl))
    m
  }

  /**
   * Helper method to map Distribution metadata parameters to an existing RDF resource.
   * Ensures SHACL compliance for formatting and node constraints.
   *
   * @param m        The Jena Model context.
   * @param dist     The Target Resource (Main, Sample, or Analytics distribution).
   * @param distMeta The extracted user input defining the distribution.
   */
  private def populateDistribution(m: Model, dist: Resource, distMeta: DistributionMetadataUserInput): Unit = {
    distMeta.accessURL.foreach(url => dist.addProperty(DCAT.accessURL, safeRes(m, url)))
    distMeta.applicableLegislation.foreach(al => dist.addProperty(m.createProperty(DCATAP_NS, "applicableLegislation"), safeRes(m, al).addProperty(RDF.`type`, m.createResource(LEGAL_RESOURCE))))
    distMeta.format.foreach(fmt => dist.addProperty(DCTerms.format, safeRes(m, fmt).addProperty(RDF.`type`, m.createResource(MEDIA_TYPE_EXTENT))))

    distMeta.title.foreach(dist.addProperty(DCTerms.title, _))
    distMeta.description.foreach(dist.addProperty(DCTerms.description, _))
    distMeta.license.foreach(lic => dist.addProperty(DCTerms.license, safeRes(m, lic).addProperty(RDF.`type`, DCTerms.LicenseDocument)))
    distMeta.availability.foreach(av => dist.addProperty(m.createProperty(DCATAP_NS, "availability"), safeRes(m, av)))
    distMeta.byteSize.foreach(bs => dist.addProperty(DCAT.byteSize, m.createTypedLiteral(bs.toLong, XSDDatatype.XSDnonNegativeInteger)))

    distMeta.checksum.foreach { cs =>
      val checksumRes = m.createResource().addProperty(RDF.`type`, m.createResource("http://spdx.org/rdf/terms#Checksum"))
      checksumRes.addProperty(m.createProperty("http://spdx.org/rdf/terms#algorithm"), safeRes(m, cs.algorithm).addProperty(RDF.`type`, m.createResource("http://spdx.org/rdf/terms#ChecksumAlgorithm")))
      val hex = cs.value.replaceAll("[^0-9a-fA-F]", "")
      val validHex = if (hex.length % 2 != 0) hex.dropRight(1) else hex
      if (validHex.nonEmpty) {
        checksumRes.addProperty(m.createProperty("http://spdx.org/rdf/terms#checksumValue"), m.createTypedLiteral(validHex, XSDDatatype.XSDhexBinary))
      }
      dist.addProperty(m.createProperty("http://spdx.org/rdf/terms#checksum"), checksumRes)
    }

    distMeta.mediaType.foreach(mt => dist.addProperty(DCAT.mediaType, safeRes(m, mt).addProperty(RDF.`type`, m.createResource(MEDIA_TYPE))))
    distMeta.packagingFormat.foreach(pf => dist.addProperty(DCAT.packageFormat, safeRes(m, pf).addProperty(RDF.`type`, m.createResource(MEDIA_TYPE))))
    distMeta.releaseDate.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(rd => dist.addProperty(DCTerms.issued, m.createTypedLiteral(rd, XSDDatatype.XSDdate)))
    distMeta.rights.foreach(r => dist.addProperty(DCTerms.rights, m.createResource().addProperty(RDF.`type`, DCTerms.RightsStatement).addProperty(DCTerms.description, r)))
    distMeta.spatialResolution.foreach(sr => dist.addProperty(DCAT.spatialResolutionInMeters, m.createTypedLiteral(sr, XSDDatatype.XSDdecimal)))
    distMeta.status.foreach(s => dist.addProperty(m.createProperty(ADMS_NS, "status"), safeRes(m, s)))

    distMeta.temporalResolution.filter(_.matches("^P.*")).foreach(tr => dist.addProperty(DCAT.temporalResolution, m.createTypedLiteral(tr, XSDDatatype.XSDduration)))
    distMeta.accessService.foreach(as => dist.addProperty(m.createProperty(DCAT.NS, "accessService"), safeRes(m, as)))
  }

  /**
   * Creates the RDF Model for the Primary Distribution.
   * This represents the actual downloadable file containing the cohort data.
   *
   * @param distMeta   Configuration input for the distribution.
   * @param datasetUri The URI of the parent Dataset.
   * @param subjectUri The subject URI for the Distribution.
   * @return A populated Jena Model.
   */
  private def createDistributionModel(distMeta: DistributionMetadataUserInput, datasetUri: String, subjectUri: String): Model = {
    val m = createModel()
    val dist = createSubject(m, subjectUri).addProperty(RDF.`type`, DCAT.Distribution).addProperty(DCTerms.isPartOf, m.createResource(datasetUri))
    populateDistribution(m, dist, distMeta)
    m
  }

  /**
   * Creates the RDF Model for the Dataset.
   *
   * This method incorporates both static configuration (Title, License) and dynamic
   * statistics (Record Count, Age Range) derived from the data.
   *
   * @param meta       Configuration object.
   * @param stats      Dynamic statistics (counts, age min/max).
   * @param catalogUri The URI of the parent Catalog (required for dct:isPartOf).
   * @param subjectUri The subject URI for the Dataset.
   * @param runMode    The active run mode defining dynamic fallback behavior.
   * @return A populated Jena Model.
   */
  private def createDatasetModel(meta: ConfigLoader, stats: DatasetStats, catalogUri: String, subjectUri: String, runMode: String): Model = {
    val m = createModel()
    val dataset = createSubject(m, subjectUri)
      .addProperty(RDF.`type`, DCAT.Dataset)
      .addProperty(DCTerms.isPartOf, m.createResource(catalogUri))

    val isExcel = runMode.toLowerCase == "excel"

    meta.dataset.datasetType.foreach(dt => dataset.addProperty(DCTerms.`type`, safeRes(m, dt)))
    meta.dataset.theme.foreach(t => dataset.addProperty(DCAT.theme, safeRes(m, t)))
    meta.dataset.title.foreach(dataset.addProperty(DCTerms.title, _))
    meta.dataset.description.foreach(dataset.addProperty(DCTerms.description, _))
    meta.dataset.provenance.foreach(dataset.addProperty(DCTerms.provenance, _))
    meta.dataset.version.foreach(dataset.addProperty(m.createProperty("http://www.w3.org/ns/dcat#version"), _))
    meta.dataset.applicableLegislation.foreach(al => dataset.addProperty(m.createProperty(DCATAP_NS, "applicableLegislation"), safeRes(m, al).addProperty(RDF.`type`, m.createResource(LEGAL_RESOURCE))))
    meta.dataset.accessRights.foreach(ar => dataset.addProperty(DCTerms.accessRights, safeRes(m, ar).addProperty(RDF.`type`, DCTerms.RightsStatement)))
    meta.dataset.frequency.foreach(f => dataset.addProperty(DCTerms.accrualPeriodicity, safeRes(m, f).addProperty(RDF.`type`, DCTerms.Frequency)))

    meta.dataset.identifier.foreach(id => dataset.addProperty(DCTerms.identifier, m.createTypedLiteral(id.replaceAll("\\s+", ""), XSDDatatype.XSDanyURI)))

    meta.dataset.keyword.foreach(_.foreach(kw => dataset.addProperty(DCAT.keyword, kw)))
    meta.dataset.spatial.foreach(_.foreach(sp => dataset.addProperty(DCTerms.spatial, safeRes(m, sp).addProperty(RDF.`type`, DCTerms.Location))))

    val dsStart = if (!isExcel && stats.startDate.isDefined) stats.startDate else meta.dataset.temporalCoverage.flatMap(_.start)
    val dsEnd = if (!isExcel && stats.endDate.isDefined) stats.endDate else meta.dataset.temporalCoverage.flatMap(_.end)

    if (dsStart.isDefined || dsEnd.isDefined) {
      val temporal = m.createResource().addProperty(RDF.`type`, DCTerms.PeriodOfTime)
      dsStart.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(s => temporal.addProperty(DCAT.startDate, m.createTypedLiteral(s, XSDDatatype.XSDdate)))
      dsEnd.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(e => temporal.addProperty(DCAT.endDate, m.createTypedLiteral(e, XSDDatatype.XSDdate)))
      dataset.addProperty(DCTerms.temporal, temporal)
    }

    val contact = m.createResource().addProperty(RDF.`type`, VCARD4.Kind)
    meta.dataset.contactPoint.email.foreach(e => contact.addProperty(VCARD4.hasEmail, safeRes(m, s"mailto:$e")))
    meta.dataset.contactPoint.page.foreach(p => contact.addProperty(VCARD4.hasURL, safeRes(m, p)))
    dataset.addProperty(DCAT.contactPoint, contact)

    meta.dataset.healthCategory.foreach { hc =>
      val hcConcept = safeRes(m, hc).addProperty(RDF.`type`, SKOS.Concept)
      hcConcept.addProperty(SKOS.prefLabel, m.createLiteral(hc.split("/").last.replace("_", " "), "en"))
      dataset.addProperty(m.createProperty(HEALTH_DCAT_NS, "healthCategory"), hcConcept)
    }

    meta.dataset.healthTheme.foreach { ht =>
      val htConcept = safeRes(m, ht).addProperty(RDF.`type`, SKOS.Concept)
      htConcept.addProperty(SKOS.prefLabel, m.createLiteral(ht.split("/").last.replace("_", " "), "en"))
      dataset.addProperty(m.createProperty(HEALTH_DCAT_NS, "healthTheme"), htConcept)
    }

    meta.dataset.populationCoverage.foreach(dataset.addLiteral(m.createProperty(HEALTH_DCAT_NS, "populationCoverage"), _))

    val numRec = if (!isExcel && stats.recordCount > 0) stats.recordCount else meta.dataset.numRecords.map(_.toLong).getOrElse(0L)
    val numPat = if (!isExcel && stats.uniquePatients > 0) stats.uniquePatients else meta.dataset.numUniqueIndividual.map(_.toLong).getOrElse(0L)
    val minA = if (!isExcel && stats.minAge > 0) stats.minAge else meta.dataset.minAge.getOrElse(0)
    val maxA = if (!isExcel && stats.maxAge > 0) stats.maxAge else meta.dataset.maxAge.getOrElse(0)

    dataset.addLiteral(m.createProperty(HEALTH_DCAT_NS, "numberOfRecords"), m.createTypedLiteral(numRec, XSDDatatype.XSDnonNegativeInteger))
    dataset.addLiteral(m.createProperty(HEALTH_DCAT_NS, "numberOfUniqueIndividuals"), m.createTypedLiteral(numPat, XSDDatatype.XSDnonNegativeInteger))
    dataset.addLiteral(m.createProperty(HEALTH_DCAT_NS, "minTypicalAge"), m.createTypedLiteral(minA, XSDDatatype.XSDnonNegativeInteger))
    dataset.addLiteral(m.createProperty(HEALTH_DCAT_NS, "maxTypicalAge"), m.createTypedLiteral(maxA, XSDDatatype.XSDnonNegativeInteger))

    meta.dataset.publisher.foreach { p =>
      val pub = m.createResource().addProperty(RDF.`type`, FOAF.Agent).addProperty(FOAF.name, p.name)
      p.trusted.foreach(t => pub.addLiteral(m.createProperty(HEALTH_DCAT_NS, "trustedDataHolder"), t))
      p.`type`.foreach(t => pub.addProperty(DCTerms.`type`, safeRes(m, t)))
      p.note.foreach(n => pub.addProperty(DCTerms.description, n))
      val pubContact = m.createResource().addProperty(RDF.`type`, VCARD4.Kind)
      p.contactPoint.email.foreach(e => pubContact.addProperty(VCARD4.hasEmail, safeRes(m, s"mailto:$e")))
      p.contactPoint.page.foreach(p => pubContact.addProperty(VCARD4.hasURL, safeRes(m, p)))
      pub.addProperty(DCAT.contactPoint, pubContact)
      dataset.addProperty(DCTerms.publisher, pub)
    }

    val hdab = m.createResource().addProperty(RDF.`type`, FOAF.Agent)
    meta.dataset.hdab.name.foreach(hdab.addProperty(FOAF.name, _))
    meta.dataset.hdab.trusted.foreach(t => hdab.addLiteral(m.createProperty(HEALTH_DCAT_NS, "trustedDataHolder"), t))
    meta.dataset.hdab.`type`.foreach(t => hdab.addProperty(DCTerms.`type`, safeRes(m, t)))
    val hdabContact = m.createResource().addProperty(RDF.`type`, VCARD4.Kind)
    meta.dataset.hdab.contactPoint.email.foreach(e => hdabContact.addProperty(VCARD4.hasEmail, safeRes(m, s"mailto:$e")))
    meta.dataset.hdab.contactPoint.page.foreach(p => hdabContact.addProperty(VCARD4.hasURL, safeRes(m, p)))
    hdab.addProperty(DCAT.contactPoint, hdabContact)
    dataset.addProperty(m.createProperty(HEALTH_DCAT_NS, "hdab"), hdab)

    // --- OPTIONALS ---
    meta.dataset.conformsTo.foreach(c => dataset.addProperty(DCTerms.conformsTo, safeRes(m, c).addProperty(RDF.`type`, DCTerms.Standard)))
    meta.dataset.documentation.foreach(d => dataset.addProperty(FOAF.page, safeRes(m, d).addProperty(RDF.`type`, FOAF.Document)))
    meta.dataset.alternative.foreach(_.foreach(a => dataset.addProperty(DCTerms.alternative, a)))

    val finalCodingSystems = if (!isExcel && stats.codingSystems.nonEmpty) stats.codingSystems else meta.dataset.codingSystems.getOrElse(Seq.empty)
    finalCodingSystems.foreach(cs => dataset.addProperty(m.createProperty(HEALTH_DCAT_NS, "hasCodingSystem"), safeRes(m, cs)))

    meta.dataset.codeValues.foreach { codes =>
      codes.foreach { cv =>
        val concept = m.createResource().addProperty(RDF.`type`, SKOS.Concept).addProperty(SKOS.notation, cv.notation).addProperty(SKOS.prefLabel, m.createLiteral(cv.label, "en"))
        cv.scheme.foreach(s => concept.addProperty(SKOS.inScheme, safeRes(m, s)))
        dataset.addProperty(m.createProperty(HEALTH_DCAT_NS, "hasCodeValues"), concept)
      }
    }

    meta.dataset.retentionPeriod.foreach { period =>
      val temp = m.createResource().addProperty(RDF.`type`, DCTerms.PeriodOfTime)
      period.start.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(s => temp.addProperty(DCAT.startDate, m.createTypedLiteral(s, XSDDatatype.XSDdate)))
      period.end.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(e => temp.addProperty(DCAT.endDate, m.createTypedLiteral(e, XSDDatatype.XSDdate)))
      dataset.addProperty(m.createProperty(HEALTH_DCAT_NS, "retentionPeriod"), temp)
    }

    meta.dataset.personalData.foreach(_.foreach(pd => dataset.addProperty(m.createProperty(DPV_NS, "hasPersonalData"), safeRes(m, pd))))
    meta.dataset.landingPage.foreach(lp => dataset.addProperty(DCAT.landingPage, safeRes(m, lp).addProperty(RDF.`type`, FOAF.Document)))
    meta.dataset.language.foreach(l => dataset.addProperty(DCTerms.language, safeRes(m, l).addProperty(RDF.`type`, DCTerms.LinguisticSystem)))
    meta.dataset.modificationDate.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(md => dataset.addProperty(DCTerms.modified, m.createTypedLiteral(md, XSDDatatype.XSDdate)))
    meta.dataset.releaseDate.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(rd => dataset.addProperty(DCTerms.issued, m.createTypedLiteral(rd, XSDDatatype.XSDdate)))

    meta.dataset.otherIdentifier.foreach(_.foreach { oi =>
      val idNode = m.createResource()
        .addProperty(RDF.`type`, m.createResource(ADMS_NS + "Identifier"))
        .addProperty(SKOS.notation, m.createTypedLiteral(oi, XSDDatatype.XSDstring))
      dataset.addProperty(m.createProperty(ADMS_NS, "identifier"), idNode)
    })

    meta.dataset.qualifiedAttribution.foreach(_.foreach { qa =>
      val attr = m.createResource().addProperty(RDF.`type`, m.createResource(PROV_NS + "Attribution"))
      val agent = m.createResource().addProperty(RDF.`type`, FOAF.Agent).addProperty(FOAF.name, qa.name)
      attr.addProperty(m.createProperty(PROV_NS, "agent"), agent)
      qa.role.foreach(r => attr.addProperty(DCAT.hadRole, safeRes(m, r)))
      dataset.addProperty(m.createProperty(PROV_NS, "qualifiedAttribution"), attr)
    })

    meta.dataset.spatialResolution.foreach(sr => dataset.addProperty(DCAT.spatialResolutionInMeters, m.createTypedLiteral(sr, XSDDatatype.XSDdecimal)))
    meta.dataset.temporalResolution.filter(_.matches("^P.*")).foreach(tr => dataset.addProperty(DCAT.temporalResolution, m.createTypedLiteral(tr, XSDDatatype.XSDduration)))
    meta.dataset.versionNotes.foreach(vn => dataset.addProperty(m.createProperty(ADMS_NS, "versionNotes"), vn))
    meta.dataset.wasGeneratedBy.foreach(_.foreach(wg => dataset.addProperty(m.createProperty(PROV_NS, "wasGeneratedBy"), safeRes(m, wg).addProperty(RDF.`type`, m.createResource(PROV_NS + "Activity")))))
    meta.dataset.purpose.foreach(p => dataset.addProperty(m.createProperty(DPV_NS, "hasPurpose"), p))

    meta.dataset.creator.foreach { c =>
      val creator = m.createResource().addProperty(RDF.`type`, FOAF.Agent).addProperty(FOAF.name, c.name)
      c.`type`.foreach(t => creator.addProperty(DCTerms.`type`, safeRes(m, t)))
      c.email.foreach(e => creator.addProperty(VCARD4.hasEmail, safeRes(m, s"mailto:$e")))
      dataset.addProperty(DCTerms.creator, creator)
    }

    meta.dataset.sample.foreach { s =>
      val sample = m.createResource().addProperty(RDF.`type`, DCAT.Distribution)
      populateDistribution(m, sample, s)
      dataset.addProperty(m.createProperty(ADMS_NS, "sample"), sample)
    }

    meta.dataset.analytics.foreach { a =>
      val analyticsDist = m.createResource().addProperty(RDF.`type`, DCAT.Distribution)
      populateDistribution(m, analyticsDist, a)
      dataset.addProperty(DCTerms.relation, analyticsDist)
    }

    m
  }

  /**
   * Generates a CSV on the Web (CSVW) schema model.
   * This describes the structure of the CSV file, including column names and data types.
   *
   * @param stats DatasetStats containing the column definitions extracted from the dataframe.
   * @return A populated Jena Model representing the CSVW schema.
   */
  private def createCsvwModel(stats: DatasetStats): Model = {
    val m = createModel()
    val tableGroup = m.createResource("urn:uuid:" + UUID.randomUUID()).addProperty(RDF.`type`, m.createResource(CSVW_NS + "TableGroup"))
    val table = m.createResource("urn:uuid:" + UUID.randomUUID()).addProperty(RDF.`type`, m.createResource(CSVW_NS + "Table")).addProperty(DCTerms.title, "Tabular data schema")
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
      val scheme = m.createResource(schemeUri).addProperty(RDF.`type`, SKOS.ConceptScheme).addProperty(DCTerms.title, s"Vocabulary for $colName")
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