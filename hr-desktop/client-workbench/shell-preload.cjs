const {contextBridge, ipcRenderer} = require('electron');
contextBridge.exposeInMainWorld('heyiShell', Object.freeze({
  action: name => {
    if (['workbench', 'boss', 'minimize', 'maximize', 'hide'].includes(name)) {
      ipcRenderer.send('heyi-integrated-shell-action', name);
    }
  },
  onState: callback => {
    ipcRenderer.on('heyi-integrated-shell-state', (_event, value) => callback(value));
  },
}));
