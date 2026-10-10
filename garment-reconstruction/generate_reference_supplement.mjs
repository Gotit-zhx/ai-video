// Uses the user's existing project image runtime and active image configuration.
// No provider key, internal token or encrypted model row is written to artifacts.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { buildMultiViewPrompt, validateMultiViewSpec } from './multiview_prompt.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = process.argv[2] ? path.resolve(root, process.argv[2]) : path.join(root, 'output/garment-reference-supplement-20260911');
const recordPath = path.join(out, 'request-record.json');
const docker = '/Applications/Docker.app/Contents/Resources/bin/docker';
const org = '4d6279a0-92b2-4a26-9edf-3bfe5b58f817';
const digest = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const settingsPath = path.join(out, 'request-settings.json');
const settings = fs.existsSync(settingsPath) ? JSON.parse(fs.readFileSync(settingsPath, 'utf8')) : {};
const referencePaths = settings.references ? settings.references.map(p => path.resolve(root, p)) : [path.join(root, 'output/shirt-four-view-20260911/four-view.png'), path.join(out, 'original-sleeve-crops.png')];
if (referencePaths.length < 1 || referencePaths.length > 4) throw new Error('Need 1–4 explicit references');
const promptPath = path.join(out, 'prompt.txt');
let prompt;
if (settings.promptSpecification) {
  prompt = buildMultiViewPrompt(settings.promptSpecification, {referenceCount:referencePaths.length});
  // Never overwrite an accepted request's historical prompt or silently choose
  // between competing freeform and canonical instructions.
  if (fs.existsSync(promptPath) && fs.readFileSync(promptPath, 'utf8') !== prompt) throw new Error('Existing prompt differs from canonical specification; use a new output version');
  if (!fs.existsSync(promptPath)) fs.writeFileSync(promptPath, prompt, {flag:'wx'});
  fs.writeFileSync(path.join(out, 'prompt-validation.json'), JSON.stringify(validateMultiViewSpec(settings.promptSpecification, {referenceCount:referencePaths.length}), null, 2));
} else prompt = fs.readFileSync(promptPath, 'utf8');
const references = referencePaths.map(p => ({ mimeType: 'image/png', dataBase64: fs.readFileSync(p).toString('base64') }));
const specification = { promptSha256: digest(prompt), referenceSha256: referencePaths.map(p => digest(fs.readFileSync(p))), size: settings.size || '2048x2048', quality: 'high', modelId: 'gpt-image-2' };
if (process.argv.includes('--validate-only')) {
  console.log(JSON.stringify({status:'INPUTS_VALIDATED', canonicalPrompt:!!settings.promptSpecification, providerRequestSubmitted:false, specification}));
  process.exit(0);
}
let record;
if (fs.existsSync(recordPath)) {
  record = JSON.parse(fs.readFileSync(recordPath, 'utf8'));
  if (JSON.stringify(record.specification) !== JSON.stringify(specification)) throw new Error('Existing request has different inputs; use a new output version.');
  if (record.status === 'COMPLETED') {
    const saved = path.join(out, record.outputFile);
    if (!fs.existsSync(saved) || digest(fs.readFileSync(saved)) !== record.outputSha256) throw new Error('Saved artifact missing or changed; preserve the original request and recover its result explicitly');
    console.log(JSON.stringify({ status: 'COMPLETED', reusedSavedArtifact: true, output: out }));
    process.exit(0);
  }
} else {
  record = { status: 'PREPARED', requestId: crypto.randomUUID(), specification, createdAt: new Date().toISOString(), referencePaths, source: 'existing project image runtime', physicalEvidence: false };
  fs.writeFileSync(recordPath, JSON.stringify(record, null, 2));
}
const env = Object.fromEntries(JSON.parse(execFileSync(docker, ['inspect', 'ai-commerce-app'], { encoding: 'utf8' }))[0].Config.Env.map(s => { const i = s.indexOf('='); return [s.slice(0, i), s.slice(i + 1)]; }));
const sql = `SELECT JSON_OBJECT('baseUrl',base_url,'modelId',model_id,'apiFormat',api_format,'cipher',api_key_ciphertext) FROM agent_model_configs WHERE organization_id='${org}' AND model_id='gpt-image-2' AND status='ACTIVE' LIMIT 1;`;
const row = JSON.parse(execFileSync(docker, ['exec', '-i', 'ai-commerce-mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw'], { input: sql, encoding: 'utf8' }).trim());
const encrypted = Buffer.from(row.cipher.slice(3), 'base64');
const decipher = crypto.createDecipheriv('aes-256-gcm', crypto.createHash('sha256').update(env.APP_MODEL_SECRET_KEY).digest(), encrypted.subarray(0, 12));
decipher.setAuthTag(encrypted.subarray(-16));
const apiKey = Buffer.concat([decipher.update(encrypted.subarray(12, -16)), decipher.final()]).toString();
const model = { baseUrl: row.baseUrl, modelId: row.modelId, apiFormat: row.apiFormat, apiKey };
const headers = { 'Content-Type': 'application/json', 'X-Internal-Token': env.APP_PI_RUNTIME_TOKEN };
const safe = value => String(value || '').replaceAll(apiKey, '[REDACTED]').replaceAll(env.APP_PI_RUNTIME_TOKEN, '[REDACTED]');
const start = Date.now();
try {
  // A rerun attaches to the same persisted operation instead of creating a new provider call.
  record.status = 'SUBMITTED';
  fs.writeFileSync(recordPath, JSON.stringify(record, null, 2));
  const response = await fetch('http://127.0.0.1:8090/v1/images/generate', { method: 'POST', headers,
    body: JSON.stringify({ model, prompt, size: specification.size, quality: specification.quality, references, progressRequestId: record.requestId }),
    signal: AbortSignal.timeout(930000) });
  const payload = await response.json();
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${safe(payload.message)} (${payload.requestState || 'unknown'})`);
  let artifact = payload.artifact;
  if (!artifact?.dataBase64 && payload.result?.url) {
    const download = await fetch('http://127.0.0.1:8090/v1/images/download', { method: 'POST', headers,
      body: JSON.stringify({ model, resultUrl: payload.result.url, mimeType: payload.result.mimeType, modelId: model.modelId }), signal: AbortSignal.timeout(70000) });
    const downloaded = await download.json();
    if (!download.ok) throw new Error(`Image received; download HTTP ${download.status}: ${safe(downloaded.message)}`);
    artifact = downloaded.artifact;
  }
  if (!artifact?.dataBase64) throw new Error('Runtime returned no image artifact');
  const bytes = Buffer.from(artifact.dataBase64, 'base64');
  const filename = artifact.mimeType === 'image/jpeg' ? 'sleeve-detail-sheet.jpg' : 'sleeve-detail-sheet.png';
  fs.writeFileSync(path.join(out, filename), bytes);
  record = { ...record, status: 'COMPLETED', outputFile: filename, outputSha256: digest(bytes), seconds: (Date.now() - start) / 1000, modelId: artifact.modelId || model.modelId, mimeType: artifact.mimeType, completedAt: new Date().toISOString(), usableAsPhysicalGroundTruth: false };
  fs.writeFileSync(recordPath, JSON.stringify(record, null, 2));
  console.log(JSON.stringify({ status: record.status, outputFile: filename, bytes: bytes.length, seconds: record.seconds, modelId: record.modelId }));
} catch (error) {
  record = { ...record, status: 'FAILED_OR_UNKNOWN', message: safe(error.message), seconds: (Date.now() - start) / 1000 };
  fs.writeFileSync(recordPath, JSON.stringify(record, null, 2));
  console.log(JSON.stringify({ status: record.status, message: record.message }));
  process.exitCode = 1;
}
