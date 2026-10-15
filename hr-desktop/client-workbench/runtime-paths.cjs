const path = require('node:path');

function runtimeExecutable(resources = process.resourcesPath, platform = process.platform) {
  const targetPath = platform === 'win32' ? path.win32 : path;
  if (typeof resources !== 'string' || !targetPath.isAbsolute(resources)) {
    throw Error('Packaged runtime resources path required');
  }
  return targetPath.join(resources, 'heyi-runtime',
    platform === 'win32' ? 'heyi-boss.exe' : 'heyi-boss');
}

module.exports = {runtimeExecutable};
