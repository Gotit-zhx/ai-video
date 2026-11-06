// Standalone, offline renderer regression. No original client or account is loaded.
const {app, BrowserWindow, session} = require('electron');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');

function fixturePDF() {
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>',
    '<< /Length 27 >>\nstream\n1 0 0 rg 20 20 160 160 re f\nendstream',
  ];
  let pdf = '%PDF-1.4\n';
  const offsets = [0];
  objects.forEach((body, index) => {
    offsets.push(Buffer.byteLength(pdf));
    pdf += `${index + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xref = Buffer.byteLength(pdf);
  pdf += `xref\n0 5\n0000000000 65535 f \n`;
  pdf += offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  return Buffer.from(pdf + `trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
}

async function run() {
  const root = process.env.HEYI_PDF_TEST_ROOT;
  const output = process.env.HEYI_PDF_TEST_OUTPUT;
  const profile = process.env.HEYI_PDF_TEST_PROFILE;
  if (![root, output, profile].every(value => value && path.isAbsolute(value))) {
    throw Error('Absolute fixture files, isolated profile and report paths required');
  }
  app.setPath('userData', profile);
  await app.whenReady();
  const files = new Map([
    ['/pdf.js', ['text/javascript', await fs.readFile(path.join(root, 'pdf.js'))]],
    ['/pdf.worker.js', ['text/javascript', await fs.readFile(path.join(root, 'pdf.worker.js'))]],
    ['/fixture.pdf', ['application/pdf', fixturePDF()]],
    ['/', ['text/html', Buffer.from('<!doctype html><canvas id="page"></canvas>')]],
  ]);
  const server = http.createServer((request, response) => {
    const file = files.get(request.url);
    if (!file) {response.writeHead(404);response.end();return;}
    response.writeHead(200, {'Content-Type': file[0]});response.end(file[1]);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  session.defaultSession.webRequest.onBeforeRequest((details, done) =>
    done({cancel: !details.url.startsWith(origin + '/') && !details.url.startsWith('blob:' + origin + '/')}));
  const window = new BrowserWindow({show: false, webPreferences: {
    nodeIntegration: false, contextIsolation: true, sandbox: true,
  }});
  const deadline = setTimeout(() => app.exit(2), 20000);
  try {
    await window.loadURL(origin + '/');
    const result = await window.webContents.executeJavaScript(`(async () => {
      try {
        const library = await import('/pdf.js');
        library.GlobalWorkerOptions.workerSrc = '/pdf.worker.js';
        const bytes = new Uint8Array(await (await fetch('/fixture.pdf')).arrayBuffer());
        const loading = library.getDocument({data: bytes, isEvalSupported: false});
        const pdf = await loading.promise, page = await pdf.getPage(1);
        const canvas = document.querySelector('canvas'), viewport = page.getViewport({scale: 1});
        canvas.width = viewport.width;canvas.height = viewport.height;
        const context = canvas.getContext('2d');
        await page.render({canvasContext: context, canvas, viewport}).promise;
        const pixel = [...context.getImageData(100, 100, 1, 1).data];
        await pdf.destroy();
        return {ok: pixel.join(',') === '255,0,0,255', pixel, pages: 1};
      } catch (error) {return {ok: false, error: error.name + ': ' + error.message};}
    })()`);
    await fs.writeFile(output, JSON.stringify({electron: process.versions.electron,
      chrome: process.versions.chrome, externalRequests: 0, ...result}, null, 2));
    app.exit(result.ok ? 0 : 1);
  } finally {
    clearTimeout(deadline);
    window.destroy();
    server.close();
  }
}
run().catch(error => {console.error(error.message);app.exit(2);});
