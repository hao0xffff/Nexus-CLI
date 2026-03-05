import { app, BrowserWindow, ipcMain } from 'electron';
import * as path from 'path';
import { spawn, ChildProcess } from 'child_process';
import * as fs from 'fs';
import * as http from 'http';

let mainWindow: BrowserWindow | null = null;
let backendProcess: ChildProcess | null = null;
let backendPort = 8080;

const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 800,
    minHeight: 600,
    backgroundColor: '#1a1b26',
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js'),
    },
    titleBarStyle: 'hiddenInset',
    frame: process.platform !== 'darwin',
  });

  if (isDev) {
    mainWindow.loadURL('http://localhost:5173');
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

function findJavaExecutable(): string {
  // Check JAVA_HOME first
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const javaPath = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(javaPath)) {
      return javaPath;
    }
  }
  // Fall back to system Java
  return 'java';
}

function findBackendJar(): string | null {
  const possiblePaths = [
    // Development path
    path.join(__dirname, '../../..', 'backend/target/ai-terminal-backend-1.0.0-SNAPSHOT.jar'),
    // Production path (in resources)
    path.join(process.resourcesPath, 'backend/ai-terminal-backend.jar'),
  ];

  for (const jarPath of possiblePaths) {
    if (fs.existsSync(jarPath)) {
      console.log('Found backend JAR at:', jarPath);
      return jarPath;
    }
  }

  console.error('Backend JAR not found in any of:', possiblePaths);
  return null;
}

async function startBackend(): Promise<boolean> {
  const jarPath = findBackendJar();
  if (!jarPath) {
    console.error('Backend JAR not found');
    return false;
  }

  const javaPath = findJavaExecutable();
  console.log('Starting backend with Java:', javaPath);
  console.log('JAR path:', jarPath);

  return new Promise((resolve) => {
    backendProcess = spawn(javaPath, [
      '-jar',
      jarPath,
      `--server.port=${backendPort}`,
    ], {
      cwd: path.dirname(jarPath),
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    backendProcess.stdout?.on('data', (data) => {
      console.log('[Backend]', data.toString());
    });

    backendProcess.stderr?.on('data', (data) => {
      console.error('[Backend Error]', data.toString());
    });

    backendProcess.on('error', (err) => {
      console.error('Failed to start backend:', err);
      resolve(false);
    });

    backendProcess.on('exit', (code) => {
      console.log('Backend process exited with code:', code);
      backendProcess = null;
    });

    // Wait for backend to be ready
    waitForBackend(30000).then(resolve);
  });
}

async function waitForBackend(timeout: number): Promise<boolean> {
  const startTime = Date.now();
  const healthUrl = `http://localhost:${backendPort}/api/health`;

  while (Date.now() - startTime < timeout) {
    try {
      const isReady = await checkHealth(healthUrl);
      if (isReady) {
        console.log('Backend is ready');
        return true;
      }
    } catch {
      // Ignore errors, keep trying
    }
    await new Promise(resolve => setTimeout(resolve, 500));
  }

  console.error('Backend failed to start within timeout');
  return false;
}

function checkHealth(url: string): Promise<boolean> {
  return new Promise((resolve) => {
    const req = http.get(url, (res) => {
      resolve(res.statusCode === 200);
    });
    req.on('error', () => resolve(false));
    req.setTimeout(1000, () => {
      req.destroy();
      resolve(false);
    });
  });
}

function stopBackend() {
  if (backendProcess) {
    console.log('Stopping backend process...');
    if (process.platform === 'win32') {
      spawn('taskkill', ['/pid', backendProcess.pid!.toString(), '/f', '/t']);
    } else {
      backendProcess.kill('SIGTERM');
    }
    backendProcess = null;
  }
}

// IPC handlers
ipcMain.handle('get-backend-url', () => {
  return `http://localhost:${backendPort}`;
});

ipcMain.handle('get-ws-url', () => {
  return `ws://localhost:${backendPort}/ws/terminal`;
});

ipcMain.handle('restart-backend', async () => {
  stopBackend();
  return await startBackend();
});

ipcMain.handle('get-system-info', async () => {
  try {
    const response = await fetch(`http://localhost:${backendPort}/api/system-info`);
    return await response.json();
  } catch {
    return null;
  }
});

// App lifecycle
app.whenReady().then(async () => {
  console.log('App is ready, starting backend...');
  
  const backendStarted = await startBackend();
  if (!backendStarted) {
    console.warn('Backend did not start, but continuing anyway for development');
  }

  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    stopBackend();
    app.quit();
  }
});

app.on('before-quit', () => {
  stopBackend();
});

// Handle certificate errors in development
app.on('certificate-error', (event, webContents, url, error, certificate, callback) => {
  if (isDev) {
    event.preventDefault();
    callback(true);
  } else {
    callback(false);
  }
});
