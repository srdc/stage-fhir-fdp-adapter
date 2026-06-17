#!/usr/bin/env node
/**
 * Usage: node generate-dict-kora.js --input VarDef_AGE1_20250121_V2.xlsx [--template src/main/resources/config_kora.xlsx] \[--output  kora_dictionary_integrated.xlsx] [--vocab-base http://stage-healthyageing.eu/fdp/vocab] [--lang en|de]
 * Requirement: Node.js
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { mergeIntoTemplate } = require('./excel-template-merge');

function parseArgs() {
  const args = process.argv.slice(2);
  const opts = {
    input: null,
    output: 'kora_dictionary_integrated.xlsx',
    template: 'src/main/resources/config_kora.xlsx',
    vocabBase: 'http://stage-healthyageing.eu/fdp/vocab',
    lang: 'en'
  };
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--input' && args[i + 1]) opts.input = args[++i];
    else if (args[i] === '--output' && args[i + 1]) opts.output = args[++i];
    else if (args[i] === '--template' && args[i + 1]) opts.template = args[++i];
    else if (args[i] === '--vocab-base' && args[i + 1]) opts.vocabBase = args[++i];
    else if ((args[i] === '--lang' || args[i] === '--language') && args[i + 1]) {
      const v = String(args[++i]).toLowerCase();
      opts.lang = (v === 'de' || v === 'ger' || v === 'deu' || v === 'german') ? 'de' : 'en';
    }
    else if (args[i] === '--help' || args[i] === '-h') {
      console.log(
        'Usage: node generate-dict-kora.js --input <xlsx>\n' +
        '  [--template <xlsx>] [--output <xlsx>] [--vocab-base <uri>] [--lang en|de]'
      );
      process.exit(0);
    }
  }
  if (!opts.input) {
    console.error(
      'Error: --input is required.\n' +
      'Usage: node generate-dict-kora.js --input <xlsx>\n' +
      '  [--template <xlsx>] [--output <xlsx>] [--vocab-base <uri>] [--lang en|de]'
    );
    process.exit(1);
  }
  opts.vocabBase = String(opts.vocabBase).replace(/\/+$/, '');
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

function main() {
  const opts = parseArgs();

  console.log('='.repeat(60));
  console.log('KORA-AGE1 → Data Dictionary + ValueSet Generator');
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

  const PROPERTY_URL_BASE = opts.vocabBase;

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

  console.log(`\nMerging into template: ${opts.template}`);
  console.log(`Writing: ${opts.output}`);
  mergeIntoTemplate(
    path.resolve(opts.template),
    [
      { name: 'Data Dictionary', rows: dictRows },
      { name: 'ValueSet', rows: valueSetRows }
    ],
    path.resolve(opts.output)
  );

  const sizeKB = (fs.statSync(path.resolve(opts.output)).size / 1024).toFixed(1);
  console.log(`  File size: ${sizeKB} KB`);
  console.log(`  Data Dictionary rows: ${dictRows.length - 1} (+ header)`);
  console.log(`  ValueSet rows: ${valueSetRows.length - 1} (+ header)`);
  console.log(`  Vocab base: ${opts.vocabBase}`);
  console.log('\n' + '='.repeat(60));
  console.log('Done.');
}

main();
