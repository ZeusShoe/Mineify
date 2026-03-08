# AGENTS.md - Mineify Project Context

This file provides context for AI assistants (like Codex) working on the Mineify project.

## Project Overview

Mineify is a Minecraft Fabric mod that enables server-wide music playback using YouTube as a source. It consists of two main components:

1. **Fabric Mod (Java)** - The Minecraft mod itself
2. **Companion Service (Node.js)** - Backend for YouTube operations

## Architecture

```
Player (Client)                    Minecraft Server                 Companion Service
     │                                   │                                │
     │──── N Key ────►│               │                                │
     │                   │               │                                │
     │◄── Open GUI ─────│               │                                │
     │                   │               │                                │
     │──── Search ──────┼──────────────►│                                │
     │                   │               │────── HTTP Search ────────────►│
     │                   │               │◄───── Search Results ─────────│
     │◄── Results ──────┼───────────────│                                │
     │                   │               │                                │
     │──── Add Song ────┼──────────────►│                                │
     │                   │               │────── HTTP Download ──────────►│
     │                   │               │◄───── Audio File Path ────────│
     │                   │               │                                │
     │◄── Playback ─────┼───────────────│                                │
```

## Key Files

### Fabric Mod
- `fabric-mod/src/main/java/com/mineify/Mineify.java` - Main mod entry point
- `fabric-mod/src/client/java/com/mineify/MineifyClient.java` - Client entry point
- `fabric-mod/src/client/java/com/mineify/client/MineifyScreen.java` - Main GUI
- `fabric-mod/src/client/java/com/mineify/client/MineifyKeybinds.java` - M+N keybind
- `fabric-mod/src/main/java/com/mineify/server/PlaylistManager.java` - Server playlist state
- `fabric-mod/src/main/java/com/mineify/server/CompanionClient.java` - HTTP client
- `fabric-mod/src/main/java/com/mineify/network/` - Network packets

### Companion Service
- `companion-service/src/index.js` - Express server
- `companion-service/src/services/youtube.js` - YouTube search (uses youtube-search-api)
- `companion-service/src/services/downloader.js` - Audio download (uses yt-dlp)

## Development Commands

### Fabric Mod
```bash
cd fabric-mod
./gradlew build              # Build the mod JAR
./gradlew runClient          # Test in Minecraft client
./gradlew runServer          # Test server
```

### Companion Service
```bash
cd companion-service
npm install                  # Install dependencies
npm start                    # Start server
npm run dev                  # Start with hot reload
```

## Common Tasks for Codex

### Adding a New Packet Type
1. Create packet record in `network/packets/`
2. Register in `MineifyPackets.registerServerPackets()` or `registerClientPackets()`
3. Add handler in appropriate manager

### Modifying the GUI
- Main screen is `MineifyScreen.java`
- Uses Minecraft's widget system (ButtonWidget, TextFieldWidget)
- Render logic in `render()` method

### Adding New API Endpoints
1. Create/modify route in `companion-service/src/routes/`
2. Use service layer for business logic
3. Register route in `index.js`

## Known Limitations (TODO)

1. **Audio Streaming** - Currently tracks are downloaded but actual streaming to clients isn't fully implemented. Options:
   - Integrate with Simple Voice Chat mod
   - Use custom audio packet streaming
   - Have clients download files directly

2. **Playback Sync** - Basic timing-based sync. Could improve with:
   - Proper audio clock synchronization
   - Server-authoritative playback position

3. **Error Handling** - Need better user feedback for:
   - Companion service unavailable
   - Download failures
   - Invalid video IDs

## Tech Stack

- **Mod**: Fabric 0.17+, Minecraft 1.21.11, Java 21
- **Service**: Node.js 18+, Express, youtube-search-api, yt-dlp
- **Protocol**: Custom Minecraft packets (Fabric networking API)

## Useful Resources

- [Fabric Documentation](https://docs.fabricmc.net/)
- [Fabric Wiki - Networking](https://fabricmc.net/wiki/tutorial:networking)
- [youtube-search-api npm](https://www.npmjs.com/package/youtube-search-api)
- [yt-dlp GitHub](https://github.com/yt-dlp/yt-dlp)
