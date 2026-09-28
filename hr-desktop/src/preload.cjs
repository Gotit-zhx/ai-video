const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('desktop',{
  action:name=>ipcRenderer.invoke('desktop',name),
  onState:callback=>ipcRenderer.on('state',(_event,state)=>callback(state)),
});
