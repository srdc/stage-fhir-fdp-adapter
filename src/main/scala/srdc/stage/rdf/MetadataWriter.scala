package srdc.stage.rdf

import srdc.stage.client.FdpClient
import org.apache.jena.rdf.model.{Model, ModelFactory, Resource}
import org.apache.jena.vocabulary.{DCAT, DCTerms, RDF, SKOS, VCARD4, XSD}
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.sparql.vocabulary.FOAF
import org.slf4j.LoggerFactory
import srdc.stage.util.RdfUtils
import srdc.stage.vocab.{ADMS, CSVW, DCATAP, DPV, HealthDCATAP, PROV}

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
 * @param columnToVocabId Map bridging CSV column names to their short vocabulary identifiers.
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
                         columnToVocabId: Map[String, String] = Map.empty,
                         startDate: Option[String] = None,
                         endDate: Option[String] = None,
                         codingSystems: Seq[String] = Seq.empty
                       )

object DatasetStats {
  def mergeGlobal(statsList: Seq[DatasetStats]): DatasetStats = {
    if (statsList.isEmpty) return DatasetStats(0, 0, 0, 0, Array.empty)

    def minDate(d1: Option[String], d2: Option[String]): Option[String] =
      (d1 ++ d2).reduceOption((a, b) => if (a < b) a else b)

    def maxDate(d1: Option[String], d2: Option[String]): Option[String] =
      (d1 ++ d2).reduceOption((a, b) => if (a > b) a else b)

    DatasetStats(
      recordCount = statsList.map(_.recordCount).sum,
      uniquePatients = statsList.map(_.uniquePatients).max,
      minAge = statsList.map(_.minAge).filter(_ > 0).reduceOption(_ min _).getOrElse(0),
      maxAge = statsList.map(_.maxAge).max,
      columns = Array.empty,
      vocabularies = Map.empty,
      columnToVocabId = Map.empty,
      startDate = statsList.map(_.startDate).reduceLeftOption(minDate).flatten,
      endDate = statsList.map(_.endDate).reduceLeftOption(maxDate).flatten,
      codingSystems = statsList.flatMap(_.codingSystems).distinct
    )
  }
}

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

  private val logger = LoggerFactory.getLogger(getClass)

  private val VOCAB_BASE = "http://example.org/vocab"
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
    m.setNsPrefix("healthdcatap", HealthDCATAP.NS)
    m.setNsPrefix("dcatap", DCATAP.NS)
    m.setNsPrefix("csvw", CSVW.NS)
    m.setNsPrefix("dpv", DPV.NS)
    m.setNsPrefix("prov", PROV.NS)
    m.setNsPrefix("skos", SKOS.getURI)
    m.setNsPrefix("xsd", XSD.NS)
    m.setNsPrefix("vcard", VCARD4.NS)
    m.setNsPrefix("adms", ADMS.NS)
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

  /**
   * Prevents invalid URI characters (like spaces) from breaking the Turtle Parser.
   * @param m      The Jena Model.
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
   * 1.  Catalog: Checks if an existing catalog should be used or a new one created globally.
   * 2.  Dataset: Creates the Dataset metadata linked to the Catalog.
   * 3.  Distribution: Creates the Distribution metadata linked to the Dataset.
   * 4.  Local Files: Generates CSVW schemas (engraved with SKOS vocabularies) locally.
   *
   * All generated models are also saved as Turtle (.ttl) files in the output directory.
   *
   * @param outputDir        The local directory path to output the TTL files.
   * @param fdpUrl           The FAIR Data Point URL.
   * @param fdpEmail         The authentication email for the FAIR Data Point.
   * @param fdpPassword      The authentication password for the FAIR Data Point.
   * @param meta             The configuration object containing metadata fields.
   * @param jobStats         The calculated job-specific statistics from the ETL pipeline.
   * @param globalStats      The aggregated global statistics across all executed jobs.
   * @param runMode          The mode the application is running in (JSON, Excel, or Browser).
   * @param sharedCatalogUri An optional URI to reuse an existing Catalog across multiple jobs.
   * @return The URI of the Catalog used or created.
   */
  def exportResults(outputDir: String, fdpUrl: String, fdpEmail: String, fdpPassword: String, meta: MetadataUserInput, jobStats: DatasetStats, globalStats: DatasetStats, runMode: String, sharedCatalogUri: Option[String] = None, isFhirConfigured: Boolean = true): String = {
    logger.info("Starting Metadata Export...")

    val isFdpMode = fdpUrl.trim.nonEmpty && fdpEmail.trim.nonEmpty
    val outDir = Paths.get(outputDir)

    if (isFdpMode) {
      logger.info("FDP Mode ACTIVE. Target: {}", fdpUrl)
    } else {
      logger.info("FDP Mode INACTIVE (Missing URL or Email). Switching to LOCAL FILE generation with auto-generated UUIDs.")
    }

    if (!Files.exists(outDir)) {
      logger.info("Creating output directory: {}", outputDir)
      Files.createDirectories(outDir)
    }

    // 1. Catalog
    val finalCatalogUri: String = sharedCatalogUri.getOrElse {
      val configuredCatalogUri = meta.catalog.uri.getOrElse("")
      if (isFdpMode && meta.catalog.existing.contains(true) && configuredCatalogUri.startsWith(fdpUrl)) {
        logger.info("Using Existing Valid FDP Catalog URI: {}", configuredCatalogUri)
        configuredCatalogUri
      } else if (isFdpMode) {
        logger.info("Creating NEW Catalog on FDP...")
        val model = createCatalogModel(meta, globalStats, "", isFdpMode, fdpUrl, runMode)
        val loc = postToFdp(fdpUrl, fdpEmail, fdpPassword, "catalog", model)
        logger.info("Catalog confirmed at: {}", loc)
        loc
      } else {
        val uri = if (meta.catalog.existing.contains(true) && configuredCatalogUri.trim.nonEmpty) configuredCatalogUri.trim else s"urn:uuid:${UUID.randomUUID()}"
        logger.info("Generating Local Catalog with URI: {}", uri)
        uri
      }
    }
    saveLocalFile(outputDir, "Catalog", createCatalogModel(meta, globalStats, finalCatalogUri, isFdpMode, fdpUrl, runMode))

    // 2. Dataset (Uses Job-Specific Stats)
    val finalDatasetUri = if (isFdpMode) {
      logger.info("Creating NEW Dataset on FDP...")
      val datasetModel = createDatasetModel(meta, jobStats, finalCatalogUri, "", runMode)
      val loc = postToFdp(fdpUrl, fdpEmail, fdpPassword, "dataset", datasetModel)
      logger.info("Dataset created at: {}", loc)
      loc
    } else {
      s"urn:uuid:${UUID.randomUUID()}"
    }
    saveLocalFile(outputDir, "Dataset", createDatasetModel(meta, jobStats, finalCatalogUri, finalDatasetUri, runMode))

    // 3. Main Distribution
    logger.info("Preparing Distribution (Linking to Parent Dataset: {})...", finalDatasetUri)
    val initialDistUri = if (isFdpMode) "" else s"urn:uuid:${UUID.randomUUID()}"
    val distModel = createDistributionModel(meta.distribution, finalDatasetUri, initialDistUri)
    saveLocalFile(outputDir, "Distribution", distModel)

    val finalDistUri = if (isFdpMode) {
      val loc = postToFdp(fdpUrl, fdpEmail, fdpPassword, "distribution", distModel)
      logger.info("Distribution created at: {}", loc)
      loc
    } else {
      initialDistUri
    }

    // 4. CSVW
    val initialCsvwUri = if (isFdpMode) "" else s"urn:uuid:${UUID.randomUUID()}"
    val csvwModel = if (isFhirConfigured) {
      val model = createCsvwModel(jobStats, initialCsvwUri, finalDistUri)
      if (jobStats.vocabularies.nonEmpty) {
        model.add(createConceptSchemes(jobStats))
      }
      model
    } else {
      val fields = meta.dataDictionary.getOrElse(List.empty)
      if (fields.isEmpty) {
        logger.warn("No FHIR server configured and no Data Dictionary sheet present — CSVW will be empty.")
      }
      createDictionaryModel(
        fields = fields,
        vocabularies = meta.dataDictionaryValueSets,
        subjectUri = initialCsvwUri,
        parentDistributionUri = finalDistUri
      )
    }

    saveLocalFile(outputDir, "CSVW", csvwModel)

    if (isFdpMode) {
      val loc = postToFdp(fdpUrl, fdpEmail, fdpPassword, "csvw", csvwModel)
      logger.info("CSVW created at: {}", loc)
      logger.info("Metadata Generation and FDP Validations are Successfully Completed")
    } else {
      logger.info("Metadata Generation is Successfully Completed")
    }

    finalCatalogUri
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
    logger.info("Posting to {}...", prefix)
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
    logger.info("Writing local file: {}", path)
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
  private def createCatalogModel(meta: MetadataUserInput, stats: DatasetStats, subjectUri: String, isFdpMode: Boolean, fdpUrl: String, runMode: String): Model = {
    val m = createModel()
    val catalog = createSubject(m, subjectUri).addProperty(RDF.`type`, DCAT.Catalog)

    if (meta.catalog.existing.contains(true)) return m

    val isExcel = runMode.toLowerCase == "excel"

    meta.catalog.title.foreach(catalog.addProperty(DCTerms.title, _))
    meta.catalog.description.foreach(catalog.addProperty(DCTerms.description, _))
    meta.catalog.applicableLegislation.foreach(al => catalog.addProperty(DCATAP.applicableLegislation, safeRes(m, al).addProperty(RDF.`type`, m.createResource(LEGAL_RESOURCE))))

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
      p.trusted.foreach(t => publisher.addLiteral(HealthDCATAP.trustedDataHolder, t))
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
    distMeta.applicableLegislation.foreach(al => dist.addProperty(DCATAP.applicableLegislation, safeRes(m, al).addProperty(RDF.`type`, m.createResource(LEGAL_RESOURCE))))
    distMeta.format.foreach(fmt => dist.addProperty(DCTerms.format, safeRes(m, fmt).addProperty(RDF.`type`, m.createResource(MEDIA_TYPE_EXTENT))))

    distMeta.title.foreach(dist.addProperty(DCTerms.title, _))
    distMeta.description.foreach(dist.addProperty(DCTerms.description, _))
    distMeta.license.foreach(lic => dist.addProperty(DCTerms.license, safeRes(m, lic).addProperty(RDF.`type`, DCTerms.LicenseDocument)))
    distMeta.availability.foreach(av => dist.addProperty(DCATAP.availability, safeRes(m, av)))
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
    distMeta.status.foreach(s => dist.addProperty(ADMS.status, safeRes(m, s)))

    distMeta.temporalResolution.filter(_.matches("^P.*")).foreach(tr => dist.addProperty(DCAT.temporalResolution, m.createTypedLiteral(tr, XSDDatatype.XSDduration)))
    distMeta.accessService.foreach(as => dist.addProperty(DCAT.accessService, safeRes(m, as)))
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
  private def createDatasetModel(meta: MetadataUserInput, stats: DatasetStats, catalogUri: String, subjectUri: String, runMode: String): Model = {
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
    meta.dataset.applicableLegislation.foreach(al => dataset.addProperty(DCATAP.applicableLegislation, safeRes(m, al).addProperty(RDF.`type`, m.createResource(LEGAL_RESOURCE))))
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
      dataset.addProperty(HealthDCATAP.healthCategory, hcConcept)
    }

    meta.dataset.healthTheme.foreach { ht =>
      val htConcept = safeRes(m, ht).addProperty(RDF.`type`, SKOS.Concept)
      htConcept.addProperty(SKOS.prefLabel, m.createLiteral(ht.split("/").last.replace("_", " "), "en"))
      dataset.addProperty(HealthDCATAP.healthTheme, htConcept)
    }

    meta.dataset.populationCoverage.foreach(dataset.addLiteral(HealthDCATAP.populationCoverage, _))

    val numRec = if (!isExcel && stats.recordCount > 0) stats.recordCount else meta.dataset.numRecords.map(_.toLong).getOrElse(0L)
    val numPat = if (!isExcel && stats.uniquePatients > 0) stats.uniquePatients else meta.dataset.numUniqueIndividual.map(_.toLong).getOrElse(0L)
    val minA = if (!isExcel && stats.minAge > 0) stats.minAge else meta.dataset.minAge.getOrElse(0)
    val maxA = if (!isExcel && stats.maxAge > 0) stats.maxAge else meta.dataset.maxAge.getOrElse(0)

    dataset.addLiteral(HealthDCATAP.numberOfRecords, m.createTypedLiteral(numRec, XSDDatatype.XSDnonNegativeInteger))
    dataset.addLiteral(HealthDCATAP.numberOfUniqueIndividuals, m.createTypedLiteral(numPat, XSDDatatype.XSDnonNegativeInteger))
    dataset.addLiteral(HealthDCATAP.minTypicalAge, m.createTypedLiteral(minA, XSDDatatype.XSDnonNegativeInteger))
    dataset.addLiteral(HealthDCATAP.maxTypicalAge, m.createTypedLiteral(maxA, XSDDatatype.XSDnonNegativeInteger))

    meta.dataset.publisher.foreach { p =>
      val pub = m.createResource().addProperty(RDF.`type`, FOAF.Agent).addProperty(FOAF.name, p.name)
      p.trusted.foreach(t => pub.addLiteral(HealthDCATAP.trustedDataHolder, t))
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
    meta.dataset.hdab.trusted.foreach(t => hdab.addLiteral(HealthDCATAP.trustedDataHolder, t))
    meta.dataset.hdab.`type`.foreach(t => hdab.addProperty(DCTerms.`type`, safeRes(m, t)))
    val hdabContact = m.createResource().addProperty(RDF.`type`, VCARD4.Kind)
    meta.dataset.hdab.contactPoint.email.foreach(e => hdabContact.addProperty(VCARD4.hasEmail, safeRes(m, s"mailto:$e")))
    meta.dataset.hdab.contactPoint.page.foreach(p => hdabContact.addProperty(VCARD4.hasURL, safeRes(m, p)))
    hdab.addProperty(DCAT.contactPoint, hdabContact)
    dataset.addProperty(HealthDCATAP.hdab, hdab)

    // --- OPTIONALS ---
    meta.dataset.conformsTo.foreach(c => dataset.addProperty(DCTerms.conformsTo, safeRes(m, c).addProperty(RDF.`type`, DCTerms.Standard)))
    meta.dataset.documentation.foreach(d => dataset.addProperty(FOAF.page, safeRes(m, d).addProperty(RDF.`type`, FOAF.Document)))
    meta.dataset.alternative.foreach(_.foreach(a => dataset.addProperty(DCTerms.alternative, a)))

    val finalCodingSystems = if (!isExcel && stats.codingSystems.nonEmpty) stats.codingSystems else meta.dataset.codingSystems.getOrElse(Seq.empty)
    finalCodingSystems.foreach(cs => dataset.addProperty(HealthDCATAP.hasCodingSystem, safeRes(m, cs)))

    meta.dataset.codeValues.foreach { codes =>
      codes.foreach { cv =>
        val concept = m.createResource().addProperty(RDF.`type`, SKOS.Concept).addProperty(SKOS.notation, cv.notation).addProperty(SKOS.prefLabel, m.createLiteral(cv.label, "en"))
        cv.scheme.foreach(s => concept.addProperty(SKOS.inScheme, safeRes(m, s)))
        dataset.addProperty(HealthDCATAP.hasCodeValues, concept)
      }
    }

    meta.dataset.retentionPeriod.foreach { period =>
      val temp = m.createResource().addProperty(RDF.`type`, DCTerms.PeriodOfTime)
      period.start.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(s => temp.addProperty(DCAT.startDate, m.createTypedLiteral(s, XSDDatatype.XSDdate)))
      period.end.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(e => temp.addProperty(DCAT.endDate, m.createTypedLiteral(e, XSDDatatype.XSDdate)))
      dataset.addProperty(HealthDCATAP.retentionPeriod, temp)
    }

    meta.dataset.personalData.foreach(_.foreach(pd => dataset.addProperty(DPV.hasPersonalData, safeRes(m, pd))))
    meta.dataset.landingPage.foreach(lp => dataset.addProperty(DCAT.landingPage, safeRes(m, lp).addProperty(RDF.`type`, FOAF.Document)))
    meta.dataset.language.foreach(l => dataset.addProperty(DCTerms.language, safeRes(m, l).addProperty(RDF.`type`, DCTerms.LinguisticSystem)))
    meta.dataset.modificationDate.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(md => dataset.addProperty(DCTerms.modified, m.createTypedLiteral(md, XSDDatatype.XSDdate)))
    meta.dataset.releaseDate.filter(_.matches("\\d{4}-\\d{2}-\\d{2}")).foreach(rd => dataset.addProperty(DCTerms.issued, m.createTypedLiteral(rd, XSDDatatype.XSDdate)))

    meta.dataset.otherIdentifier.foreach(_.foreach { oi =>
      val idNode = m.createResource()
        .addProperty(RDF.`type`, ADMS.Identifier)
        .addProperty(SKOS.notation, m.createTypedLiteral(oi, XSDDatatype.XSDstring))
      dataset.addProperty(ADMS.identifier, idNode)
    })

    meta.dataset.qualifiedAttribution.foreach(_.foreach { qa =>
      val attr = m.createResource().addProperty(RDF.`type`, PROV.Attribution)
      val agent = m.createResource().addProperty(RDF.`type`, FOAF.Agent).addProperty(FOAF.name, qa.name)
      attr.addProperty(PROV.agent, agent)
      qa.role.foreach(r => attr.addProperty(DCAT.hadRole, safeRes(m, r)))
      dataset.addProperty(PROV.qualifiedAttribution, attr)
    })

    meta.dataset.spatialResolution.foreach(sr => dataset.addProperty(DCAT.spatialResolutionInMeters, m.createTypedLiteral(sr, XSDDatatype.XSDdecimal)))
    meta.dataset.temporalResolution.filter(_.matches("^P.*")).foreach(tr => dataset.addProperty(DCAT.temporalResolution, m.createTypedLiteral(tr, XSDDatatype.XSDduration)))
    meta.dataset.versionNotes.foreach(vn => dataset.addProperty(ADMS.versionNotes, vn))
    meta.dataset.wasGeneratedBy.foreach(_.foreach(wg => dataset.addProperty(PROV.wasGeneratedBy, safeRes(m, wg).addProperty(RDF.`type`, PROV.Activity))))
    meta.dataset.purpose.foreach(p => dataset.addProperty(DPV.hasPurpose, p))

    meta.dataset.creator.foreach { c =>
      val creator = m.createResource().addProperty(RDF.`type`, FOAF.Agent).addProperty(FOAF.name, c.name)
      c.`type`.foreach(t => creator.addProperty(DCTerms.`type`, safeRes(m, t)))
      c.email.foreach(e => creator.addProperty(VCARD4.hasEmail, safeRes(m, s"mailto:$e")))
      dataset.addProperty(DCTerms.creator, creator)
    }

    meta.dataset.sample.foreach { s =>
      val sample = m.createResource().addProperty(RDF.`type`, DCAT.Distribution)
      populateDistribution(m, sample, s)
      dataset.addProperty(ADMS.sample, sample)
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
   * @param stats      DatasetStats containing the column definitions extracted from the dataframe.
   * @param subjectUri The URI assigned to the CSVW Resource by the FDP server.
   * @param parentUri  The URI of the parent Distribution (for dct:isPartOf linking).
   * @return A populated Jena Model representing the CSVW schema.
   */
  private def createCsvwModel(stats: DatasetStats, subjectUri: String, parentUri: String): Model = {
    val m = createModel()

    val tableGroup = createSubject(m, subjectUri)
      .addProperty(RDF.`type`, CSVW.TableGroup)
      .addProperty(DCTerms.title, m.createLiteral("Cohort Data Schema", "en"))
      .addProperty(DCTerms.description, m.createLiteral("CSVW schema describing the flattened cohort dataset.", "en"))

    if (parentUri.nonEmpty) {
      tableGroup.addProperty(DCTerms.isPartOf, m.createResource(parentUri))
    }

    val table = m.createResource("urn:uuid:" + UUID.randomUUID())
      .addProperty(RDF.`type`, CSVW.Table)
      .addProperty(DCTerms.title, m.createLiteral("Tabular Data", "en"))
      .addProperty(m.createProperty(CSVW.NS + "url"), m.createResource("file:///dataset.csv"))

    tableGroup.addProperty(CSVW.table, table)

    stats.columns.foreach { case (colName, colType) =>
      val col = m.createResource("urn:uuid:" + UUID.randomUUID())
        .addProperty(RDF.`type`, CSVW.Column)

      val sanitizedName = colName.toLowerCase.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "")

      col.addProperty(CSVW.name, m.createTypedLiteral(sanitizedName, XSDDatatype.XSDstring))
      col.addProperty(CSVW.titles, m.createLiteral(colName, "en"))
      col.addProperty(DCTerms.description, m.createLiteral(s"Data values for $colName", "en"))

      if (stats.columnToVocabId.contains(colName)) {
        val shortId = stats.columnToVocabId(colName)
        if (stats.vocabularies.contains(shortId)) {
          val propertyUrlPredicate = m.createProperty("http://www.w3.org/ns/csvw#propertyUrl")
          col.addProperty(propertyUrlPredicate, m.createResource(s"$VOCAB_BASE/$shortId"))
        }
      }

      val dt = colType.toLowerCase match {
        case t if t.contains("int") || t.contains("long") => "integer"
        case t if t.contains("double") || t.contains("float") => "double"
        case t if t.contains("binary") => "binary"
        case _ => "string"
      }
      col.addProperty(CSVW.datatype, m.createTypedLiteral(dt, XSDDatatype.XSDstring))

      table.addProperty(CSVW.column, col)
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
  private def createConceptSchemes(stats: DatasetStats): Model = {
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

  /**
   * Creates a standalone Dictionary RDF Model from parsed Data Dictionary fields.
   * Generates a CSVW TableGroup describing each variable with its metadata.
   * (name, title, datatype, description, propertyUrl, unit)
   *
   * Used by the "dictionary" job to produce a Dictionary.ttl without requiring any FHIR extraction or Dataset/Distribution context.
   * @param fields The list of CsvwField entries parsed from the Data Dictionary Excel sheet.
   * @param vocabularies Map of variable name to its code -> display pairs from Value Sets sheet.
   * @return A populated Jena Model representing the dictionary as a CSVW schema with SKOS vocabularies.
   */
  def createDictionaryModel(fields: List[CsvwField], vocabularies: Map[String, Map[String, String]] = Map.empty, subjectUri: String = "", parentDistributionUri: String = ""): Model = {
    val m = createModel()
    val qudtUnit = m.createProperty("http://qudt.org/schema/qudt/unit")

    val COHORT_NS = "http://example.org/cohort#"
    m.setNsPrefix("cohort", COHORT_NS)
    val cohortSampleSize = m.createProperty(COHORT_NS + "sampleSize")
    val cohortIsIdentifier = m.createProperty(COHORT_NS + "isIdentifier")
    val cohortSelectionStatus = m.createProperty(COHORT_NS + "selectionStatus")
    val cohortConditionalOn = m.createProperty(COHORT_NS + "conditionalOn")
    val cohortFromStudy = m.createProperty(COHORT_NS + "fromStudy")

    val responsibleRole = m.createResource("http://example.org/cohort/role/responsible")

    // URI-safe slug for SKOS concept fragments (Study, Group)
    def slug(s: String): String = s.trim.replaceAll("\\s+", "_").replaceAll("[<>\"{}|\\\\^`#]", "")

    val tableGroup = createSubject(m, subjectUri)
      .addProperty(RDF.`type`, CSVW.TableGroup)
      .addProperty(DCTerms.title, m.createLiteral("Data Dictionary Schema", "en"))
      .addProperty(DCTerms.description, m.createLiteral("CSVW schema describing the variables defined in the data dictionary.", "en"))

    if (parentDistributionUri.nonEmpty) {
      tableGroup.addProperty(DCTerms.isPartOf, m.createResource(parentDistributionUri))
    }

    val table = m.createResource(s"urn:uuid:${UUID.randomUUID()}")
      .addProperty(RDF.`type`, CSVW.Table)
      .addProperty(DCTerms.title, m.createLiteral("Variable Definitions", "en"))

    tableGroup.addProperty(CSVW.table, table)

    val studyScheme = m.createResource(s"$VOCAB_BASE/study")
      .addProperty(RDF.`type`, SKOS.ConceptScheme)
      .addProperty(DCTerms.title, m.createLiteral("Studies / Cohorts", "en"))

    val sectionScheme = m.createResource(s"$VOCAB_BASE/section")
      .addProperty(RDF.`type`, SKOS.ConceptScheme)
      .addProperty(DCTerms.title, m.createLiteral("Variable groups / sections", "en"))

    val parentGroupScheme = m.createResource(s"$VOCAB_BASE/parent-group")
      .addProperty(RDF.`type`, SKOS.ConceptScheme)
      .addProperty(DCTerms.title, m.createLiteral("Parent groups (coarser groupings above Section)", "en"))

    val selectionScheme = m.createResource(s"$VOCAB_BASE/selection")
      .addProperty(RDF.`type`, SKOS.ConceptScheme)
      .addProperty(DCTerms.title, m.createLiteral("Variable selection / inclusion status", "en"))

    fields.foreach { f =>
      val col = m.createResource(s"urn:uuid:${UUID.randomUUID()}")
        .addProperty(RDF.`type`, CSVW.Column)

      col.addProperty(CSVW.name, m.createTypedLiteral(f.name, XSDDatatype.XSDstring))
      col.addProperty(CSVW.titles, m.createLiteral(f.title, "en"))
      col.addProperty(CSVW.datatype, m.createTypedLiteral(f.datatype, XSDDatatype.XSDstring))
      f.description.foreach(d => col.addProperty(DCTerms.description, m.createLiteral(d, "en")))
      f.propertyUrl.filter(_.nonEmpty).foreach(p => col.addProperty(CSVW.propertyURL, m.createResource(p)))
      f.unit.filter(_.nonEmpty).foreach(u => col.addProperty(qudtUnit, u))

      f.study.filter(_.nonEmpty).foreach { s =>
        val concept = m.createResource(s"$VOCAB_BASE/study/${slug(s)}")
          .addProperty(RDF.`type`, SKOS.Concept)
          .addProperty(SKOS.inScheme, studyScheme)
          .addProperty(SKOS.prefLabel, m.createLiteral(s, "en"))
        studyScheme.addProperty(SKOS.hasTopConcept, concept)
        col.addProperty(cohortFromStudy, concept)
      }

      f.group.filter(_.nonEmpty).foreach { g =>
        val concept = m.createResource(s"$VOCAB_BASE/section/${slug(g)}")
          .addProperty(RDF.`type`, SKOS.Concept)
          .addProperty(SKOS.inScheme, sectionScheme)
          .addProperty(SKOS.prefLabel, m.createLiteral(g, "en"))
        sectionScheme.addProperty(SKOS.hasTopConcept, concept)
        col.addProperty(SKOS.broader, concept)
      }

      f.subpopulation.filter(_.nonEmpty).foreach(sp =>
        col.addLiteral(HealthDCATAP.populationCoverage, sp)
      )

      f.sampleSize.filter(_.nonEmpty).foreach { n =>
        n.trim.toLongOption match {
          case Some(v) => col.addLiteral(cohortSampleSize, m.createTypedLiteral(v, XSDDatatype.XSDnonNegativeInteger))
          case None    => col.addProperty(cohortSampleSize, m.createLiteral(n, "en"))
        }
      }

      f.dataOwner.filter(_.nonEmpty).foreach { owner =>
        val agent = m.createResource()
          .addProperty(RDF.`type`, FOAF.Agent)
          .addProperty(FOAF.name, owner)
        col.addProperty(DCTerms.rightsHolder, agent)
      }

      f.identifier.filter(_.nonEmpty).foreach { flag =>
        val truthy = Set("yes", "y", "true", "1")
        val v = truthy.contains(flag.trim.toLowerCase)
        col.addLiteral(cohortIsIdentifier, m.createTypedLiteral(v, XSDDatatype.XSDboolean))
      }

      // Selection -> SKOS Concept
      f.selection.filter(_.nonEmpty).foreach { s =>
        val concept = m.createResource(s"$VOCAB_BASE/selection/${slug(s)}")
          .addProperty(RDF.`type`, SKOS.Concept)
          .addProperty(SKOS.inScheme, selectionScheme)
          .addProperty(SKOS.prefLabel, m.createLiteral(s, "en"))
        selectionScheme.addProperty(SKOS.hasTopConcept, concept)
        col.addProperty(cohortSelectionStatus, concept)
      }

      // Parent Group -> skos:broader
      f.parentGroup.filter(_.nonEmpty).foreach { pg =>
        val concept = m.createResource(s"$VOCAB_BASE/parent-group/${slug(pg)}")
          .addProperty(RDF.`type`, SKOS.Concept)
          .addProperty(SKOS.inScheme, parentGroupScheme)
          .addProperty(SKOS.prefLabel, m.createLiteral(pg, "en"))
        parentGroupScheme.addProperty(SKOS.hasTopConcept, concept)
        col.addProperty(SKOS.broader, concept)
      }

      // Responsible -> prov:qualifiedAttribution
      f.responsible.filter(_.nonEmpty).foreach { person =>
        val attribution = m.createResource()
          .addProperty(RDF.`type`, PROV.Attribution)
          .addProperty(DCAT.hadRole, responsibleRole)
          .addProperty(PROV.agent,
            m.createResource()
              .addProperty(RDF.`type`, FOAF.Agent)
              .addProperty(FOAF.name, person)
          )
        col.addProperty(PROV.qualifiedAttribution, attribution)
      }

      // Note -> skos:scopeNote
      f.note.filter(_.nonEmpty).foreach(n =>
        col.addProperty(SKOS.scopeNote, m.createLiteral(n, "en"))
      )

      // Min / Max -> csvw:minimum, csvw:maximum
      def emitBoundary(prop: org.apache.jena.rdf.model.Property, raw: String): Unit = {
        val t = raw.trim
        t.toLongOption match {
          case Some(v) => col.addLiteral(prop, m.createTypedLiteral(v, XSDDatatype.XSDinteger))
          case None => t.toDoubleOption match {
            case Some(v) => col.addLiteral(prop, m.createTypedLiteral(v, XSDDatatype.XSDdouble))
            case None    => col.addProperty(prop, m.createLiteral(t, "en"))
          }
        }
      }
      f.minValue.filter(_.nonEmpty).foreach(emitBoundary(CSVW.minimum, _))
      f.maxValue.filter(_.nonEmpty).foreach(emitBoundary(CSVW.maximum, _))

      // Required -> csvw:required xsd:boolean
      f.required.filter(_.nonEmpty).foreach { flag =>
        val truthy = Set("yes", "y", "true", "1")
        val v = truthy.contains(flag.trim.toLowerCase)
        col.addLiteral(CSVW.required, m.createTypedLiteral(v, XSDDatatype.XSDboolean))
      }

      // Conditional On -> cohort:conditionalOn literal
      f.conditionalOn.filter(_.nonEmpty).foreach(expr =>
        col.addProperty(cohortConditionalOn, m.createLiteral(expr, "en"))
      )

      table.addProperty(CSVW.column, col)
    }

    // Embed SKOS ConceptSchemes for variables with value sets (same pattern as createConceptSchemes)
    vocabularies.foreach { case (varName, options) =>
      val schemeUri = s"$VOCAB_BASE/$varName"
      val scheme = m.createResource(schemeUri)
        .addProperty(RDF.`type`, SKOS.ConceptScheme)
        .addProperty(DCTerms.title, s"Vocabulary for $varName")

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