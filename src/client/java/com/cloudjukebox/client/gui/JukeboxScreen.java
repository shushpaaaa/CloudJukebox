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

public class JukeboxScreen extends Screen {
    private final BlockPos pos;
    private String currentlyPlaying;
    private boolean isPlaying;

    private EditBox searchBox;
    private Button searchButton;
    private Button playButton;
    private Button stopButton;
    private TrackListWidget trackList;

    private SoundCloudAudioService.TrackInfo selectedTrack;
    private String statusMessage = "Search for a song on SoundCloud...";
    private int statusColor = 0xAAAAAA;

    public JukeboxScreen(BlockPos pos, String currentlyPlaying, boolean isPlaying) {
        super(Component.literal("Cloud Jukebox"));
        this.pos = pos;
        this.currentlyPlaying = currentlyPlaying;
        this.isPlaying = isPlaying;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        // Search Input Box
        searchBox = new EditBox(this.font, centerX - 130, 22, 195, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Enter song or artist..."));
        searchBox.setMaxLength(100);
        searchBox.setResponder(text -> searchButton.active = !text.trim().isEmpty());
        this.addWidget(searchBox);

        // Search Button
        searchButton = Button.builder(Component.literal("Search"), btn -> performSearch())
                .bounds(centerX + 70, 22, 60, 20)
                .build();
        searchButton.active = false;
        this.addRenderableWidget(searchButton);

        // Scrollable Track List
        int listTop = 46;
        int listHeight = this.height - listTop - 64;
        trackList = new TrackListWidget(this.minecraft, 260, listHeight, listTop, 26, track -> {
            this.selectedTrack = track;
            this.playButton.active = true;
            this.statusMessage = "Selected: " + track.title();
            this.statusColor = 0x55FF55;
        });
        // Center the list widget
        trackList.setX(centerX - 130);
        this.addRenderableWidget(trackList);

        // Play Selected Button
        playButton = Button.builder(Component.literal("Play Track"), btn -> playSelected())
                .bounds(centerX - 130, this.height - 32, 80, 20)
                .build();
        playButton.active = false;
        this.addRenderableWidget(playButton);

        // Stop Jukebox Button
        stopButton = Button.builder(Component.literal("Stop"), btn -> stopJukebox())
                .bounds(centerX - 45, this.height - 32, 60, 20)
                .build();
        stopButton.active = isPlaying;
        this.addRenderableWidget(stopButton);

        // Close Button
        Button closeButton = Button.builder(Component.literal("Done"), btn -> this.onClose())
                .bounds(centerX + 20, this.height - 32, 50, 20)
                .build();
        this.addRenderableWidget(closeButton);

        // Volume Toggle / Adjuster Button (Cycles 100% -> 75% -> 50% -> 25% -> 0%)
        int volPercent = Math.round(ClientJukeboxManager.INSTANCE.getMasterVolume() * 100);
        Button volumeButton = Button.builder(Component.literal("Vol: " + volPercent + "%"), btn -> {
            float cur = ClientJukeboxManager.INSTANCE.getMasterVolume();
            float next = cur <= 0.05f ? 1.0f : cur - 0.25f;
            ClientJukeboxManager.INSTANCE.setMasterVolume(next);
            btn.setMessage(Component.literal("Vol: " + Math.round(next * 100) + "%"));
        }).bounds(centerX + 75, this.height - 32, 55, 20).build();
        this.addRenderableWidget(volumeButton);
    }

    private void performSearch() {
        String query = searchBox.getValue().trim();
        if (query.isEmpty()) return;

        statusMessage = "Searching SoundCloud for \"" + query + "\"...";
        statusColor = 0xFFFF55;
        searchButton.active = false;

        SoundCloudAudioService.INSTANCE.search(query).thenAccept(tracks -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    searchButton.active = true;
                    if (tracks.isEmpty()) {
                        statusMessage = "No tracks found.";
                        statusColor = 0xFF5555;
                    } else {
                        statusMessage = "Found " + tracks.size() + " tracks. Select one to play.";
                        statusColor = 0x55FF55;
                        trackList.setTracks(tracks);
                    }
                });
            }
        }).exceptionally(ex -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    searchButton.active = true;
                    Throwable cause = ex;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                    statusMessage = "Search error: " + msg;
                    statusColor = 0xFF5555;
                });
            }
            return null;
        });
    }

    private void playSelected() {
        if (selectedTrack == null) return;

        ClientPlayNetworking.send(new PlayTrackPayload(
                pos,
                selectedTrack.uri(),
                selectedTrack.title(),
                selectedTrack.artist(),
                selectedTrack.durationMs()
        ));

        currentlyPlaying = selectedTrack.title() + " - " + selectedTrack.artist();
        isPlaying = true;
        stopButton.active = true;
        statusMessage = "Playing: " + selectedTrack.title();
        statusColor = 0x55FF55;
    }

    private void stopJukebox() {
        ClientPlayNetworking.send(new StopTrackPayload(pos));
        currentlyPlaying = "";
        isPlaying = false;
        stopButton.active = false;
        statusMessage = "Jukebox stopped.";
        statusColor = 0xAAAAAA;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Pressing Enter in the search box triggers search
        if (keyCode == 257 && searchBox.isFocused()) {
            performSearch();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // Screen Header Title
        guiGraphics.drawCenteredString(this.font, "Cloud Jukebox — SoundCloud", centerX, 8, 0xFFFFFF);

        // Search Box (Render after super.render to ensure proper widget layering)
        searchBox.render(guiGraphics, mouseX, mouseY, partialTick);

        // Status or Now Playing Bar
        int statusBarY = this.height - 48;
        guiGraphics.fill(centerX - 130, statusBarY, centerX + 130, statusBarY + 12, 0x88000000);

        String displayStatus = statusMessage;
        if (isPlaying && (statusMessage.startsWith("Search") || statusMessage.startsWith("Found"))) {
            displayStatus = "Now Playing: " + currentlyPlaying;
        }

        if (this.font.width(displayStatus) > 250) {
            displayStatus = this.font.plainSubstrByWidth(displayStatus, 245) + "...";
        }
        guiGraphics.drawCenteredString(this.font, displayStatus, centerX, statusBarY + 2, statusColor);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
