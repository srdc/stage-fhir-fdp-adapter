#!/usr/bin/env node
/**
 * Usage: node generate-dict-nfbc.js --input NFBC196660vKerys_DataDictionary_2026-02-12.csv [--template src/main/resources/config_nfbc.xlsx] \[--output  nfbc_dictionary_integrated.xlsx] \[--vocab-base http://stage-healthyageing.eu/fdp/vocab]
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
    output: 'nfbc_dictionary_integrated.xlsx',
    template: 'src/main/resources/config_nfbc.xlsx',
    vocabBase: 'http://stage-healthyageing.eu/fdp/vocab'
  };
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--input' && args[i + 1]) opts.input = args[++i];
    else if (args[i] === '--output' && args[i + 1]) opts.output = args[++i];
    else if (args[i] === '--template' && args[i + 1]) opts.template = args[++i];
    else if (args[i] === '--vocab-base' && args[i + 1]) opts.vocabBase = args[++i];
    else if (args[i] === '--help' || args[i] === '-h') {
      console.log(
        'Usage: node generate-dict-nfbc.js --input <csv>\n' +
        '  [--template <xlsx>] [--output <xlsx>] [--vocab-base <uri>]'
      );
      process.exit(0);
    }
  }
  if (!opts.input) {
    console.error(
      'Error: --input is required.\n' +
      'Usage: node generate-dict-nfbc.js --input <csv>\n' +
      '  [--template <xlsx>] [--output <xlsx>] [--vocab-base <uri>]'
    );
    process.exit(1);
  }
  opts.vocabBase = String(opts.vocabBase).replace(/\/+$/, '');
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

function main() {
  const opts = parseArgs();

  console.log('='.repeat(60));
  console.log('NFBC1966 → Data Dictionary + ValueSet Generator');
  console.log('='.repeat(60));

  console.log(`\nReading: ${opts.input}`);
  const raw = fs.readFileSync(path.resolve(opts.input), 'utf-8');
  const records = csvToObjects(raw);

  console.log(`  Total records in CSV: ${records.length}`);

  const SKIP_FORMS = new Set(['etusivu', 'suostumus']);
  const SKIP_TYPES = new Set(['descriptive']);
  const PROPERTY_URL_BASE = opts.vocabBase;
  const STUDY_NAME = 'NFBC1966';

  const dictRows = [[
    'Variable', 'Name', 'Description', 'Datatype', 'Property URL (ontology)', 'Unit',
    'Study', 'Group', 'Subpopulation', 'Sample Size', 'Data Owner', 'Identifier',
    'Selection', 'Parent Group', 'Responsible',
    'Note', 'Min Value', 'Max Value', 'Required', 'Conditional On'
  ]];
  const valueSetRows = [['Variable', 'Code', 'Display']];

  let skipped = 0;
  let included = 0;
  let codedCount = 0;

  for (const row of records) {
    const formName = row['Form Name'] || '';
    const sectionHeader = stripHtml(row['Section Header'] || '');
    const fieldType = row['Field Type'] || '';
    const fieldName = row['Variable / Field Name'] || '';
    const fieldLabel = row['Field Label'] || '';
    const choicesRaw = row['Choices, Calculations, OR Slider Labels'] || '';
    const validation = row['Text Validation Type OR Show Slider Number'] || '';
    const identifierFlag = (row['Identifier?'] || '').trim();
    const fieldNote = stripHtml(row['Field Note'] || '');
    const validMin = (row['Text Validation Min'] || '').trim();
    const validMax = (row['Text Validation Max'] || '').trim();
    const requiredFlag = (row['Required Field?'] || '').trim();
    const branchingLogic = (row['Branching Logic (Show field only if...)'] || '').trim();

    // Skip front page, consent, display-only, empty
    if (SKIP_FORMS.has(formName)) { skipped++; continue; }
    if (SKIP_TYPES.has(fieldType)) { skipped++; continue; }
    if (!fieldName) { skipped++; continue; }
    if (fieldName === 'record_id' || fieldName === 'kukuid') { skipped++; continue; }

    const cleanLabel = stripHtml(fieldLabel).substring(0, 300);
    const datatype = mapRedcapType(fieldType, validation);
    const name = cleanLabel || fieldName;
    const group = sectionHeader || formName;
    const parentGroup = sectionHeader ? formName : '';

    const codes = parseChoices(choicesRaw);
    let propertyUrl = '';

    if (codes.length > 0) {
      propertyUrl = `${PROPERTY_URL_BASE}/${fieldName}`;
      codedCount++;

      for (const c of codes) {
        valueSetRows.push([fieldName, c.code, c.display]);
      }
    }
    dictRows.push([
      fieldName, name, name, datatype, propertyUrl, '',
      STUDY_NAME,
      group,
      '',
      '',
      '',
      identifierFlag,
      '',
      parentGroup,
      '',
      fieldNote,
      validMin,
      validMax,
      requiredFlag,
      branchingLogic
    ]);
    included++;
  }

  console.log(`\n  Included: ${included}`);
  console.log(`  Skipped (descriptive/consent/IDs): ${skipped}`);
  console.log(`  Variables with structured choices: ${codedCount}`);
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
