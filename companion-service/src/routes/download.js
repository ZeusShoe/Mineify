import { Router } from 'express';
import { downloadAsWav, deleteDownload, isValidYouTubeVideoId } from '../services/downloader.js';
import path from 'path';
import fs from 'fs';

const router = Router();
const DOWNLOAD_FILE_EXT = '.wav';

function validateVideoIdParam(videoId) {
    return typeof videoId === 'string' && isValidYouTubeVideoId(videoId);
}

function resolveSafeDownloadFilePath(downloadDir, videoId) {
    const safeDir = path.resolve(downloadDir);
    const filePath = path.resolve(path.join(safeDir, `${videoId}${DOWNLOAD_FILE_EXT}`));
    const relative = path.relative(safeDir, filePath);
    if (relative.startsWith('..') || path.isAbsolute(relative)) {
        return null;
    }
    return filePath;
}

// POST /api/download - trigger download, return metadata
router.post('/', async (req, res, next) => {
    try {
        const { videoId } = req.body;
        if (!videoId) {
            return res.status(400).json({ error: 'Missing videoId' });
        }
        if (!validateVideoIdParam(videoId)) {
            return res.status(400).json({ error: 'Invalid videoId format' });
        }
        const downloadDir = process.env.DOWNLOAD_DIR || './downloads';
        await downloadAsWav(videoId, downloadDir);
        res.json({
            videoId,
            downloadUrl: `/api/download/${videoId}`
        });
    } catch (err) {
        next(err);
    }
});

// GET /api/download/:videoId - serve the WAV file
router.get('/:videoId', (req, res) => {
    if (!validateVideoIdParam(req.params.videoId)) {
        return res.status(400).json({ error: 'Invalid videoId format' });
    }
    const downloadDir = process.env.DOWNLOAD_DIR || './downloads';
    const filePath = resolveSafeDownloadFilePath(downloadDir, req.params.videoId);
    if (!filePath) {
        return res.status(400).json({ error: 'Invalid videoId format' });
    }
    if (!fs.existsSync(filePath)) {
        return res.status(404).json({ error: 'File not found' });
    }
    res.setHeader('Content-Type', 'audio/wav');
    res.sendFile(filePath);
});

// DELETE /api/download/:videoId - delete the downloaded file
router.delete('/:videoId', (req, res) => {
    if (!validateVideoIdParam(req.params.videoId)) {
        return res.status(400).json({ error: 'Invalid videoId format' });
    }
    const downloadDir = process.env.DOWNLOAD_DIR || './downloads';
    const deleted = deleteDownload(req.params.videoId, downloadDir);
    if (deleted) {
        res.json({ success: true, videoId: req.params.videoId });
    } else {
        res.status(404).json({ error: 'File not found' });
    }
});

export default router;
