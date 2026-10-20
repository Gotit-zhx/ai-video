import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { gzip, gunzip } from 'node:zlib';
import { parseEnv, promisify } from 'node:util';

const compress = promisify(gzip), decompress = promisify(gunzip);
const digest = value => createHash('sha256').update(value).digest('hex');
const MAX_BYTES = 128 * 1024 * 1024;
const MAX_COMPRESSED_BYTES = MAX_BYTES + 1024 * 1024;
// Match the production Java uploader: Dayan rejects 4 MiB non-final parts.
const PART_BYTES = 8 * 1024 * 1024;
const HEX = /^[a-f0-9]{64}$/;

export class ImageOperationOss {
  constructor(client, bucket) {
    this.client = client;
    this.bucket = bucket;
  }
  async store(operationKey, content) {
    if (!HEX.test(operationKey) || !Buffer.isBuffer(content) || !content.length || content.length > MAX_BYTES)
      throw new Error('Invalid image operation archive');
    const sha256 = digest(content), zipped = await compress(content, {level: 1});
    const key = `heyi/image-operations/${operationKey}/${sha256}.json.gz`;
    const options = {mime:'application/gzip', headers:{'x-oss-object-acl':'private'}};
    if (zipped.length <= PART_BYTES) {
      await this.client.put(key, zipped, {...options, headers:{...options.headers,
        'Content-MD5':createHash('md5').update(zipped).digest('base64')}});
    } else {
      const {uploadId} = await this.client.initMultipartUpload(key, options);
      try {
        const parts = [];
        for (let start = 0; start < zipped.length; start += PART_BYTES) {
          const end = Math.min(start + PART_BYTES, zipped.length), number = parts.length + 1;
          const part = zipped.subarray(start,end), md5 = createHash('md5').update(part).digest();
          const result = await this.client.uploadPart(key,uploadId,number,zipped,start,end,
            {headers:{'Content-MD5':md5.toString('base64')}});
          if (result.etag.replaceAll('"','').toLowerCase() !== md5.toString('hex'))
            throw new Error('Image operation archive part mismatch');
          parts.push({number,etag:result.etag.replaceAll('"','')});
        }
        await this.client.completeMultipartUpload(key,uploadId,parts,
          {headers:{'x-oss-object-acl':'private'}});
      } catch (error) {
        try { await this.client.abortMultipartUpload(key,uploadId); } catch {}
        throw error;
      }
    }
    const copy = await this.client.get(key);
    if (copy.content.length !== zipped.length || digest(copy.content) !== digest(zipped))
      throw new Error('Image operation archive readback mismatch');
    return {bucket: this.bucket, key, sha256, size: content.length,
      compressedSha256: digest(zipped), compressedSize: zipped.length};
  }
  async read(operationKey, pointer) {
    if (!HEX.test(operationKey) || pointer?.bucket !== this.bucket || !HEX.test(pointer?.sha256 || '')
        || pointer.key !== `heyi/image-operations/${operationKey}/${pointer.sha256}.json.gz`
        || !HEX.test(pointer.compressedSha256 || '') || !Number.isInteger(pointer.size)
        || pointer.size < 1 || pointer.size > MAX_BYTES || !Number.isInteger(pointer.compressedSize)
        || pointer.compressedSize < 1 || pointer.compressedSize > MAX_COMPRESSED_BYTES)
      throw new Error('Invalid image operation archive reference');
    const result = await this.client.get(pointer.key);
    if (result.content.length !== pointer.compressedSize || digest(result.content) !== pointer.compressedSha256)
      throw new Error('Image operation archive checksum mismatch');
    const content = await decompress(result.content, {maxOutputLength: MAX_BYTES});
    if (content.length !== pointer.size || digest(content) !== pointer.sha256)
      throw new Error('Image operation archive content mismatch');
    return content;
  }
}

export async function createImageOperationOss(env = process.env) {
  if (!env.IMAGE_OPERATION_OSS_BUCKET && !env.IMAGE_OPERATION_OSS_CREDENTIALS_FILE) return null;
  if (!env.IMAGE_OPERATION_OSS_BUCKET || !env.IMAGE_OPERATION_OSS_CREDENTIALS_FILE)
    throw new Error('Incomplete image operation OSS configuration');
  const credentials = parseEnv(await readFile(env.IMAGE_OPERATION_OSS_CREDENTIALS_FILE, 'utf8'));
  if (!credentials.accessKeyId || !credentials.accessKeySecret)
    throw new Error('Invalid image operation OSS credentials');
  const sdk = import.meta.resolve(env.IMAGE_OPERATION_OSS_SDK_MODULE || 'ali-oss');
  const {default: OSS} = await import(sdk);
  const {default: signing} = await import(new URL('./common/signUtils.js',sdk));
  const client = new OSS({bucket: env.IMAGE_OPERATION_OSS_BUCKET,
    endpoint: 'https://oss.cloud.dayan.cloud', region: 'oss-cn-chengdu',
    accessKeyId: credentials.accessKeyId, accessKeySecret: credentials.accessKeySecret,
    timeout: 120_000, authorizationV4: false, retryMax: 1});
  client.setSLDEnabled(true);
  // Dayan accepts the standard V1 Date header, but rejects ali-oss's x-oss-date.
  // Reuse the pinned official SDK's canonicalizer and signer, including subresources.
  client.authorization = function(method, resource, subres, headers) {
    const date = headers['x-oss-date'];
    delete headers['x-oss-date'];
    headers.Date = date;
    return signing.authorization(this.options.accessKeyId, this.options.accessKeySecret,
      signing.buildCanonicalString(method, resource, {headers,parameters:subres}, date),
      this.options.headerEncoding);
  };
  return new ImageOperationOss(client, env.IMAGE_OPERATION_OSS_BUCKET);
}
