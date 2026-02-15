package com.mineify.client;

import com.mineify.MineifyClient;
import com.mineify.client.audio.AudioPlayer;
import com.mineify.network.packets.AddToPlaylistPacket;
import com.mineify.network.packets.PlaybackControlPacket;
import com.mineify.network.packets.RemoveFromPlaylistPacket;
import com.mineify.network.packets.SearchRequestPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class MineifyScreen extends Screen {
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 245;
    private static final int NOW_PLAYING_HEIGHT = 38;
    private static final int VOLUME_HEIGHT = 20;
    private static final int BOTTOM_PADDING = 8;

    private TextFieldWidget searchField;
    private ButtonWidget searchButton;
    private ButtonWidget pauseResumeButton;
    private ButtonWidget skipButton;
    private SliderWidget volumeSlider;

    private List<SearchResult> searchResults = new ArrayList<>();
    private List<PlaylistEntry> playlist = new ArrayList<>();

    private int currentTab = 0;
    private int searchScrollOffset = 0;
    private int playlistScrollOffset = 0;

    private String nowPlaying = null;
    private float playbackProgress = 0f;
    private long playbackElapsedMs = 0;
    private long playbackDurationMs = 0;
    private boolean playbackPaused = false;

    private String currentPlayerName;

    public MineifyScreen() {
        super(Text.literal("Mineify - Music Player"));
    }

    @Override
    protected void init() {
        super.init();

        this.currentPlayerName = MinecraftClient.getInstance().getSession().getUsername();

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = centerY - PANEL_HEIGHT / 2;

        int listTop = panelTop + 55;
        int bottomSectionTop = panelTop + PANEL_HEIGHT - (NOW_PLAYING_HEIGHT + VOLUME_HEIGHT + BOTTOM_PADDING);

        this.searchField = new TextFieldWidget(
                this.textRenderer,
                panelLeft + 10,
                panelTop + 30,
                PANEL_WIDTH - 80,
                20,
                Text.literal("Search YouTube...")
        );
        this.searchField.setMaxLength(100);
        this.searchField.setPlaceholder(Text.literal("Search for songs..."));
        this.addDrawableChild(this.searchField);

        this.searchButton = ButtonWidget.builder(Text.literal("Search"), button -> performSearch())
                .dimensions(panelLeft + PANEL_WIDTH - 60, panelTop + 30, 50, 20)
                .build();
        this.addDrawableChild(this.searchButton);

        ButtonWidget searchTabBtn = ButtonWidget.builder(Text.literal("Search"), button -> this.currentTab = 0)
                .dimensions(panelLeft + 10, panelTop + 5, 60, 20)
                .build();
        this.addDrawableChild(searchTabBtn);

        ButtonWidget playlistTabBtn = ButtonWidget.builder(Text.literal("Playlist"), button -> this.currentTab = 1)
                .dimensions(panelLeft + 75, panelTop + 5, 60, 20)
                .build();
        this.addDrawableChild(playlistTabBtn);

        this.pauseResumeButton = ButtonWidget.builder(Text.literal("Pause"), button -> {
            ClientPlayNetworking.send(new PlaybackControlPacket(playbackPaused ? "resume" : "pause"));
        }).dimensions(panelLeft + PANEL_WIDTH - 120, bottomSectionTop + 2, 55, 18).build();
        this.addDrawableChild(this.pauseResumeButton);

        this.skipButton = ButtonWidget.builder(Text.literal("Skip"), button -> {
            ClientPlayNetworking.send(new PlaybackControlPacket("skip"));
        }).dimensions(panelLeft + PANEL_WIDTH - 60, bottomSectionTop + 2, 50, 18).build();
        this.addDrawableChild(this.skipButton);

        this.volumeSlider = new SliderWidget(
                panelLeft + 10,
                panelTop + PANEL_HEIGHT - VOLUME_HEIGHT - 4,
                PANEL_WIDTH - 20,
                20,
                Text.literal("Volume: " + (int) (AudioPlayer.getInstance().getVolume() * 100) + "%"),
                AudioPlayer.getInstance().getVolume()
        ) {
            @Override
            protected void updateMessage() {
                this.setMessage(Text.literal("Volume: " + (int) (this.value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                AudioPlayer.getInstance().setVolume((float) this.value);
            }
        };
        this.addDrawableChild(this.volumeSlider);

        requestPlaylistSync();
        updateControlButtons();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelLeft = centerX - PANEL_WIDTH / 2;
        int panelTop = centerY - PANEL_HEIGHT / 2;

        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xE0101010);
        context.drawHorizontalLine(panelLeft, panelLeft + PANEL_WIDTH - 1, panelTop, 0xFFAAAAAA);
        context.drawHorizontalLine(panelLeft, panelLeft + PANEL_WIDTH - 1, panelTop + PANEL_HEIGHT - 1, 0xFF555555);
        context.drawVerticalLine(panelLeft, panelTop, panelTop + PANEL_HEIGHT - 1, 0xFFAAAAAA);
        context.drawVerticalLine(panelLeft + PANEL_WIDTH - 1, panelTop, panelTop + PANEL_HEIGHT - 1, 0xFF555555);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, panelTop - 15, 0xFFFFFFFF);

        super.render(context, mouseX, mouseY, delta);

        if (currentTab == 0) {
            renderSearchTab(context, panelLeft, panelTop, mouseX, mouseY);
        } else {
            renderPlaylistTab(context, panelLeft, panelTop, mouseX, mouseY);
        }

        renderNowPlaying(context, panelLeft, panelTop + PANEL_HEIGHT - (NOW_PLAYING_HEIGHT + VOLUME_HEIGHT + BOTTOM_PADDING));
    }

    private int getListBottom(int panelTop) {
        return panelTop + PANEL_HEIGHT - (NOW_PLAYING_HEIGHT + VOLUME_HEIGHT + BOTTOM_PADDING + 4);
    }

    private void renderSearchTab(DrawContext context, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int listTop = panelTop + 55;
        int listBottom = getListBottom(panelTop);
        int itemHeight = 25;

        if (searchResults.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Search for songs above"),
                    panelLeft + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
        } else {
            int y = listTop;
            for (int i = searchScrollOffset; i < searchResults.size() && y + itemHeight <= listBottom; i++) {
                SearchResult result = searchResults.get(i);
                boolean hovered = mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                        && mouseY >= y && mouseY < y + itemHeight;

                int bgColor = hovered ? 0x44FFFFFF : 0x22FFFFFF;
                context.fill(panelLeft + 10, y, panelLeft + PANEL_WIDTH - 10, y + itemHeight - 2, bgColor);

                String title = truncateText(result.title, PANEL_WIDTH - 80);
                context.drawTextWithShadow(this.textRenderer, Text.literal(title), panelLeft + 15, y + 4, 0xFFFFFFFF);
                context.drawTextWithShadow(this.textRenderer, Text.literal(result.duration), panelLeft + PANEL_WIDTH - 50, y + 4, 0xFFAAAAAA);

                String channel = truncateText(result.channel, PANEL_WIDTH - 40);
                context.drawTextWithShadow(this.textRenderer, Text.literal(channel), panelLeft + 15, y + 14, 0xFF888888);

                y += itemHeight;
            }
        }
    }

    private void renderPlaylistTab(DrawContext context, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int listTop = panelTop + 55;
        int listBottom = getListBottom(panelTop);
        int itemHeight = 25;

        if (playlist.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Playlist is empty"),
                    panelLeft + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Search and add songs!"),
                    panelLeft + PANEL_WIDTH / 2, listTop + 35, 0xFF666666);
        } else {
            int y = listTop;
            for (int i = playlistScrollOffset; i < playlist.size() && y + itemHeight <= listBottom; i++) {
                PlaylistEntry entry = playlist.get(i);
                boolean hovered = mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                        && mouseY >= y && mouseY < y + itemHeight;
                boolean isPlaying = i == 0 && nowPlaying != null;

                int bgColor = isPlaying ? 0x4400FF00 : (hovered ? 0x44FFFFFF : 0x22FFFFFF);
                context.fill(panelLeft + 10, y, panelLeft + PANEL_WIDTH - 10, y + itemHeight - 2, bgColor);

                String pos = (i + 1) + ".";
                context.drawTextWithShadow(this.textRenderer, Text.literal(pos), panelLeft + 15, y + 8, 0xFFAAAAAA);

                boolean canRemove = entry.addedBy.equals(currentPlayerName);
                int titleMaxWidth = canRemove ? PANEL_WIDTH - 125 : PANEL_WIDTH - 100;
                String title = truncateText(entry.title, titleMaxWidth);
                context.drawTextWithShadow(this.textRenderer, Text.literal(title), panelLeft + 35, y + 4, 0xFFFFFFFF);

                String addedBy = "by " + entry.addedBy;
                context.drawTextWithShadow(this.textRenderer, Text.literal(addedBy), panelLeft + 35, y + 14, 0xFF888888);

                if (canRemove) {
                    int removeX = panelLeft + PANEL_WIDTH - 25;
                    int removeY = y + 5;
                    boolean removeHovered = mouseX >= removeX && mouseX <= removeX + 15
                            && mouseY >= removeY && mouseY <= removeY + 15;
                    int removeBgColor = removeHovered ? 0xAAFF4444 : 0x66FF4444;
                    context.fill(removeX, removeY, removeX + 15, removeY + 15, removeBgColor);
                    context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("X"),
                            removeX + 8, removeY + 4, 0xFFFFFFFF);
                }

                y += itemHeight;
            }
        }
    }

    private void renderNowPlaying(DrawContext context, int panelLeft, int y) {
        context.fill(panelLeft, y, panelLeft + PANEL_WIDTH, y + NOW_PLAYING_HEIGHT, 0x60000000);

        if (nowPlaying != null) {
            String prefix = playbackPaused ? "|| " : "\u266A ";
            String text = prefix + truncateText(nowPlaying, PANEL_WIDTH - 145);
            context.drawTextWithShadow(this.textRenderer, Text.literal(text), panelLeft + 10, y + 5, 0xFF55FF55);

            String timeText = formatTime(playbackElapsedMs) + " / " + formatTime(playbackDurationMs);
            int timeWidth = this.textRenderer.getWidth(timeText);
            context.drawTextWithShadow(this.textRenderer, Text.literal(timeText), panelLeft + PANEL_WIDTH - 10 - timeWidth, y + 5, 0xFFAAAAAA);

            int barWidth = PANEL_WIDTH - 20;
            int barX = panelLeft + 10;
            int barY = y + 24;
            context.fill(barX, barY, barX + barWidth, barY + 4, 0x44FFFFFF);
            context.fill(barX, barY, barX + (int) (barWidth * playbackProgress), barY + 4, playbackPaused ? 0xFFFFAA00 : 0xFF55FF55);
        } else {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Nothing playing"), panelLeft + 10, y + 12, 0xFF666666);
        }
    }

    private String formatTime(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            double mouseX = click.x();
            double mouseY = click.y();

            int centerX = this.width / 2;
            int centerY = this.height / 2;
            int panelLeft = centerX - PANEL_WIDTH / 2;
            int panelTop = centerY - PANEL_HEIGHT / 2;
            int listTop = panelTop + 55;
            int listBottom = getListBottom(panelTop);
            int itemHeight = 25;

            if (currentTab == 0 && !searchResults.isEmpty()) {
                int y = listTop;
                for (int i = searchScrollOffset; i < searchResults.size() && y + itemHeight <= listBottom; i++) {
                    if (mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                            && mouseY >= y && mouseY < y + itemHeight) {
                        addToPlaylist(searchResults.get(i));
                        return true;
                    }
                    y += itemHeight;
                }
            }

            if (currentTab == 1 && !playlist.isEmpty()) {
                int y = listTop;
                for (int i = playlistScrollOffset; i < playlist.size() && y + itemHeight <= listBottom; i++) {
                    PlaylistEntry entry = playlist.get(i);
                    if (entry.addedBy.equals(currentPlayerName)) {
                        int removeX = panelLeft + PANEL_WIDTH - 25;
                        int removeY = y + 5;
                        if (mouseX >= removeX && mouseX <= removeX + 15
                                && mouseY >= removeY && mouseY <= removeY + 15) {
                            removeFromPlaylist(entry.videoId);
                            return true;
                        }
                    }
                    y += itemHeight;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int visibleItems = 5;
        if (currentTab == 0) {
            searchScrollOffset = Math.max(0, Math.min(searchScrollOffset - (int) verticalAmount, Math.max(0, searchResults.size() - visibleItems)));
        } else {
            playlistScrollOffset = Math.max(0, Math.min(playlistScrollOffset - (int) verticalAmount, Math.max(0, playlist.size() - visibleItems)));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ENTER && this.searchField.isFocused()) {
            performSearch();
            return true;
        }
        return super.keyPressed(input);
    }

    private void performSearch() {
        String query = this.searchField.getText().trim();
        if (query.isEmpty()) {
            return;
        }

        MineifyClient.LOGGER.info("Searching for: {}", query);
        ClientPlayNetworking.send(new SearchRequestPacket(query));
        this.searchResults.clear();
        this.searchScrollOffset = 0;
        this.currentTab = 0;
    }

    private void addToPlaylist(SearchResult result) {
        MineifyClient.LOGGER.info("Adding to playlist: {}", result.title);
        ClientPlayNetworking.send(new AddToPlaylistPacket(result.videoId, result.title, result.duration));
        this.currentTab = 1;
    }

    private void removeFromPlaylist(String videoId) {
        MineifyClient.LOGGER.info("Removing from playlist: {}", videoId);
        ClientPlayNetworking.send(new RemoveFromPlaylistPacket(videoId));
    }

    private void requestPlaylistSync() {
        this.playlist = MineifyClient.getCachedPlaylist();
        this.nowPlaying = MineifyClient.getCachedNowPlaying();
        this.playbackProgress = MineifyClient.getCachedProgress();
        this.playbackElapsedMs = MineifyClient.getCachedElapsedMs();
        this.playbackDurationMs = MineifyClient.getCachedDurationMs();
        this.playbackPaused = MineifyClient.isCachedPaused();
    }

    public void updateSearchResults(List<SearchResult> results) {
        this.searchResults = results;
        this.searchScrollOffset = 0;
    }

    public void updatePlaylist(List<PlaylistEntry> entries) {
        this.playlist = entries;
    }

    public void updateNowPlaying(String title, float progress, long elapsedMs, long durationMs, boolean paused) {
        this.nowPlaying = title == null || title.isEmpty() ? null : title;
        this.playbackProgress = progress;
        this.playbackElapsedMs = elapsedMs;
        this.playbackDurationMs = durationMs;
        this.playbackPaused = paused;
        updateControlButtons();
    }

    public void updatePlaybackPaused(boolean paused) {
        this.playbackPaused = paused;
        updateControlButtons();
    }

    private void updateControlButtons() {
        boolean hasTrack = nowPlaying != null;
        if (pauseResumeButton != null) {
            pauseResumeButton.active = hasTrack;
            pauseResumeButton.setMessage(Text.literal(playbackPaused ? "Resume" : "Pause"));
        }
        if (skipButton != null) {
            skipButton.active = hasTrack;
        }
    }

    private String truncateText(String text, int maxWidth) {
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        while (this.textRenderer.getWidth(text + "...") > maxWidth && text.length() > 0) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public static class SearchResult {
        public final String videoId;
        public final String title;
        public final String channel;
        public final String duration;
        public final String thumbnail;

        public SearchResult(String videoId, String title, String channel, String duration, String thumbnail) {
            this.videoId = videoId;
            this.title = title;
            this.channel = channel;
            this.duration = duration;
            this.thumbnail = thumbnail;
        }
    }

    public static class PlaylistEntry {
        public final String videoId;
        public final String title;
        public final String duration;
        public final String addedBy;

        public PlaylistEntry(String videoId, String title, String duration, String addedBy) {
            this.videoId = videoId;
            this.title = title;
            this.duration = duration;
            this.addedBy = addedBy;
        }
    }
}
