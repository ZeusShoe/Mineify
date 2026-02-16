package com.mineify.client;

import com.mineify.MineifyClient;
import com.mineify.client.audio.AudioPlayer;
import com.mineify.network.packets.AddToPlaylistPacket;
import com.mineify.network.packets.AddToUserPlaylistPacket;
import com.mineify.network.packets.CreateUserPlaylistPacket;
import com.mineify.network.packets.PlaybackControlPacket;
import com.mineify.network.packets.ReorderQueuePacket;
import com.mineify.network.packets.RequestRecentlyPlayedPacket;
import com.mineify.network.packets.RequestProfilesPacket;
import com.mineify.network.packets.RequestUserPlaylistsPacket;
import com.mineify.network.packets.ResolveSpotifyImportChoicePacket;
import com.mineify.network.packets.RemoveFromPlaylistPacket;
import com.mineify.network.packets.SearchRequestPacket;
import com.mineify.network.packets.StartSpotifyImportPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class MineifyScreen extends Screen {
    private static final int PANEL_WIDTH = 430;
    private static final int PANEL_HEIGHT = 245;
    private static final int NOW_PLAYING_HEIGHT = 38;
    private static final int VOLUME_HEIGHT = 20;
    private static final int BOTTOM_PADDING = 8;
    private static final int CONTROL_SECTION_WIDTH = 120;
    private static final int LIST_ITEM_HEIGHT = 25;
    private static final int SEARCH_PLAYLIST_BUTTON_WIDTH = 56;
    private static final int SEARCH_QUEUE_BUTTON_WIDTH = 48;
    private static final int SEARCH_BUTTON_GAP = 4;
    private static final int MODAL_WIDTH = 220;
    private static final int MODAL_HEIGHT = 140;
    private static final int PLAYLISTS_LEFT_WIDTH = 130;
    private static final int SPOTIFY_MODAL_WIDTH = 300;
    private static final int SPOTIFY_MODAL_HEIGHT = 170;

    private TextFieldWidget searchField;
    private ButtonWidget searchButton;
    private ButtonWidget pauseResumeButton;
    private ButtonWidget skipButton;
    private SliderWidget volumeSlider;
    private TextFieldWidget newPlaylistNameField;
    private TextFieldWidget profileSearchField;
    private TextFieldWidget spotifyLinkField;
    private ButtonWidget playlistPrivacyButton;
    private ButtonWidget createPlaylistButton;
    private ButtonWidget cancelCreatePlaylistButton;
    private ButtonWidget importSpotifyButton;
    private ButtonWidget spotifyOptionButton1;
    private ButtonWidget spotifyOptionButton2;
    private ButtonWidget spotifyOptionButton3;
    private ButtonWidget spotifySkipButton;

    private List<SearchResult> searchResults = new ArrayList<>();
    private List<PlaylistEntry> playlist = new ArrayList<>();
    private List<UserPlaylistSummary> userPlaylists = new ArrayList<>();
    private List<ProfileSummary> profiles = new ArrayList<>();
    private List<RecentlyPlayedEntry> recentlyPlayed = new ArrayList<>();

    private int currentTab = 0;
    private int searchScrollOffset = 0;
    private int playlistScrollOffset = 0;
    private int profileScrollOffset = 0;

    private String nowPlaying = null;
    private float playbackProgress = 0f;
    private long playbackElapsedMs = 0;
    private long playbackDurationMs = 0;
    private boolean playbackPaused = false;
    private boolean queueDragActive = false;
    private int queueDragFromIndex = -1;
    private int queueDragTargetIndex = -1;
    private boolean showPlaylistPicker = false;
    private boolean showCreatePlaylistDialog = false;
    private boolean showSpotifyPromptDialog = false;
    private SearchResult pendingPlaylistSearchResult = null;
    private boolean newPlaylistPublic = true;
    private String selectedProfileId = null;
    private String selectedUserPlaylistId = null;
    private int playlistsSubtab = 0; // 0: Me, 1: Others, 2: Liked
    private SpotifyPromptState spotifyPromptState = null;

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

        int bottomSectionTop = panelTop + PANEL_HEIGHT - (NOW_PLAYING_HEIGHT + VOLUME_HEIGHT + BOTTOM_PADDING);
        int controlsLeft = panelLeft + PANEL_WIDTH - CONTROL_SECTION_WIDTH;

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

        ButtonWidget playlistTabBtn = ButtonWidget.builder(Text.literal("Queue"), button -> this.currentTab = 1)
                .dimensions(panelLeft + 75, panelTop + 5, 60, 20)
                .build();
        this.addDrawableChild(playlistTabBtn);

        ButtonWidget playlistsTabBtn = ButtonWidget.builder(Text.literal("Playlists"), button -> {
                    this.currentTab = 2;
                    requestProfilesSync(profileSearchField == null ? "" : profileSearchField.getText());
                })
                .dimensions(panelLeft + 140, panelTop + 5, 70, 20)
                .build();
        this.addDrawableChild(playlistsTabBtn);

        ButtonWidget recentTabBtn = ButtonWidget.builder(Text.literal("Recent"), button -> {
                    this.currentTab = 3;
                    requestRecentlyPlayedSync();
                })
                .dimensions(panelLeft + 215, panelTop + 5, 60, 20)
                .build();
        this.addDrawableChild(recentTabBtn);

        this.pauseResumeButton = ButtonWidget.builder(Text.literal("Pause"), button -> {
            ClientPlayNetworking.send(new PlaybackControlPacket(playbackPaused ? "resume" : "pause"));
        }).dimensions(controlsLeft, bottomSectionTop + 2, 55, 18).build();
        this.addDrawableChild(this.pauseResumeButton);

        this.skipButton = ButtonWidget.builder(Text.literal("Skip"), button -> {
            ClientPlayNetworking.send(new PlaybackControlPacket("skip"));
        }).dimensions(controlsLeft + 60, bottomSectionTop + 2, 50, 18).build();
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

        int modalLeft = centerX - (MODAL_WIDTH / 2);
        int modalTop = centerY - (MODAL_HEIGHT / 2);

        this.newPlaylistNameField = new TextFieldWidget(
                this.textRenderer,
                modalLeft + 12,
                modalTop + 35,
                MODAL_WIDTH - 24,
                18,
                Text.literal("Playlist name")
        );
        this.newPlaylistNameField.setMaxLength(50);
        this.newPlaylistNameField.setPlaceholder(Text.literal("My Playlist"));
        this.newPlaylistNameField.visible = false;
        this.newPlaylistNameField.setEditable(false);
        this.addDrawableChild(this.newPlaylistNameField);

        this.playlistPrivacyButton = ButtonWidget.builder(Text.literal("Privacy: Public"), button -> {
            newPlaylistPublic = !newPlaylistPublic;
            updateCreatePlaylistControls();
        }).dimensions(modalLeft + 12, modalTop + 60, MODAL_WIDTH - 24, 20).build();
        this.playlistPrivacyButton.visible = false;
        this.playlistPrivacyButton.active = false;
        this.addDrawableChild(this.playlistPrivacyButton);

        this.createPlaylistButton = ButtonWidget.builder(Text.literal("Create"), button -> createPlaylistFromDialog())
                .dimensions(modalLeft + 12, modalTop + MODAL_HEIGHT - 28, 92, 20)
                .build();
        this.createPlaylistButton.visible = false;
        this.createPlaylistButton.active = false;
        this.addDrawableChild(this.createPlaylistButton);

        this.cancelCreatePlaylistButton = ButtonWidget.builder(Text.literal("Cancel"), button -> closeCreatePlaylistDialog())
                .dimensions(modalLeft + MODAL_WIDTH - 104, modalTop + MODAL_HEIGHT - 28, 92, 20)
                .build();
        this.cancelCreatePlaylistButton.visible = false;
        this.cancelCreatePlaylistButton.active = false;
        this.addDrawableChild(this.cancelCreatePlaylistButton);

        this.profileSearchField = new TextFieldWidget(
                this.textRenderer,
                panelLeft + 10,
                panelTop + 30,
                PLAYLISTS_LEFT_WIDTH - 20,
                18,
                Text.literal("Search players")
        );
        this.profileSearchField.setMaxLength(40);
        this.profileSearchField.setPlaceholder(Text.literal("Find player..."));
        this.profileSearchField.visible = false;
        this.profileSearchField.setEditable(false);
        this.addDrawableChild(this.profileSearchField);

        this.spotifyLinkField = new TextFieldWidget(
                this.textRenderer,
                panelLeft + PLAYLISTS_LEFT_WIDTH + 8,
                panelTop + 30,
                PANEL_WIDTH - PLAYLISTS_LEFT_WIDTH - 82,
                18,
                Text.literal("Spotify playlist link")
        );
        this.spotifyLinkField.setMaxLength(200);
        this.spotifyLinkField.setPlaceholder(Text.literal("https://open.spotify.com/playlist/..."));
        this.spotifyLinkField.visible = false;
        this.spotifyLinkField.setEditable(false);
        this.addDrawableChild(this.spotifyLinkField);

        this.importSpotifyButton = ButtonWidget.builder(Text.literal("Import"), button -> startSpotifyImport())
                .dimensions(panelLeft + PANEL_WIDTH - 68, panelTop + 30, 58, 18)
                .build();
        this.importSpotifyButton.visible = false;
        this.importSpotifyButton.active = false;
        this.addDrawableChild(this.importSpotifyButton);

        int spotifyModalLeft = centerX - (SPOTIFY_MODAL_WIDTH / 2);
        int spotifyModalTop = centerY - (SPOTIFY_MODAL_HEIGHT / 2);
        this.spotifyOptionButton1 = ButtonWidget.builder(Text.literal("Option 1"), button -> chooseSpotifyOption(0))
                .dimensions(spotifyModalLeft + 12, spotifyModalTop + 60, SPOTIFY_MODAL_WIDTH - 24, 18).build();
        this.spotifyOptionButton2 = ButtonWidget.builder(Text.literal("Option 2"), button -> chooseSpotifyOption(1))
                .dimensions(spotifyModalLeft + 12, spotifyModalTop + 82, SPOTIFY_MODAL_WIDTH - 24, 18).build();
        this.spotifyOptionButton3 = ButtonWidget.builder(Text.literal("Option 3"), button -> chooseSpotifyOption(2))
                .dimensions(spotifyModalLeft + 12, spotifyModalTop + 104, SPOTIFY_MODAL_WIDTH - 24, 18).build();
        this.spotifySkipButton = ButtonWidget.builder(Text.literal("Skip Track"), button -> skipSpotifyOption())
                .dimensions(spotifyModalLeft + SPOTIFY_MODAL_WIDTH - 100, spotifyModalTop + SPOTIFY_MODAL_HEIGHT - 24, 88, 18).build();
        for (ButtonWidget btn : List.of(spotifyOptionButton1, spotifyOptionButton2, spotifyOptionButton3, spotifySkipButton)) {
            btn.visible = false;
            btn.active = false;
            this.addDrawableChild(btn);
        }

        requestPlaylistSync();
        requestUserPlaylistSync();
        requestProfilesSync("");
        requestRecentlyPlayedSync();
        updateControlButtons();
        updateCreatePlaylistControls();
        updateSpotifyControls(panelLeft, panelTop);
        updateSpotifyPromptButtons();
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

        int titleY = Math.max(4, panelTop - 15);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, titleY, 0xFFFFFFFF);

        boolean hasBlockingModal = showPlaylistPicker || showCreatePlaylistDialog || showSpotifyPromptDialog;
        boolean showSearchControls = currentTab == 0 && !hasBlockingModal;
        if (searchField != null) {
            searchField.visible = showSearchControls;
            searchField.setEditable(showSearchControls);
        }
        if (searchButton != null) {
            searchButton.visible = showSearchControls;
            searchButton.active = showSearchControls;
        }

        boolean showProfileSearch = currentTab == 2 && !hasBlockingModal;
        if (profileSearchField != null) {
            profileSearchField.visible = showProfileSearch;
            profileSearchField.setEditable(showProfileSearch);
        }

        boolean showSpotifyControls = currentTab == 2 && !hasBlockingModal && isSelfProfileSelected();
        if (spotifyLinkField != null) {
            spotifyLinkField.visible = showSpotifyControls;
            spotifyLinkField.setEditable(showSpotifyControls);
        }
        if (importSpotifyButton != null) {
            importSpotifyButton.visible = showSpotifyControls;
            importSpotifyButton.active = showSpotifyControls && selectedUserPlaylistId != null;
        }

        super.render(context, mouseX, mouseY, delta);

        if (currentTab == 0) {
            renderSearchTab(context, panelLeft, panelTop, mouseX, mouseY);
        } else if (currentTab == 1) {
            renderPlaylistTab(context, panelLeft, panelTop, mouseX, mouseY);
        } else if (currentTab == 2) {
            renderProfilesTab(context, panelLeft, panelTop, mouseX, mouseY);
        } else {
            renderRecentlyPlayedTab(context, panelLeft, panelTop);
        }

        renderNowPlaying(context, panelLeft, panelTop + PANEL_HEIGHT - (NOW_PLAYING_HEIGHT + VOLUME_HEIGHT + BOTTOM_PADDING));
        renderPlaylistModals(context, mouseX, mouseY);
        renderSpotifyPromptModal(context);
    }

    private int getListBottom(int panelTop) {
        return panelTop + PANEL_HEIGHT - (NOW_PLAYING_HEIGHT + VOLUME_HEIGHT + BOTTOM_PADDING + 4);
    }

    private void renderSearchTab(DrawContext context, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int listTop = panelTop + 55;
        int listBottom = getListBottom(panelTop);
        int itemHeight = LIST_ITEM_HEIGHT;

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

                int rowRight = panelLeft + PANEL_WIDTH - 10;
                int queueButtonX = rowRight - SEARCH_QUEUE_BUTTON_WIDTH;
                int playlistButtonX = queueButtonX - SEARCH_BUTTON_GAP - SEARCH_PLAYLIST_BUTTON_WIDTH;
                int buttonY = y + 4;

                int titleMaxWidth = Math.max(20, playlistButtonX - (panelLeft + 15) - 6);
                String title = truncateText(result.title, titleMaxWidth);
                context.drawTextWithShadow(this.textRenderer, Text.literal(title), panelLeft + 15, y + 4, 0xFFFFFFFF);
                String channel = truncateText(result.channel, titleMaxWidth);
                context.drawTextWithShadow(this.textRenderer, Text.literal(channel), panelLeft + 15, y + 14, 0xFF888888);

                boolean playlistHovered = mouseX >= playlistButtonX && mouseX <= playlistButtonX + SEARCH_PLAYLIST_BUTTON_WIDTH
                        && mouseY >= buttonY && mouseY <= buttonY + 16;
                boolean queueHovered = mouseX >= queueButtonX && mouseX <= queueButtonX + SEARCH_QUEUE_BUTTON_WIDTH
                        && mouseY >= buttonY && mouseY <= buttonY + 16;

                context.fill(
                        playlistButtonX,
                        buttonY,
                        playlistButtonX + SEARCH_PLAYLIST_BUTTON_WIDTH,
                        buttonY + 16,
                        playlistHovered ? 0xAA3366FF : 0x663366FF
                );
                context.fill(
                        queueButtonX,
                        buttonY,
                        queueButtonX + SEARCH_QUEUE_BUTTON_WIDTH,
                        buttonY + 16,
                        queueHovered ? 0xAA33AA33 : 0x6633AA33
                );
                context.drawCenteredTextWithShadow(
                        this.textRenderer,
                        Text.literal("Playlist"),
                        playlistButtonX + (SEARCH_PLAYLIST_BUTTON_WIDTH / 2),
                        buttonY + 4,
                        0xFFFFFFFF
                );
                context.drawCenteredTextWithShadow(
                        this.textRenderer,
                        Text.literal("Queue"),
                        queueButtonX + (SEARCH_QUEUE_BUTTON_WIDTH / 2),
                        buttonY + 4,
                        0xFFFFFFFF
                );

                y += itemHeight;
            }
        }
    }

    private void renderPlaylistTab(DrawContext context, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int listTop = panelTop + 55;
        int listBottom = getListBottom(panelTop);
        int itemHeight = LIST_ITEM_HEIGHT;

        if (playlist.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Queue is empty"),
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
                if (queueDragActive && i == queueDragTargetIndex) {
                    bgColor = 0x66FFFF55;
                }
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

    private void renderProfilesTab(DrawContext context, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int contentTop = panelTop + 55;
        int contentBottom = getListBottom(panelTop);
        int leftPaneRight = panelLeft + PLAYLISTS_LEFT_WIDTH;

        context.fill(panelLeft + 6, contentTop, leftPaneRight, contentBottom, 0x33000000);
        context.fill(leftPaneRight + 2, contentTop, panelLeft + PANEL_WIDTH - 10, contentBottom, 0x22000000);

        int tabY = contentTop + 4;
        drawSubtab(context, panelLeft + PLAYLISTS_LEFT_WIDTH + 8, tabY, 36, "Me", playlistsSubtab == 0);
        drawSubtab(context, panelLeft + PLAYLISTS_LEFT_WIDTH + 48, tabY, 52, "Others", playlistsSubtab == 1);
        drawSubtab(context, panelLeft + PLAYLISTS_LEFT_WIDTH + 104, tabY, 45, "Liked", playlistsSubtab == 2);

        List<ProfileSummary> visibleProfiles = getFilteredProfiles();
        int rowY = contentTop + 8;
        int rowHeight = 22;
        int drawn = 0;
        for (int i = profileScrollOffset; i < visibleProfiles.size() && rowY + rowHeight <= contentBottom; i++) {
            ProfileSummary profile = visibleProfiles.get(i);
            boolean selected = profile.ownerId.equals(selectedProfileId);
            boolean hovered = mouseX >= panelLeft + 8 && mouseX <= leftPaneRight - 2 && mouseY >= rowY && mouseY <= rowY + rowHeight;
            int color = selected ? 0x6644AA44 : (hovered ? 0x44444444 : 0x22222222);
            context.fill(panelLeft + 8, rowY, leftPaneRight - 2, rowY + rowHeight, color);

            renderPlayerHead(context, profile.ownerName, panelLeft + 12, rowY + 3, 16);
            String name = truncateText(profile.ownerName, PLAYLISTS_LEFT_WIDTH - 44);
            context.drawTextWithShadow(this.textRenderer, Text.literal(name), panelLeft + 32, rowY + 8, 0xFFFFFFFF);

            rowY += rowHeight + 2;
            drawn++;
        }

        if (drawn == 0) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No players"), panelLeft + (PLAYLISTS_LEFT_WIDTH / 2), contentTop + 24, 0xFF888888);
        }

        ProfileSummary selected = getSelectedProfile();
        int detailsLeft = leftPaneRight + 8;
        if (selected == null) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Select a player profile"), detailsLeft, contentTop + 24, 0xFF999999);
            return;
        }

        String header = selected.ownerName + (selected.isSelf ? " (You)" : "");
        context.drawTextWithShadow(this.textRenderer, Text.literal(header), detailsLeft, contentTop + 26, 0xFFFFFFFF);
        if (selected.isSelf) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Editable playlists"), detailsLeft, contentTop + 38, 0xFF77FF77);
        }

        List<UserPlaylistSummary> filteredPlaylists = filterPlaylistsForSubtab(selected);
        int py = contentTop + 52;
        for (int i = 0; i < filteredPlaylists.size() && py + 18 <= contentBottom; i++) {
            UserPlaylistSummary summary = filteredPlaylists.get(i);
            boolean playlistSelected = summary.id.equals(selectedUserPlaylistId);
            context.fill(detailsLeft, py, panelLeft + PANEL_WIDTH - 14, py + 18, playlistSelected ? 0x66448844 : 0x33222222);
            String privacy = summary.isPublic ? "Public" : "Private";
            context.drawTextWithShadow(
                    this.textRenderer,
                    Text.literal(truncateText(summary.name, 150) + " - " + summary.trackCount + " tracks - " + privacy),
                    detailsLeft + 4,
                    py + 5,
                    0xFFFFFFFF
            );
            py += 21;
        }

        if (filteredPlaylists.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("No playlists in this section"), detailsLeft, contentTop + 58, 0xFF888888);
        }
    }

    private void renderRecentlyPlayedTab(DrawContext context, int panelLeft, int panelTop) {
        int listTop = panelTop + 55;
        int listBottom = getListBottom(panelTop);
        int y = listTop;
        int rowHeight = 20;

        if (recentlyPlayed.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No recently played tracks"), panelLeft + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
            return;
        }

        for (int i = 0; i < recentlyPlayed.size() && y + rowHeight <= listBottom; i++) {
            RecentlyPlayedEntry entry = recentlyPlayed.get(i);
            context.fill(panelLeft + 10, y, panelLeft + PANEL_WIDTH - 10, y + rowHeight - 2, 0x22222222);
            String text = truncateText(entry.title + " (" + entry.duration + ")", PANEL_WIDTH - 100);
            context.drawTextWithShadow(this.textRenderer, Text.literal(text), panelLeft + 14, y + 5, 0xFFFFFFFF);
            context.drawTextWithShadow(this.textRenderer, Text.literal(formatTimeAgo(entry.playedAtEpochMs)), panelLeft + PANEL_WIDTH - 88, y + 5, 0xFFAAAAAA);
            y += rowHeight + 2;
        }
    }

    private void renderSpotifyPromptModal(DrawContext context) {
        if (!showSpotifyPromptDialog || spotifyPromptState == null) {
            return;
        }

        int left = (this.width / 2) - (SPOTIFY_MODAL_WIDTH / 2);
        int top = (this.height / 2) - (SPOTIFY_MODAL_HEIGHT / 2);
        int right = left + SPOTIFY_MODAL_WIDTH;
        int bottom = top + SPOTIFY_MODAL_HEIGHT;

        context.fill(0, 0, this.width, this.height, 0x88000000);
        context.fill(left, top, right, bottom, 0xF0101010);
        context.drawHorizontalLine(left, right - 1, top, 0xFFAAAAAA);
        context.drawHorizontalLine(left, right - 1, bottom - 1, 0xFF666666);
        context.drawVerticalLine(left, top, bottom - 1, 0xFFAAAAAA);
        context.drawVerticalLine(right - 1, top, bottom - 1, 0xFF666666);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Spotify Match Needed"), this.width / 2, top + 8, 0xFFFFFFFF);
        context.drawTextWithShadow(
                this.textRenderer,
                Text.literal(spotifyPromptState.currentIndex + "/" + spotifyPromptState.totalTracks + " " + truncateText(spotifyPromptState.spotifyTitle + " - " + spotifyPromptState.spotifyArtist, SPOTIFY_MODAL_WIDTH - 20)),
                left + 10,
                top + 28,
                0xFFDDDDDD
        );
    }

    private void drawSubtab(DrawContext context, int x, int y, int width, String label, boolean active) {
        context.fill(x, y, x + width, y + 16, active ? 0x8844AA44 : 0x33444444);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label), x + (width / 2), y + 4, 0xFFFFFFFF);
    }

    private void renderPlayerHead(DrawContext context, String playerName, int x, int y, int size) {
        PlayerListEntry entry = findPlayerListEntry(playerName);
        if (entry != null) {
            PlayerSkinDrawer.draw(context, entry.getSkinTextures(), x, y, size);
            return;
        }

        context.fill(x, y, x + size, y + size, 0x88444444);
        String initial = playerName == null || playerName.isEmpty() ? "?" : playerName.substring(0, 1).toUpperCase();
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(initial), x + (size / 2), y + 4, 0xFFFFFFFF);
    }

    private PlayerListEntry findPlayerListEntry(String playerName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.networkHandler == null) {
            return null;
        }

        for (PlayerListEntry entry : client.player.networkHandler.getPlayerList()) {
            if (entry.getProfile().name().equalsIgnoreCase(playerName)) {
                return entry;
            }
        }
        return null;
    }

    private ProfileSummary getSelectedProfile() {
        if (selectedProfileId == null) {
            return null;
        }
        for (ProfileSummary profile : profiles) {
            if (profile.ownerId.equals(selectedProfileId)) {
                return profile;
            }
        }
        return null;
    }

    private List<ProfileSummary> getFilteredProfiles() {
        String query = profileSearchField == null ? "" : profileSearchField.getText().trim().toLowerCase();
        List<ProfileSummary> filtered = new ArrayList<>();
        for (ProfileSummary profile : profiles) {
            if (query.isEmpty() || profile.ownerName.toLowerCase().contains(query)) {
                filtered.add(profile);
            }
        }
        filtered.sort((a, b) -> {
            if (a.isSelf != b.isSelf) {
                return a.isSelf ? -1 : 1;
            }
            return a.ownerName.compareToIgnoreCase(b.ownerName);
        });
        return filtered;
    }

    private List<UserPlaylistSummary> filterPlaylistsForSubtab(ProfileSummary profile) {
        if (playlistsSubtab == 2) {
            return new ArrayList<>();
        }

        List<UserPlaylistSummary> filtered = new ArrayList<>();
        for (UserPlaylistSummary summary : profile.playlists) {
            if (playlistsSubtab == 0 && !profile.isSelf) {
                continue;
            }
            if (playlistsSubtab == 1 && profile.isSelf) {
                continue;
            }
            filtered.add(summary);
        }
        return filtered;
    }

    private void ensureSelectedProfile() {
        if (profiles.isEmpty()) {
            selectedProfileId = null;
            selectedUserPlaylistId = null;
            return;
        }
        if (selectedProfileId != null) {
            for (ProfileSummary profile : profiles) {
                if (profile.ownerId.equals(selectedProfileId)) {
                    return;
                }
            }
        }
        for (ProfileSummary profile : profiles) {
            if (profile.isSelf) {
                selectedProfileId = profile.ownerId;
                return;
            }
        }
        selectedProfileId = profiles.get(0).ownerId;
        selectedUserPlaylistId = null;
    }

    private boolean isSelfProfileSelected() {
        ProfileSummary selected = getSelectedProfile();
        return selected != null && selected.isSelf;
    }

    private void startSpotifyImport() {
        if (!isSelfProfileSelected() || selectedUserPlaylistId == null || spotifyLinkField == null) {
            return;
        }
        String spotifyUrl = spotifyLinkField.getText().trim();
        if (spotifyUrl.isEmpty()) {
            return;
        }
        ClientPlayNetworking.send(new StartSpotifyImportPacket(spotifyUrl, selectedUserPlaylistId));
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("Spotify import started..."), false);
        }
    }

    private void chooseSpotifyOption(int optionIndex) {
        if (spotifyPromptState == null || optionIndex < 0 || optionIndex >= spotifyPromptState.options.size()) {
            return;
        }
        String videoId = spotifyPromptState.options.get(optionIndex).videoId;
        ClientPlayNetworking.send(new ResolveSpotifyImportChoicePacket(videoId));
        showSpotifyPromptDialog = false;
        spotifyPromptState = null;
        updateSpotifyPromptButtons();
    }

    private void skipSpotifyOption() {
        ClientPlayNetworking.send(new ResolveSpotifyImportChoicePacket(""));
        showSpotifyPromptDialog = false;
        spotifyPromptState = null;
        updateSpotifyPromptButtons();
    }

    private void updateSpotifyPromptButtons() {
        boolean visible = showSpotifyPromptDialog && spotifyPromptState != null;
        List<ButtonWidget> optionButtons = List.of(spotifyOptionButton1, spotifyOptionButton2, spotifyOptionButton3);
        for (int i = 0; i < optionButtons.size(); i++) {
            ButtonWidget button = optionButtons.get(i);
            if (button == null) {
                continue;
            }
            boolean hasOption = visible && i < spotifyPromptState.options.size();
            button.visible = hasOption;
            button.active = hasOption;
            if (hasOption) {
                SpotifyChoiceOption option = spotifyPromptState.options.get(i);
                button.setMessage(Text.literal((i + 1) + ". " + truncateText(option.title, SPOTIFY_MODAL_WIDTH - 46)));
            }
        }
        if (spotifySkipButton != null) {
            spotifySkipButton.visible = visible;
            spotifySkipButton.active = visible;
        }
    }

    private void updateSpotifyControls(int panelLeft, int panelTop) {
        if (spotifyLinkField != null) {
            spotifyLinkField.visible = false;
            spotifyLinkField.setEditable(false);
        }
        if (importSpotifyButton != null) {
            importSpotifyButton.visible = false;
            importSpotifyButton.active = false;
        }
    }

    private String formatTimeAgo(long epochMs) {
        long diffSec = Math.max(0, (System.currentTimeMillis() - epochMs) / 1000);
        if (diffSec < 60) {
            return diffSec + "s ago";
        }
        long min = diffSec / 60;
        if (min < 60) {
            return min + "m ago";
        }
        long hours = min / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24;
        return days + "d ago";
    }

    private void renderNowPlaying(DrawContext context, int panelLeft, int y) {
        context.fill(panelLeft, y, panelLeft + PANEL_WIDTH, y + NOW_PLAYING_HEIGHT, 0x60000000);
        int contentLeft = panelLeft + 10;
        int controlsLeft = panelLeft + PANEL_WIDTH - CONTROL_SECTION_WIDTH;
        int contentRight = controlsLeft - 8;
        int contentWidth = Math.max(20, contentRight - contentLeft);

        if (nowPlaying != null) {
            String prefix = playbackPaused ? "|| " : "\u266A ";
            String text = prefix + truncateText(nowPlaying, contentWidth);
            context.drawTextWithShadow(this.textRenderer, Text.literal(text), contentLeft, y + 5, 0xFF55FF55);

            String timeText = formatTime(playbackElapsedMs) + " / " + formatTime(playbackDurationMs);
            int timeWidth = this.textRenderer.getWidth(timeText);
            int timeX = Math.max(contentLeft, contentRight - timeWidth);
            context.drawTextWithShadow(this.textRenderer, Text.literal(timeText), timeX, y + 15, 0xFFAAAAAA);

            int barWidth = contentWidth;
            int barX = contentLeft;
            int barY = y + 24;
            context.fill(barX, barY, barX + barWidth, barY + 4, 0x44FFFFFF);
            context.fill(barX, barY, barX + (int) (barWidth * playbackProgress), barY + 4, playbackPaused ? 0xFFFFAA00 : 0xFF55FF55);
        } else {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Nothing playing"), contentLeft, y + 12, 0xFF666666);
        }
    }

    private String formatTime(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void renderPlaylistModals(DrawContext context, int mouseX, int mouseY) {
        if (!showPlaylistPicker && !showCreatePlaylistDialog) {
            return;
        }

        context.fill(0, 0, this.width, this.height, 0x88000000);

        if (showPlaylistPicker) {
            renderPlaylistPicker(context, mouseX, mouseY);
        }
        if (showCreatePlaylistDialog) {
            renderCreatePlaylistDialog(context);
        }
    }

    private void renderPlaylistPicker(DrawContext context, int mouseX, int mouseY) {
        int left = (this.width / 2) - (MODAL_WIDTH / 2);
        int top = (this.height / 2) - (MODAL_HEIGHT / 2);
        int right = left + MODAL_WIDTH;
        int bottom = top + MODAL_HEIGHT;

        context.fill(left, top, right, bottom, 0xF0101010);
        context.drawHorizontalLine(left, right - 1, top, 0xFF888888);
        context.drawHorizontalLine(left, right - 1, bottom - 1, 0xFF555555);
        context.drawVerticalLine(left, top, bottom - 1, 0xFF888888);
        context.drawVerticalLine(right - 1, top, bottom - 1, 0xFF555555);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Add to Playlist"), this.width / 2, top + 8, 0xFFFFFFFF);

        int rowY = top + 28;
        int rowHeight = 18;
        context.fill(left + 12, rowY, right - 12, rowY + rowHeight, 0x663366FF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("+ New Playlist"), left + 18, rowY + 5, 0xFFFFFFFF);

        rowY += rowHeight + 4;
        if (userPlaylists.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No playlists yet"), this.width / 2, rowY + 6, 0xFFAAAAAA);
            return;
        }

        int maxRows = 4;
        for (int i = 0; i < userPlaylists.size() && i < maxRows; i++) {
            UserPlaylistSummary summary = userPlaylists.get(i);
            boolean hovered = mouseX >= left + 12 && mouseX <= right - 12 && mouseY >= rowY && mouseY <= rowY + rowHeight;
            context.fill(left + 12, rowY, right - 12, rowY + rowHeight, hovered ? 0x6644AA44 : 0x4422AA22);

            String privacy = summary.isPublic ? "Public" : "Private";
            String label = truncateText(summary.name, 110);
            context.drawTextWithShadow(this.textRenderer, Text.literal(label), left + 16, rowY + 5, 0xFFFFFFFF);
            context.drawTextWithShadow(
                    this.textRenderer,
                    Text.literal(privacy + " - " + summary.trackCount + " tracks"),
                    left + 124,
                    rowY + 5,
                    0xFFBBBBBB
            );
            rowY += rowHeight + 3;
        }
    }

    private void renderCreatePlaylistDialog(DrawContext context) {
        int left = (this.width / 2) - (MODAL_WIDTH / 2);
        int top = (this.height / 2) - (MODAL_HEIGHT / 2);
        int right = left + MODAL_WIDTH;
        int bottom = top + MODAL_HEIGHT;

        context.fill(left, top, right, bottom, 0xF0101010);
        context.drawHorizontalLine(left, right - 1, top, 0xFFAAAAAA);
        context.drawHorizontalLine(left, right - 1, bottom - 1, 0xFF666666);
        context.drawVerticalLine(left, top, bottom - 1, 0xFFAAAAAA);
        context.drawVerticalLine(right - 1, top, bottom - 1, 0xFF666666);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("New Playlist"), this.width / 2, top + 8, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (showSpotifyPromptDialog) {
            return super.mouseClicked(click, doubled);
        }

        if (showCreatePlaylistDialog) {
            return super.mouseClicked(click, doubled);
        }

        if (showPlaylistPicker) {
            if (click.button() != 0) {
                return true;
            }

            double mouseX = click.x();
            double mouseY = click.y();

            int left = (this.width / 2) - (MODAL_WIDTH / 2);
            int top = (this.height / 2) - (MODAL_HEIGHT / 2);
            int right = left + MODAL_WIDTH;
            int bottom = top + MODAL_HEIGHT;

            if (mouseX < left || mouseX > right || mouseY < top || mouseY > bottom) {
                closePlaylistPicker();
                return true;
            }

            int rowY = top + 28;
            int rowHeight = 18;
            if (mouseX >= left + 12 && mouseX <= right - 12 && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                openCreatePlaylistDialog();
                return true;
            }

            rowY += rowHeight + 4;
            for (int i = 0; i < userPlaylists.size() && i < 4; i++) {
                if (mouseX >= left + 12 && mouseX <= right - 12 && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                    addSearchResultToUserPlaylist(userPlaylists.get(i));
                    closePlaylistPicker();
                    return true;
                }
                rowY += rowHeight + 3;
            }
            return true;
        }

        if (click.button() == 0) {
            double mouseX = click.x();
            double mouseY = click.y();

            int centerX = this.width / 2;
            int centerY = this.height / 2;
            int panelLeft = centerX - PANEL_WIDTH / 2;
            int panelTop = centerY - PANEL_HEIGHT / 2;
            int listTop = panelTop + 55;
            int listBottom = getListBottom(panelTop);
            int itemHeight = LIST_ITEM_HEIGHT;

            if (currentTab == 0 && !searchResults.isEmpty()) {
                int y = listTop;
                for (int i = searchScrollOffset; i < searchResults.size() && y + itemHeight <= listBottom; i++) {
                    int rowRight = panelLeft + PANEL_WIDTH - 10;
                    int queueButtonX = rowRight - SEARCH_QUEUE_BUTTON_WIDTH;
                    int playlistButtonX = queueButtonX - SEARCH_BUTTON_GAP - SEARCH_PLAYLIST_BUTTON_WIDTH;
                    int buttonY = y + 4;

                    if (mouseX >= queueButtonX && mouseX <= queueButtonX + SEARCH_QUEUE_BUTTON_WIDTH
                            && mouseY >= buttonY && mouseY <= buttonY + 16) {
                        addToQueue(searchResults.get(i));
                        return true;
                    }

                    if (mouseX >= playlistButtonX && mouseX <= playlistButtonX + SEARCH_PLAYLIST_BUTTON_WIDTH
                            && mouseY >= buttonY && mouseY <= buttonY + 16) {
                        addToPlaylistLibrary(searchResults.get(i));
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

                    if (mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                            && mouseY >= y && mouseY < y + itemHeight) {
                        queueDragActive = true;
                        queueDragFromIndex = i;
                        queueDragTargetIndex = i;
                        return true;
                    }
                    y += itemHeight;
                }
            }

            if (currentTab == 2) {
                int contentTop = panelTop + 55;
                int contentBottom = getListBottom(panelTop);
                int leftPaneRight = panelLeft + PLAYLISTS_LEFT_WIDTH;

                int subtabY = contentTop + 4;
                if (mouseX >= panelLeft + PLAYLISTS_LEFT_WIDTH + 8 && mouseX <= panelLeft + PLAYLISTS_LEFT_WIDTH + 44
                        && mouseY >= subtabY && mouseY <= subtabY + 16) {
                    playlistsSubtab = 0;
                    selectedUserPlaylistId = null;
                    return true;
                }
                if (mouseX >= panelLeft + PLAYLISTS_LEFT_WIDTH + 48 && mouseX <= panelLeft + PLAYLISTS_LEFT_WIDTH + 100
                        && mouseY >= subtabY && mouseY <= subtabY + 16) {
                    playlistsSubtab = 1;
                    selectedUserPlaylistId = null;
                    return true;
                }
                if (mouseX >= panelLeft + PLAYLISTS_LEFT_WIDTH + 104 && mouseX <= panelLeft + PLAYLISTS_LEFT_WIDTH + 149
                        && mouseY >= subtabY && mouseY <= subtabY + 16) {
                    playlistsSubtab = 2;
                    selectedUserPlaylistId = null;
                    return true;
                }

                List<ProfileSummary> visibleProfiles = getFilteredProfiles();
                int rowY = contentTop + 8;
                int rowHeight = 22;
                for (int i = profileScrollOffset; i < visibleProfiles.size() && rowY + rowHeight <= contentBottom; i++) {
                    if (mouseX >= panelLeft + 8 && mouseX <= leftPaneRight - 2 && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                        selectedProfileId = visibleProfiles.get(i).ownerId;
                        selectedUserPlaylistId = null;
                        return true;
                    }
                    rowY += rowHeight + 2;
                }

                ProfileSummary selected = getSelectedProfile();
                if (selected != null) {
                    List<UserPlaylistSummary> filtered = filterPlaylistsForSubtab(selected);
                    int detailsLeft = leftPaneRight + 8;
                    int py = contentTop + 52;
                    for (int i = 0; i < filtered.size() && py + 18 <= contentBottom; i++) {
                        if (mouseX >= detailsLeft && mouseX <= panelLeft + PANEL_WIDTH - 14 && mouseY >= py && mouseY <= py + 18) {
                            selectedUserPlaylistId = filtered.get(i).id;
                            return true;
                        }
                        py += 21;
                    }
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (click.button() == 0 && queueDragActive && currentTab == 1) {
            int target = getQueueIndexFromMouse(click.y());
            if (target >= 0) {
                queueDragTargetIndex = target;
            }
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0 && queueDragActive) {
            int from = queueDragFromIndex;
            int to = getQueueIndexFromMouse(click.y());
            if (to < 0) {
                to = queueDragTargetIndex;
            }

            queueDragActive = false;
            queueDragFromIndex = -1;
            queueDragTargetIndex = -1;

            if (from >= 0 && to >= 0 && from != to) {
                ClientPlayNetworking.send(new ReorderQueuePacket(from, to));
            }
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (showPlaylistPicker || showCreatePlaylistDialog || showSpotifyPromptDialog) {
            return true;
        }
        if (queueDragActive) {
            return true;
        }
        int visibleItems = 5;
        if (currentTab == 0) {
            searchScrollOffset = Math.max(0, Math.min(searchScrollOffset - (int) verticalAmount, Math.max(0, searchResults.size() - visibleItems)));
        } else if (currentTab == 1) {
            playlistScrollOffset = Math.max(0, Math.min(playlistScrollOffset - (int) verticalAmount, Math.max(0, playlist.size() - visibleItems)));
        } else {
            profileScrollOffset = Math.max(0, Math.min(profileScrollOffset - (int) verticalAmount, Math.max(0, getFilteredProfiles().size() - visibleItems)));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (showSpotifyPromptDialog) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                skipSpotifyOption();
                return true;
            }
            return super.keyPressed(input);
        }
        if (showCreatePlaylistDialog && input.key() == GLFW.GLFW_KEY_ENTER) {
            createPlaylistFromDialog();
            return true;
        }
        if ((showPlaylistPicker || showCreatePlaylistDialog) && input.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (showCreatePlaylistDialog) {
                closeCreatePlaylistDialog();
            } else {
                closePlaylistPicker();
            }
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_ENTER && this.searchField.isFocused()) {
            performSearch();
            return true;
        }
        if (currentTab == 2 && profileSearchField != null && profileSearchField.isFocused() && input.key() == GLFW.GLFW_KEY_ENTER) {
            requestProfilesSync(profileSearchField.getText());
            return true;
        }
        if (currentTab == 2 && spotifyLinkField != null && spotifyLinkField.isFocused() && input.key() == GLFW.GLFW_KEY_ENTER) {
            startSpotifyImport();
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

    private void addToQueue(SearchResult result) {
        MineifyClient.LOGGER.info("Adding to queue: {}", result.title);
        ClientPlayNetworking.send(new AddToPlaylistPacket(result.videoId, result.title, result.duration));
        this.currentTab = 1;
    }

    private void addToPlaylistLibrary(SearchResult result) {
        MineifyClient.LOGGER.info("Opening playlist picker for: {}", result.title);
        this.pendingPlaylistSearchResult = result;
        this.showPlaylistPicker = true;
        this.showCreatePlaylistDialog = false;
        updateCreatePlaylistControls();
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
        this.userPlaylists = MineifyClient.getCachedUserPlaylists();
        this.profiles = MineifyClient.getCachedProfiles();
        this.recentlyPlayed = MineifyClient.getCachedRecentlyPlayed();
        ensureSelectedProfile();
    }

    private void requestUserPlaylistSync() {
        ClientPlayNetworking.send(new RequestUserPlaylistsPacket());
    }

    private void requestProfilesSync(String query) {
        ClientPlayNetworking.send(new RequestProfilesPacket(query == null ? "" : query));
    }

    private void requestRecentlyPlayedSync() {
        ClientPlayNetworking.send(new RequestRecentlyPlayedPacket());
    }

    public void updateSearchResults(List<SearchResult> results) {
        this.searchResults = results;
        this.searchScrollOffset = 0;
    }

    public void updatePlaylist(List<PlaylistEntry> entries) {
        this.playlist = entries;
    }

    public void updateUserPlaylists(List<UserPlaylistSummary> entries) {
        this.userPlaylists = entries;
    }

    public void updateProfiles(List<ProfileSummary> entries) {
        this.profiles = entries;
        ensureSelectedProfile();
    }

    public void updateRecentlyPlayed(List<RecentlyPlayedEntry> entries) {
        this.recentlyPlayed = entries;
    }

    public void showSpotifyImportPrompt(
            String spotifyTitle,
            String spotifyArtist,
            int currentIndex,
            int totalTracks,
            List<SpotifyChoiceOption> options
    ) {
        this.spotifyPromptState = new SpotifyPromptState(spotifyTitle, spotifyArtist, currentIndex, totalTracks, options);
        this.showSpotifyPromptDialog = true;
        updateSpotifyPromptButtons();
    }

    public void showSpotifyImportFinished(int added, int skipped, int unresolved) {
        this.showSpotifyPromptDialog = false;
        this.spotifyPromptState = null;
        updateSpotifyPromptButtons();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(
                    "Spotify import done. Added: " + added + ", Skipped: " + skipped + ", Manual/Unresolved: " + unresolved
            ), false);
        }
        requestProfilesSync(profileSearchField == null ? "" : profileSearchField.getText());
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

    private int getQueueIndexFromMouse(double mouseY) {
        if (playlist.isEmpty()) {
            return -1;
        }

        int panelTop = (this.height / 2) - (PANEL_HEIGHT / 2);
        int listTop = panelTop + 55;
        int listBottom = getListBottom(panelTop);
        if (mouseY < listTop) {
            return 0;
        }
        if (mouseY > listBottom) {
            return playlist.size() - 1;
        }

        int relative = (int) ((mouseY - listTop) / LIST_ITEM_HEIGHT);
        int index = playlistScrollOffset + relative;
        if (index < 0) {
            return 0;
        }
        return Math.min(index, playlist.size() - 1);
    }

    private void closePlaylistPicker() {
        showPlaylistPicker = false;
        pendingPlaylistSearchResult = null;
    }

    private void openCreatePlaylistDialog() {
        showCreatePlaylistDialog = true;
        showPlaylistPicker = false;
        newPlaylistPublic = true;
        if (newPlaylistNameField != null) {
            newPlaylistNameField.setText("");
            newPlaylistNameField.setEditable(true);
            newPlaylistNameField.visible = true;
            this.setFocused(newPlaylistNameField);
        }
        updateCreatePlaylistControls();
    }

    private void closeCreatePlaylistDialog() {
        showCreatePlaylistDialog = false;
        pendingPlaylistSearchResult = null;
        if (newPlaylistNameField != null) {
            newPlaylistNameField.setEditable(false);
            newPlaylistNameField.visible = false;
        }
        updateCreatePlaylistControls();
    }

    private void updateCreatePlaylistControls() {
        boolean dialogVisible = showCreatePlaylistDialog;

        if (playlistPrivacyButton != null) {
            playlistPrivacyButton.visible = dialogVisible;
            playlistPrivacyButton.active = dialogVisible;
            playlistPrivacyButton.setMessage(Text.literal(newPlaylistPublic ? "Privacy: Public" : "Privacy: Private"));
        }
        if (createPlaylistButton != null) {
            createPlaylistButton.visible = dialogVisible;
            createPlaylistButton.active = dialogVisible;
        }
        if (cancelCreatePlaylistButton != null) {
            cancelCreatePlaylistButton.visible = dialogVisible;
            cancelCreatePlaylistButton.active = dialogVisible;
        }
    }

    private void createPlaylistFromDialog() {
        if (!showCreatePlaylistDialog || pendingPlaylistSearchResult == null || newPlaylistNameField == null) {
            return;
        }
        String name = newPlaylistNameField.getText().trim();
        if (name.isEmpty()) {
            return;
        }

        ClientPlayNetworking.send(new CreateUserPlaylistPacket(
                name,
                newPlaylistPublic,
                true,
                pendingPlaylistSearchResult.videoId,
                pendingPlaylistSearchResult.title,
                pendingPlaylistSearchResult.duration
        ));

        closeCreatePlaylistDialog();
        pendingPlaylistSearchResult = null;
        requestUserPlaylistSync();
    }

    private void addSearchResultToUserPlaylist(UserPlaylistSummary playlistSummary) {
        if (pendingPlaylistSearchResult == null) {
            return;
        }
        ClientPlayNetworking.send(new AddToUserPlaylistPacket(
                playlistSummary.id,
                pendingPlaylistSearchResult.videoId,
                pendingPlaylistSearchResult.title,
                pendingPlaylistSearchResult.duration
        ));
    }

    public static class SpotifyChoiceOption {
        public final String videoId;
        public final String title;
        public final String channel;
        public final String duration;

        public SpotifyChoiceOption(String videoId, String title, String channel, String duration) {
            this.videoId = videoId;
            this.title = title;
            this.channel = channel;
            this.duration = duration;
        }
    }

    public static class RecentlyPlayedEntry {
        public final String videoId;
        public final String title;
        public final String duration;
        public final long playedAtEpochMs;

        public RecentlyPlayedEntry(String videoId, String title, String duration, long playedAtEpochMs) {
            this.videoId = videoId;
            this.title = title;
            this.duration = duration;
            this.playedAtEpochMs = playedAtEpochMs;
        }
    }

    private static class SpotifyPromptState {
        final String spotifyTitle;
        final String spotifyArtist;
        final int currentIndex;
        final int totalTracks;
        final List<SpotifyChoiceOption> options;

        SpotifyPromptState(String spotifyTitle, String spotifyArtist, int currentIndex, int totalTracks, List<SpotifyChoiceOption> options) {
            this.spotifyTitle = spotifyTitle;
            this.spotifyArtist = spotifyArtist;
            this.currentIndex = currentIndex;
            this.totalTracks = totalTracks;
            this.options = options;
        }
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

    public static class UserPlaylistSummary {
        public final String id;
        public final String name;
        public final boolean isPublic;
        public final int trackCount;

        public UserPlaylistSummary(String id, String name, boolean isPublic, int trackCount) {
            this.id = id;
            this.name = name;
            this.isPublic = isPublic;
            this.trackCount = trackCount;
        }
    }

    public static class ProfileSummary {
        public final String ownerId;
        public final String ownerName;
        public final boolean isSelf;
        public final List<UserPlaylistSummary> playlists;

        public ProfileSummary(String ownerId, String ownerName, boolean isSelf, List<UserPlaylistSummary> playlists) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.isSelf = isSelf;
            this.playlists = playlists;
        }
    }
}
