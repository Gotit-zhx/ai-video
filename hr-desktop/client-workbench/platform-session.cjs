const fs = require('node:fs/promises');
const path = require('node:path');

// Chromium does not restore session-only cookies after the process exits.
// Preserve our own website's session cookie in the OS encrypted store. This
// neither saves passwords nor renews/recreates a server-expired session.
class PlatformSession {
  constructor({session, safeStorage, directory, origin = 'https://hoyee.org.cn', now = Date.now}) {
    this.session = session;this.safeStorage = safeStorage;this.origin = origin;
    this.host = new URL(origin).hostname;this.now = now;
    this.file = path.join(directory, 'platform-session.enc');
    this.chain = Promise.resolve();this.closed = false;
  }
  accepts(cookie) {
    return cookie?.name === 'HEYI_SESSION' &&
      String(cookie.domain || '').replace(/^\./, '') === this.host &&
      cookie.path === '/' && cookie.secure === true && cookie.httpOnly === true;
  }
  async start() {
    if (!this.safeStorage.isEncryptionAvailable()) return {available: false};
    const existing = await this.session.cookies.get({url: this.origin});
    if (!existing.some(cookie => this.accepts(cookie))) {
      try {
        const data = JSON.parse(this.safeStorage.decryptString(await fs.readFile(this.file)));
        if (data.origin === this.origin && data.savedAt <= this.now() &&
            this.now() - data.savedAt < 8 * 3600 * 1000) {
          for (const cookie of data.cookies || []) {
            if (!this.accepts(cookie) || !cookie.value ||
                (cookie.expirationDate && cookie.expirationDate <= this.now() / 1000)) continue;
            const {name, value, path: cookiePath, secure, httpOnly, sameSite, expirationDate} = cookie;
            await this.session.cookies.set({url: this.origin, name, value, path: cookiePath,
              secure, httpOnly, sameSite, ...(expirationDate ? {expirationDate} : {})});
          }
        }
      } catch {} // A missing, obsolete or unreadable cache never creates a login.
    }
    this.listener = (_event, cookie, cause, removed) => {
      if (!this.accepts(cookie) || this.closed) return;
      // Replacement emits a removal followed by an insertion. Serialize a
      // snapshot of the resulting cookie jar, rather than reviving old values.
      void this.save();
    };
    this.session.cookies.on('changed', this.listener);
    await this.save();
    return {available: true};
  }
  save() {
    this.chain = this.chain.catch(() => {}).then(async () => {
      if (!this.safeStorage.isEncryptionAvailable()) return;
      const cookies = (await this.session.cookies.get({url: this.origin})).filter(cookie =>
        this.accepts(cookie) && cookie.value &&
        (!cookie.expirationDate || cookie.expirationDate > this.now() / 1000));
      if (!cookies.length) {await fs.rm(this.file, {force: true});return;}
      await fs.mkdir(path.dirname(this.file), {recursive: true, mode: 0o700});
      const encrypted = this.safeStorage.encryptString(JSON.stringify({
        origin: this.origin, savedAt: this.now(), cookies,
      }));
      const temporary = this.file + '.tmp';
      await fs.writeFile(temporary, encrypted, {mode: 0o600});
      await fs.rename(temporary, this.file);
      await this.session.cookies.flushStore();
    });
    // Do not leak cookies, credentials, or encryption errors through UI logs.
    void this.chain.catch(() => {});
    return this.chain;
  }
  async close() {
    this.closed = true;
    if (this.listener) this.session.cookies.removeListener('changed', this.listener);
    await this.save();
  }
}
module.exports = {PlatformSession};
