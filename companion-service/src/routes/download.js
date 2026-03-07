import { Router } from 'express';
import { downloadAsWav, deleteDownload } from '../services/downloader.js';
import path from 'path';

const router = Router();

// POST /api/download — trigger download, return metadata
router.post('/', async (req, res, next) => {
    try {
        const { videoId } = req.body;
        if (!videoId) {
            return res.status(400).json({ error: 'Missing videoId' });
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

// GET /api/download/:videoId — serve the WAV file
router.get('/:videoId', (req, res) => {
    const downloadDir = process.env.DOWNLOAD_DIR || './downloads';
    const filePath = path.resolve(path.join(downloadDir, `${req.params.videoId}.mp3`));
    res.sendFile(filePath);
});

// DELETE /api/download/:videoId — delete the downloaded file
router.delete('/:videoId', (req, res) => {
    const downloadDir = process.env.DOWNLOAD_DIR || './downloads';
    const deleted = deleteDownload(req.params.videoId, downloadDir);
    if (deleted) {
        res.json({ success: true, videoId: req.params.videoId });
    } else {
        res.status(404).json({ error: 'File not found' });
    }
});

export default router;
