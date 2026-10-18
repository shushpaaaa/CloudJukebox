package com.cloudjukebox.client.gui;

import com.cloudjukebox.client.audio.ClientJukeboxManager;
import com.cloudjukebox.client.audio.SoundCloudAudioService;
import com.cloudjukebox.network.PlayTrackPayload;
import com.cloudjukebox.network.StopTrackPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class JukeboxScreen extends Screen {
    private static final ResourceLocation POPUP_BG = ResourceLocation.withDefaultNamespace("popup/background");
    private static final int WINDOW_WIDTH = 290;
    private static final int WINDOW_HEIGHT = 236;
    private static final int PAGE_SIZE = 8;

    // Global persistent UI state across closes/re-opens
    private static String savedQuery = "";
    private static final List<SoundCloudAudioService.TrackInfo> savedTracks = new ArrayList<>();
    private static int savedCurrentPage = 1;
    private static int savedTotalPages = 1;
    private static int savedTotalResults = 0;
    private static String savedStatusMessage = "Search for any song, artist, or remix above";
    private static SoundCloudAudioService.TrackInfo savedSelectedTrack = null;

    private final BlockPos pos;
    private String currentlyPlaying;
    private boolean isPlaying;

    private EditBox searchBox;
    private Button searchButton;
    private Button prevButton;
    private Button nextButton;
    private Button playButton;
    private Button stopButton;
    private Button volumeButton;
    private TrackListWidget trackList;

    private SoundCloudAudioService.TrackInfo selectedTrack;
    private String currentQuery = "";
    private int currentPage = 1;
    private int totalPages = 1;
    private int totalResults = 0;

    private String statusMessage = "Search for any song, artist, or remix above";

    public JukeboxScreen(BlockPos pos, String currentlyPlaying, boolean isPlaying) {
        super(Component.literal("CloudJukebox"));
        this.pos = pos;
        this.currentlyPlaying = currentlyPlaying != null ? currentlyPlaying : "";
        this.isPlaying = isPlaying;

        // Restore global persistent UI state
        this.currentQuery = savedQuery;
        this.currentPage = savedCurrentPage;
        this.totalPages = Math.max(1, savedTotalPages);
        this.totalResults = savedTotalResults;
        this.selectedTrack = savedSelectedTrack;
        if (!savedTracks.isEmpty()) {
            this.statusMessage = savedStatusMessage;
        }
    }

    @Override
    protected void init() {
        int startX = (this.width - WINDOW_WIDTH) / 2;
        int startY = (this.height - WINDOW_HEIGHT) / 2;

        // 1. Modern Search Box
        int searchWidth = 200;
        searchBox = new EditBox(this.font, startX + 12, startY + 22, searchWidth, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search songs, artists, remixes..."));
        searchBox.setMaxLength(100);
        if (!currentQuery.isEmpty()) {
            searchBox.setValue(currentQuery);
        }
        searchBox.setResponder(text -> {
            searchButton.active = !text.trim().isEmpty();
            savedQuery = text;
        });
        this.addWidget(searchBox);

        // 2. Search Button
        searchButton = Button.builder(Component.literal("Search"), btn -> {
            currentPage = 1;
            performSearch();
        }).bounds(startX + 218, startY + 22, 60, 20).build();
        searchButton.active = !searchBox.getValue().trim().isEmpty();
        this.addRenderableWidget(searchButton);

        // 3. Track List Widget
        int listWidth = WINDOW_WIDTH - 24; // 266
        int listHeight = 110;
        int listTop = startY + 46;

        trackList = new TrackListWidget(
                this.minecraft,
                listWidth,
                listHeight,
                listTop,
                30,
                track -> {
                    this.selectedTrack = track;
                    savedSelectedTrack = track;
                    if (playButton != null) {
                        playButton.active = true;
                    }
                    this.statusMessage = "Selected: " + track.title();
                    savedStatusMessage = this.statusMessage;
                },
                track -> {
                    this.selectedTrack = track;
                    savedSelectedTrack = track;
                    playTrack(track);
                }
        );
        trackList.setX(startX + 12);
        if (!currentlyPlaying.isEmpty() && isPlaying) {
            trackList.setCurrentlyPlayingTitle(currentlyPlaying);
        }
        if (!savedTracks.isEmpty()) {
            trackList.setTracks(savedTracks);
        }
        this.addRenderableWidget(trackList);

        // 4. Pagination Controls (◄ Prev and Next ►)
        int paginationY = startY + 158;
        prevButton = Button.builder(Component.literal("◄ Prev"), btn -> {
            if (currentPage > 1) {
                currentPage--;
                performSearch();
            }
        }).bounds(startX + 12, paginationY, 48, 18).build();
        prevButton.active = currentPage > 1;
        this.addRenderableWidget(prevButton);

        nextButton = Button.builder(Component.literal("Next ►"), btn -> {
            if (currentPage < totalPages) {
                currentPage++;
                performSearch();
            }
        }).bounds(startX + WINDOW_WIDTH - 12 - 48, paginationY, 48, 18).build();
        nextButton.active = currentPage < totalPages;
        this.addRenderableWidget(nextButton);

        // 5. Bottom Action Controls
        int bottomY = startY + 204;

        // Play Button
        playButton = Button.builder(Component.literal("▶ Play"), btn -> {
            if (selectedTrack != null) {
                playTrack(selectedTrack);
            }
        }).bounds(startX + 12, bottomY, 56, 20).build();
        playButton.active = selectedTrack != null;
        this.addRenderableWidget(playButton);

        // Stop Button
        stopButton = Button.builder(Component.literal("⏹ Stop"), btn -> stopJukebox())
                .bounds(startX + 72, bottomY, 54, 20)
                .build();
        stopButton.active = isPlaying;
        this.addRenderableWidget(stopButton);

        // Volume Toggle Button (cycles 100% -> 80% -> 60% -> 40% -> 20% -> 100%, never 0%)
        int volPercent = Math.round(ClientJukeboxManager.INSTANCE.getMasterVolume() * 100);
        if (volPercent <= 0) {
            ClientJukeboxManager.INSTANCE.setMasterVolume(1.0f);
            volPercent = 100;
        }
        volumeButton = Button.builder(Component.literal("Vol: " + volPercent + "%"), btn -> {
            float cur = ClientJukeboxManager.INSTANCE.getMasterVolume();
            float next;
            if (cur >= 0.95f) next = 0.80f;
            else if (cur >= 0.75f) next = 0.60f;
            else if (cur >= 0.55f) next = 0.40f;
            else if (cur >= 0.35f) next = 0.20f;
            else next = 1.0f; // loop back to 100%

            ClientJukeboxManager.INSTANCE.setMasterVolume(next);
            btn.setMessage(Component.literal("Vol: " + Math.round(next * 100) + "%"));
        }).bounds(startX + 130, bottomY, 68, 20).build();
        this.addRenderableWidget(volumeButton);

        // Done / Close Button
        Button doneButton = Button.builder(Component.literal("Done"), btn -> this.onClose())
                .bounds(startX + 202, bottomY, 76, 20)
                .build();
        this.addRenderableWidget(doneButton);
    }

    private void performSearch() {
        String query = searchBox.getValue().trim();
        if (query.isEmpty()) return;

        this.currentQuery = query;
        statusMessage = "Searching for \"" + query + "\" (Page " + currentPage + ")...";
        searchButton.active = false;
        prevButton.active = false;
        nextButton.active = false;

        SoundCloudAudioService.INSTANCE.search(query, currentPage, PAGE_SIZE).thenAccept(result -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    searchButton.active = true;
                    this.totalPages = Math.max(1, result.totalPages());
                    this.totalResults = result.totalResults();
                    prevButton.active = currentPage > 1;
                    nextButton.active = currentPage < totalPages;

                    savedQuery = query;
                    savedCurrentPage = currentPage;
                    savedTotalPages = totalPages;
                    savedTotalResults = totalResults;
                    savedTracks.clear();

                    if (result.tracks().isEmpty()) {
                        statusMessage = "No full playable songs found on page " + currentPage + ".";
                        trackList.clear();
                    } else {
                        statusMessage = "Found " + result.totalResults() + " songs. Showing page " + currentPage + " of " + totalPages + ".";
                        trackList.setTracks(result.tracks());
                        savedTracks.addAll(result.tracks());
                    }
                    savedStatusMessage = statusMessage;
                });
            }
        }).exceptionally(ex -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    searchButton.active = true;
                    prevButton.active = currentPage > 1;
                    nextButton.active = currentPage < totalPages;
                    Throwable cause = ex;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                    statusMessage = "Search error: " + msg;
                    savedStatusMessage = statusMessage;
                });
            }
            return null;
        });
    }

    private void playTrack(SoundCloudAudioService.TrackInfo track) {
        if (track == null) return;

        ClientPlayNetworking.send(new PlayTrackPayload(
                pos,
                track.uri(),
                track.title(),
                track.artist(),
                track.durationMs()
        ));

        currentlyPlaying = track.title() + " - " + track.artist();
        isPlaying = true;
        stopButton.active = true;
        statusMessage = "Buffering & playing: " + track.title();
        savedSelectedTrack = track;
        savedStatusMessage = statusMessage;
        trackList.setCurrentlyPlayingTitle(track.title());

        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.literal("§6♪ Now Playing: §f" + track.title() + " §7- " + track.artist()),
                    true
            );
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft != null && this.minecraft.level != null) {
            if (!this.minecraft.level.getBlockState(pos).is(Blocks.JUKEBOX)) {
                this.onClose();
            }
        }
    }

    @Override
    public void onClose() {
        if (searchBox != null) {
            savedQuery = searchBox.getValue();
        }
        savedCurrentPage = currentPage;
        savedTotalPages = totalPages;
        savedTotalResults = totalResults;
        savedStatusMessage = statusMessage;
        savedSelectedTrack = selectedTrack;
        super.onClose();
    }

    private void stopJukebox() {
        // 1. Immediately stop local audio stream for this pos
        ClientJukeboxManager.INSTANCE.stopJukebox(pos);

        // 2. Broadcast stop across server/network
        ClientPlayNetworking.send(new StopTrackPayload(pos));

        currentlyPlaying = "";
        isPlaying = false;
        stopButton.active = false;
        statusMessage = "Jukebox stopped.";
        savedStatusMessage = statusMessage;
        trackList.setCurrentlyPlayingTitle("");

        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.literal("§e♪ Jukebox stopped"),
                    true
            );
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Pressing Enter in the search box triggers search
        if (keyCode == 257 && searchBox.isFocused()) {
            currentPage = 1;
            performSearch();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int startX = (this.width - WINDOW_WIDTH) / 2;
        int startY = (this.height - WINDOW_HEIGHT) / 2;

        // 1. Clean translucent dark gradient over the world (NO blur shader!)
        this.renderTransparentBackground(guiGraphics);

        // 2. Official Vanilla 9-Slice Popup Frame
        guiGraphics.blitSprite(POPUP_BG, startX, startY, WINDOW_WIDTH, WINDOW_HEIGHT);

        // 3. 3D Sunken Track List Panel
        int listLeft = startX + 11;
        int listTop = startY + 45;
        int listWidth = WINDOW_WIDTH - 22;
        int listHeight = 110;
        int listRight = listLeft + listWidth;
        int listBottom = listTop + listHeight;

        // Outer crisp black outline
        guiGraphics.renderOutline(listLeft - 1, listTop - 1, listWidth + 2, listHeight + 2, 0xFF000000);
        // Sunken top & left dark shadow
        guiGraphics.fill(listLeft, listTop, listRight, listTop + 1, 0xFF141418);
        guiGraphics.fill(listLeft, listTop, listLeft + 1, listBottom, 0xFF141418);
        // Sunken bottom & right highlight
        guiGraphics.fill(listLeft, listBottom - 1, listRight, listBottom, 0xFF35353C);
        guiGraphics.fill(listRight - 1, listTop, listRight, listBottom, 0xFF35353C);
        // Sunken deep interior
        guiGraphics.fill(listLeft + 1, listTop + 1, listRight - 1, listBottom - 1, 0xFF121215);

        // 4. Lower Action Divider Line
        guiGraphics.fill(startX + 12, startY + 198, startX + WINDOW_WIDTH - 12, startY + 199, 0xFF141418);
        guiGraphics.fill(startX + 12, startY + 199, startX + WINDOW_WIDTH - 12, startY + 200, 0xFF35353C);

        // 5. Render All Widgets (Buttons, EditBox, TrackList) ON TOP of container
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        searchBox.render(guiGraphics, mouseX, mouseY, partialTick);

        // 6. Clean Header Title: Just CloudJukebox
        guiGraphics.drawString(this.font, "§f§lCloudJukebox", startX + 14, startY + 8, 0xFFFFFF, true);

        // 7. Pagination Text
        int paginationY = startY + 158;
        String pageInfo = "Page " + currentPage + " of " + totalPages;
        guiGraphics.drawCenteredString(this.font, pageInfo, startX + (WINDOW_WIDTH / 2), paginationY + 5, 0xAAAAAA);

        // 8. Status / Now Playing Banner (moved down with clear spacing below Prev/Next)
        int statusY = startY + 185;
        String displayStatus = statusMessage;
        if (isPlaying && !currentlyPlaying.isEmpty()) {
            displayStatus = "§a♪ Now Playing: §f" + currentlyPlaying;
        } else {
            displayStatus = "§7" + displayStatus;
        }

        int maxStatusWidth = WINDOW_WIDTH - 24;
        if (this.font.width(displayStatus) > maxStatusWidth) {
            displayStatus = this.font.plainSubstrByWidth(displayStatus, maxStatusWidth - 6) + "...";
        }
        guiGraphics.drawString(this.font, displayStatus, startX + 12, statusY, 0xFFFFFF, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
