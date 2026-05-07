#!/usr/bin/env node
/**
 * Usage: node generate-dict-nfbc.js --input NFBC196660vKerys_DataDictionary_2026-02-12.csv [--output nfbc_data_dictionary.xlsx]
 * Requirement: Node.js
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function parseArgs() {
  const args = process.argv.slice(2);
  const opts = { input: null, output: 'nfbc_data_dictionary.xlsx' };
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--input' && args[i + 1]) opts.input = args[++i];
    else if (args[i] === '--output' && args[i + 1]) opts.output = args[++i];
    else if (args[i] === '--help' || args[i] === '-h') {
      console.log('Usage: node generate-dict-nfbc.js --input <csv> [--output <xlsx>]');
      process.exit(0);
    }
  }
  if (!opts.input) {
    console.error('Error: --input is required.\nUsage: node generate-dict-nfbc.js --input <csv> [--output <xlsx>]');
    process.exit(1);
  }
  return opts;
}

function parseCSV(text) {
  const rows = [];
  let current = [];
  let field = '';
  let inQuotes = false;
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (i + 1 < text.length && text[i + 1] === '"') { field += '"'; i += 2; }
        else { inQuotes = false; i++; }
      } else { field += ch; i++; }
    } else {
      if (ch === '"') { inQuotes = true; i++; }
      else if (ch === ',') { current.push(field); field = ''; i++; }
      else if (ch === '\n' || ch === '\r') {
        current.push(field); field = '';
        if (ch === '\r' && i + 1 < text.length && text[i + 1] === '\n') i++;
        rows.push(current); current = []; i++;
      } else { field += ch; i++; }
    }
  }
  if (field || current.length > 0) { current.push(field); rows.push(current); }
  return rows;
}

function csvToObjects(text) {
  const rows = parseCSV(text);
  if (rows.length < 2) return [];
  let headers = rows[0];
  if (headers[0]) headers[0] = headers[0].replace(/^﻿/, '');
  return rows.slice(1)
    .filter(r => r.length >= headers.length / 2)
    .map(r => {
      const obj = {};
      headers.forEach((h, idx) => { obj[h.trim()] = (r[idx] || '').trim(); });
      return obj;
    });
}

function mapRedcapType(fieldType, validation) {
  const v = (validation || '').toLowerCase();
  if (fieldType === 'radio' || fieldType === 'checkbox' || fieldType === 'dropdown') return 'string';
  if (fieldType === 'yesno') return 'string';
  if (fieldType === 'text') {
    if (v.includes('integer')) return 'integer';
    if (v.includes('number')) return 'double';
    if (v.includes('date')) return 'date';
    return 'string';
  }
  if (fieldType === 'notes') return 'string';
  if (fieldType === 'calc') return 'double';
  if (fieldType === 'slider') return 'integer';
  return 'string';
}

function stripHtml(str) {
  return (str || '').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').trim();
}

function parseChoices(choicesStr) {
  if (!choicesStr || !choicesStr.trim()) return [];
  const parts = choicesStr.split('|');
  const codes = [];
  for (const part of parts) {
    const trimmed = part.trim();
    const commaIdx = trimmed.indexOf(',');
    if (commaIdx > 0) {
      const code = trimmed.substring(0, commaIdx).trim();
      const display = trimmed.substring(commaIdx + 1).trim();
      if (code && display) {
        codes.push({ code, display });
      }
    }
  }
  return codes.length >= 2 ? codes : [];
}

function escapeXml(str) {
  return String(str || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function buildXlsx(sheetsData) {
  const sharedStrings = [];
  const ssIndex = {};
  function getSSIndex(str) {
    const s = String(str || '');
    if (ssIndex[s] !== undefined) return ssIndex[s];
    const idx = sharedStrings.length;
    sharedStrings.push(s);
    ssIndex[s] = idx;
    return idx;
  }

  for (const sheet of sheetsData) {
    for (const row of sheet.rows) {
      for (const cell of row) {
        if (cell !== null && cell !== undefined) getSSIndex(cell);
      }
    }
  }

  const colLetter = (idx) => {
    let s = '';
    idx++;
    while (idx > 0) { idx--; s = String.fromCharCode(65 + (idx % 26)) + s; idx = Math.floor(idx / 26); }
    return s;
  };

  function buildSheetXml(rows) {
    let xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
    xml += '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>';
    for (let r = 0; r < rows.length; r++) {
      xml += `<row r="${r + 1}">`;
      for (let c = 0; c < rows[r].length; c++) {
        const val = rows[r][c];
        if (val !== null && val !== undefined) {
          xml += `<c r="${colLetter(c)}${r + 1}" t="s"><v>${getSSIndex(val)}</v></c>`;
        }
      }
      xml += '</row>';
    }
    xml += '</sheetData></worksheet>';
    return xml;
  }

  const sheetXmls = sheetsData.map(s => buildSheetXml(s.rows));

  let ssXml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
  ssXml += `<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sharedStrings.length}" uniqueCount="${sharedStrings.length}">`;
  for (const s of sharedStrings) ssXml += `<si><t>${escapeXml(s)}</t></si>`;
  ssXml += '</sst>';

  let sheetsXml = '';
  for (let i = 0; i < sheetsData.length; i++) {
    sheetsXml += `<sheet name="${escapeXml(sheetsData[i].name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`;
  }
  const wbXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>${sheetsXml}</sheets></workbook>`;

  let wbRelsBody = '';
  for (let i = 0; i < sheetsData.length; i++) {
    wbRelsBody += `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`;
  }
  wbRelsBody += `<Relationship Id="rId${sheetsData.length + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>`;
  wbRelsBody += `<Relationship Id="rId${sheetsData.length + 2}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>`;
  const wbRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${wbRelsBody}</Relationships>`;

  let overrides = '';
  overrides += '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>';
  for (let i = 0; i < sheetsData.length; i++) {
    overrides += `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`;
  }
  overrides += '<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>';
  overrides += '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>';
  const contentTypes = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>${overrides}</Types>`;

  const rootRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`;

  const stylesXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>`;

  const files = {
    '[Content_Types].xml': Buffer.from(contentTypes, 'utf-8'),
    '_rels/.rels': Buffer.from(rootRels, 'utf-8'),
    'xl/workbook.xml': Buffer.from(wbXml, 'utf-8'),
    'xl/_rels/workbook.xml.rels': Buffer.from(wbRels, 'utf-8'),
    'xl/sharedStrings.xml': Buffer.from(ssXml, 'utf-8'),
    'xl/styles.xml': Buffer.from(stylesXml, 'utf-8'),
  };
  for (let i = 0; i < sheetXmls.length; i++) {
    files[`xl/worksheets/sheet${i + 1}.xml`] = Buffer.from(sheetXmls[i], 'utf-8');
  }

  return buildZip(files);
}

function buildZip(files) {
  const entries = Object.entries(files);
  const localHeaders = [];
  const centralHeaders = [];
  let offset = 0;
  for (const [name, data] of entries) {
    const nameBytes = Buffer.from(name, 'utf-8');
    const compressed = zlib.deflateRawSync(data);
    const useCompressed = compressed.length < data.length;
    const storedData = useCompressed ? compressed : data;
    const crc = crc32(data);
    const lh = Buffer.alloc(30 + nameBytes.length);
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0, 6);
    lh.writeUInt16LE(useCompressed ? 8 : 0, 8); lh.writeUInt16LE(0, 10); lh.writeUInt16LE(0, 12);
    lh.writeUInt32LE(crc, 14); lh.writeUInt32LE(storedData.length, 18);
    lh.writeUInt32LE(data.length, 22); lh.writeUInt16LE(nameBytes.length, 26); lh.writeUInt16LE(0, 28);
    nameBytes.copy(lh, 30);
    localHeaders.push({ header: lh, data: storedData, offset });
    const ch = Buffer.alloc(46 + nameBytes.length);
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6); ch.writeUInt16LE(0, 8);
    ch.writeUInt16LE(useCompressed ? 8 : 0, 10); ch.writeUInt16LE(0, 12); ch.writeUInt16LE(0, 14);
    ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(storedData.length, 20); ch.writeUInt32LE(data.length, 24);
    ch.writeUInt16LE(nameBytes.length, 28); ch.writeUInt16LE(0, 30); ch.writeUInt16LE(0, 32);
    ch.writeUInt16LE(0, 34); ch.writeUInt16LE(0, 36); ch.writeUInt32LE(0, 38); ch.writeUInt32LE(offset, 42);
    nameBytes.copy(ch, 46);
    centralHeaders.push(ch);
    offset += lh.length + storedData.length;
  }
  const cdOffset = offset;
  let cdSize = 0;
  for (const ch of centralHeaders) cdSize += ch.length;
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(0, 4); eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cdSize, 12); eocd.writeUInt32LE(cdOffset, 16); eocd.writeUInt16LE(0, 20);
  const parts = [];
  for (const lh of localHeaders) { parts.push(lh.header); parts.push(lh.data); }
  for (const ch of centralHeaders) parts.push(ch);
  parts.push(eocd);
  return Buffer.concat(parts);
}

function crc32(buf) {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) { crc ^= buf[i]; for (let j = 0; j < 8; j++) crc = (crc >>> 1) ^ (crc & 1 ? 0xEDB88320 : 0); }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function main() {
  const opts = parseArgs();

  console.log('='.repeat(60));
  console.log('NFBC1966 → Data Dictionary + Value Sets Generator');
  console.log('='.repeat(60));

  console.log(`\nReading: ${opts.input}`);
  const raw = fs.readFileSync(path.resolve(opts.input), 'utf-8');
  const records = csvToObjects(raw);

  console.log(`  Total records in CSV: ${records.length}`);

  const SKIP_FORMS = new Set(['etusivu', 'suostumus']);
  const SKIP_TYPES = new Set(['descriptive']);
  const PROPERTY_URL_BASE = 'http://example.org/vocab';

  const dictRows = [['Variable', 'Name', 'Description', 'Datatype', 'Property URL (ontology)', 'Unit']];
  const valueSetRows = [['Variable', 'Code', 'Display']];

  let skipped = 0;
  let included = 0;
  let codedCount = 0;

  for (const row of records) {
    const formName = row['Form Name'] || '';
    const fieldType = row['Field Type'] || '';
    const fieldName = row['Variable / Field Name'] || '';
    const fieldLabel = row['Field Label'] || '';
    const choicesRaw = row['Choices, Calculations, OR Slider Labels'] || '';
    const validation = row['Text Validation Type OR Show Slider Number'] || '';

    // Skip front page, consent, display-only, empty
    if (SKIP_FORMS.has(formName)) { skipped++; continue; }
    if (SKIP_TYPES.has(fieldType)) { skipped++; continue; }
    if (!fieldName) { skipped++; continue; }
    if (fieldName === 'record_id' || fieldName === 'kukuid') { skipped++; continue; }

    const cleanLabel = stripHtml(fieldLabel).substring(0, 300);
    const datatype = mapRedcapType(fieldType, validation);
    const name = cleanLabel || fieldName;

    const codes = parseChoices(choicesRaw);
    let propertyUrl = '';

    if (codes.length > 0) {
      propertyUrl = `${PROPERTY_URL_BASE}/${fieldName}`;
      codedCount++;

      for (const c of codes) {
        valueSetRows.push([fieldName, c.code, c.display]);
      }
    }
    dictRows.push([fieldName, name, name, datatype, propertyUrl, '']);
    included++;
  }

  console.log(`\n  Included: ${included}`);
  console.log(`  Skipped (descriptive/consent/IDs): ${skipped}`);
  console.log(`  Variables with structured choices: ${codedCount}`);
  console.log(`  Total value set entries: ${valueSetRows.length - 1}`);

  console.log(`\nWriting: ${opts.output}`);
  const xlsxBuf = buildXlsx([
    { name: 'Data Dictionary', rows: dictRows },
    { name: 'Value Sets', rows: valueSetRows }
  ]);
  fs.writeFileSync(path.resolve(opts.output), xlsxBuf);

  const sizeKB = (fs.statSync(path.resolve(opts.output)).size / 1024).toFixed(1);
  console.log(`  File size: ${sizeKB} KB`);
  console.log(`  Data Dictionary rows: ${dictRows.length - 1} (+ header)`);
  console.log(`  Value Sets rows: ${valueSetRows.length - 1} (+ header)`);
  console.log('\n' + '='.repeat(60));
  console.log('Done.');
}

main();
