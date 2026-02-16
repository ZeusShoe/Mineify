function extractPlaylistId(spotifyUrl) {
    if (!spotifyUrl) {
        return null;
    }

    const url = spotifyUrl.trim();
    const uriMatch = url.match(/^spotify:playlist:([a-zA-Z0-9]+)$/);
    if (uriMatch) {
        return uriMatch[1];
    }

    const webMatch = url.match(/spotify\.com\/playlist\/([a-zA-Z0-9]+)/);
    if (webMatch) {
        return webMatch[1];
    }

    return null;
}

async function getSpotifyAccessToken() {
    const response = await fetch('https://open.spotify.com/get_access_token?reason=transport&productType=web_player');
    if (!response.ok) {
        throw new Error(`Spotify access token request failed with status ${response.status}`);
    }

    const payload = await response.json();
    if (!payload || !payload.accessToken) {
        throw new Error('Spotify access token missing in response');
    }
    return payload.accessToken;
}

async function fetchPlaylistTracksPage(playlistId, accessToken, offset) {
    const endpoint = `https://api.spotify.com/v1/playlists/${playlistId}/tracks?limit=100&offset=${offset}`;
    const response = await fetch(endpoint, {
        headers: {
            Authorization: `Bearer ${accessToken}`
        }
    });

    if (!response.ok) {
        throw new Error(`Spotify playlist request failed with status ${response.status}`);
    }
    return response.json();
}

export async function getSpotifyPlaylistTracks(spotifyUrl) {
    const playlistId = extractPlaylistId(spotifyUrl);
    if (!playlistId) {
        throw new Error('Invalid Spotify playlist URL');
    }

    const accessToken = await getSpotifyAccessToken();
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
                query: artistText ? `${track.name} ${artistText}` : track.name
            });
        }

        offset += items.length;
        if (items.length === 0) {
            break;
        }
    } while (offset < total);

    return {
        playlistId,
        trackCount: tracks.length,
        tracks
    };
}
