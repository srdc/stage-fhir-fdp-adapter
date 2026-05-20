#!/usr/bin/env node
/**
 * Usage: node generate-dict-kora.js --input VarDef_AGE1_20250121_V2.xlsx [--output kora_data_dictionary.xlsx]
 * Requirement: Node.js
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function parseArgs() {
  const args = process.argv.slice(2);
  const opts = { input: null, output: 'kora_data_dictionary.xlsx', lang: 'en' };
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--input' && args[i + 1]) opts.input = args[++i];
    else if (args[i] === '--output' && args[i + 1]) opts.output = args[++i];
    else if ((args[i] === '--lang' || args[i] === '--language') && args[i + 1]) {
      const v = String(args[++i]).toLowerCase();
      opts.lang = (v === 'de' || v === 'ger' || v === 'deu' || v === 'german') ? 'de' : 'en';
    }
    else if (args[i] === '--help' || args[i] === '-h') {
      console.log('Usage: node generate-dict-kora.js --input <xlsx> [--output <xlsx>] [--lang en|de]');
      process.exit(0);
    }
  }
  if (!opts.input) {
    console.error('Error: --input is required.\nUsage: node generate-dict-kora.js --input <xlsx> [--output <xlsx>] [--lang en|de]');
    process.exit(1);
  }
  return opts;
}

function readZipEntries(buf) {
  const entries = {};
  let eocdOffset = -1;
  for (let i = buf.length - 22; i >= 0; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocdOffset = i; break; }
  }
  if (eocdOffset < 0) throw new Error('Not a valid ZIP file');
  const cdOffset = buf.readUInt32LE(eocdOffset + 16);
  const cdCount = buf.readUInt16LE(eocdOffset + 10);
  let pos = cdOffset;
  for (let i = 0; i < cdCount; i++) {
    if (buf.readUInt32LE(pos) !== 0x02014b50) break;
    const compressionMethod = buf.readUInt16LE(pos + 10);
    const compressedSize = buf.readUInt32LE(pos + 20);
    const uncompressedSize = buf.readUInt32LE(pos + 24);
    const nameLen = buf.readUInt16LE(pos + 28);
    const extraLen = buf.readUInt16LE(pos + 30);
    const commentLen = buf.readUInt16LE(pos + 32);
    const localHeaderOffset = buf.readUInt32LE(pos + 42);
    const name = buf.toString('utf-8', pos + 46, pos + 46 + nameLen);
    const lhPos = localHeaderOffset;
    const lhExtraLen = buf.readUInt16LE(lhPos + 28);
    const lhNameLen = buf.readUInt16LE(lhPos + 26);
    const dataStart = lhPos + 30 + lhNameLen + lhExtraLen;
    let data;
    if (compressionMethod === 0) data = buf.slice(dataStart, dataStart + uncompressedSize);
    else if (compressionMethod === 8) data = zlib.inflateRawSync(buf.slice(dataStart, dataStart + compressedSize));
    else data = Buffer.alloc(0);
    entries[name] = data;
    pos += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

function parseSharedStrings(xml) {
  if (!xml) return [];
  const strings = [];
  const siPattern = /<si>([\s\S]*?)<\/si>/g;
  let m;
  while ((m = siPattern.exec(xml)) !== null) {
    let text = '';
    const tPattern = /<t[^>]*>([\s\S]*?)<\/t>/g;
    let tm;
    while ((tm = tPattern.exec(m[1])) !== null) text += tm[1];
    text = text.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#10;/g, '\n');
    strings.push(text);
  }
  return strings;
}

function colToIndex(col) {
  let n = 0;
  for (let i = 0; i < col.length; i++) n = n * 26 + (col.charCodeAt(i) - 64);
  return n - 1;
}

function xmlAttr(attrStr, name) {
  const m = attrStr.match(new RegExp(`${name}="([^"]*)"`));
  return m ? m[1] : null;
}

function xmlGetAll(xml, tagName) {
  const results = [];
  const pattern = new RegExp(`<${tagName}(\\s[^>]*)?\\/?>|<${tagName}(\\s[^>]*)?>([\\s\\S]*?)<\\/${tagName}>`, 'g');
  let m;
  while ((m = pattern.exec(xml)) !== null) results.push({ attrs: m[1] || m[2] || '', inner: m[3] || '' });
  return results;
}

function parseSheet(xml, sharedStrings) {
  const rows = [];
  const rowPattern = /<row\s[^>]*>([\s\S]*?)<\/row>/g;
  let rm;
  while ((rm = rowPattern.exec(xml)) !== null) {
    const cells = [];
    const cellPattern = /<c\s([^>]*)(?:\/>|>([\s\S]*?)<\/c>)/g;
    let cm;
    while ((cm = cellPattern.exec(rm[1])) !== null) {
      const cellAttrs = cm[1];
      const cellInner = cm[2] || '';
      const ref = xmlAttr(cellAttrs, 'r');
      const type = xmlAttr(cellAttrs, 't');
      const vMatch = cellInner.match(/<v>([\s\S]*?)<\/v>/);
      let value = vMatch ? vMatch[1] : null;
      if (value !== null) {
        if (type === 's') { const idx = parseInt(value, 10); value = sharedStrings[idx] !== undefined ? sharedStrings[idx] : value; }
        else if (type === 'b') value = value === '1' ? 'true' : 'false';
      }
      if (ref) {
        const colMatch = ref.match(/^([A-Z]+)/);
        if (colMatch) {
          const colIdx = colToIndex(colMatch[1]);
          while (cells.length <= colIdx) cells.push(null);
          cells[colIdx] = value;
        }
      }
    }
    rows.push(cells);
  }
  return rows;
}

function findSheetFile(workbookXml, relsXml, sheetName) {
  const sheets = xmlGetAll(workbookXml, 'sheet');
  let rId = null;
  for (const s of sheets) {
    const name = xmlAttr(s.attrs, 'name');
    if (name === sheetName) { rId = xmlAttr(s.attrs, 'r:id') || xmlAttr(s.attrs, 'id'); break; }
  }
  if (!rId) {
    for (const s of sheets) {
      const name = xmlAttr(s.attrs, 'name');
      if (name && name.includes(sheetName)) { rId = xmlAttr(s.attrs, 'r:id') || xmlAttr(s.attrs, 'id'); break; }
    }
  }
  if (!rId) return null;
  const rels = xmlGetAll(relsXml, 'Relationship');
  for (const r of rels) {
    if (xmlAttr(r.attrs, 'Id') === rId) {
      let target = xmlAttr(r.attrs, 'Target');
      if (!target.startsWith('/')) target = 'xl/' + target; else target = target.substring(1);
      return target;
    }
  }
  return null;
}

function mapKoraType(rawType) {
  if (!rawType) return 'string';
  const t = rawType.trim().toLowerCase();
  if (t.startsWith('date')) return 'date';
  if (t.startsWith('smallint') || t === 'integer') return 'integer';
  if (t.startsWith('decimal') || t.startsWith('float') || t.startsWith('decima')) return 'double';
  if (t.startsWith('varchar')) return 'string';
  return 'string';
}

function parseCodes(codeNoticeEng) {
  if (!codeNoticeEng) return [];
  const pattern = /(-?\d+)\s*=\s*(.+?)(?:\r?\n|$)/g;
  const codes = [];
  let m;
  while ((m = pattern.exec(codeNoticeEng)) !== null) {
    codes.push({ code: m[1].trim(), display: m[2].trim() });
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

  // Build sheet XMLs
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

  // [Content_Types].xml
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

  // Minimal styles.xml required by Apache POI
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
  console.log('KORA-AGE1 → Data Dictionary + Value Sets Generator');
  console.log('='.repeat(60));

  console.log(`\nLanguage: ${opts.lang === 'de' ? 'German (Group GER / Content GER / Code-Notice GER)' : 'English (Group ENG / Content ENG / Code-Notice ENG)'}`);
  console.log(`Reading: ${opts.input}`);
  const buf = fs.readFileSync(path.resolve(opts.input));
  const entries = readZipEntries(buf);

  const ssXml = entries['xl/sharedStrings.xml'] ? entries['xl/sharedStrings.xml'].toString('utf-8') : '';
  const sharedStrings = parseSharedStrings(ssXml);
  const wbXml = entries['xl/workbook.xml'].toString('utf-8');
  const relsXml = (entries['xl/_rels/workbook.xml.rels'] || Buffer.alloc(0)).toString('utf-8');

  let sheetFile = findSheetFile(wbXml, relsXml, 'KORA_W_Phenotypes');
  if (!sheetFile) sheetFile = 'xl/worksheets/sheet2.xml';
  const sheetXml = entries[sheetFile].toString('utf-8');
  const allRows = parseSheet(sheetXml, sharedStrings);

  // TODO: Change if needed
  const PROPERTY_URL_BASE = 'http://example.org/vocab';

  // Language-aware cell indices. KORA_W_Phenotypes pairs GER/ENG cells; pick the right side per --lang.
  const isGerman = opts.lang === 'de';
  const idxGroup      = isGerman ? 5 : 6;   // r[5] Group GER  vs r[6] Group ENG
  const idxContent    = isGerman ? 7 : 8;   // r[7] Content GER vs r[8] Content ENG
  const idxCodeNotice = isGerman ? 9 : 10;  // r[9] Code/Notice GER vs r[10] Code/Notice ENG

  const dictRows = [[
    'Variable', 'Name', 'Description', 'Datatype', 'Property URL (ontology)', 'Unit',
    'Study', 'Group', 'Subpopulation', 'Sample Size', 'Data Owner', 'Identifier',
    'Selection', 'Parent Group', 'Responsible',
    'Note', 'Min Value', 'Max Value', 'Required', 'Conditional On'
  ]];
  const valueSetRows = [['Variable', 'Code', 'Display']];

  let included = 0;
  let skipped = 0;
  let codedCount = 0;

  for (let i = 1; i < allRows.length; i++) {
    const r = allRows[i];
    if (!r) continue;

    const variable = r[1] ? String(r[1]).trim() : '';
    const hidden = r[23] ? String(r[23]).trim().toLowerCase() : '';

    if (!variable) continue;

    if (hidden === 'yes') { skipped++; continue; }

    const selection = r[2] ? String(r[2]).trim() : '';
    const parentTable = r[3] ? String(r[3]).trim() : '';
    const study = r[4] ? String(r[4]).trim() : '';

    const group  = r[idxGroup] ? String(r[idxGroup]).trim() : '';
    const content = r[idxContent] ? String(r[idxContent]).trim() : '';
    const codeNotice = r[idxCodeNotice] ? String(r[idxCodeNotice]).trim() : '';
    const dataOwner = r[11] ? String(r[11]).trim() : '';
    const responsible = r[12] ? String(r[12]).trim() : '';
    const subpopulation = r[13] ? String(r[13]).trim() : '';
    const sampleN = r[14] ? String(r[14]).trim() : '';
    const rawType = r[15] ? String(r[15]).trim() : '';

    const datatype = mapKoraType(rawType);

    const codes = parseCodes(codeNotice);
    let propertyUrl = '';

    if (codes.length > 0) {
      propertyUrl = `${PROPERTY_URL_BASE}/${variable}`;
      codedCount++;

      for (const c of codes) {
        valueSetRows.push([variable, c.code, c.display]);
      }
    }

    dictRows.push([
      variable,
      content || variable,
      content || variable,
      datatype,
      propertyUrl,
      '',
      study,
      group,
      subpopulation,
      sampleN,
      dataOwner,
      '',
      selection,
      parentTable,
      responsible,
      '',
      '',
      '',
      '',
      ''
    ]);
    included++;
  }

  console.log(`\n  Included: ${included}`);
  console.log(`  Skipped (hidden): ${skipped}`);
  console.log(`  Variables with structured codes: ${codedCount}`);
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
