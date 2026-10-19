import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, writeFile, rm } from 'node:fs/promises';
import path from 'node:path';

const hash = value => createHash('sha256').update(value).digest('hex');
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort()
    .filter(key => key !== 'apiKey').map(key => [key,canonical(value[key])]));
  return value;
}
const ERROR_FIELDS = ['requestState','retrySafe','retryAuthorized','safeRetryCount','networkRetryCount','failureCode','failureKind','providerStatus','requestId','imageModelFallback','imageOutputRequirement'];
const LOOKUP_ERROR_FIELDS = Object.freeze({requestState:'string',retrySafe:'boolean',retryAuthorized:'boolean',
  safeRetryCount:'number',networkRetryCount:'number',failureCode:'string',failureKind:'string',providerStatus:'number',requestId:'string'});
function lookupError(error) {
  if (!error || typeof error !== 'object') return null;
  return Object.fromEntries(Object.entries(LOOKUP_ERROR_FIELDS).filter(([key,type]) => typeof error[key] === type
    && (type !== 'number' || Number.isFinite(error[key]))).map(([key,type]) => [key,type === 'string' ? error[key].slice(0,128) : error[key]]));
}
function failure(message, code = 'IMAGE_RECOVERY_EXHAUSTED') {
  return Object.assign(new Error(message), {requestState:'ACCEPTED_OR_UNKNOWN',retrySafe:false,retryAuthorized:false,failureKind:'NETWORK',failureCode:code});
}
function serializeError(error) {
  return {message:String(error?.message || '图片生成失败').slice(0,1500),
    ...Object.fromEntries(ERROR_FIELDS.filter(key => error?.[key] !== undefined).map(key => [key,error[key]]))};
}
const restoreError = value => Object.assign(new Error(value.message), value);

