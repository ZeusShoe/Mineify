package com.mineify.client;

import com.mineify.MineifyClient;
import com.mineify.MineifyConfig;
import com.mineify.client.audio.AudioPlayer;
import com.mineify.network.packets.AddToPlaylistPacket;
import com.mineify.network.packets.AddToUserPlaylistPacket;
import com.mineify.network.packets.ConfirmSpotifyImportPacket;
import com.mineify.network.packets.CreateUserPlaylistPacket;
import com.mineify.network.packets.PlaybackControlPacket;
import com.mineify.network.packets.ReorderQueuePacket;
import com.mineify.network.packets.RequestRecentlyPlayedPacket;
import com.mineify.network.packets.RequestProfilesPacket;
import com.mineify.network.packets.RequestSpotifyImportPreviewPacket;
import com.mineify.network.packets.RequestUserPlaylistsPacket;
import com.mineify.network.packets.ResolveSpotifyImportChoicePacket;
import com.mineify.network.packets.RemoveFromPlaylistPacket;
import com.mineify.network.packets.SearchRequestPacket;
import com.mineify.network.packets.ToggleLikedPlaylistPacket;
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
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Environment(EnvType.CLIENT)
public class MineifyScreen extends Screen {
    private static final String HEART_OUTLINE = "♡";
    private static final String HEART_FILLED = "♥";
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
    private static final int SEARCH_ACTION_ROW_RIGHT_PADDING = 8;
    private static final int TAB_BAR_Y = 30;
    private static final int CONTENT_TOP_WITH_SEARCH = 55;
    private static final int CONTENT_TOP_NO_SEARCH = 34;
    private static final int MODAL_WIDTH = 220;
    private static final int MODAL_HEIGHT = 140;
    private static final int PLAYLISTS_LEFT_WIDTH = 130;
    private static final int SPOTIFY_MODAL_WIDTH = 300;
    private static final int SPOTIFY_MODAL_HEIGHT = 170;
    private static final int SPOTIFY_PREVIEW_MODAL_WIDTH = 340;
    private static final int SPOTIFY_PREVIEW_MODAL_HEIGHT = 188;
    private static final int RECENT_UI_MAX_VISIBLE = MineifyConfig.getRecentlyPlayedUiMaxVisibleEntries();

    private TextFieldWidget searchField;
    private ButtonWidget searchButton;
    private ButtonWidget pauseResumeButton;
    private ButtonWidget skipButton;
    private ButtonWidget compactModeButton;
    private ButtonWidget undoQueueButton;
    private SliderWidget volumeSlider;
    private TextFieldWidget newPlaylistNameField;
    private TextFieldWidget profileSearchField;
    private TextFieldWidget spotifyLinkField;
    private TextFieldWidget spotifyImportNameField;
    private ButtonWidget playlistPrivacyButton;
    private ButtonWidget createPlaylistButton;
    private ButtonWidget cancelCreatePlaylistButton;
    private ButtonWidget importSpotifyButton;
    private ButtonWidget spotifyPreviewImportButton;
    private ButtonWidget spotifyPreviewCancelButton;
    private ButtonWidget spotifyImportPrivacyButton;
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
    private int profilePlaylistScrollOffset = 0;
    private int recentlyPlayedScrollOffset = 0;
    private int rightPaneTab = 0; // 0: Player's Playlists, 1: Player's Liked Playlists
    private boolean compactMode = MineifyConfig.isUiCompactDefault();

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
    private boolean showSpotifyPreviewDialog = false;
    private boolean showSpotifyPromptDialog = false;
    private SearchResult pendingPlaylistSearchResult = null;
    private boolean newPlaylistPublic = true;
    private boolean spotifyImportPublic = true;
    private String selectedProfileId = null;
    private String selectedUserPlaylistId = null;
    private String openedProfilePlaylistId = null;
    private int playlistDetailScrollOffset = 0;
    private SpotifyPromptState spotifyPromptState = null;
    private SpotifyImportPreviewState spotifyImportPreviewState = null;
    private Set<String> selectedSpotifyTrackIds = new LinkedHashSet<>();
    private Set<Integer> selectedDetailTrackIndexes = new LinkedHashSet<>();
    private boolean showContextMenu = false;
    private int contextMenuX = 0;
    private int contextMenuY = 0;
    private ContextMenuState contextMenuState = null;

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

        this.compactModeButton = ButtonWidget.builder(
                        Text.literal(compactMode ? "Compact" : "Expanded"),
                        button -> {
                            compactMode = !compactMode;
                            compactModeButton.setMessage(Text.literal(compactMode ? "Compact" : "Expanded"));
                        })
                .dimensions(panelLeft + PANEL_WIDTH - 78, panelTop + 5, 68, 20)
                .build();
        this.addDrawableChild(this.compactModeButton);

        int controlButtonY = bottomSectionTop + ((NOW_PLAYING_HEIGHT - 18) / 2);

        this.pauseResumeButton = ButtonWidget.builder(Text.literal("⏸"), button -> {
            ClientPlayNetworking.send(new PlaybackControlPacket(playbackPaused ? "resume" : "pause"));
        }).dimensions(controlsLeft + 8, controlButtonY, 42, 18).build();
        this.addDrawableChild(this.pauseResumeButton);

        this.skipButton = ButtonWidget.builder(Text.literal("⏭"), button -> {
            ClientPlayNetworking.send(new PlaybackControlPacket("skip"));
        }).dimensions(controlsLeft + 54, controlButtonY, 48, 18).build();
        this.addDrawableChild(this.skipButton);

        this.undoQueueButton = ButtonWidget.builder(Text.literal("Undo"), button -> {
                    ClientPlayNetworking.send(new PlaybackControlPacket("undo_queue"));
                })
                .dimensions(panelLeft + PANEL_WIDTH - 60, panelTop + CONTENT_TOP_NO_SEARCH - 18, 50, 14)
                .build();
        this.undoQueueButton.visible = false;
        this.undoQueueButton.active = false;
        this.addDrawableChild(this.undoQueueButton);

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

        int previewModalLeft = centerX - (SPOTIFY_PREVIEW_MODAL_WIDTH / 2);
        int previewModalTop = centerY - (SPOTIFY_PREVIEW_MODAL_HEIGHT / 2);
        this.spotifyImportNameField = new TextFieldWidget(
                this.textRenderer,
                previewModalLeft + 12,
                previewModalTop + 35,
                SPOTIFY_PREVIEW_MODAL_WIDTH - 24,
                18,
                Text.literal("Playlist name")
        );
        this.spotifyImportNameField.setMaxLength(50);
        this.spotifyImportNameField.visible = false;
        this.spotifyImportNameField.setEditable(false);
        this.addDrawableChild(this.spotifyImportNameField);

        this.spotifyImportPrivacyButton = ButtonWidget.builder(Text.literal("Privacy: Public"), button -> {
                    spotifyImportPublic = !spotifyImportPublic;
                    updateSpotifyPreviewControls();
                })
                .dimensions(previewModalLeft + 12, previewModalTop + 57, 128, 18)
                .build();
        this.spotifyImportPrivacyButton.visible = false;
        this.spotifyImportPrivacyButton.active = false;
        this.addDrawableChild(this.spotifyImportPrivacyButton);

        this.spotifyPreviewImportButton = ButtonWidget.builder(Text.literal("Import Selected"), button -> confirmSpotifyPreviewImport())
                .dimensions(previewModalLeft + 12, previewModalTop + SPOTIFY_PREVIEW_MODAL_HEIGHT - 24, 140, 18)
                .build();
        this.spotifyPreviewImportButton.visible = false;
        this.spotifyPreviewImportButton.active = false;
        this.addDrawableChild(this.spotifyPreviewImportButton);

        this.spotifyPreviewCancelButton = ButtonWidget.builder(Text.literal("Cancel"), button -> closeSpotifyPreviewDialog())
                .dimensions(previewModalLeft + SPOTIFY_PREVIEW_MODAL_WIDTH - 92, previewModalTop + SPOTIFY_PREVIEW_MODAL_HEIGHT - 24, 80, 18)
                .build();
        this.spotifyPreviewCancelButton.visible = false;
        this.spotifyPreviewCancelButton.active = false;
        this.addDrawableChild(this.spotifyPreviewCancelButton);

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
        updateSpotifyPreviewControls();
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

