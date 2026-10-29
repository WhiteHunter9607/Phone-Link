const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

let pending = null;   // Electron's callback for the Bluetooth chooser that is currently open
let scanTimer = null;

function finish(id) {
  clearTimeout(scanTimer);
  if (pending) { const f = pending; pending = null; f(id || ''); }
}
ipcMain.on('bt-select', (_e, id) => finish(id));

function createWindow() {
  const win = new BrowserWindow({
    width: 520, height: 860, autoHideMenuBar: true, backgroundColor: '#0f1115',
    webPreferences: { contextIsolation: true, preload: path.join(__dirname, 'preload.js') }
  });

  // Electron has no built-in Bluetooth picker. It fires this event with the list of devices
  // found so far (again whenever the list changes); we forward it to the page's own scan list.
  win.webContents.on('select-bluetooth-device', (event, devices, callback) => {
    event.preventDefault();
    pending = callback;
    win.webContents.send('bt-devices', devices.map(d => ({ id: d.deviceId, name: d.deviceName || '' })));
    clearTimeout(scanTimer);
    scanTimer = setTimeout(() => finish(''), 45000);
  });

  win.loadFile(path.join(__dirname, 'index.html'));
}

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
