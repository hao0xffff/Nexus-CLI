import { contextBridge, ipcRenderer } from 'electron';

// Expose protected methods that allow the renderer process to use
// the ipcRenderer without exposing the entire object
contextBridge.exposeInMainWorld('electronAPI', {
  getBackendUrl: () => ipcRenderer.invoke('get-backend-url'),
  getWsUrl: () => ipcRenderer.invoke('get-ws-url'),
  restartBackend: () => ipcRenderer.invoke('restart-backend'),
  getSystemInfo: () => ipcRenderer.invoke('get-system-info'),
  
  // Platform info
  platform: process.platform,
  
  // Window controls
  minimize: () => ipcRenderer.send('window-minimize'),
  maximize: () => ipcRenderer.send('window-maximize'),
  close: () => ipcRenderer.send('window-close'),
});

// Type declaration for the exposed API
declare global {
  interface Window {
    electronAPI: {
      getBackendUrl: () => Promise<string>;
      getWsUrl: () => Promise<string>;
      restartBackend: () => Promise<boolean>;
      getSystemInfo: () => Promise<SystemInfo | null>;
      platform: string;
      minimize: () => void;
      maximize: () => void;
      close: () => void;
    };
  }
}

interface SystemInfo {
  os: {
    type: string;
    name: string;
    version: string;
  };
  shell: {
    type: string;
    path: string;
    version: string;
  };
  encoding: string;
  user: {
    name: string;
    home: string;
  };
  cwd: string;
  activeSessions: number;
}

export {};