        boolean hasBlockingModal = showPlaylistPicker || showCreatePlaylistDialog || showSpotifyPromptDialog || showSpotifyPreviewDialog;
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
            importSpotifyButton.active = showSpotifyControls && isValidSpotifyUrl(spotifyLinkField.getText());
        }
        if (undoQueueButton != null) {
            boolean showUndo = currentTab == 1 && !hasBlockingModal && !playlist.isEmpty();
            undoQueueButton.visible = showUndo;
            undoQueueButton.active = showUndo;
        }

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
        renderSpotifyPreviewModal(context, mouseX, mouseY);
        renderSpotifyPromptModal(context);
        renderContextMenu(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private int getListBottom(int panelTop) {
        return panelTop + PANEL_HEIGHT - (NOW_PLAYING_HEIGHT + VOLUME_HEIGHT + BOTTOM_PADDING + 4);
    }

    private int getSearchRowHeight() {
        return compactMode ? 20 : LIST_ITEM_HEIGHT;
    }

    private int getQueueRowHeight() {
        return compactMode ? 20 : LIST_ITEM_HEIGHT;
    }

    private void renderSearchTab(DrawContext context, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int listTop = panelTop + CONTENT_TOP_WITH_SEARCH;
        int listBottom = getListBottom(panelTop);
        int itemHeight = getSearchRowHeight();
        int visibleItems = Math.max(1, (listBottom - listTop) / itemHeight);
        searchScrollOffset = Math.max(0, Math.min(searchScrollOffset, Math.max(0, searchResults.size() - visibleItems)));

        if (searchResults.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Search for songs above"),
                    panelLeft + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
        } else {
            int y = listTop;
            int end = Math.min(searchResults.size(), searchScrollOffset + visibleItems);
            for (int i = searchScrollOffset; i < end && y + itemHeight <= listBottom; i++) {
                SearchResult result = searchResults.get(i);
                boolean hovered = mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                        && mouseY >= y && mouseY < y + itemHeight;

                int bgColor = hovered ? 0x44FFFFFF : 0x22FFFFFF;
                context.fill(panelLeft + 10, y, panelLeft + PANEL_WIDTH - 10, y + itemHeight - 2, bgColor);

                int rowRight = panelLeft + PANEL_WIDTH - 10 - SEARCH_ACTION_ROW_RIGHT_PADDING;
                int queueButtonX = rowRight - SEARCH_QUEUE_BUTTON_WIDTH;
                int playlistButtonX = queueButtonX - SEARCH_BUTTON_GAP - SEARCH_PLAYLIST_BUTTON_WIDTH;
                int buttonY = y + 4;

                int titleMaxWidth = Math.max(20, playlistButtonX - (panelLeft + 15) - 6);
                String title = truncateText(result.title, titleMaxWidth);
                context.drawTextWithShadow(this.textRenderer, Text.literal(title), panelLeft + 15, y + 4, 0xFFFFFFFF);
                if (!compactMode) {
                    String channel = truncateText(result.channel, titleMaxWidth);
                    context.drawTextWithShadow(this.textRenderer, Text.literal(channel), panelLeft + 15, y + 14, 0xFF888888);
                }

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
            drawScrollbar(context, panelLeft + PANEL_WIDTH - 5, listTop, listBottom, searchResults.size(), visibleItems, searchScrollOffset);
        }
    }

    private void renderPlaylistTab(DrawContext context, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int listTop = panelTop + CONTENT_TOP_NO_SEARCH;
        int listBottom = getListBottom(panelTop);
        int itemHeight = getQueueRowHeight();
        int rowStep = itemHeight;
        int visibleItems = Math.max(1, (listBottom - listTop) / rowStep);
        playlistScrollOffset = Math.max(0, Math.min(playlistScrollOffset, Math.max(0, playlist.size() - visibleItems)));

        if (playlist.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Queue is empty"),
                    panelLeft + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Search and add songs!"),
                    panelLeft + PANEL_WIDTH / 2, listTop + 35, 0xFF666666);
        } else {
            int y = listTop;
            int end = Math.min(playlist.size(), playlistScrollOffset + visibleItems);
            for (int i = playlistScrollOffset; i < end && y + itemHeight <= listBottom; i++) {
                PlaylistEntry entry = playlist.get(i);
                boolean hovered = mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                        && mouseY >= y && mouseY < y + itemHeight;
                boolean isPlaying = i == 0 && nowPlaying != null;

                int bgColor = isPlaying ? 0x4400FF00 : (hovered ? 0x44FFFFFF : 0x22FFFFFF);
                context.fill(panelLeft + 10, y, panelLeft + PANEL_WIDTH - 10, y + itemHeight - 2, bgColor);
                if (queueDragActive && i == queueDragTargetIndex) {
                    int markerY = y + 1;
                    context.fill(panelLeft + 12, markerY, panelLeft + PANEL_WIDTH - 12, markerY + 2, 0xAAFFFF55);
                }

                String pos = (i + 1) + ".";
                context.drawTextWithShadow(this.textRenderer, Text.literal(pos), panelLeft + 15, y + 8, 0xFFAAAAAA);

                boolean canRemove = entry.addedBy.equals(currentPlayerName);
                int titleMaxWidth = canRemove ? PANEL_WIDTH - 125 : PANEL_WIDTH - 100;
                String title = truncateText(entry.title, titleMaxWidth);
                context.drawTextWithShadow(this.textRenderer, Text.literal(title), panelLeft + 35, y + 4, 0xFFFFFFFF);

                String addedBy = isPlaying ? "Now Playing • by " + entry.addedBy : "by " + entry.addedBy;
                if (!compactMode) {
                    context.drawTextWithShadow(this.textRenderer, Text.literal(addedBy), panelLeft + 35, y + 14, 0xFF888888);
                } else if (isPlaying) {
                    context.drawTextWithShadow(this.textRenderer, Text.literal("Now Playing"), panelLeft + 35, y + 11, 0xFF88FF88);
                }

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
            drawScrollbar(context, panelLeft + PANEL_WIDTH - 5, listTop, listBottom, playlist.size(), visibleItems, playlistScrollOffset);
        }
    }

    private void renderProfilesTab(DrawContext context, int panelLeft, int panelTop, int mouseX, int mouseY) {
        int contentTop = panelTop + CONTENT_TOP_WITH_SEARCH;
        int contentBottom = getListBottom(panelTop);
        int leftPaneRight = panelLeft + PLAYLISTS_LEFT_WIDTH;
        int detailsLeft = leftPaneRight + 8;
        int detailsRight = panelLeft + PANEL_WIDTH - 10;

        context.fill(panelLeft + 6, contentTop, leftPaneRight, contentBottom, 0x33000000);
        context.fill(leftPaneRight + 2, contentTop, detailsRight, contentBottom, 0x22000000);

        int tabY = contentTop + 4;
        int playlistsTabX = panelLeft + PLAYLISTS_LEFT_WIDTH + 8;
        int playlistsTabWidth = 78;
        int likedTabX = playlistsTabX + playlistsTabWidth + 4;
        int likedTabWidth = 110;
        drawSubtab(context, playlistsTabX, tabY, playlistsTabWidth, "Playlists", rightPaneTab == 0);
        drawSubtab(context, likedTabX, tabY, likedTabWidth, "Liked Playlists", rightPaneTab == 1);

        List<ProfileSummary> visibleProfiles = getFilteredProfiles();
        int rowY = contentTop + 8;
        int rowHeight = 22;
        int drawn = 0;
        int profileAreaTop = rowY;
        int profileAreaBottom = contentBottom;
        int profileVisibleRows = Math.max(1, (profileAreaBottom - profileAreaTop) / (rowHeight + 2));
        profileScrollOffset = Math.max(0, Math.min(profileScrollOffset, Math.max(0, visibleProfiles.size() - profileVisibleRows)));
        int profileEnd = Math.min(visibleProfiles.size(), profileScrollOffset + profileVisibleRows);
        for (int i = profileScrollOffset; i < profileEnd && rowY + rowHeight <= contentBottom; i++) {
            ProfileSummary profile = visibleProfiles.get(i);
            boolean selected = profile.ownerId.equals(selectedProfileId);
            boolean hovered = mouseX >= panelLeft + 8 && mouseX <= leftPaneRight - 2 && mouseY >= rowY && mouseY <= rowY + rowHeight;
            int color = selected ? 0x6644AA44 : (hovered ? 0x44444444 : 0x22222222);
            context.fill(panelLeft + 8, rowY, leftPaneRight - 2, rowY + rowHeight, color);

            renderPlayerHead(context, profile.ownerId, profile.ownerName, panelLeft + 12, rowY + 3, 16);
            String name = truncateText(profile.ownerName, PLAYLISTS_LEFT_WIDTH - 44);
            context.drawTextWithShadow(this.textRenderer, Text.literal(name), panelLeft + 32, rowY + 8, 0xFFFFFFFF);

            rowY += rowHeight + 2;
            drawn++;
        }

        if (drawn == 0) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No players"), panelLeft + (PLAYLISTS_LEFT_WIDTH / 2), contentTop + 24, 0xFF888888);
        }
        drawScrollbar(context, leftPaneRight - 5, profileAreaTop, profileAreaBottom, visibleProfiles.size(), profileVisibleRows, profileScrollOffset);

        ProfileSummary selected = getSelectedProfile();
        if (selected == null) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Select a player profile"), detailsLeft, contentTop + 28, 0xFF999999);
            return;
        }

        if (!selected.isSelf) {
            int headerTop = panelTop + 30;
            int headerBottom = headerTop + 19;
            context.fill(detailsLeft, headerTop, detailsRight, headerBottom, 0x33222222);
            renderPlayerHead(context, selected.ownerId, selected.ownerName, detailsLeft + 4, headerTop + 1, 16);
            String headerName = truncateText(selected.ownerName, detailsRight - detailsLeft - 28);
            context.drawTextWithShadow(
                    this.textRenderer,
                    Text.literal(headerName).styled(style -> style.withBold(true)),
                    detailsLeft + 24,
                    headerTop + 6,
                    0xFFFFFFFF
            );
        }

        if (openedProfilePlaylistId != null) {
            ProfilePlaylistSummary opened = findProfilePlaylistById(openedProfilePlaylistId);
            if (opened == null) {
                openedProfilePlaylistId = null;
                return;
            }
            int backY = contentTop + 28;
            context.fill(detailsLeft, backY, detailsLeft + 44, backY + 16, 0x33446699);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Back"), detailsLeft + 22, backY + 4, 0xFFFFFFFF);
            int queueSelectedX = detailsRight - 76;
            context.fill(queueSelectedX, backY, queueSelectedX + 72, backY + 16, 0x6633AA33);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Queue Selected"), queueSelectedX + 36, backY + 4, 0xFFFFFFFF);
            int headerTextRight = queueSelectedX - 6;
            int headerTextWidth = Math.max(30, headerTextRight - (detailsLeft + 52));
            context.drawTextWithShadow(this.textRenderer, Text.literal(truncateText(opened.name, headerTextWidth)), detailsLeft + 52, backY + 4, 0xFFFFFFFF);
            context.drawTextWithShadow(this.textRenderer, Text.literal("by " + truncateText(opened.ownerName, headerTextWidth - 18)), detailsLeft + 52, backY + 14, 0xFFAAAAAA);

            int detailListTop = contentTop + 50;
            int detailRowHeight = 18;
            int detailVisibleRows = Math.max(1, (contentBottom - detailListTop) / 21);
            playlistDetailScrollOffset = Math.max(0, Math.min(playlistDetailScrollOffset, Math.max(0, opened.tracks.size() - detailVisibleRows)));
            int detailEnd = Math.min(opened.tracks.size(), playlistDetailScrollOffset + detailVisibleRows);
            int py = detailListTop;
            for (int i = playlistDetailScrollOffset; i < detailEnd && py + detailRowHeight <= contentBottom; i++) {
                ProfileTrackEntry track = opened.tracks.get(i);
                context.fill(detailsLeft, py, detailsRight - 4, py + 18, 0x33222222);
                int checkboxX = detailsLeft + 4;
                boolean selectedTrack = selectedDetailTrackIndexes.contains(i);
                context.fill(checkboxX, py + 4, checkboxX + 10, py + 14, selectedTrack ? 0x6677CC55 : 0x66333333);
                if (selectedTrack) {
                    context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("X"), checkboxX + 5, py + 5, 0xFFFFFFFF);
                }
                int queueX = detailsRight - 56;
                int addX = detailsRight - 110;
                context.fill(addX, py + 2, addX + 50, py + 16, 0x663366FF);
                context.fill(queueX, py + 2, queueX + 50, py + 16, 0x6633AA33);
                context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Playlist"), addX + 25, py + 6, 0xFFFFFFFF);
                context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Queue"), queueX + 25, py + 6, 0xFFFFFFFF);
                String trackTitle = truncateText(track.title, addX - (detailsLeft + 18) - 10);
                context.drawTextWithShadow(this.textRenderer, Text.literal(trackTitle), detailsLeft + 18, py + 5, 0xFFFFFFFF);
                py += 21;
            }
            drawScrollbar(context, detailsRight - 3, detailListTop, contentBottom, opened.tracks.size(), detailVisibleRows, playlistDetailScrollOffset);
            if (opened.tracks.isEmpty()) {
                context.drawTextWithShadow(this.textRenderer, Text.literal("This playlist has no tracks"), detailsLeft, detailListTop + 4, 0xFF999999);
            }
            return;
        }

        List<ProfilePlaylistSummary> filteredPlaylists = getPlaylistsForRightPane(selected);
        int listTop = contentTop + 28;
        int visibleRows = Math.max(1, (contentBottom - listTop) / 21);
        profilePlaylistScrollOffset = Math.max(0, Math.min(profilePlaylistScrollOffset, Math.max(0, filteredPlaylists.size() - visibleRows)));
        int end = Math.min(filteredPlaylists.size(), profilePlaylistScrollOffset + visibleRows);
        int py = listTop;
        for (int i = profilePlaylistScrollOffset; i < end && py + 18 <= contentBottom; i++) {
            ProfilePlaylistSummary summary = filteredPlaylists.get(i);
            boolean playlistSelected = summary.id.equals(selectedUserPlaylistId);
            context.fill(detailsLeft, py, detailsRight - 4, py + 18, playlistSelected ? 0x66448844 : 0x33222222);
            int likeX = detailsRight - 18;
            context.fill(likeX, py + 2, likeX + 14, py + 16, summary.likedByRequester ? 0x66440000 : 0x66333333);
            context.drawCenteredTextWithShadow(
                    this.textRenderer,
                    Text.literal(summary.likedByRequester ? HEART_FILLED : HEART_OUTLINE),
                    likeX + 7,
                    py + 6,
                    summary.likedByRequester ? 0xFFFF5555 : 0xFFAAAAAA
            );
            context.drawTextWithShadow(
                    this.textRenderer,
                    Text.literal(truncateText(summary.name, 136) + " - " + summary.trackCount + " songs"),
                    detailsLeft + 4,
                    py + 5,
                    0xFFFFFFFF
            );
            py += 21;
        }
        drawScrollbar(context, detailsRight - 3, listTop, contentBottom, filteredPlaylists.size(), visibleRows, profilePlaylistScrollOffset);

        if (filteredPlaylists.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("No playlists in this section"), detailsLeft, listTop + 6, 0xFF888888);
        }
    }

    private void renderRecentlyPlayedTab(DrawContext context, int panelLeft, int panelTop) {
        int listTop = panelTop + CONTENT_TOP_NO_SEARCH;
        int listBottom = getListBottom(panelTop);
        int y = listTop;
        int rowHeight = 20;

        if (recentlyPlayed.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No recently played tracks"), panelLeft + PANEL_WIDTH / 2, listTop + 20, 0xFF888888);
            return;
        }

        int maxRows = Math.max(1, Math.min((listBottom - listTop) / (rowHeight + 2), RECENT_UI_MAX_VISIBLE));
        recentlyPlayedScrollOffset = Math.max(0, Math.min(recentlyPlayedScrollOffset, Math.max(0, recentlyPlayed.size() - maxRows)));
        int end = Math.min(recentlyPlayed.size(), recentlyPlayedScrollOffset + maxRows);
        for (int i = recentlyPlayedScrollOffset; i < end && y + rowHeight <= listBottom; i++) {
            RecentlyPlayedEntry entry = recentlyPlayed.get(i);
            context.fill(panelLeft + 10, y, panelLeft + PANEL_WIDTH - 10, y + rowHeight - 2, 0x22222222);
            String text = truncateText(entry.title + " (" + entry.duration + ")", PANEL_WIDTH - 100);
            context.drawTextWithShadow(this.textRenderer, Text.literal(text), panelLeft + 14, y + 5, 0xFFFFFFFF);
            context.drawTextWithShadow(this.textRenderer, Text.literal(formatTimeAgo(entry.playedAtEpochMs)), panelLeft + PANEL_WIDTH - 88, y + 5, 0xFFAAAAAA);
            y += rowHeight + 2;
        }
        drawScrollbar(context, panelLeft + PANEL_WIDTH - 5, listTop, listBottom, recentlyPlayed.size(), maxRows, recentlyPlayedScrollOffset);
    }

    private void renderSpotifyPreviewModal(DrawContext context, int mouseX, int mouseY) {
        if (!showSpotifyPreviewDialog || spotifyImportPreviewState == null) {
            return;
        }

        int left = (this.width / 2) - (SPOTIFY_PREVIEW_MODAL_WIDTH / 2);
        int top = (this.height / 2) - (SPOTIFY_PREVIEW_MODAL_HEIGHT / 2);
        int right = left + SPOTIFY_PREVIEW_MODAL_WIDTH;
        int bottom = top + SPOTIFY_PREVIEW_MODAL_HEIGHT;

        context.fill(0, 0, this.width, this.height, 0x88000000);
        context.fill(left, top, right, bottom, 0xF0101010);
        context.drawHorizontalLine(left, right - 1, top, 0xFFAAAAAA);
        context.drawHorizontalLine(left, right - 1, bottom - 1, 0xFF666666);
        context.drawVerticalLine(left, top, bottom - 1, 0xFFAAAAAA);
        context.drawVerticalLine(right - 1, top, bottom - 1, 0xFF666666);

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Spotify Import Preview"), this.width / 2, top + 8, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Created by " + truncateText(spotifyImportPreviewState.spotifyOwnerName, 160)), left + 12, top + 24, 0xFFCCCCCC);

        int listTop = top + 79;
        int listBottom = bottom - 30;
        int y = listTop;
        int rowHeight = 16;
        for (int i = 0; i < spotifyImportPreviewState.tracks.size() && y + rowHeight <= listBottom; i++) {
            SpotifyPreviewTrack track = spotifyImportPreviewState.tracks.get(i);
            boolean checked = selectedSpotifyTrackIds.contains(track.spotifyTrackId);
            context.fill(left + 12, y, right - 12, y + rowHeight, 0x33222222);
            context.fill(left + 14, y + 3, left + 24, y + 13, checked ? 0x6677CC55 : 0x66333333);
            if (checked) {
                context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("X"), left + 19, y + 4, 0xFFFFFFFF);
            }
            String confidence = estimateSpotifyPreviewConfidence(track);
            int confidenceColor = "High".equals(confidence) ? 0xFF66FF66 : ("Medium".equals(confidence) ? 0xFFFFCC66 : 0xFFFF8888);
            context.drawTextWithShadow(this.textRenderer, Text.literal(confidence), right - 54, y + 4, confidenceColor);
            String label = truncateText(track.title + " - " + track.artist + (track.duration.isBlank() ? "" : " (" + track.duration + ")"), SPOTIFY_PREVIEW_MODAL_WIDTH - 104);
            context.drawTextWithShadow(this.textRenderer, Text.literal(label), left + 30, y + 4, 0xFFFFFFFF);
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

    private void renderContextMenu(DrawContext context, int mouseX, int mouseY) {
        if (!showContextMenu || contextMenuState == null) {
            return;
        }

        int rowH = 16;
        int width = 154;
        int x = Math.min(contextMenuX, this.width - width - 4);
        int y = Math.min(contextMenuY, this.height - 120);
        int options = contextMenuState.type == ContextType.PLAYLIST ? 3 : 2;
        int height = 4 + (options * rowH);
        context.fill(x, y, x + width, y + height, 0xEE101010);
        context.drawHorizontalLine(x, x + width - 1, y, 0xFFAAAAAA);
        context.drawHorizontalLine(x, x + width - 1, y + height - 1, 0xFF666666);
        context.drawVerticalLine(x, y, y + height - 1, 0xFFAAAAAA);
        context.drawVerticalLine(x + width - 1, y, y + height - 1, 0xFF666666);

        String[] labels = contextMenuState.type == ContextType.PLAYLIST
                ? new String[]{"Queue All", "Shuffle Queue", "Add to Playlist"}
                : new String[]{"Add to Queue", "Add to Playlist"};
        String[] hints = contextMenuState.type == ContextType.PLAYLIST
                ? new String[]{"1", "2", "3"}
                : new String[]{"1", "2"};
        int hoverIdx = -1;
        for (int i = 0; i < labels.length; i++) {
            int ry = y + 2 + (i * rowH);
            boolean hovered = mouseX >= x + 2 && mouseX <= x + width - 2 && mouseY >= ry && mouseY <= ry + rowH - 1;
            if (hovered) {
                hoverIdx = i;
                context.fill(x + 2, ry, x + width - 2, ry + rowH - 1, 0x6644AA44);
            }
            context.drawTextWithShadow(this.textRenderer, Text.literal(labels[i]), x + 6, ry + 4, 0xFFFFFFFF);
            context.drawTextWithShadow(this.textRenderer, Text.literal(hints[i]), x + width - 14, ry + 4, 0xFFBBBBBB);
            if (i == labels.length - 1) {
                context.drawTextWithShadow(this.textRenderer, Text.literal(">"), x + width - 24, ry + 4, 0xFFCCCCCC);
            }
            if (i < labels.length - 1) {
                context.fill(x + 5, ry + rowH - 1, x + width - 5, ry + rowH, 0x55222222);
            }
        }
        contextMenuState.hoveredIndex = hoverIdx;

        boolean mouseInsideExistingSubmenu = mouseX >= contextMenuState.submenuX
                && mouseX <= contextMenuState.submenuX + 170
                && mouseY >= contextMenuState.submenuY
                && mouseY <= contextMenuState.submenuY + (4 + (Math.max(1, contextMenuState.submenuRows) * rowH));
        boolean showPlaylistSubmenu = hoverIdx == (labels.length - 1) || mouseInsideExistingSubmenu;
        if (!showPlaylistSubmenu) {
            return;
        }

        int subW = 184;
        int subX = Math.min(x + width + 2, this.width - subW - 4);
        int subRows = Math.max(1, Math.min(8, userPlaylists.size() + 1));
        int subH = 4 + (subRows * rowH);
        int subY = y + 2 + ((labels.length - 1) * rowH);
        if (subY + subH > this.height - 4) {
            subY = Math.max(4, this.height - subH - 4);
        }
        contextMenuState.submenuX = subX;
        contextMenuState.submenuY = subY;
        contextMenuState.submenuRows = subRows;
        context.fill(subX, subY, subX + subW, subY + subH, 0xEE101010);
        context.drawHorizontalLine(subX, subX + subW - 1, subY, 0xFFAAAAAA);
        context.drawHorizontalLine(subX, subX + subW - 1, subY + subH - 1, 0xFF666666);
        context.drawVerticalLine(subX, subY, subY + subH - 1, 0xFFAAAAAA);
        context.drawVerticalLine(subX + subW - 1, subY, subY + subH - 1, 0xFF666666);

        int subHover = -1;
        for (int i = 0; i < subRows; i++) {
            int ry = subY + 2 + (i * rowH);
            boolean hovered = mouseX >= subX + 2 && mouseX <= subX + subW - 2 && mouseY >= ry && mouseY <= ry + rowH - 1;
            if (hovered) {
                subHover = i;
                context.fill(subX + 2, ry, subX + subW - 2, ry + rowH - 1, 0x664466AA);
            }
            if (i == 0) {
                context.drawTextWithShadow(this.textRenderer, Text.literal("+ | New Playlist"), subX + 6, ry + 4, 0xFFFFFFFF);
                context.drawTextWithShadow(this.textRenderer, Text.literal("N"), subX + subW - 12, ry + 4, 0xFFBBBBBB);
            } else {
                UserPlaylistSummary summary = userPlaylists.get(i - 1);
                context.drawTextWithShadow(
                        this.textRenderer,
                        Text.literal(truncateText(summary.name, subW - 24)),
                        subX + 6,
                        ry + 4,
                        0xFFFFFFFF
                );
                if (i >= 1 && i <= 7) {
                    context.drawTextWithShadow(this.textRenderer, Text.literal(String.valueOf(i + 2)), subX + subW - 12, ry + 4, 0xFFBBBBBB);
                }
            }
        }
        contextMenuState.submenuHoveredIndex = subHover;
    }

    private boolean handleContextMenuLeftClick(double mouseX, double mouseY) {
        if (contextMenuState == null) {
            return false;
        }
        int rowH = 16;
        int baseW = 154;
        int baseX = Math.min(contextMenuX, this.width - baseW - 4);
        int baseY = Math.min(contextMenuY, this.height - 120);
        int options = contextMenuState.type == ContextType.PLAYLIST ? 3 : 2;
        int baseH = 4 + (options * rowH);
        boolean inBase = mouseX >= baseX && mouseX <= baseX + baseW && mouseY >= baseY && mouseY <= baseY + baseH;
        if (inBase) {
            int idx = (int) ((mouseY - baseY - 2) / rowH);
            if (idx < 0 || idx >= options) {
                return true;
            }
            if (contextMenuState.type == ContextType.PLAYLIST) {
                if (idx == 0) {
                    queueAllFromProfilePlaylist(contextMenuState.playlistSummary, false);
                    return true;
                }
                if (idx == 1) {
                    queueAllFromProfilePlaylist(contextMenuState.playlistSummary, true);
                    return true;
                }
                return handleContextSubmenuClick(mouseX, mouseY);
            }
            if (idx == 0) {
                addToQueue(contextMenuState.songResult);
                return true;
            }
            return handleContextSubmenuClick(mouseX, mouseY);
        }
        return handleContextSubmenuClick(mouseX, mouseY);
    }

    private boolean handleContextSubmenuClick(double mouseX, double mouseY) {
        if (contextMenuState == null || contextMenuState.submenuRows <= 0) {
            return false;
        }
        int rowH = 16;
        int subW = 184;
        int subH = 4 + (contextMenuState.submenuRows * rowH);
        boolean inSub = mouseX >= contextMenuState.submenuX && mouseX <= contextMenuState.submenuX + subW
                && mouseY >= contextMenuState.submenuY && mouseY <= contextMenuState.submenuY + subH;
        if (!inSub) {
            return false;
        }
        int idx = (int) ((mouseY - contextMenuState.submenuY - 2) / rowH);
        if (idx < 0 || idx >= contextMenuState.submenuRows) {
            return true;
        }
        if (idx == 0) {
            openCreatePlaylistDialog();
            if (contextMenuState.songResult != null) {
                pendingPlaylistSearchResult = contextMenuState.songResult;
            }
            return true;
        }
        int playlistIdx = idx - 1;
        if (playlistIdx < 0 || playlistIdx >= userPlaylists.size() || contextMenuState.songResult == null) {
            return true;
        }
        UserPlaylistSummary summary = userPlaylists.get(playlistIdx);
        SearchResult song = contextMenuState.songResult;
        ClientPlayNetworking.send(new AddToUserPlaylistPacket(summary.id, song.videoId, song.title, song.duration));
        playUiSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        return true;
    }

    private void queueAllFromProfilePlaylist(ProfilePlaylistSummary summary, boolean shuffled) {
        if (summary == null || summary.tracks == null || summary.tracks.isEmpty()) {
            return;
        }
        List<ProfileTrackEntry> tracks = new ArrayList<>(summary.tracks);
        if (shuffled) {
            java.util.Collections.shuffle(tracks);
        }
        for (ProfileTrackEntry track : tracks) {
            ClientPlayNetworking.send(new AddToPlaylistPacket(track.videoId, track.title, track.duration));
        }
        currentTab = 1;
        playUiSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, shuffled ? 1.35f : 1.15f);
    }

    private void drawSubtab(DrawContext context, int x, int y, int width, String label, boolean active) {
        context.fill(x, y, x + width, y + 16, active ? 0x8844AA44 : 0x33444444);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(truncateText(label, width - 6)), x + (width / 2), y + 4, 0xFFFFFFFF);
    }

    private void drawScrollbar(DrawContext context, int x, int top, int bottom, int totalItems, int visibleItems, int scrollOffset) {
        if (bottom <= top) {
            return;
        }
        context.fill(x, top, x + 3, bottom, 0x44222222);
        if (totalItems <= visibleItems || totalItems <= 0) {
            context.fill(x, top, x + 3, bottom, 0x66555555);
            return;
        }

        int trackHeight = bottom - top;
        int thumbHeight = Math.max(14, (int) ((visibleItems / (double) totalItems) * trackHeight));
        int maxOffset = Math.max(1, totalItems - visibleItems);
        int maxTravel = Math.max(1, trackHeight - thumbHeight);
        int thumbTop = top + (int) ((scrollOffset / (double) maxOffset) * maxTravel);
        context.fill(x, thumbTop, x + 3, thumbTop + thumbHeight, 0x99BBBBBB);
    }

    private void renderPlayerHead(DrawContext context, String ownerId, String playerName, int x, int y, int size) {
        PlayerListEntry entry = findPlayerListEntry(ownerId, playerName);
        if (entry != null) {
            PlayerSkinDrawer.draw(context, entry.getSkinTextures(), x, y, size);
            return;
        }

        int hash = (ownerId == null ? playerName : ownerId).hashCode();
        int r = 70 + Math.abs(hash % 120);
        int g = 70 + Math.abs((hash / 7) % 120);
        int b = 70 + Math.abs((hash / 13) % 120);
        int color = 0x88000000 | (r << 16) | (g << 8) | b;
        context.fill(x, y, x + size, y + size, color);
        String initial = playerName == null || playerName.isEmpty() ? "?" : playerName.substring(0, 1).toUpperCase();
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(initial), x + (size / 2), y + 4, 0xFFFFFFFF);
    }

    private UUID parseOwnerUuid(String ownerId, String ownerName) {
        if (ownerId != null && !ownerId.isBlank()) {
            try {
                return UUID.fromString(ownerId);
            } catch (IllegalArgumentException ignored) {
            }
        }
        String source = ownerName == null ? "mineify-unknown" : ("mineify-" + ownerName.toLowerCase(Locale.ROOT));
        return UUID.nameUUIDFromBytes(source.getBytes());
    }

    private PlayerListEntry findPlayerListEntry(String ownerId, String playerName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.networkHandler == null) {
            return null;
        }

        UUID targetUuid = parseOwnerUuid(ownerId, playerName);
        for (PlayerListEntry entry : client.player.networkHandler.getPlayerList()) {
            if (entry.getProfile().id().equals(targetUuid) || entry.getProfile().name().equalsIgnoreCase(playerName)) {
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

    private List<ProfilePlaylistSummary> getPlaylistsForRightPane(ProfileSummary profile) {
        if (profile == null) {
            return List.of();
        }
        return rightPaneTab == 0 ? profile.playlists : profile.likedPlaylists;
    }

    private ProfilePlaylistSummary findProfilePlaylistById(String playlistId) {
        if (playlistId == null || playlistId.isBlank()) {
            return null;
        }
        for (ProfileSummary profile : profiles) {
            for (ProfilePlaylistSummary summary : profile.playlists) {
                if (summary.id.equals(playlistId)) {
                    return summary;
                }
            }
            for (ProfilePlaylistSummary summary : profile.likedPlaylists) {
                if (summary.id.equals(playlistId)) {
                    return summary;
                }
            }
        }
        return null;
    }

    private void ensureSelectedProfile() {
        if (profiles.isEmpty()) {
            selectedProfileId = null;
            selectedUserPlaylistId = null;
            openedProfilePlaylistId = null;
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
        openedProfilePlaylistId = null;
    }

    private boolean isSelfProfileSelected() {
        ProfileSummary selected = getSelectedProfile();
        return selected != null && selected.isSelf;
    }

    private void startSpotifyImport() {
        if (!isSelfProfileSelected() || spotifyLinkField == null) {
            return;
        }
        String spotifyUrl = spotifyLinkField.getText().trim();
        if (!isValidSpotifyUrl(spotifyUrl)) {
            return;
        }
        ClientPlayNetworking.send(new RequestSpotifyImportPreviewPacket(spotifyUrl));
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("Loading Spotify playlist preview..."), false);
        }
    }

    private boolean isValidSpotifyUrl(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("https://open.spotify.com/playlist/");
    }

    private String estimateSpotifyPreviewConfidence(SpotifyPreviewTrack track) {
        String query = (track.query == null ? "" : track.query).toLowerCase(Locale.ROOT);
        String title = (track.title == null ? "" : track.title).toLowerCase(Locale.ROOT);
        String artist = (track.artist == null ? "" : track.artist).toLowerCase(Locale.ROOT);
        int score = 0;
        if (!title.isBlank() && query.contains(title)) {
            score += 2;
        }
        if (!artist.isBlank() && query.contains(artist.split(",")[0].trim())) {
            score += 2;
        }
        if (query.contains("official")) {
            score += 1;
        }
        if (score >= 4) {
            return "High";
        }
        if (score >= 2) {
            return "Medium";
        }
        return "Low";
    }

    private String estimatePromptOptionConfidence(SpotifyChoiceOption option) {
        if (spotifyPromptState == null || option == null) {
            return "Low";
        }
        String yt = (option.title == null ? "" : option.title).toLowerCase(Locale.ROOT);
        String spTitle = spotifyPromptState.spotifyTitle == null ? "" : spotifyPromptState.spotifyTitle.toLowerCase(Locale.ROOT);
        String spArtist = spotifyPromptState.spotifyArtist == null ? "" : spotifyPromptState.spotifyArtist.toLowerCase(Locale.ROOT);
        int score = 0;
        if (!spTitle.isBlank() && yt.contains(spTitle)) {
            score += 2;
        }
        if (!spArtist.isBlank() && yt.contains(spArtist.split(",")[0].trim())) {
            score += 2;
        }
        if (yt.contains("karaoke") || yt.contains("slowed") || yt.contains("nightcore")) {
            score -= 1;
        }
        if (score >= 3) {
            return "High";
        }
        if (score >= 1) {
            return "Medium";
        }
        return "Low";
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
                String badge = estimatePromptOptionConfidence(option);
                button.setMessage(Text.literal((i + 1) + ". [" + badge + "] " + truncateText(option.title, SPOTIFY_MODAL_WIDTH - 82)));
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

    private void updateSpotifyPreviewControls() {
        boolean visible = showSpotifyPreviewDialog && spotifyImportPreviewState != null;
        if (spotifyImportNameField != null) {
            spotifyImportNameField.visible = visible;
            spotifyImportNameField.setEditable(visible);
        }
        if (spotifyImportPrivacyButton != null) {
            spotifyImportPrivacyButton.visible = visible;
            spotifyImportPrivacyButton.active = visible;
            spotifyImportPrivacyButton.setMessage(Text.literal(spotifyImportPublic ? "Privacy: Public" : "Privacy: Private"));
        }
        if (spotifyPreviewImportButton != null) {
            spotifyPreviewImportButton.visible = visible;
            spotifyPreviewImportButton.active = visible && !selectedSpotifyTrackIds.isEmpty();
        }
        if (spotifyPreviewCancelButton != null) {
            spotifyPreviewCancelButton.visible = visible;
            spotifyPreviewCancelButton.active = visible;
        }
    }

    private void closeSpotifyPreviewDialog() {
        showSpotifyPreviewDialog = false;
        spotifyImportPreviewState = null;
        selectedSpotifyTrackIds.clear();
        spotifyImportPublic = true;
        updateSpotifyPreviewControls();
    }

    private void confirmSpotifyPreviewImport() {
        if (spotifyImportPreviewState == null || spotifyImportNameField == null || selectedSpotifyTrackIds.isEmpty()) {
            return;
        }
        String name = spotifyImportNameField.getText().trim();
        if (name.isEmpty()) {
            name = spotifyImportPreviewState.spotifyPlaylistName;
        }
        ClientPlayNetworking.send(new ConfirmSpotifyImportPacket(name, spotifyImportPublic, new ArrayList<>(selectedSpotifyTrackIds)));
        closeSpotifyPreviewDialog();
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

        int maxRows = Math.max(1, (MODAL_HEIGHT - 56) / (rowHeight + 3));
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
        if (showContextMenu) {
            if (click.button() == 0) {
                if (handleContextMenuLeftClick(click.x(), click.y())) {
                    return true;
                }
            }
            showContextMenu = false;
            contextMenuState = null;
            if (click.button() != 1) {
                return super.mouseClicked(click, doubled);
            }
        }

        if (showSpotifyPreviewDialog) {
            if (click.button() != 0 || spotifyImportPreviewState == null) {
                return true;
            }
            double mouseX = click.x();
            double mouseY = click.y();
            int left = (this.width / 2) - (SPOTIFY_PREVIEW_MODAL_WIDTH / 2);
            int top = (this.height / 2) - (SPOTIFY_PREVIEW_MODAL_HEIGHT / 2);
            int right = left + SPOTIFY_PREVIEW_MODAL_WIDTH;
            int bottom = top + SPOTIFY_PREVIEW_MODAL_HEIGHT;
            if (mouseX < left || mouseX > right || mouseY < top || mouseY > bottom) {
                closeSpotifyPreviewDialog();
                return true;
            }

            int rowY = top + 79;
            int rowHeight = 16;
            int listBottom = bottom - 30;
            for (int i = 0; i < spotifyImportPreviewState.tracks.size() && rowY + rowHeight <= listBottom; i++) {
                if (mouseX >= left + 14 && mouseX <= right - 12 && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                    String trackId = spotifyImportPreviewState.tracks.get(i).spotifyTrackId;
                    if (selectedSpotifyTrackIds.contains(trackId)) {
                        selectedSpotifyTrackIds.remove(trackId);
                    } else {
                        selectedSpotifyTrackIds.add(trackId);
                    }
                    updateSpotifyPreviewControls();
                    return true;
                }
                rowY += rowHeight + 2;
            }
            return super.mouseClicked(click, doubled);
        }

        if (showSpotifyPromptDialog) {
            if (click.button() == 0) {
                int left = (this.width / 2) - (SPOTIFY_MODAL_WIDTH / 2);
                int top = (this.height / 2) - (SPOTIFY_MODAL_HEIGHT / 2);
                int right = left + SPOTIFY_MODAL_WIDTH;
                int bottom = top + SPOTIFY_MODAL_HEIGHT;
                if (click.x() < left || click.x() > right || click.y() < top || click.y() > bottom) {
                    skipSpotifyOption();
                    return true;
                }
            }
            return super.mouseClicked(click, doubled);
        }

        if (showCreatePlaylistDialog) {
            if (click.button() == 0) {
                int left = (this.width / 2) - (MODAL_WIDTH / 2);
                int top = (this.height / 2) - (MODAL_HEIGHT / 2);
                int right = left + MODAL_WIDTH;
                int bottom = top + MODAL_HEIGHT;
                if (click.x() < left || click.x() > right || click.y() < top || click.y() > bottom) {
                    closeCreatePlaylistDialog();
                    return true;
                }
            }
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
            int maxRows = Math.max(1, (MODAL_HEIGHT - 56) / (rowHeight + 3));
            for (int i = 0; i < userPlaylists.size() && i < maxRows; i++) {
                if (mouseX >= left + 12 && mouseX <= right - 12 && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                    addSearchResultToUserPlaylist(userPlaylists.get(i));
                    closePlaylistPicker();
                    return true;
                }
                rowY += rowHeight + 3;
            }
            return true;
        }

        if (click.button() == 0 || click.button() == 1) {
            double mouseX = click.x();
            double mouseY = click.y();

            int centerX = this.width / 2;
            int centerY = this.height / 2;
            int panelLeft = centerX - PANEL_WIDTH / 2;
            int panelTop = centerY - PANEL_HEIGHT / 2;
            int listTop = currentTab == 0 ? panelTop + CONTENT_TOP_WITH_SEARCH : panelTop + CONTENT_TOP_NO_SEARCH;
            int listBottom = getListBottom(panelTop);
            int itemHeight = currentTab == 0 ? getSearchRowHeight() : getQueueRowHeight();

            if (currentTab == 0 && !searchResults.isEmpty()) {
                int y = listTop;
                int visibleRows = Math.max(1, (listBottom - listTop) / itemHeight);
                int end = Math.min(searchResults.size(), searchScrollOffset + visibleRows);
                for (int i = searchScrollOffset; i < end && y + itemHeight <= listBottom; i++) {
                    int rowRight = panelLeft + PANEL_WIDTH - 10;
                    int queueButtonX = rowRight - SEARCH_QUEUE_BUTTON_WIDTH;
                    int playlistButtonX = queueButtonX - SEARCH_BUTTON_GAP - SEARCH_PLAYLIST_BUTTON_WIDTH;
                    int buttonY = y + 4;

                    if (click.button() == 1 && mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                            && mouseY >= y && mouseY <= y + itemHeight) {
                        contextMenuState = ContextMenuState.forSong(searchResults.get(i));
                        contextMenuX = (int) mouseX;
                        contextMenuY = (int) mouseY;
                        showContextMenu = true;
                        return true;
                    }

                    if (mouseX >= queueButtonX && mouseX <= queueButtonX + SEARCH_QUEUE_BUTTON_WIDTH
                            && mouseY >= buttonY && mouseY <= buttonY + 16) {
                        if (click.button() != 0) {
                            return true;
                        }
                        addToQueue(searchResults.get(i));
                        return true;
                    }

                    if (mouseX >= playlistButtonX && mouseX <= playlistButtonX + SEARCH_PLAYLIST_BUTTON_WIDTH
                            && mouseY >= buttonY && mouseY <= buttonY + 16) {
                        if (click.button() != 0) {
                            return true;
                        }
                        addToPlaylistLibrary(searchResults.get(i));
                        return true;
                    }
                    y += itemHeight;
                }
            }

            if (currentTab == 1 && !playlist.isEmpty()) {
                int y = listTop;
                int visibleQueueRows = Math.max(1, (listBottom - listTop) / itemHeight);
                int queueEnd = Math.min(playlist.size(), playlistScrollOffset + visibleQueueRows);
                for (int i = playlistScrollOffset; i < queueEnd && y + itemHeight <= listBottom; i++) {
                    PlaylistEntry entry = playlist.get(i);
                    if (click.button() == 1 && mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                            && mouseY >= y && mouseY <= y + itemHeight) {
                        contextMenuState = ContextMenuState.forQueueSong(entry);
                        contextMenuX = (int) mouseX;
                        contextMenuY = (int) mouseY;
                        showContextMenu = true;
                        return true;
                    }
                    if (entry.addedBy.equals(currentPlayerName)) {
                        int removeX = panelLeft + PANEL_WIDTH - 25;
                        int removeY = y + 5;
                        if (mouseX >= removeX && mouseX <= removeX + 15
                                && mouseY >= removeY && mouseY <= removeY + 15) {
                            if (click.button() != 0) {
                                return true;
                            }
                            removeFromPlaylist(entry.videoId);
                            return true;
                        }
                    }

                    if (mouseX >= panelLeft + 10 && mouseX <= panelLeft + PANEL_WIDTH - 10
                            && mouseY >= y && mouseY < y + itemHeight) {
                        if (click.button() != 0) {
                            return true;
                        }
                        if (nowPlaying != null && i == 0) {
                            return true;
                        }
                        queueDragActive = true;
                        queueDragFromIndex = i;
                        queueDragTargetIndex = i;
                        return true;
                    }
                    y += itemHeight;
                }
            }

            if (currentTab == 2) {
                int contentTop = panelTop + CONTENT_TOP_WITH_SEARCH;
                int contentBottom = getListBottom(panelTop);
                int leftPaneRight = panelLeft + PLAYLISTS_LEFT_WIDTH;
                int detailsLeft = leftPaneRight + 8;
                int detailsRight = panelLeft + PANEL_WIDTH - 10;

                int subtabY = contentTop + 4;
                int playlistsTabX = panelLeft + PLAYLISTS_LEFT_WIDTH + 8;
                int playlistsTabWidth = 78;
                int likedTabX = playlistsTabX + playlistsTabWidth + 4;
                int likedTabWidth = 110;
                if (mouseX >= playlistsTabX && mouseX <= playlistsTabX + playlistsTabWidth
                        && mouseY >= subtabY && mouseY <= subtabY + 16) {
                    rightPaneTab = 0;
                    openedProfilePlaylistId = null;
                    selectedUserPlaylistId = null;
                    profilePlaylistScrollOffset = 0;
                    selectedDetailTrackIndexes.clear();
                    return true;
                }
                if (mouseX >= likedTabX && mouseX <= likedTabX + likedTabWidth
                        && mouseY >= subtabY && mouseY <= subtabY + 16) {
                    rightPaneTab = 1;
                    openedProfilePlaylistId = null;
                    selectedUserPlaylistId = null;
                    profilePlaylistScrollOffset = 0;
                    selectedDetailTrackIndexes.clear();
                    return true;
                }

                List<ProfileSummary> visibleProfiles = getFilteredProfiles();
                int rowY = contentTop + 8;
                int rowHeight = 22;
                int visibleProfileRows = Math.max(1, (contentBottom - rowY) / (rowHeight + 2));
                int profileEnd = Math.min(visibleProfiles.size(), profileScrollOffset + visibleProfileRows);
                for (int i = profileScrollOffset; i < profileEnd && rowY + rowHeight <= contentBottom; i++) {
                    if (mouseX >= panelLeft + 8 && mouseX <= leftPaneRight - 2 && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                        selectedProfileId = visibleProfiles.get(i).ownerId;
                        openedProfilePlaylistId = null;
                        selectedUserPlaylistId = null;
                        profilePlaylistScrollOffset = 0;
                        playlistDetailScrollOffset = 0;
                        selectedDetailTrackIndexes.clear();
                        return true;
                    }
                    rowY += rowHeight + 2;
                }

                ProfileSummary selected = getSelectedProfile();
                if (selected != null) {
                    if (openedProfilePlaylistId != null) {
                        int backY = contentTop + 28;
                        if (mouseX >= detailsLeft && mouseX <= detailsLeft + 44 && mouseY >= backY && mouseY <= backY + 16) {
                            openedProfilePlaylistId = null;
                            playlistDetailScrollOffset = 0;
                            selectedDetailTrackIndexes.clear();
                            return true;
                        }
                        int queueSelectedX = detailsRight - 76;
                        if (mouseX >= queueSelectedX && mouseX <= queueSelectedX + 72 && mouseY >= backY && mouseY <= backY + 16) {
                            ProfilePlaylistSummary opened = findProfilePlaylistById(openedProfilePlaylistId);
                            if (opened != null) {
                                for (Integer idx : selectedDetailTrackIndexes) {
                                    if (idx != null && idx >= 0 && idx < opened.tracks.size()) {
                                        ProfileTrackEntry t = opened.tracks.get(idx);
                                        addToQueue(new SearchResult(t.videoId, t.title, opened.ownerName, t.duration, ""));
                                    }
                                }
                                currentTab = 1;
                            }
                            return true;
                        }
                        ProfilePlaylistSummary opened = findProfilePlaylistById(openedProfilePlaylistId);
                        if (opened != null) {
                            int py = contentTop + 50;
                            int detailVisibleRows = Math.max(1, (contentBottom - py) / 21);
                            int detailEnd = Math.min(opened.tracks.size(), playlistDetailScrollOffset + detailVisibleRows);
                            for (int i = playlistDetailScrollOffset; i < detailEnd && py + 18 <= contentBottom; i++) {
                                int checkboxX = detailsLeft + 4;
                                if (mouseX >= checkboxX && mouseX <= checkboxX + 10 && mouseY >= py + 4 && mouseY <= py + 14) {
                                    if (selectedDetailTrackIndexes.contains(i)) {
                                        selectedDetailTrackIndexes.remove(i);
                                    } else {
                                        selectedDetailTrackIndexes.add(i);
                                    }
                                    return true;
                                }
                                int queueX = detailsRight - 56;
                                int addX = detailsRight - 110;
                                if (click.button() == 1 && mouseX >= detailsLeft && mouseX <= detailsRight - 6 && mouseY >= py && mouseY <= py + 18) {
                                    contextMenuState = ContextMenuState.forProfileTrack(
                                            new SearchResult(opened.tracks.get(i).videoId, opened.tracks.get(i).title, opened.ownerName, opened.tracks.get(i).duration, "")
                                    );
                                    contextMenuX = (int) mouseX;
                                    contextMenuY = (int) mouseY;
                                    showContextMenu = true;
                                    return true;
                                }
                                if (mouseX >= queueX && mouseX <= queueX + 50 && mouseY >= py + 2 && mouseY <= py + 16) {
                                    if (click.button() != 0) {
                                        return true;
                                    }
                                    ProfileTrackEntry track = opened.tracks.get(i);
                                    addToQueue(new SearchResult(track.videoId, track.title, opened.ownerName, track.duration, ""));
                                    return true;
                                }
                                if (mouseX >= addX && mouseX <= addX + 50 && mouseY >= py + 2 && mouseY <= py + 16) {
                                    if (click.button() != 0) {
                                        return true;
                                    }
                                    ProfileTrackEntry track = opened.tracks.get(i);
                                    addToPlaylistLibrary(new SearchResult(track.videoId, track.title, opened.ownerName, track.duration, ""));
                                    return true;
                                }
                                py += 21;
                            }
                        }
                        boolean inProfilesContent = mouseX >= panelLeft + 8 && mouseX <= panelLeft + PANEL_WIDTH - 10
                                && mouseY >= contentTop && mouseY <= contentBottom;
                        if (inProfilesContent) {
                            return true;
                        }
                    }

                    List<ProfilePlaylistSummary> filtered = getPlaylistsForRightPane(selected);
                    int py = contentTop + 28;
                    int playlistVisibleRows = Math.max(1, (contentBottom - py) / 21);
                    int playlistEnd = Math.min(filtered.size(), profilePlaylistScrollOffset + playlistVisibleRows);
                    for (int i = profilePlaylistScrollOffset; i < playlistEnd && py + 18 <= contentBottom; i++) {
                        int likeX = detailsRight - 18;
                        if (click.button() == 1 && mouseX >= detailsLeft && mouseX <= detailsRight - 6 && mouseY >= py && mouseY <= py + 18) {
                            contextMenuState = ContextMenuState.forPlaylist(filtered.get(i));
                            contextMenuX = (int) mouseX;
                            contextMenuY = (int) mouseY;
                            showContextMenu = true;
                            return true;
                        }
                        if (mouseX >= likeX && mouseX <= likeX + 14 && mouseY >= py + 2 && mouseY <= py + 16) {
                            if (click.button() != 0) {
                                return true;
                            }
                            ProfilePlaylistSummary playlistSummary = filtered.get(i);
                            boolean newLikedState = !playlistSummary.likedByRequester;
                            ClientPlayNetworking.send(new ToggleLikedPlaylistPacket(playlistSummary.id, newLikedState));
                            playUiSound(
                                    newLikedState ? SoundEvents.BLOCK_NOTE_BLOCK_BELL : SoundEvents.BLOCK_NOTE_BLOCK_PLING,
                                    0.7f,
                                    newLikedState ? 1.25f : 0.9f
                            );
                            requestProfilesSync(profileSearchField == null ? "" : profileSearchField.getText());
                            return true;
                        }
                        if (mouseX >= detailsLeft && mouseX <= detailsRight - 22 && mouseY >= py && mouseY <= py + 18) {
                            selectedUserPlaylistId = filtered.get(i).id;
                            openedProfilePlaylistId = filtered.get(i).id;
                            playlistDetailScrollOffset = 0;
                            selectedDetailTrackIndexes.clear();
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
                if (nowPlaying != null) {
                    target = Math.max(1, target);
                }
                queueDragTargetIndex = target;
            }
            int panelTop = (this.height / 2) - (PANEL_HEIGHT / 2);
            int listTop = panelTop + CONTENT_TOP_NO_SEARCH;
            int listBottom = getListBottom(panelTop);
            int rowHeight = getQueueRowHeight();
            int visibleRows = Math.max(1, (listBottom - listTop) / rowHeight);
            int maxOffset = Math.max(0, playlist.size() - visibleRows);
            if (click.y() <= listTop + 10 && playlistScrollOffset > 0) {
                playlistScrollOffset = Math.max(0, playlistScrollOffset - 1);
            } else if (click.y() >= listBottom - 10 && playlistScrollOffset < maxOffset) {
                playlistScrollOffset = Math.min(maxOffset, playlistScrollOffset + 1);
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
            if (nowPlaying != null) {
                to = Math.max(1, to);
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
        if (showPlaylistPicker || showCreatePlaylistDialog || showSpotifyPromptDialog || showSpotifyPreviewDialog) {
            return true;
        }
        if (queueDragActive) {
            return true;
        }
        int panelTop = (this.height / 2) - (PANEL_HEIGHT / 2);
        if (currentTab == 0) {
            int listTop = panelTop + CONTENT_TOP_WITH_SEARCH;
            int listBottom = getListBottom(panelTop);
            int visibleItems = Math.max(1, (listBottom - listTop) / getSearchRowHeight());
            searchScrollOffset = Math.max(0, Math.min(searchScrollOffset - (int) verticalAmount, Math.max(0, searchResults.size() - visibleItems)));
        } else if (currentTab == 1) {
            int listTop = panelTop + CONTENT_TOP_NO_SEARCH;
            int listBottom = getListBottom(panelTop);
            int visibleItems = Math.max(1, (listBottom - listTop) / getQueueRowHeight());
            playlistScrollOffset = Math.max(0, Math.min(playlistScrollOffset - (int) verticalAmount, Math.max(0, playlist.size() - visibleItems)));
        } else if (currentTab == 2) {
            int panelLeft = (this.width / 2) - (PANEL_WIDTH / 2);
            int contentTop = panelTop + CONTENT_TOP_WITH_SEARCH;
            int contentBottom = getListBottom(panelTop);
            int leftPaneRight = panelLeft + PLAYLISTS_LEFT_WIDTH;
            boolean inLeftPane = mouseX >= panelLeft + 8 && mouseX <= leftPaneRight - 2 && mouseY >= contentTop + 8 && mouseY <= contentBottom;

            if (openedProfilePlaylistId != null) {
                ProfilePlaylistSummary opened = findProfilePlaylistById(openedProfilePlaylistId);
                int detailTop = contentTop + 50;
                int visibleItems = Math.max(1, (contentBottom - detailTop) / 21);
                int max = opened == null ? 0 : Math.max(0, opened.tracks.size() - visibleItems);
                playlistDetailScrollOffset = Math.max(0, Math.min(playlistDetailScrollOffset - (int) verticalAmount, max));
            } else if (inLeftPane) {
                int profileVisible = Math.max(1, (contentBottom - (contentTop + 8)) / 24);
                profileScrollOffset = Math.max(0, Math.min(profileScrollOffset - (int) verticalAmount, Math.max(0, getFilteredProfiles().size() - profileVisible)));
            } else {
                ProfileSummary selected = getSelectedProfile();
                int listTop = contentTop + 28;
                int visibleItems = Math.max(1, (contentBottom - listTop) / 21);
                int total = selected == null ? 0 : getPlaylistsForRightPane(selected).size();
                profilePlaylistScrollOffset = Math.max(0, Math.min(profilePlaylistScrollOffset - (int) verticalAmount, Math.max(0, total - visibleItems)));
            }
        } else {
            int listTop = panelTop + CONTENT_TOP_NO_SEARCH;
            int listBottom = getListBottom(panelTop);
            int visibleRecent = Math.max(1, Math.min((listBottom - listTop) / 22, RECENT_UI_MAX_VISIBLE));
            int maxRecent = Math.max(0, recentlyPlayed.size() - visibleRecent);
            recentlyPlayedScrollOffset = Math.max(0, Math.min(recentlyPlayedScrollOffset - (int) verticalAmount, maxRecent));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (showContextMenu && contextMenuState != null) {
            int key = input.key();
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                showContextMenu = false;
                contextMenuState = null;
                return true;
            }
            if (contextMenuState.type == ContextType.PLAYLIST) {
                if (key == GLFW.GLFW_KEY_1) {
                    queueAllFromProfilePlaylist(contextMenuState.playlistSummary, false);
                    showContextMenu = false;
                    contextMenuState = null;
                    return true;
                }
                if (key == GLFW.GLFW_KEY_2) {
                    queueAllFromProfilePlaylist(contextMenuState.playlistSummary, true);
                    showContextMenu = false;
                    contextMenuState = null;
                    return true;
                }
                if (key == GLFW.GLFW_KEY_3) {
                    return true;
                }
            } else {
                if (key == GLFW.GLFW_KEY_1) {
                    if (contextMenuState.songResult != null) {
                        addToQueue(contextMenuState.songResult);
                    }
                    showContextMenu = false;
                    contextMenuState = null;
                    return true;
                }
                if (key == GLFW.GLFW_KEY_2) {
                    return true;
                }
            }
            if (key == GLFW.GLFW_KEY_N) {
                openCreatePlaylistDialog();
                if (contextMenuState.songResult != null) {
                    pendingPlaylistSearchResult = contextMenuState.songResult;
                }
                showContextMenu = false;
                contextMenuState = null;
                return true;
            }
            if (key >= GLFW.GLFW_KEY_3 && key <= GLFW.GLFW_KEY_9 && contextMenuState.songResult != null) {
                int index = key - GLFW.GLFW_KEY_3;
                if (index >= 0 && index < userPlaylists.size()) {
                    UserPlaylistSummary summary = userPlaylists.get(index);
                    SearchResult song = contextMenuState.songResult;
                    ClientPlayNetworking.send(new AddToUserPlaylistPacket(summary.id, song.videoId, song.title, song.duration));
                    playUiSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
                    showContextMenu = false;
                    contextMenuState = null;
                    return true;
                }
            }
        }

        if (showSpotifyPreviewDialog) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                closeSpotifyPreviewDialog();
                return true;
            }
            if (input.key() == GLFW.GLFW_KEY_ENTER) {
                confirmSpotifyPreviewImport();
                return true;
            }
            return super.keyPressed(input);
        }
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
        playUiSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.15f);
        this.currentTab = 1;
    }

    private void addToPlaylistLibrary(SearchResult result) {
        MineifyClient.LOGGER.info("Opening playlist picker for: {}", result.title);
        this.pendingPlaylistSearchResult = result;
        this.showPlaylistPicker = true;
        this.showCreatePlaylistDialog = false;
        playUiSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 0.65f, 1.25f);
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
        this.profileScrollOffset = 0;
        this.profilePlaylistScrollOffset = 0;
        this.playlistDetailScrollOffset = 0;
        ensureSelectedProfile();
    }

    public void updateRecentlyPlayed(List<RecentlyPlayedEntry> entries) {
        this.recentlyPlayed = entries;
    }

    public void showSpotifyImportPreview(SpotifyImportPreviewState state) {
        this.spotifyImportPreviewState = state;
        this.showSpotifyPreviewDialog = true;
        this.spotifyImportPublic = true;
        this.selectedSpotifyTrackIds = new LinkedHashSet<>();
        for (SpotifyPreviewTrack track : state.tracks) {
            this.selectedSpotifyTrackIds.add(track.spotifyTrackId);
        }
        if (spotifyImportNameField != null) {
            spotifyImportNameField.setText(state.spotifyPlaylistName);
        }
        updateSpotifyPreviewControls();
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
        closeSpotifyPreviewDialog();
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
            pauseResumeButton.setMessage(Text.literal(playbackPaused ? "▶" : "⏸"));
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
        int listTop = panelTop + CONTENT_TOP_NO_SEARCH;
        int listBottom = getListBottom(panelTop);
        int minIndex = nowPlaying != null ? 1 : 0;
        if (mouseY < listTop) {
            return minIndex;
        }
        if (mouseY > listBottom) {
            return playlist.size() - 1;
        }

        int relative = (int) ((mouseY - listTop) / getQueueRowHeight());
        int index = playlistScrollOffset + relative;
        if (index < minIndex) {
            return minIndex;
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
        playUiSound(SoundEvents.ITEM_BOOK_PAGE_TURN, 0.65f, 1.00f);
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
        playUiSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.1f);

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
        playUiSound(SoundEvents.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
    }

    private void playUiSound(RegistryEntry.Reference<SoundEvent> sound, float volume, float pitch) {
        playUiSound(sound.value(), volume, pitch);
    }

    private void playUiSound(SoundEvent sound, float volume, float pitch) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.playSound(sound, volume, pitch);
        }
    }

    private enum ContextType {
        SONG,
        PLAYLIST
    }

    private static class ContextMenuState {
        final ContextType type;
        final SearchResult songResult;
        final ProfilePlaylistSummary playlistSummary;
        int hoveredIndex = -1;
        int submenuX = 0;
        int submenuY = 0;
        int submenuRows = 0;
        int submenuHoveredIndex = -1;

        private ContextMenuState(ContextType type, SearchResult songResult, ProfilePlaylistSummary playlistSummary) {
            this.type = type;
            this.songResult = songResult;
            this.playlistSummary = playlistSummary;
        }

        static ContextMenuState forSong(SearchResult song) {
            return new ContextMenuState(ContextType.SONG, song, null);
        }

        static ContextMenuState forQueueSong(PlaylistEntry entry) {
            return new ContextMenuState(ContextType.SONG, new SearchResult(entry.videoId, entry.title, entry.addedBy, entry.duration, ""), null);
        }

        static ContextMenuState forProfileTrack(SearchResult track) {
            return new ContextMenuState(ContextType.SONG, track, null);
        }

        static ContextMenuState forPlaylist(ProfilePlaylistSummary summary) {
            return new ContextMenuState(ContextType.PLAYLIST, null, summary);
        }
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

    public static class SpotifyPreviewTrack {
        public final String spotifyTrackId;
        public final String title;
        public final String artist;
        public final String query;
        public final String duration;

        public SpotifyPreviewTrack(String spotifyTrackId, String title, String artist, String query, String duration) {
            this.spotifyTrackId = spotifyTrackId;
            this.title = title;
            this.artist = artist;
            this.query = query;
            this.duration = duration == null ? "" : duration;
        }
    }

    public static class SpotifyImportPreviewState {
        public final String spotifyPlaylistId;
        public final String spotifyPlaylistName;
        public final String spotifyOwnerName;
        public final List<SpotifyPreviewTrack> tracks;

        public SpotifyImportPreviewState(String spotifyPlaylistId, String spotifyPlaylistName, String spotifyOwnerName, List<SpotifyPreviewTrack> tracks) {
            this.spotifyPlaylistId = spotifyPlaylistId;
            this.spotifyPlaylistName = spotifyPlaylistName;
            this.spotifyOwnerName = spotifyOwnerName;
            this.tracks = tracks;
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

    public static class ProfileTrackEntry {
        public final String videoId;
        public final String title;
        public final String duration;

        public ProfileTrackEntry(String videoId, String title, String duration) {
            this.videoId = videoId;
            this.title = title;
            this.duration = duration;
        }
    }

    public static class ProfilePlaylistSummary {
        public final String id;
        public final String name;
        public final boolean isPublic;
        public final int trackCount;
        public final String ownerId;
        public final String ownerName;
        public final boolean likedByRequester;
        public final List<ProfileTrackEntry> tracks;

        public ProfilePlaylistSummary(
                String id,
                String name,
                boolean isPublic,
                int trackCount,
                String ownerId,
                String ownerName,
                boolean likedByRequester,
                List<ProfileTrackEntry> tracks
        ) {
            this.id = id;
            this.name = name;
            this.isPublic = isPublic;
            this.trackCount = trackCount;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.likedByRequester = likedByRequester;
            this.tracks = tracks;
        }
    }

    public static class ProfileSummary {
        public final String ownerId;
        public final String ownerName;
        public final boolean isSelf;
        public final List<ProfilePlaylistSummary> playlists;
        public final List<ProfilePlaylistSummary> likedPlaylists;

        public ProfileSummary(
                String ownerId,
                String ownerName,
                boolean isSelf,
                List<ProfilePlaylistSummary> playlists,
                List<ProfilePlaylistSummary> likedPlaylists
        ) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.isSelf = isSelf;
            this.playlists = playlists;
            this.likedPlaylists = likedPlaylists;
        }
    }
}
