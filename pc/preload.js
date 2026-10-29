const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('plBridge', {
  onDevices: (cb) => ipcRenderer.on('bt-devices', (_e, list) => cb(list)),
  select: (id) => ipcRenderer.send('bt-select', id || ''),
});