// The caller's persisted operation ID joins duplicate callers and binds one input.
// Only hashes, attempt metadata and returned output are stored; never model keys
// or source images. Writes finish before a POST or before returning its result.
export class ImageOperationStore {
  constructor(directory, {maxNetworkRetries = 2, maxAttemptsPerRoute = 8} = {}) {
    this.directory = directory; this.maxNetworkRetries = maxNetworkRetries;
    this.maxAttemptsPerRoute = maxAttemptsPerRoute; this.active = new Map();
  }
  async initialize() {
    await mkdir(this.directory, {recursive:true,mode:0o700});
    const probe = path.join(this.directory, `.write-check-${randomUUID()}`);
    try { await writeFile(probe, 'ready', {mode:0o600,flag:'wx'}); }
    finally { await rm(probe, {force:true}); }
  }
  async write(file, value) {
    await mkdir(this.directory, {recursive:true,mode:0o700});
    const temporary = `${file}.${randomUUID()}.tmp`;
    await writeFile(temporary, JSON.stringify(value), {mode:0o600});
    await rename(temporary, file);
  }
  async lookup(requestId) {
    if (typeof requestId !== 'string' || !/^[A-Za-z0-9_-]{8,128}$/.test(requestId)) {
      throw Object.assign(new Error('图片恢复任务缺少有效请求编号'), {status:400});
    }
    let record;
    try { record = JSON.parse(await readFile(path.join(this.directory, `${hash(requestId)}.json`), 'utf8')); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    const active = this.active.get(requestId);
    if (active) return {status:'RUNNING',active:true,updatedAt:record?.updatedAt || active.updatedAt,response:null,error:null};
    if (!record) return {status:'NOT_FOUND',active:false,updatedAt:0,response:null,error:null};
    const error = record.status === 'RECOVERABLE'
      ? {...lookupError(record.error),savedResponseAvailable:Object.values(record.routes || {}).some(route => route?.response != null)}
      : record.status === 'FAILED' ? lookupError(record.error) : null;
    return {status:record.status,active:false,updatedAt:Number.isFinite(record.updatedAt) ? record.updatedAt : 0,
      response:record.status === 'COMPLETED' ? record.result ?? null : null,
      error};
  }
  async archive(requestId, storageKey) {
    if (!/^[A-Za-z0-9_-]{8,128}$/.test(String(requestId || ''))
      || !/^[a-f0-9]{64}\/[a-f0-9]{64}$/.test(String(storageKey || '')))
      throw Object.assign(new Error('图片归档凭据无效'), {status:400});
    if (this.active.has(requestId)) throw Object.assign(new Error('图片任务仍在运行'), {status:409});
    const file = path.join(this.directory, `${hash(requestId)}.json`);
    let record;
    try { record = JSON.parse(await readFile(file, 'utf8')); }
    catch (error) { if (error.code === 'ENOENT') return {status:'NOT_FOUND'}; throw error; }
    if (record.status === 'ARCHIVED') {
      if (record.archive?.storageKey !== storageKey) throw Object.assign(new Error('图片归档编号已改变'), {status:409});
      return {status:'ARCHIVED'};
    }
    if (record.status !== 'COMPLETED') throw Object.assign(new Error('只能清理已完成且已归档的图片结果'), {status:409});
    const encoded = record.result?.artifact?.dataBase64;
    if (encoded && hash(Buffer.from(encoded, 'base64')) !== storageKey.slice(65))
      throw Object.assign(new Error('归档图片与生成结果不一致'), {status:409});
    // The backend acknowledges only after a verified OSS copy and a durable READY row.
    // Keep the paid request identity and counters so replay can never start another POST.
    record.status = 'ARCHIVED';
    record.archive = {storageKey, archivedAt:Date.now()};
    delete record.result;
    delete record.error;
    for (const route of Object.values(record.routes || {})) delete route.response;
    record.updatedAt = Date.now();
    await this.write(file, record);
    return {status:'ARCHIVED'};
  }
  execute(requestId, input, task) {
    if (!/^[A-Za-z0-9_-]{8,128}$/.test(String(requestId || ''))) {
      return Promise.reject(Object.assign(new Error('图片恢复任务缺少有效请求编号'), {status:400}));
    }
    const fingerprint = hash(JSON.stringify(canonical(input)));
    const current = this.active.get(requestId);
    if (current) {
      if (current.fingerprint !== fingerprint) return Promise.reject(Object.assign(new Error('同一图片任务的输入已改变'), {status:409}));
      return current.promise;
    }
    const promise = this.run(requestId, fingerprint, task);
    this.active.set(requestId, {fingerprint,promise,updatedAt:Date.now()});
    promise.finally(() => {if (this.active.get(requestId)?.promise === promise) this.active.delete(requestId);}).catch(() => {});
    return promise;
  }
  async run(requestId, fingerprint, task) {
    const file = path.join(this.directory, `${hash(requestId)}.json`);
    let record;
    try { record = JSON.parse(await readFile(file, 'utf8')); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (record && record.fingerprint !== fingerprint) throw Object.assign(new Error('同一图片任务的输入已改变'), {status:409});
    if (record?.status === 'ARCHIVED') throw failure('图片已归档到OSS，请读取业务图片记录；禁止重复生图', 'IMAGE_RESULT_ARCHIVED');
    if (record?.status === 'COMPLETED') return record.result;
    if (record?.status === 'FAILED') throw restoreError(record.error);
    record ||= {version:1,fingerprint,createdAt:Date.now(),routes:{}};
    record.status = 'RUNNING';
    const persist = async () => {record.updatedAt=Date.now();await this.write(file,record);};
    await persist();
    const operation = {
      forRoute: async (config, {maxNetworkRetries = this.maxNetworkRetries} = {}) => {
        const routeId = hash(JSON.stringify([config.baseUrl?.replace(/\/+$/,''),config.modelId]));
        const route = record.routes[routeId] ||= {idempotencyKey:hash(`${requestId}:${routeId}`),attempts:0,networkRetryCount:0};
        if (route.response) return {cachedResponse:route.response};
        if (route.error) throw restoreError(route.error);
        if (route.pending) {
          if (route.networkRetryCount >= Math.min(maxNetworkRetries, this.maxNetworkRetries)) {
            const error=failure('图片连接中断，自动恢复次数已用完，已保留请求记录');
            error.retryAuthorized=true;throw error;
          }
          // A restart may have interrupted a submitted request. Resuming consumes
          // the same bounded, user-authorized network recovery budget.
          route.networkRetryCount++; route.pending=false; await persist();
        }
        return {
          idempotencyKey:route.idempotencyKey, networkRetryCount:route.networkRetryCount, maxNetworkRetries,
          beforeAttempt:async () => {
            if (route.attempts >= this.maxAttemptsPerRoute) throw failure('图片请求已达到自动恢复次数上限');
            route.attempts++;route.pending=true;await persist();
          },
          recordNetworkRetry:async count => {
            if (count > this.maxNetworkRetries) throw failure('图片连接中断，自动恢复次数已用完');
            route.networkRetryCount=count;route.pending=false;await persist();
          },
          recordResponse:async response => {route.response=response;route.pending=false;await persist();},
          recordFailure:async error => {route.pending=false;route.error=serializeError(error);await persist();}
        };
      }
    };
    try {
      const result = await task(operation);
      record.status='COMPLETED';record.result=result;await persist();
      return result;
    } catch (error) {
      // A received provider response can be decoded/downloaded again without a
      // new model POST. All exhausted transport failures remain terminal.
      record.status = error.requestState === 'COMPLETED_RESPONSE_RECEIVED'
        || record.result !== undefined || Object.values(record.routes).some(route => route.response)
        ? 'RECOVERABLE' : 'FAILED';
      record.error=serializeError(error);await persist();throw error;
    }
  }
}
