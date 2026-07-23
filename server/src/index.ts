import express from 'express';
import multer from 'multer';
import cors from 'cors';
import path from 'path';
import fs from 'fs';
import crypto from 'crypto';
import sqlite3 from 'sqlite3';
import dotenv from 'dotenv';
import { Device, SyncLog, StorageInfo } from './types';

dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;
const UPLOAD_DIR = process.env.UPLOAD_DIR || path.join(__dirname, '../../uploads');
const DB_PATH = process.env.DB_PATH || path.join(__dirname, '../../data/qsyncphoto.db');

// Ensure directories exist
if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
}
const dbDir = path.dirname(DB_PATH);
if (!fs.existsSync(dbDir)) {
  fs.mkdirSync(dbDir, { recursive: true });
}

// Database setup
const db = new sqlite3.Database(DB_PATH);

db.serialize(() => {
  db.run(`
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      token TEXT NOT NULL UNIQUE,
      lastSync TEXT,
      createdAt TEXT NOT NULL
    )
  `);

  db.run(`
    CREATE TABLE IF NOT EXISTS sync_logs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      deviceId TEXT NOT NULL,
      fileName TEXT NOT NULL,
      fileSize INTEGER NOT NULL,
      filePath TEXT NOT NULL,
      md5 TEXT NOT NULL,
      timestamp TEXT NOT NULL,
      FOREIGN KEY(deviceId) REFERENCES devices(id) ON DELETE CASCADE
    )
  `);
});

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, '../public')));
app.use('/photos', express.static(UPLOAD_DIR));

// Temporary Pairing Codes (code -> { deviceName, expires })
const pairingCodes = new Map<string, { expires: number }>();

// Helper to clean expired pairing codes
setInterval(() => {
  const now = Date.now();
  for (const [code, data] of pairingCodes.entries()) {
    if (data.expires < now) {
      pairingCodes.delete(code);
    }
  }
}, 30000);

// Multer Storage Configuration
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    cb(null, UPLOAD_DIR);
  },
  filename: (req, file, cb) => {
    // Save files with a unique name prefix to avoid collision, but preserve original ext
    const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
    const ext = path.extname(file.originalname);
    cb(null, `${uniqueSuffix}${ext}`);
  }
});

const upload = multer({ storage });

// Helper: Calculate File MD5
function calculateMD5(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('md5');
    const stream = fs.createReadStream(filePath);
    stream.on('data', (data) => hash.update(data));
    stream.on('end', () => resolve(hash.digest('hex')));
    stream.on('error', (err) => reject(err));
  });
});

// Authentication middleware
const authenticateDevice = (req: express.Request, res: express.Response, next: express.NextFunction) => {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Missing or invalid authorization header' });
  }
  const token = authHeader.substring(7);
  
  db.get('SELECT * FROM devices WHERE token = ?', [token], (err, row) => {
    if (err || !row) {
      return res.status(401).json({ error: 'Unauthorized device' });
    }
    res.locals.authenticatedDevice = row;
    next();
  });
};

// API Endpoints

// 1. Get pairing code (for Web UI)
app.get('/api/auth/pairing-code', (req, res) => {
  const code = crypto.randomBytes(3).toString('hex').toUpperCase(); // 6 character code
  const expires = Date.now() + 5 * 60 * 1000; // 5 minutes validity
  pairingCodes.set(code, { expires });
  res.json({ code, expires });
});

// 2. Client registration using pairing code (from Android)
app.post('/api/auth/pair', (req, res) => {
  const { code, deviceName } = req.body;
  if (!code || !deviceName) {
    return res.status(400).json({ error: 'Missing code or deviceName' });
  }

  const pData = pairingCodes.get(code.toUpperCase());
  if (!pData) {
    return res.status(400).json({ error: 'Invalid or expired pairing code' });
  }

  if (pData.expires < Date.now()) {
    pairingCodes.delete(code.toUpperCase());
    return res.status(400).json({ error: 'Pairing code expired' });
  }

  // Remove code after use
  pairingCodes.delete(code.toUpperCase());

  const deviceId = crypto.randomUUID();
  const deviceToken = crypto.randomBytes(32).toString('hex');
  const now = new Date().toISOString();

  db.run(
    'INSERT INTO devices (id, name, token, createdAt) VALUES (?, ?, ?, ?)',
    [deviceId, deviceName, deviceToken, now],
    (err) => {
      if (err) {
        return res.status(500).json({ error: 'Failed to register device' });
      }
      res.json({ token: deviceToken, deviceId });
    }
  );
});

