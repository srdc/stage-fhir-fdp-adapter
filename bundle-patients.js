const fs = require('fs');
const path = require('path');

function findPatientDirs(root) {
  const dirs = [];
  const entries = fs.readdirSync(root, { withFileTypes: true });
  for (const e of entries) {
    if (e.isDirectory()) {
      const full = path.join(root, e.name);
      // Skip the Questionnaire folder here to avoid double processing if it's in the root
      if (e.name === 'Questionnaire') continue;

      const patientFile = path.join(full, 'patient.json');
      if (fs.existsSync(patientFile) && fs.statSync(patientFile).isFile()) {
        dirs.push(full);
      } else {
        // also consider directories where any JSON inside has resourceType Patient
        try {
          const subs = fs.readdirSync(full);
          for (const sf of subs) {
            if (path.extname(sf).toLowerCase() !== '.json') continue;
            try {
              const content = fs.readFileSync(path.join(full, sf), 'utf8');
              const j = JSON.parse(content);
              if (j && j.resourceType === 'Patient') {
                dirs.push(full);
                break;
              }
            } catch (e) { /* ignore parse errors */ }
          }
        } catch (e) {
          // ignore
        }
      }
    }
  }
  return dirs;
}

function collectResourcesInDir(dir) {
  const resources = [];
  if (!fs.existsSync(dir)) return resources;

  const files = fs.readdirSync(dir);
  for (const f of files) {
    if (path.extname(f).toLowerCase() !== '.json') continue;
    const full = path.join(dir, f);
    try {
      const raw = fs.readFileSync(full, 'utf8');
      const j = JSON.parse(raw);
      if (j && j.resourceType) {
        resources.push(j);
      }
    } catch (err) {
      // ignore parse errors
    }
  }
  return resources;
}

function makeEntryForResource(res) {
  // prefer PUT when id exists to allow upsert semantics in a transaction
  const entry = { resource: res };
  if (res.id) {
    entry.request = { method: 'PUT', url: `${res.resourceType}/${res.id}` };
  } else {
    entry.request = { method: 'POST', url: `${res.resourceType}` };
  }
  return entry;
}

function buildTransactionBundle(root) {
  const bundle = {
    resourceType: 'Bundle',
    type: 'transaction',
    timestamp: new Date().toISOString(),
    entry: []
  };

  const seen = new Set(); // resourceType/id dedupe

  // --- NEW LOGIC START: Process Questionnaire Folder ---
  const questionnaireDir = path.join(root, 'Questionnaire');

  if (fs.existsSync(questionnaireDir) && fs.statSync(questionnaireDir).isDirectory()) {
    console.log(`Found Questionnaire directory at: ${questionnaireDir}`);
    const qResources = collectResourcesInDir(questionnaireDir);

    for (const r of qResources) {
      // Create unique key for deduplication
      const key = r.resourceType + '|' + (r.id || JSON.stringify(r));
      if (seen.has(key)) continue;
      seen.add(key);

      // Add to bundle
      bundle.entry.push(makeEntryForResource(r));
    }
  } else {
    console.log('No "Questionnaire" directory found in root. Skipping questionnaires.');
  }
  // --- NEW LOGIC END ---

  const patientDirs = findPatientDirs(root);

  // Only include resources that live inside patient directories.
  for (const dir of patientDirs) {
    const resources = collectResourcesInDir(dir);
    for (const r of resources) {
      const key = r.resourceType + '|' + (r.id || JSON.stringify(r));
      if (seen.has(key)) continue;
      seen.add(key);
      bundle.entry.push(makeEntryForResource(r));
    }
  }

  return bundle;
}

function writeBundle(root, bundle, filename = 'transaction-bundle.json') {
  const out = path.join(root, filename);
  fs.writeFileSync(out, JSON.stringify(bundle, null, 2), 'utf8');
  return out;
}

if (require.main === module) {
  const root = __dirname;
  const bundle = buildTransactionBundle(root);
  const out = writeBundle(root, bundle);
  console.log(`Wrote bundle with ${bundle.entry.length} entries to ${out}`);
}

module.exports = { buildTransactionBundle, writeBundle, findPatientDirs };