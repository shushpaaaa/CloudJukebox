package com.cloudjukebox.client.gui;

import com.cloudjukebox.client.audio.SoundCloudAudioService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class TrackListWidget extends ObjectSelectionList<TrackListWidget.TrackEntry> {
    private final Consumer<SoundCloudAudioService.TrackInfo> onSelectCallback;

    public TrackListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight, Consumer<SoundCloudAudioService.TrackInfo> onSelectCallback) {
        super(minecraft, width, height, y, itemHeight);
        this.onSelectCallback = onSelectCallback;
    }

    public void setTracks(java.util.List<SoundCloudAudioService.TrackInfo> tracks) {
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
            // Background highlight on hover
            if (hovering) {
                guiGraphics.fill(left, top, left + width, top + height, 0x33FFFFFF);
            }

            // Title (Yellow) - trim if too long
            String title = track.title();
            if (minecraft.font.width(title) > width - 70) {
                title = minecraft.font.plainSubstrByWidth(title, width - 75) + "...";
            }
            guiGraphics.drawString(minecraft.font, title, left + 4, top + 2, 0xFFFF55, false);

            // Artist (Gray) and Duration (Aqua)
            String subtitle = track.artist() + " • " + track.getFormattedDuration();
            if (minecraft.font.width(subtitle) > width - 10) {
                subtitle = minecraft.font.plainSubstrByWidth(subtitle, width - 15) + "...";
            }
            guiGraphics.drawString(minecraft.font, subtitle, left + 4, top + 13, 0xAAAAAA, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                setSelected(this);
                if (onSelectCallback != null) {
                    onSelectCallback.accept(this.track);
                }
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }
}
