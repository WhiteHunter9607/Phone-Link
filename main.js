const { app, BrowserWindow } = require('electron');
const path = require('path');

function createWindow() {
  const win = new BrowserWindow({
    width: 520, height: 820, autoHideMenuBar: true, backgroundColor: '#0f1115',
    webPreferences: { contextIsolation: true }
  });

  // Electron has no built-in Bluetooth picker: auto-pick the first phone
  // advertising the PhoneLink service (the page filters by service UUID).
  let latest = [], cb = null, pickTimer = null, cancelTimer = null;
  const reset = () => { clearTimeout(pickTimer); clearTimeout(cancelTimer); pickTimer = cancelTimer = null; cb = null; latest = []; };

  win.webContents.on('select-bluetooth-device', (event, devices, callback) => {
    event.preventDefault();
    latest = devices; cb = callback;
    if (!cancelTimer) cancelTimer = setTimeout(() => { if (cb) cb(''); reset(); }, 15000);
    if (devices.length && !pickTimer) {
      pickTimer = setTimeout(() => {
        if (cb && latest.length) cb(latest[0].deviceId);
        reset();
      }, 1500);
    }
  });

  win.loadFile(path.join(__dirname, 'index.html'));
}

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
