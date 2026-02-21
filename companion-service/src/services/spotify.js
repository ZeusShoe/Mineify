import fs from 'fs';
import path from 'path';
import crypto from 'crypto';

const SPOTIFY_SCOPES = 'playlist-read-private playlist-read-collaborative';
const TOKEN_STORE_PATH = process.env.SPOTIFY_TOKEN_STORE || './spotify_tokens.json';
const REDIRECT_URI = process.env.SPOTIFY_REDIRECT_URI || 'http://localhost:3001/api/spotify/callback';
const AUTH_STATE_TTL_MS = 10 * 60 * 1000;

const spotifyTokensByPlayer = new Map();
const authStateById = new Map();

class SpotifyAuthRequiredError extends Error {
    constructor(message, authUrl) {
        super(message);
        this.name = 'SpotifyAuthRequiredError';
        this.authUrl = authUrl;
    }
}

function loadTokenStore() {
    if (!fs.existsSync(TOKEN_STORE_PATH)) {
        return;
    }
    try {
        const raw = fs.readFileSync(TOKEN_STORE_PATH, 'utf8');
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== 'object') {
            return;
        }
        for (const [playerId, value] of Object.entries(parsed)) {
            if (!value || typeof value !== 'object') {
                continue;
            }
            spotifyTokensByPlayer.set(playerId, {
                accessToken: value.accessToken || '',
                refreshToken: value.refreshToken || '',
                expiresAtMs: Number(value.expiresAtMs || 0),
                scope: value.scope || '',
                spotifyUserId: value.spotifyUserId || '',
                spotifyDisplayName: value.spotifyDisplayName || ''
            });
        }
    } catch (err) {
        console.warn('Failed to load Spotify token store:', err.message);
    }
}

function saveTokenStore() {
    const out = {};
    for (const [playerId, token] of spotifyTokensByPlayer.entries()) {
        out[playerId] = token;
    }
    fs.writeFileSync(TOKEN_STORE_PATH, JSON.stringify(out, null, 2), 'utf8');
}

function extractPlaylistId(spotifyUrl) {
    if (!spotifyUrl) {
        return null;
    }

    const url = spotifyUrl.trim();
    const uriMatch = url.match(/^spotify:playlist:([a-zA-Z0-9]+)$/);
    if (uriMatch) {
        return uriMatch[1];
    }

    try {
        const parsed = new URL(url);
        const host = parsed.hostname.toLowerCase();
        if (host === 'open.spotify.com' || host === 'play.spotify.com') {
            const segments = parsed.pathname.split('/').filter(Boolean);
            const playlistIndex = segments.indexOf('playlist');
            if (playlistIndex >= 0 && playlistIndex + 1 < segments.length) {
                const candidate = segments[playlistIndex + 1];
                if (/^[a-zA-Z0-9]+$/.test(candidate)) {
                    return candidate;
                }
            }
        }
    } catch (_) {
        // Fallback to regex for non-URL strings.
    }

    const webMatch = url.match(/spotify\.com\/(?:intl-[^/]+\/)?playlist\/([a-zA-Z0-9]+)/i);
    if (webMatch) {
        return webMatch[1];
    }

    return null;
}

function requireClientCredentials() {
    const clientId = process.env.SPOTIFY_CLIENT_ID;
    const clientSecret = process.env.SPOTIFY_CLIENT_SECRET;
    if (!clientId || !clientSecret) {
        throw new Error('Spotify credentials missing. Set SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET.');
    }
    return { clientId, clientSecret };
}

function buildAuthUrl(playerId, stateId) {
    const { clientId } = requireClientCredentials();
    const params = new URLSearchParams({
        response_type: 'code',
        client_id: clientId,
        scope: SPOTIFY_SCOPES,
        redirect_uri: REDIRECT_URI,
        state: stateId,
        show_dialog: 'true'
    });
    return `https://accounts.spotify.com/authorize?${params.toString()}`;
}

function createAuthState(playerId) {
    const stateId = crypto.randomBytes(18).toString('hex');
    authStateById.set(stateId, {
        playerId,
        createdAtMs: Date.now()
    });
    return stateId;
}

function consumeAuthState(stateId) {
    const value = authStateById.get(stateId);
    authStateById.delete(stateId);
    if (!value) {
        return null;
    }
    if (Date.now() - value.createdAtMs > AUTH_STATE_TTL_MS) {
        return null;
    }
    return value;
}

