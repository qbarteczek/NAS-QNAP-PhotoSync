export interface Device {
  id: string;
  name: string;
  token: string;
  lastSync: string | null;
  createdAt: string;
}

export interface SyncLog {
  id: number;
  deviceId: string;
  deviceName?: string;
  fileName: string;
  fileSize: number;
  filePath: string;
  md5: string;
  timestamp: string;
}

export interface StorageInfo {
  total: number;
  free: number;
  used: number;
  percentUsed: number;
}
