package com.mineify.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineify.Mineify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class CompanionClient {
    private final String baseUrl;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public CompanionClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newHttpClient();
    }

    public CompletableFuture<List<SearchResult>> search(String query) {
        String url = baseUrl + "/api/search?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    List<SearchResult> results = new ArrayList<>();
                    try {
                        JsonArray arr = gson.fromJson(response.body(), JsonArray.class);
                        for (var el : arr) {
                            JsonObject obj = el.getAsJsonObject();
                            results.add(new SearchResult(
                                    obj.has("videoId") ? obj.get("videoId").getAsString() : "",
                                    obj.has("title") ? obj.get("title").getAsString() : "",
                                    obj.has("channel") ? obj.get("channel").getAsString() : "",
                                    obj.has("duration") ? obj.get("duration").getAsString() : "",
                                    obj.has("thumbnail") ? obj.get("thumbnail").getAsString() : ""
                            ));
                        }
                    } catch (Exception e) {
                        Mineify.LOGGER.error("Failed to parse search results", e);
                    }
                    return results;
                })
                .exceptionally(e -> {
                    Mineify.LOGGER.error("Search request failed", e);
                    return new ArrayList<>();
                });
    }

    public CompletableFuture<SpotifyPlaylist> getSpotifyPlaylistTracks(String spotifyUrl) {
        String url = baseUrl + "/api/spotify/playlist?url=" + java.net.URLEncoder.encode(spotifyUrl, java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    List<SpotifyTrack> tracks = new ArrayList<>();
                    try {
                        JsonObject obj = gson.fromJson(response.body(), JsonObject.class);
                        String playlistId = obj.has("playlistId") ? obj.get("playlistId").getAsString() : "";
                        String playlistName = obj.has("playlistName") ? obj.get("playlistName").getAsString() : "Imported Playlist";
                        String ownerName = obj.has("ownerDisplayName") ? obj.get("ownerDisplayName").getAsString() : "Spotify User";
                        JsonArray arr = obj.getAsJsonArray("tracks");
                        if (arr == null) {
                            return new SpotifyPlaylist(playlistId, playlistName, ownerName, tracks);
                        }
                        for (var el : arr) {
                            JsonObject trackObj = el.getAsJsonObject();
                            tracks.add(new SpotifyTrack(
                                    trackObj.has("spotifyTrackId") ? trackObj.get("spotifyTrackId").getAsString() : "",
                                    trackObj.has("title") ? trackObj.get("title").getAsString() : "",
                                    trackObj.has("artist") ? trackObj.get("artist").getAsString() : "",
                                    trackObj.has("query") ? trackObj.get("query").getAsString() : "",
                                    trackObj.has("duration") ? trackObj.get("duration").getAsString() : ""
                            ));
                        }
                        return new SpotifyPlaylist(playlistId, playlistName, ownerName, tracks);
                    } catch (Exception e) {
                        Mineify.LOGGER.error("Failed to parse Spotify playlist response", e);
                        return new SpotifyPlaylist("", "Imported Playlist", "Spotify User", tracks);
                    }
                })
                .exceptionally(e -> {
                    Mineify.LOGGER.error("Spotify playlist fetch failed", e);
                    return new SpotifyPlaylist("", "Imported Playlist", "Spotify User", new ArrayList<>());
                });
    }

    /**
     * Request the companion service to download a video as WAV.
     * Returns the full download URL that clients can fetch audio from.
     */
    public CompletableFuture<String> requestDownload(String videoId) {
        String url = baseUrl + "/api/download";
        String body = gson.toJson(java.util.Map.of("videoId", videoId));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        JsonObject obj = gson.fromJson(response.body(), JsonObject.class);
                        String downloadPath = obj.get("downloadUrl").getAsString();
                        return baseUrl + downloadPath;
                    } catch (Exception e) {
                        Mineify.LOGGER.error("Failed to parse download response", e);
                        return null;
                    }
                })
                .exceptionally(e -> {
                    Mineify.LOGGER.error("Download request failed for videoId: {}", videoId, e);
                    return null;
                });
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Request the companion service to delete a downloaded audio file.
     */
    public void deleteDownload(String videoId) {
        String url = baseUrl + "/api/download/" + videoId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .DELETE()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        Mineify.LOGGER.info("Deleted download for videoId: {}", videoId);
                    }
                })
                .exceptionally(e -> {
                    Mineify.LOGGER.warn("Failed to delete download for videoId: {}", videoId);
                    return null;
                });
    }

    public record SearchResult(String videoId, String title, String channel, String duration, String thumbnail) {}
    public record SpotifyTrack(String spotifyTrackId, String title, String artist, String query, String duration) {}
    public record SpotifyPlaylist(String playlistId, String playlistName, String ownerDisplayName, List<SpotifyTrack> tracks) {}
}
