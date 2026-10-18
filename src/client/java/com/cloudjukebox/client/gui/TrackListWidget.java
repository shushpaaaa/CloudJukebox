package com.cloudjukebox.client.gui;

import com.cloudjukebox.client.audio.SoundCloudAudioService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Consumer;

public class TrackListWidget extends ObjectSelectionList<TrackListWidget.TrackEntry> {
    private final Consumer<SoundCloudAudioService.TrackInfo> onSelectCallback;
    private final Consumer<SoundCloudAudioService.TrackInfo> onPlayCallback;
    private final int entryItemHeight;
    private String currentlyPlayingTitle = "";

    public TrackListWidget(
            Minecraft minecraft,
            int width,
            int height,
            int y,
            int itemHeight,
            Consumer<SoundCloudAudioService.TrackInfo> onSelectCallback,
            Consumer<SoundCloudAudioService.TrackInfo> onPlayCallback
    ) {
        super(minecraft, width, height, y, itemHeight);
        this.entryItemHeight = itemHeight;
        this.onSelectCallback = onSelectCallback;
        this.onPlayCallback = onPlayCallback;
    }

    public void setCurrentlyPlayingTitle(String title) {
        this.currentlyPlayingTitle = title != null ? title : "";
    }

    public void clear() {
        this.clearEntries();
    }

    @Override
    protected void renderListBackground(GuiGraphics guiGraphics) {
        // Suppress default list background to keep the GUI clean
    }

    @Override
    protected void renderListSeparators(GuiGraphics guiGraphics) {
        // Suppress full-width blurry separators
    }

    @Override
    protected void renderSelection(GuiGraphics guiGraphics, int top, int width, int height, int outerColor, int innerColor) {
        // Suppress default outer selection rectangle so TrackEntry's custom border is the only one drawn
    }

    public void setTracks(List<SoundCloudAudioService.TrackInfo> tracks) {
        this.clearEntries();
        for (SoundCloudAudioService.TrackInfo track : tracks) {
            this.addEntry(new TrackEntry(track));
        }
        this.setScrollAmount(0);
    }

    public class TrackEntry extends ObjectSelectionList.Entry<TrackEntry> {
        private final SoundCloudAudioService.TrackInfo track;

        public TrackEntry(SoundCloudAudioService.TrackInfo track) {
            this.track = track;
        }

        public SoundCloudAudioService.TrackInfo getTrack() {
            return track;
        }

        @Override
        public Component getNarration() {
            return Component.literal(track.title() + " by " + track.artist());
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
            boolean isSelected = getSelected() == this;
            boolean isThisPlaying = !currentlyPlayingTitle.isEmpty() &&
                    (currentlyPlayingTitle.equalsIgnoreCase(track.title()) ||
                     currentlyPlayingTitle.contains(track.title()));

            // Background card style: Sunken slot card look
            int bgColor = isThisPlaying ? 0x801B3B1B : (isSelected ? 0x80373737 : (hovering ? 0x40FFFFFF : 0x25000000));
            guiGraphics.fill(left, top, left + width, top + height - 2, bgColor);

            if (isThisPlaying) {
                guiGraphics.renderOutline(left, top, width, height - 2, 0xFF55FF55);
            } else if (isSelected) {
                guiGraphics.renderOutline(left, top, width, height - 2, 0xFFFFAA00);
            } else if (hovering) {
                guiGraphics.renderOutline(left, top, width, height - 2, 0x60FFFFFF);
            }

            // 1. Cover Art (20x20)
            int coverX = left + 4;
            int coverY = top + (height - 2 - 20) / 2;
            ResourceLocation coverLoc = CoverArtManager.getOrLoad(track.artworkUrl());
            if (coverLoc != null) {
                guiGraphics.blit(coverLoc, coverX, coverY, 20, 20, 0f, 0f, 32, 32, 32, 32);
            } else {
                guiGraphics.fill(coverX, coverY, coverX + 20, coverY + 20, 0xFF1E1E22);
                guiGraphics.drawCenteredString(minecraft.font, "♫", coverX + 10, coverY + 5, 0x55FFFF);
            }
            guiGraphics.renderOutline(coverX - 1, coverY - 1, 22, 22, 0xFF37373C);

            // 2. Play Button / Playing Status (18x18)
            int playX = coverX + 25;
            int playY = top + (height - 2 - 18) / 2;
            boolean playHover = mouseX >= playX && mouseX <= playX + 18 && mouseY >= playY && mouseY <= playY + 18;

            int btnBg = isThisPlaying ? 0xFF2E7D32 : (playHover ? 0xFF4E7D4E : 0xFF3A3A3A);
            int btnBorder = isThisPlaying ? 0xFF81C784 : (playHover ? 0xFFFFFFFF : 0xFF555555);
            guiGraphics.fill(playX, playY, playX + 18, playY + 18, btnBg);
            guiGraphics.renderOutline(playX, playY, 18, 18, btnBorder);

            String icon = isThisPlaying ? "♫" : "▶";
            int iconColor = isThisPlaying ? 0x55FF55 : (playHover ? 0xFFFFFF : 0xBBFFBB);
            guiGraphics.drawCenteredString(minecraft.font, icon, playX + 9, playY + 5, iconColor);

            // 3. Track Title & Artist
            int textX = playX + 23;
            int maxTextWidth = width - (textX - left) - 38;

            String title = track.title();
            if (minecraft.font.width(title) > maxTextWidth) {
                title = minecraft.font.plainSubstrByWidth(title, maxTextWidth - 6) + "...";
            }
            guiGraphics.drawString(minecraft.font, title, textX, top + 3, isThisPlaying ? 0x55FF55 : (isSelected ? 0xFFAA00 : 0xFFFFFF), true);

            String artist = track.artist();
            if (minecraft.font.width(artist) > maxTextWidth) {
                artist = minecraft.font.plainSubstrByWidth(artist, maxTextWidth - 6) + "...";
            }
            guiGraphics.drawString(minecraft.font, artist, textX, top + 15, 0xAAAAAA, true);

            // 4. Duration Badge
            int durX = left + width - 36;
            guiGraphics.drawString(minecraft.font, track.getFormattedDuration(), durX, top + 9, 0x55FFFF, true);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                int index = TrackListWidget.this.children().indexOf(this);
                int top = TrackListWidget.this.getRowTop(index);
                int left = TrackListWidget.this.getRowLeft();
                int coverX = left + 4;
                int playX = coverX + 25;
                int playY = top + (entryItemHeight - 2 - 18) / 2;

                boolean clickedPlayBtn = mouseX >= playX && mouseX <= playX + 18 && mouseY >= playY && mouseY <= playY + 18;
                boolean alreadySelected = getSelected() == this;

                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                setSelected(this);

                if (onSelectCallback != null) {
                    onSelectCallback.accept(this.track);
                }

                // If user clicked the play button OR double-clicked / re-clicked an already selected row, play!
                if (clickedPlayBtn || alreadySelected) {
                    if (onPlayCallback != null) {
                        onPlayCallback.accept(this.track);
                    }
                }
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }
}
