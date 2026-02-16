import { Router } from 'express';
import { getSpotifyPlaylistTracks } from '../services/spotify.js';

const router = Router();

router.get('/playlist', async (req, res, next) => {
    try {
        const { url } = req.query;
        if (!url) {
            return res.status(400).json({ error: 'Missing query parameter url' });
        }

        const result = await getSpotifyPlaylistTracks(url);
        res.json(result);
    } catch (err) {
        next(err);
    }
});

export default router;