// 3. Upload photo (from Android)
app.post('/api/upload', authenticateDevice, upload.single('photo'), async (req, res) => {
  const device = res.locals.authenticatedDevice as Device;
  if (!req.file) {
    return res.status(400).json({ error: 'No file uploaded' });
  }

  const clientMD5 = req.headers['x-file-md5'] as string;
  const clientDate = req.headers['x-file-date'] as string || new Date().toISOString();
  const filePath = req.file.path;
  const fileName = req.file.originalname;
  const fileSize = req.file.size;

  try {
    const serverMD5 = await calculateMD5(filePath);
    
    // MD5 verification if provided by client
    if (clientMD5 && clientMD5.toLowerCase() !== serverMD5.toLowerCase()) {
      fs.unlinkSync(filePath); // delete mismatched file
      return res.status(400).json({ error: 'MD5 checksum mismatch. File might be corrupted.' });
    }

    const now = new Date().toISOString();
    
    db.serialize(() => {
      // Insert log
      db.run(
        'INSERT INTO sync_logs (deviceId, fileName, fileSize, filePath, md5, timestamp) VALUES (?, ?, ?, ?, ?, ?)',
        [device.id, fileName, fileSize, path.basename(filePath), serverMD5, clientDate]
      );
      
      // Update device lastSync
      db.run(
        'UPDATE devices SET lastSync = ? WHERE id = ?',
        [now, device.id]
      );
    });

    res.json({ success: true, fileName: req.file.filename });
  } catch (error) {
    if (fs.existsSync(filePath)) fs.unlinkSync(filePath);
    res.status(500).json({ error: 'Server error during upload processing' });
  }
});

// 4. Check if file is already synced (MD5 lookup, returns true/false)
app.post('/api/sync-check', authenticateDevice, (req, res) => {
  const { md5s } = req.body; // Array of MD5 strings to check
  if (!md5s || !Array.isArray(md5s)) {
    return res.status(400).json({ error: 'Missing or invalid md5s list' });
  }

  if (md5s.length === 0) {
    return res.json({ synced: [] });
  }

  // Parameterize placeholders
  const placeholders = md5s.map(() => '?').join(',');
  db.all(
    `SELECT md5 FROM sync_logs WHERE md5 IN (${placeholders})`,
    md5s,
    (err, rows) => {
      if (err) {
        return res.status(500).json({ error: 'Database error' });
      }
      const syncedMd5s = rows.map((r: any) => r.md5);
      res.json({ synced: syncedMd5s });
    }
  );
});

// 5. Get system status / stats (for Web UI)
app.get('/api/status', async (req, res) => {
  try {
    let totalFiles = 0;
    let totalDevices = 0;

    const countFiles = () => new Promise<number>((resolve) => {
      db.get('SELECT COUNT(*) as count FROM sync_logs', (err, row: any) => {
        resolve(row ? row.count : 0);
      });
    });

    const countDevices = () => new Promise<number>((resolve) => {
      db.get('SELECT COUNT(*) as count FROM devices', (err, row: any) => {
        resolve(row ? row.count : 0);
      });
    });

    totalFiles = await countFiles();
    totalDevices = await countDevices();

    // Get disk space info using statfsSync (Node v18.9.0+)
    let storage: StorageInfo = { total: 0, free: 0, used: 0, percentUsed: 0 };
    try {
      if (typeof fs.statfsSync === 'function') {
        const stats = fs.statfsSync(UPLOAD_DIR);
        const total = stats.bsize * stats.blocks;
        const free = stats.bsize * stats.bavail;
        const used = total - free;
        storage = {
          total,
          free,
          used,
          percentUsed: total > 0 ? Math.round((used / total) * 100) : 0
        };
      } else {
        // Fallback mock space in case statfsSync is not available
        storage = { total: 100 * 1024 * 1024 * 1024, free: 60 * 1024 * 1024 * 1024, used: 40 * 1024 * 1024 * 1024, percentUsed: 40 };
      }
    } catch (e) {
      storage = { total: 100 * 1024 * 1024 * 1024, free: 60 * 1024 * 1024 * 1024, used: 40 * 1024 * 1024 * 1024, percentUsed: 40 };
    }

    res.json({
      totalFiles,
      totalDevices,
      storage
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch status' });
  }
});

// 6. List devices (for Web UI)
app.get('/api/devices', (req, res) => {
  db.all('SELECT id, name, lastSync, createdAt FROM devices ORDER BY name ASC', (err, rows) => {
    if (err) {
      return res.status(500).json({ error: 'Database error' });
    }
    res.json(rows);
  });
});

// 7. Delete device (for Web UI)
app.delete('/api/devices/:id', (req, res) => {
  const { id } = req.params;
  db.run('DELETE FROM devices WHERE id = ?', [id], function(err) {
    if (err) {
      return res.status(500).json({ error: 'Database error' });
    }
    res.json({ success: true, changes: this.changes });
  });
});

// 8. List photos (for Web UI) - sorted chronologically, newest first
app.get('/api/photos', (req, res) => {
  const page = parseInt(req.query.page as string) || 1;
  const limit = parseInt(req.query.limit as string) || 50;
  const offset = (page - 1) * limit;

  db.all(
    `SELECT s.id, s.fileName, s.fileSize, s.filePath, s.md5, s.timestamp, d.name as deviceName 
     FROM sync_logs s 
     LEFT JOIN devices d ON s.deviceId = d.id 
     ORDER BY s.timestamp DESC 
     LIMIT ? OFFSET ?`,
    [limit, offset],
    (err, rows) => {
      if (err) {
        return res.status(500).json({ error: 'Database error' });
      }
      res.json(rows);
    }
  );
});

// Start Server
app.listen(PORT, () => {
  console.log(`NAS QNAP PhotoSync Server running on port ${PORT}`);
  console.log(`Photos directory: ${UPLOAD_DIR}`);
});