function cleanupAuthStates() {
    const now = Date.now();
    for (const [stateId, state] of authStateById.entries()) {
        if (now - state.createdAtMs > AUTH_STATE_TTL_MS) {
            authStateById.delete(stateId);
        }
    }
}

async function exchangeAuthCodeForToken(code) {
    const { clientId, clientSecret } = requireClientCredentials();
    const auth = Buffer.from(`${clientId}:${clientSecret}`).toString('base64');
    const body = new URLSearchParams({
        grant_type: 'authorization_code',
        code,
        redirect_uri: REDIRECT_URI
    });

    const response = await fetch('https://accounts.spotify.com/api/token', {
        method: 'POST',
        headers: {
            Authorization: `Basic ${auth}`,
            'Content-Type': 'application/x-www-form-urlencoded'
        },
        body: body.toString()
    });
    const payloadText = await response.text();
    let payload = {};
    try {
        payload = JSON.parse(payloadText);
    } catch (_) {
        // Keep empty payload.
    }
    if (!response.ok) {
        throw new Error(`Spotify auth token exchange failed with status ${response.status}: ${payloadText}`);
    }
    return payload;
}

async function refreshAccessToken(playerId, refreshToken) {
    const { clientId, clientSecret } = requireClientCredentials();
    const auth = Buffer.from(`${clientId}:${clientSecret}`).toString('base64');
    const body = new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: refreshToken
    });

    const response = await fetch('https://accounts.spotify.com/api/token', {
        method: 'POST',
        headers: {
            Authorization: `Basic ${auth}`,
            'Content-Type': 'application/x-www-form-urlencoded'
        },
        body: body.toString()
    });
    const payloadText = await response.text();
    let payload = {};
    try {
        payload = JSON.parse(payloadText);
    } catch (_) {
        // Keep empty payload.
    }
    if (!response.ok) {
        throw new Error(`Spotify refresh token failed with status ${response.status}: ${payloadText}`);
    }

    const existing = spotifyTokensByPlayer.get(playerId) || {};
    const expiresInSec = Number(payload.expires_in || 3600);
    spotifyTokensByPlayer.set(playerId, {
        ...existing,
        accessToken: payload.access_token || '',
        refreshToken: payload.refresh_token || existing.refreshToken || '',
        expiresAtMs: Date.now() + Math.max(60, expiresInSec - 30) * 1000,
        scope: payload.scope || existing.scope || '',
        spotifyUserId: existing.spotifyUserId || '',
        spotifyDisplayName: existing.spotifyDisplayName || ''
    });
    saveTokenStore();
    return spotifyTokensByPlayer.get(playerId).accessToken;
}

async function fetchSpotifyMe(accessToken) {
    const response = await fetch('https://api.spotify.com/v1/me', {
        headers: {
            Authorization: `Bearer ${accessToken}`,
            Accept: 'application/json'
        }
    });
    const payloadText = await response.text();
    let payload = {};
    try {
        payload = JSON.parse(payloadText);
    } catch (_) {
        // Keep empty payload.
    }
    if (!response.ok) {
        throw new Error(`Spotify /me request failed with status ${response.status}: ${payloadText}`);
    }
    return payload;
}

async function getPlayerAccessToken(playerId) {
    const token = spotifyTokensByPlayer.get(playerId);
    if (!token || !token.accessToken) {
        const stateId = createAuthState(playerId);
        throw new SpotifyAuthRequiredError('Spotify account not linked for this player.', buildAuthUrl(playerId, stateId));
    }

    if (token.expiresAtMs > Date.now() + 15_000) {
        return token.accessToken;
    }

    if (!token.refreshToken) {
        const stateId = createAuthState(playerId);
        throw new SpotifyAuthRequiredError('Spotify session expired. Please reconnect.', buildAuthUrl(playerId, stateId));
    }

    try {
        return await refreshAccessToken(playerId, token.refreshToken);
    } catch (_) {
        const stateId = createAuthState(playerId);
        throw new SpotifyAuthRequiredError('Spotify session refresh failed. Please reconnect.', buildAuthUrl(playerId, stateId));
    }
}

