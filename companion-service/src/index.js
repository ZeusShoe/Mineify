import express from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import fs from 'fs';

import searchRouter from './routes/search.js';
import downloadRouter from './routes/download.js';
import spotifyRouter from './routes/spotify.js';

// Load environment variables
dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3001;

// Middleware
app.use(cors({
    origin: process.env.ALLOWED_ORIGINS
        ? process.env.ALLOWED_ORIGINS.split(',')
        : true
}));
app.use(express.json());

// Request logging
app.use((req, res, next) => {
    console.log(`[${new Date().toISOString()}] ${req.method} ${req.path}`);
    next();
});

// Ensure download directory exists
const downloadDir = process.env.DOWNLOAD_DIR || './downloads';
if (!fs.existsSync(downloadDir)) {
    fs.mkdirSync(downloadDir, { recursive: true });
    console.log(`Created download directory: ${downloadDir}`);
}

// Health check endpoint
app.get('/api/health', (req, res) => {
    res.json({
        status: 'ok',
        version: '1.0.0',
        timestamp: new Date().toISOString()
    });
});

// API Routes
app.use('/api/search', searchRouter);
app.use('/api/download', downloadRouter);
app.use('/api/spotify', spotifyRouter);

// Error handling middleware
app.use((err, req, res, next) => {
    console.error('Error:', err.message);
    res.status(500).json({
        error: err.message || 'Internal server error'
    });
});

// 404 handler
app.use((req, res) => {
    res.status(404).json({
        error: 'Not found'
    });
});

// Start server
app.listen(PORT, () => {
    console.log(`
╔═══════════════════════════════════════════════════╗
║          Mineify Companion Service                ║
║                                                   ║
║  Server running on http://localhost:${PORT}          ║
║  Download directory: ${downloadDir.padEnd(26)}║
║                                                   ║
║  Endpoints:                                       ║
║    GET  /api/health            - Health check      ║
║    GET  /api/search?q=...      - YouTube search    ║
║    POST /api/download          - Download audio    ║
║    GET  /api/download/:videoId - Serve audio file  ║
╚═══════════════════════════════════════════════════╝
    `);
});

// Cleanup old downloads periodically
const cleanupAgeHours = parseInt(process.env.CLEANUP_AGE_HOURS) || 24;
setInterval(() => {
    const cutoffTime = Date.now() - (cleanupAgeHours * 60 * 60 * 1000);

    fs.readdir(downloadDir, (err, files) => {
        if (err) return;

        files.forEach(file => {
            const filePath = join(downloadDir, file);
            fs.stat(filePath, (err, stats) => {
                if (err) return;
                if (stats.mtimeMs < cutoffTime) {
                    fs.unlink(filePath, () => {
                        console.log(`Cleaned up old file: ${file}`);
                    });
                }
            });
        });
    });
}, 60 * 60 * 1000);
