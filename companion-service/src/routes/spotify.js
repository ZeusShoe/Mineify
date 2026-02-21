import { Router } from 'express';
import {
    completeSpotifyAuthCallback,
    getSpotifyAuthStartUrl,
    getSpotifyPlaylistTracks,
    SpotifyAuthRequiredError
} from '../services/spotify.js';

const router = Router();

router.get('/playlist', async (req, res, next) => {
    try {
        const { url, playerId } = req.query;
        if (!url) {
            return res.status(400).json({ error: 'Missing query parameter url' });
        }
        if (!playerId) {
            return res.status(400).json({ error: 'Missing query parameter playerId' });
        }

        const result = await getSpotifyPlaylistTracks(url, playerId);
        res.json(result);
    } catch (err) {
        if (err instanceof SpotifyAuthRequiredError) {
            return res.status(401).json({
                error: err.message,
                authRequired: true,
                authUrl: err.authUrl
            });
        }
        next(err);
    }
});

router.get('/auth/start', async (req, res, next) => {
    try {
        const { playerId } = req.query;
        if (!playerId) {
            return res.status(400).json({ error: 'Missing query parameter playerId' });
        }
        const authUrl = getSpotifyAuthStartUrl(playerId);
        res.redirect(authUrl);
    } catch (err) {
        next(err);
    }
});

router.get('/callback', async (req, res) => {
    try {
        const { code, state } = req.query;
        const result = await completeSpotifyAuthCallback(code, state);
        const safePlayerId = String(result.playerId || '').replace(/[<>]/g, '');
        const safeName = String(result.spotifyDisplayName || 'Spotify User').replace(/[<>]/g, '');
        res.status(200).send(`<!doctype html>
<html>
<head><meta charset="utf-8"><title>Mineify Spotify Connected</title></head>
<body style="font-family:Arial,sans-serif;background:#111;color:#eee;padding:24px;">
  <h2>Spotify connected successfully</h2>
  <p>Player <strong>${safePlayerId}</strong> is now linked to Spotify account <strong>${safeName}</strong>.</p>
  <p>You can close this tab and return to Minecraft.</p>
</body>
</html>`);
    } catch (err) {
        res.status(400).send(`<!doctype html>
<html>
<head><meta charset="utf-8"><title>Mineify Spotify Link Failed</title></head>
<body style="font-family:Arial,sans-serif;background:#111;color:#eee;padding:24px;">
  <h2>Spotify link failed</h2>
  <p>${String(err.message || 'Unknown error').replace(/[<>]/g, '')}</p>
  <p>Return to Minecraft and try the link command again.</p>
</body>
</html>`);
    }
});

export default router;