async function fetchPlaylistTracksPage(playlistId, accessToken, offset) {
    const endpoint = `https://api.spotify.com/v1/playlists/${playlistId}/tracks?limit=100&offset=${offset}`;
    const response = await fetch(endpoint, {
        headers: {
            Authorization: `Bearer ${accessToken}`,
            Accept: 'application/json'
        }
    });

    if (!response.ok) {
        const body = await response.text();
        throw new Error(`Spotify playlist request failed with status ${response.status}: ${body}`);
    }
    return response.json();
}

async function fetchPlaylistMetadata(playlistId, accessToken) {
    const endpoint = `https://api.spotify.com/v1/playlists/${playlistId}`;
    const response = await fetch(endpoint, {
        headers: {
            Authorization: `Bearer ${accessToken}`,
            Accept: 'application/json'
        }
    });
    if (!response.ok) {
        const body = await response.text();
        throw new Error(`Spotify playlist metadata request failed with status ${response.status}: ${body}`);
    }
    return response.json();
}

export function getSpotifyAuthStartUrl(playerId) {
    if (!playerId || typeof playerId !== 'string' || !playerId.trim()) {
        throw new Error('Missing playerId');
    }
    cleanupAuthStates();
    const stateId = createAuthState(playerId.trim());
    return buildAuthUrl(playerId.trim(), stateId);
}

export async function completeSpotifyAuthCallback(code, state) {
    if (!code || !state) {
        throw new Error('Missing code or state');
    }
    const stateValue = consumeAuthState(state);
    if (!stateValue || !stateValue.playerId) {
        throw new Error('Invalid or expired Spotify auth state');
    }

    const tokenPayload = await exchangeAuthCodeForToken(code);
    const accessToken = tokenPayload.access_token || '';
    const refreshToken = tokenPayload.refresh_token || '';
    const expiresInSec = Number(tokenPayload.expires_in || 3600);
    if (!accessToken || !refreshToken) {
        throw new Error('Spotify auth response missing access or refresh token');
    }

    const me = await fetchSpotifyMe(accessToken);
    spotifyTokensByPlayer.set(stateValue.playerId, {
        accessToken,
        refreshToken,
        expiresAtMs: Date.now() + Math.max(60, expiresInSec - 30) * 1000,
        scope: tokenPayload.scope || '',
        spotifyUserId: me?.id || '',
        spotifyDisplayName: me?.display_name || me?.id || 'Spotify User'
    });
    saveTokenStore();
    return {
        playerId: stateValue.playerId,
        spotifyUserId: me?.id || '',
        spotifyDisplayName: me?.display_name || me?.id || 'Spotify User'
    };
}

export async function getSpotifyPlaylistTracks(spotifyUrl, playerId) {
    const playlistId = extractPlaylistId(spotifyUrl);
    if (!playlistId) {
        throw new Error('Invalid Spotify playlist URL');
    }
    if (!playerId || typeof playerId !== 'string' || !playerId.trim()) {
        throw new Error('Missing playerId for Spotify import');
    }

    cleanupAuthStates();
    const accessToken = await getPlayerAccessToken(playerId.trim());
    const metadata = await fetchPlaylistMetadata(playlistId, accessToken);
    const tracks = [];
    let offset = 0;
    let total = 0;

    do {
        const page = await fetchPlaylistTracksPage(playlistId, accessToken, offset);
        total = page.total || 0;
        const items = page.items || [];

        for (const item of items) {
            const track = item?.track;
            if (!track || !track.name) {
                continue;
            }

            const artists = Array.isArray(track.artists)
                ? track.artists.map(artist => artist?.name).filter(Boolean)
                : [];
            const artistText = artists.join(', ');
            tracks.push({
                spotifyTrackId: track.id || '',
                title: track.name,
                artist: artistText,
                query: artistText ? `${track.name} ${artistText}` : track.name,
                duration: track.duration_ms ? `${Math.floor(track.duration_ms / 60000)}:${String(Math.floor((track.duration_ms % 60000) / 1000)).padStart(2, '0')}` : ''
            });
        }

        offset += items.length;
        if (items.length === 0) {
            break;
        }
    } while (offset < total);

    return {
        playlistId,
        playlistName: metadata?.name || 'Imported Playlist',
        ownerDisplayName: metadata?.owner?.display_name || 'Spotify User',
        trackCount: tracks.length,
        tracks
    };
}

export { SpotifyAuthRequiredError };

loadTokenStore();
