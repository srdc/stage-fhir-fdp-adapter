'use strict';

const fs = require('fs');
const zlib = require('zlib');

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

function crc32(buf) {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i];
    for (let j = 0; j < 8; j++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function buildZip(files) {
  const entries = Object.entries(files);
  const centralHeaders = [];
  let offset = 0;
  const parts = [];
  for (const [name, raw] of entries) {
    const nameBuf = Buffer.from(name, 'utf-8');
    const compressed = zlib.deflateRawSync(raw);
    const crc = crc32(raw);
    const local = Buffer.alloc(30 + nameBuf.length);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    nameBuf.copy(local, 30);
    parts.push(local, compressed);
    const cd = Buffer.alloc(46 + nameBuf.length);
    cd.writeUInt32LE(0x02014b50, 0);
    cd.writeUInt16LE(20, 4);
    cd.writeUInt16LE(20, 6);
    cd.writeUInt16LE(0, 8);
    cd.writeUInt16LE(8, 10);
    cd.writeUInt16LE(0, 12);
    cd.writeUInt16LE(0, 14);
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(compressed.length, 20);
    cd.writeUInt32LE(raw.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28);
    cd.writeUInt16LE(0, 30);
    cd.writeUInt16LE(0, 32);
    cd.writeUInt16LE(0, 34);
    cd.writeUInt16LE(0, 36);
    cd.writeUInt32LE(0, 38);
    cd.writeUInt32LE(offset, 42);
    nameBuf.copy(cd, 46);
    centralHeaders.push(cd);
    offset += local.length + compressed.length;
  }
  const cdStart = offset;
  for (const cd of centralHeaders) { parts.push(cd); offset += cd.length; }
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(offset - cdStart, 12);
  eocd.writeUInt32LE(cdStart, 16);
  eocd.writeUInt16LE(0, 20);
  parts.push(eocd);
  return Buffer.concat(parts);
}

function escapeXml(str) {
  return String(str == null ? '' : str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function xmlAttr(attrStr, name) {
  const m = attrStr.match(new RegExp(`\\b${name}="([^"]*)"`));
  return m ? m[1] : null;
}

function parseSheetEntries(workbookXml) {
  const out = [];
  const re = /<sheet\s+([\s\S]*?)\s*(?:\/>|>[\s\S]*?<\/sheet>)/g;
  let m;
  while ((m = re.exec(workbookXml)) !== null) {
    const attrs = m[1];
    const name = xmlAttr(attrs, 'name');
    const sheetId = xmlAttr(attrs, 'sheetId');
    const rId = xmlAttr(attrs, 'r:id') || xmlAttr(attrs, 'id');
    const state = xmlAttr(attrs, 'state') || 'visible';
    if (name && rId) out.push({ name, sheetId, rId, state });
  }
  return out;
}

function parseRelationships(relsXml) {
  const out = [];
  const re = /<Relationship\s+([\s\S]*?)\s*\/>/g;
  let m;
  while ((m = re.exec(relsXml)) !== null) {
    const attrs = m[1];
    const id = xmlAttr(attrs, 'Id');
    if (!id) continue;
    out.push({
      Id: id,
      Type: xmlAttr(attrs, 'Type'),
      Target: xmlAttr(attrs, 'Target')
    });
  }
  return out;
}

function buildInlineSheetXml(rows) {
  const colLetter = (idx) => {
    let s = '';
    idx++;
    while (idx > 0) { idx--; s = String.fromCharCode(65 + (idx % 26)) + s; idx = Math.floor(idx / 26); }
    return s;
  };

  let xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
  xml += '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" ' +
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">';
  xml += '<sheetData>';
  for (let r = 0; r < rows.length; r++) {
    xml += `<row r="${r + 1}">`;
    for (let c = 0; c < rows[r].length; c++) {
      const val = rows[r][c];
      if (val !== null && val !== undefined && String(val).length > 0) {
        xml += `<c r="${colLetter(c)}${r + 1}" t="inlineStr"><is><t xml:space="preserve">${escapeXml(val)}</t></is></c>`;
      }
    }
    xml += '</row>';
  }
  xml += '</sheetData>';
  xml += '</worksheet>';
  return xml;
}

/**
 * Merge generator-produced sheets into an existing XLSX template.
 *
 * @param {string} templatePath  Path to the template .xlsx file.
 * @param {Array}  newSheets     [{name: string, rows: any[][]}, ...]
 * @param {string} outputPath    Where to write the merged result.
 */
function mergeIntoTemplate(templatePath, newSheets, outputPath) {
  if (!fs.existsSync(templatePath)) {
    throw new Error(`Template not found: ${templatePath}`);
  }
  const templateBuf = fs.readFileSync(templatePath);
  const entries = readZipEntries(templateBuf);

  const wbXmlBuf = entries['xl/workbook.xml'];
  const wbRelsBuf = entries['xl/_rels/workbook.xml.rels'];
  const ctBuf = entries['[Content_Types].xml'];
  if (!wbXmlBuf || !wbRelsBuf || !ctBuf) {
    throw new Error(`Template ${templatePath} is missing core XLSX parts.`);
  }

  let wbXml = wbXmlBuf.toString('utf-8');
  let wbRels = wbRelsBuf.toString('utf-8');
  let ctXml = ctBuf.toString('utf-8');

  // Index existing sheets by name to see if overwrite or append
  const sheetList = parseSheetEntries(wbXml);
  const relList = parseRelationships(wbRels);
  const relById = new Map(relList.map(r => [r.Id, r]));

  const nameToInfo = new Map();
  for (const s of sheetList) {
    const rel = relById.get(s.rId);
    if (!rel || !rel.Target) continue;
    const partPath = rel.Target.startsWith('/')
      ? rel.Target.slice(1)
      : 'xl/' + rel.Target;
    nameToInfo.set(s.name, { sheetId: s.sheetId, rId: s.rId, partPath, state: s.state });
  }

  const allSheetIds = sheetList
    .map(s => parseInt(s.sheetId, 10))
    .filter(n => !isNaN(n));
  const allRIdNums = relList
    .map(r => parseInt(String(r.Id || '').replace(/^rId/, ''), 10))
    .filter(n => !isNaN(n));
  let nextSheetId = (allSheetIds.length ? Math.max(...allSheetIds) : 0) + 1;
  let nextRIdNum = (allRIdNums.length ? Math.max(...allRIdNums) : 0) + 1;

  let appendedSheetsXml = '';
  let appendedRelsXml = '';
  let appendedOverridesXml = '';

  for (const ns of newSheets) {
    const xml = buildInlineSheetXml(ns.rows);
    const existing = nameToInfo.get(ns.name);
    if (existing) {
      entries[existing.partPath] = Buffer.from(xml, 'utf-8');
      continue;
    }

    let i = 1;
    while (entries[`xl/worksheets/sheet${i}.xml`]) i++;
    const partPath = `xl/worksheets/sheet${i}.xml`;
    entries[partPath] = Buffer.from(xml, 'utf-8');

    const rId = `rId${nextRIdNum++}`;
    const sheetId = String(nextSheetId++);
    const target = partPath.replace(/^xl\//, '');

    appendedSheetsXml +=
      `<sheet name="${escapeXml(ns.name)}" sheetId="${sheetId}" r:id="${rId}"/>`;
    appendedRelsXml +=
      `<Relationship Id="${rId}" ` +
      `Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" ` +
      `Target="${escapeXml(target)}"/>`;
    appendedOverridesXml +=
      `<Override PartName="/${escapeXml(partPath)}" ` +
      `ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`;
  }

  if (appendedSheetsXml) {
    wbXml = wbXml.replace('</sheets>', appendedSheetsXml + '</sheets>');
    entries['xl/workbook.xml'] = Buffer.from(wbXml, 'utf-8');
  }
  if (appendedRelsXml) {
    wbRels = wbRels.replace('</Relationships>', appendedRelsXml + '</Relationships>');
    entries['xl/_rels/workbook.xml.rels'] = Buffer.from(wbRels, 'utf-8');
  }
  if (appendedOverridesXml) {
    ctXml = ctXml.replace('</Types>', appendedOverridesXml + '</Types>');
    entries['[Content_Types].xml'] = Buffer.from(ctXml, 'utf-8');
  }

  fs.writeFileSync(outputPath, buildZip(entries));
}

module.exports = { mergeIntoTemplate };
