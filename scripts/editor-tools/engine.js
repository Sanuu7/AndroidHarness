// This worker parses source text; it never evaluates workspace code or loads workspace plugins.
import ts from 'typescript';
import * as prettier from 'prettier/standalone';
import * as babel from 'prettier/plugins/babel';
import * as estree from 'prettier/plugins/estree';
import * as typescript from 'prettier/plugins/typescript';
import * as htmlPlugin from 'prettier/plugins/html';
import * as postcss from 'prettier/plugins/postcss';
import * as markdown from 'prettier/plugins/markdown';
import * as yaml from 'prettier/plugins/yaml';
import { getLanguageService as getHtml } from 'vscode-html-languageservice';
import { getCSSLanguageService, getSCSSLanguageService, getLESSLanguageService } from 'vscode-css-languageservice';
import { getLanguageService as getJson } from 'vscode-json-languageservice';
import { TextDocument } from 'vscode-languageserver-textdocument';

const libraries = HARNESS_STANDARD_LIBRARIES;
let files = Object.create(null), versions = Object.create(null), options = {}, service;
const html = getHtml(), css = getCSSLanguageService(), scss = getSCSSLanguageService(), less = getLESSLanguageService();
const json = getJson({ schemaRequestService: () => Promise.reject(new Error('Remote schemas are disabled.')) });
json.configure({ validate: false, allowComments: true });
const ext = name => name.split('.').pop().toLowerCase();
const isScript = name => /\.(?:[cm]?[jt]sx?)$/i.test(name);
const absolute = name => {
  const resolved = `/project/${name}`;
  if (!cleanPath(name) || resolved.split('/').some(p => p === '..' || p === '.') || !resolved.startsWith('/project/'))
    throw new Error('Invalid workspace path.');
  return resolved;
};
const uri = name => `file://${absolute(name).split('/').map(encodeURIComponent).join('/')}`;
function relative(name) {
  const decoded = name.startsWith('file:') ? decodeURIComponent(new URL(name).pathname) : name;
  return decoded.startsWith('/project/') ? decoded.slice(9) : null;
}
function doc(name) { return TextDocument.create(uri(name), ext(name), versions[absolute(name)] || 0, files[absolute(name)] || ''); }
function cleanPath(name) {
  return typeof name === 'string' && name.length > 0 && name.length < 2048 && !name.startsWith('/') &&
    !name.includes('\\') && !name.includes('\0') && !name.split('/').some(p => p === '..' || p === '.');
}
function sync(request) {
  const next = request.reset ? Object.create(null) : Object.assign(Object.create(null), files);
  for (const name of request.removed || []) if (cleanPath(name)) delete next[absolute(name)];
  for (const [name, text] of Object.entries(request.files || {})) {
    if (!cleanPath(name) || typeof text !== 'string') throw new Error('Invalid workspace snapshot.');
    next[absolute(name)] = text;
  }
  for (const [name, text] of Object.entries(next)) if (files[name] !== text) versions[name] = (versions[name] || 0) + 1;
  files = next;
  const configName = Object.keys(files).filter(n => /\/(?:tsconfig|jsconfig)\.json$/.test(n))
    .filter(n => absolute(request.path).startsWith(n.substring(0, n.lastIndexOf('/') + 1)))
    .sort((a, b) => b.length - a.length)[0];
  const defaults = { allowJs: true, checkJs: true, target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler, jsx: ts.JsxEmit.Preserve, allowNonTsExtensions: true,
    resolveJsonModule: true, noEmit: true };
  const host = { useCaseSensitiveFileNames: true, readDirectory: () => Object.keys(files),
    fileExists: name => name in files || name in libraries, readFile: name => files[name] ?? libraries[name] };
  const config = configName ? ts.parseConfigFileTextToJson(configName, files[configName]) : null;
  const parsed = config && !config.error ? ts.parseJsonConfigFileContent(config.config, host, configName.substring(0, configName.lastIndexOf('/'))) : null;
  const updated = { ...defaults, ...(parsed?.options || {}), noEmit: true, noLib: false };
  if (!service || JSON.stringify(updated) !== JSON.stringify(options)) {
    service?.dispose(); options = updated;
    service = ts.createLanguageService({
      getCompilationSettings: () => options,
      getScriptFileNames: () => Object.keys(files).filter(isScript),
      getScriptVersion: name => String(versions[name] || 0),
      getScriptSnapshot: name => { const text = files[name] ?? libraries[name]; return text === undefined ? undefined : ts.ScriptSnapshot.fromString(text); },
      getCurrentDirectory: () => '/project', getDefaultLibFileName: () => '/lib/lib.es2022.full.d.ts',
      ...host, useCaseSensitiveFileNames: () => true,
      directoryExists: name => Object.keys(files).some(n => n.startsWith(`${name}/`)) || name === '/lib',
    });
  }
}
function location(name, start, length = 0, label = '') {
  const path = relative(name);
  if (!path || !(absolute(path) in files)) return null;
  const document = doc(path), pos = document.positionAt(start);
  return { path, start, end: start + length, line: pos.line + 1, column: pos.character + 1,
    label, preview: document.getText().split('\n')[pos.line]?.trim().slice(0, 180) || '' };
}
function fromLocation(item, label = '') {
  const path = relative(item.uri); if (!path || !(absolute(path) in files)) return null;
  const document = doc(path);
  return location(absolute(path), document.offsetAt(item.range.start), document.offsetAt(item.range.end) - document.offsetAt(item.range.start), label);
}
function symbols(name) {
  const document = doc(name), language = ext(name);
  const result = [];
  function add(name, kind, start, end, depth) { result.push({ name, kind: String(kind), start, end, depth, line: document.positionAt(start).line + 1 }); }
  if (isScript(name)) {
    function visit(node, depth) {
      if (node.kind !== 'script' && node.kind !== 'alias') {
        const span = node.nameSpan || node.spans?.[0];
        if (span) add(node.text, node.kind, span.start, span.start + span.length, depth);
        depth++;
      }
      for (const child of node.childItems || []) visit(child, depth);
    }
    visit(service.getNavigationTree(absolute(name)), 0);
  } else {
    let entries = [];
    if (language === 'html' || language === 'htm') entries = html.findDocumentSymbols2(document, html.parseHTMLDocument(document));
    else if (['css', 'scss', 'less'].includes(language)) { const ls = language === 'scss' ? scss : language === 'less' ? less : css; entries = ls.findDocumentSymbols2(document, ls.parseStylesheet(document)); }
    else if (language === 'json' || language === 'jsonc') entries = json.findDocumentSymbols2(document, json.parseJSONDocument(document));
    else if (language === 'md' || language === 'markdown') {
      const tree = markdown.parsers.markdown.parse(document.getText());
      function visit(node) {
        if (node.type === 'heading') add(document.getText().slice(node.position.start.offset, node.position.end.offset).replace(/^#+\s*/, '').split('\n')[0], 'heading', node.position.start.offset, node.position.end.offset, node.depth - 1);
        for (const child of node.children || []) visit(child);
      }
      visit(tree);
    }
    function visit(node, depth) { const range = node.selectionRange || node.range; add(node.name, node.kind, document.offsetAt(range.start), document.offsetAt(range.end), depth); for (const child of node.children || []) visit(child, depth + 1); }
    entries.forEach(node => visit(node, 0));
  }
  return result.slice(0, 2000);
}
function simpleSnippet(value) {
  let text = '', from = 0, cursor = null, stop = Infinity;
  const expression = /\$\{(\d+):([^}]*)\}|\$\{(\d+)\|([^,|}]+)[^}]*\}|\$\{?(\d+)\}?/g;
  for (const match of value.matchAll(expression)) {
    text += value.slice(from, match.index);
    const number = Number(match[1] ?? match[3] ?? match[5]), rank = number === 0 ? Infinity - 1 : number;
    if (rank < stop) { stop = rank; cursor = text.length; }
    text += match[2] ?? match[4] ?? '';
    from = match.index + match[0].length;
  }
  text += value.slice(from);
  return { text, cursor: cursor ?? text.length };
}
async function completions(name, offset) {
  const document = doc(name), position = document.positionAt(offset), language = ext(name);
  if (isScript(name)) {
    const data = service.getCompletionsAtPosition(absolute(name), offset, { includeCompletionsForModuleExports: false, includeCompletionsWithInsertText: true });
    const prefix = document.getText().slice(0, offset).match(/[\p{ID_Continue}$]*$/u)?.[0] || '';
    return (data?.entries || []).filter(e => !e.hasAction && (!prefix || e.name.toLowerCase().startsWith(prefix.toLowerCase())))
      .sort((a, b) => a.sortText.localeCompare(b.sortText) || a.name.localeCompare(b.name)).slice(0, 50).map(e => ({
        label: e.name, detail: e.kind, text: e.insertText || e.name,
        start: e.replacementSpan?.start ?? offset - prefix.length,
        end: e.replacementSpan ? e.replacementSpan.start + e.replacementSpan.length : offset,
      }));
  }
  let items = [];
  if (language === 'html' || language === 'htm') items = html.doComplete(document, position, html.parseHTMLDocument(document)).items;
  else if (['css', 'scss', 'less'].includes(language)) { const ls = language === 'scss' ? scss : language === 'less' ? less : css; items = ls.doComplete(document, position, ls.parseStylesheet(document)).items; }
  else if (language === 'json' || language === 'jsonc') items = (await json.doComplete(document, position, json.parseJSONDocument(document)))?.items || [];
  const prefix = document.getText().slice(0, offset).match(/[\w$-]*$/)?.[0] || '';
  return items.filter(e => !e.additionalTextEdits?.length && (!prefix || (e.filterText || e.label).toLowerCase().startsWith(prefix.toLowerCase())))
    .slice(0, 50).map(e => {
      const edit = e.textEdit;
      const value = edit?.newText || e.insertText || e.label;
      const insertion = e.insertTextFormat === 2 ? simpleSnippet(value) : { text: value, cursor: value.length };
      return { label: e.label, detail: e.detail || 'Suggestion', ...insertion,
        start: edit?.range ? document.offsetAt(edit.range.start) : offset - prefix.length, end: edit?.range ? document.offsetAt(edit.range.end) : offset };
    });
}
function navigate(name, offset, references) {
  if (isScript(name)) {
    const matches = references ? service.getReferencesAtPosition(absolute(name), offset) : service.getDefinitionAtPosition(absolute(name), offset);
    return (matches || []).map(m => location(m.fileName, m.textSpan.start, m.textSpan.length, m.name || '')).filter(Boolean).slice(0, 1000);
  }
  const language = ext(name), document = doc(name), position = document.positionAt(offset);
  if (['css', 'scss', 'less'].includes(language)) {
    const ls = language === 'scss' ? scss : language === 'less' ? less : css, tree = ls.parseStylesheet(document);
    const matches = references ? ls.findReferences(document, position, tree) : [ls.findDefinition(document, position, tree)].filter(Boolean);
    return matches.map(m => fromLocation(m)).filter(Boolean);
  }
  return [];
}
function rename(name, offset, newName) {
  const token = ts.stringToToken(newName || '');
  if (!newName || !ts.isIdentifierText(newName, ts.ScriptTarget.Latest) ||
    (token >= ts.SyntaxKind.FirstKeyword && token <= ts.SyntaxKind.LastReservedWord) ||
    ['await', 'yield', 'let', 'enum', 'implements', 'interface', 'package', 'private', 'protected', 'public', 'static'].includes(newName))
    throw new Error('Enter a valid symbol name, not a reserved keyword.');
  if (!isScript(name)) throw new Error('Rename Symbol supports JavaScript and TypeScript.');
  const info = service.getRenameInfo(absolute(name), offset, { allowRenameOfImportPath: false });
  if (!info.canRename) throw new Error(info.localizedErrorMessage || 'Place the cursor on a project symbol.');
  const matches = service.findRenameLocations(absolute(name), offset, false, false, true) || [];
  // Refuse a partial rename when a definition belongs to a library outside this snapshot.
  if (matches.some(m => !relative(m.fileName))) throw new Error('This symbol belongs to a library and cannot be renamed here.');
  return matches.map(m => ({ path: relative(m.fileName), start: m.textSpan.start, end: m.textSpan.start + m.textSpan.length,
    text: (m.prefixText || '') + newName + (m.suffixText || '') }));
}
function formatterOptions(name) {
  let current = name.includes('/') ? name.substring(0, name.lastIndexOf('/')) : '';
  const allowed = new Set(['tabWidth', 'useTabs', 'semi', 'singleQuote', 'quoteProps', 'jsxSingleQuote', 'trailingComma', 'bracketSpacing', 'bracketSameLine', 'arrowParens', 'printWidth', 'proseWrap', 'htmlWhitespaceSensitivity', 'singleAttributePerLine', 'endOfLine']);
  let selected = {};
  while (true) {
    const prefix = current ? current + '/' : '';
    const config = ['.prettierrc', '.prettierrc.json', '.prettierrc.jsonc'].find(n => absolute(prefix + n) in files);
    const pkg = files[absolute(prefix + 'package.json')];
    if (config) {
      const parsed = ts.parseConfigFileTextToJson(config, files[absolute(prefix + config)]);
      if (parsed.error || !parsed.config || typeof parsed.config !== 'object' || Array.isArray(parsed.config)) throw new Error('Use a JSON Prettier configuration for in-app formatting.');
      selected = parsed.config; break;
    }
    if (pkg) { try { const value = JSON.parse(pkg).prettier; if (value) { if (typeof value !== 'object') throw new Error(); selected = value; break; } } catch { throw new Error('The package.json formatter configuration could not be read.'); } }
    if (['prettier.config.js', 'prettier.config.cjs', 'prettier.config.mjs', 'prettier.config.ts', '.prettierrc.js', '.prettierrc.cjs', '.prettierrc.mjs', '.prettierrc.yaml', '.prettierrc.yml', '.prettierrc.toml'].some(n => absolute(prefix + n) in files))
      throw new Error('This project uses an external formatter configuration. In-app formatting supports JSON Prettier configurations.');
    if (!current) break;
    current = current.includes('/') ? current.substring(0, current.lastIndexOf('/')) : '';
  }
  if (selected.plugins?.length || selected.overrides?.length) throw new Error('This formatter configuration uses plugins or overrides. Use the project formatter in Terminal.');
  return Object.fromEntries(Object.entries(selected).filter(([key]) => allowed.has(key)));
}
async function format(name) {
  const parser = { js: 'babel', jsx: 'babel', mjs: 'babel', cjs: 'babel', ts: 'typescript', tsx: 'typescript', mts: 'typescript', cts: 'typescript',
    html: 'html', htm: 'html', css: 'css', scss: 'scss', less: 'less', json: 'json', jsonc: 'jsonc', md: 'markdown', markdown: 'markdown', yaml: 'yaml', yml: 'yaml' }[ext(name)];
  if (!parser) throw new Error('No bundled formatter for this file type.');
  return prettier.format(files[absolute(name)], { parser, plugins: [babel, estree, typescript, htmlPlugin, postcss, markdown, yaml], ...formatterOptions(name), endOfLine: 'lf' });
}
globalThis.handleEditorRequest = async function(request) {
  if (!cleanPath(request.path)) throw new Error('Invalid editor path.');
  sync(request);
  if (!(absolute(request.path) in files)) throw new Error('File is outside the indexed workspace.');
  const offset = Math.max(0, Math.min(request.offset || 0, files[absolute(request.path)].length));
  switch (request.op) {
    case 'outline': return symbols(request.path);
    case 'complete': return completions(request.path, offset);
    case 'definition': return navigate(request.path, offset, false);
    case 'references': return navigate(request.path, offset, true);
    case 'rename': return rename(request.path, offset, request.newName);
    case 'format': return format(request.path);
    default: throw new Error('Unknown editor action.');
  }
};
// A serial queue prevents asynchronous formatter/completion requests mixing project snapshots.
let queue = Promise.resolve();
globalThis.onmessage = event => {
  queue = queue.then(async () => {
    const request = event.data;
    try { postMessage({ id: request.id, result: await globalThis.handleEditorRequest(request) }); }
    catch (error) { postMessage({ id: request.id, error: String(error.message || error).slice(0, 600) }); }
  });
};
