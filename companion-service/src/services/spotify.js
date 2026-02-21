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

async function getSpotifyAccessToken() {
    const clientId = process.env.SPOTIFY_CLIENT_ID;
    const clientSecret = process.env.SPOTIFY_CLIENT_SECRET;
    if (!clientId || !clientSecret) {
        throw new Error('Spotify credentials missing. Set SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET.');
    }

    const auth = Buffer.from(`${clientId}:${clientSecret}`).toString('base64');
    const response = await fetch('https://accounts.spotify.com/api/token', {
        method: 'POST',
        headers: {
            Authorization: `Basic ${auth}`,
            'Content-Type': 'application/x-www-form-urlencoded'
        },
        body: 'grant_type=client_credentials'
    });
    if (!response.ok) {
        throw new Error(`Spotify access token request failed with status ${response.status}`);
    }

    const payload = await response.json();
    const token = payload?.access_token || payload?.accessToken || null;
    if (!token) {
        throw new Error('Spotify access token missing in response');
    }
    return token;
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

export async function getSpotifyPlaylistTracks(spotifyUrl) {
    const playlistId = extractPlaylistId(spotifyUrl);
    if (!playlistId) {
        throw new Error('Invalid Spotify playlist URL');
    }

    const accessToken = await getSpotifyAccessToken();
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
