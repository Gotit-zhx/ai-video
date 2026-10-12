import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile, rename } from "node:fs/promises";
import path from "node:path";

const hash = value => createHash("sha256").update(value).digest("hex");
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort()
    .map(key => [key, key === "apiKey" ? hash(String(value[key])) : canonical(value[key])]));
  return value;
}

// Persist returned groups before moving on. Never store credentials, cookies,
// prompts or input cards. Duplicate/reconnected callers reuse completed work.
export class RecruitmentOperationStore {
  constructor(directory) { this.directory = directory; this.active = new Map(); }
  execute(id, body, task) {
    if (!/^[a-f0-9]{64}$/.test(String(id)))
      return Promise.reject(new Error("Invalid recruitment operation ID"));
    const fingerprint = hash(JSON.stringify(canonical(body)));
    const current = this.active.get(id);
    if (current) {
      if (current.fingerprint !== fingerprint) return Promise.reject(new Error("Recruitment input changed"));
      return current.promise;
    }
    const promise = this.run(id, fingerprint, task);
    this.active.set(id, { fingerprint, promise });
    promise.finally(() => { if (this.active.get(id)?.promise === promise) this.active.delete(id); }).catch(() => {});
    return promise;
  }
  async run(id, fingerprint, task) {
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    const file = path.join(this.directory, id + ".json");
    let record;
    try { record = JSON.parse(await readFile(file, "utf8")); } catch (error) { if (error.code !== "ENOENT") throw error; }
    if (record && record.fingerprint !== fingerprint) throw new Error("Recruitment input changed");
    if (record?.status === "COMPLETED") return record.result;
    record ||= { fingerprint, results: {}, splits: {}, createdAt: Date.now() };
    record.splits ||= {};
    const save = async () => {
      record.updatedAt = Date.now();
      const temporary = file + "." + randomUUID() + ".tmp";
      await writeFile(temporary, JSON.stringify(record), { mode: 0o600 });
      await rename(temporary, file);
    };
    record.status = "RUNNING"; await save();
    try {
      record.result = await task({ results: record.results, splits: record.splits, save });
      record.status = "COMPLETED"; await save(); return record.result;
    } catch (error) {
      record.status = "RECOVERABLE"; await save(); throw error;
    }
  }
}
