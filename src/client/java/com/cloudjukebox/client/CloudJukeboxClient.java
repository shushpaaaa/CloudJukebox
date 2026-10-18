package com.cloudjukebox.client;

import com.cloudjukebox.CloudJukebox;
import com.cloudjukebox.client.audio.ClientJukeboxManager;
import com.cloudjukebox.client.gui.JukeboxScreen;
import com.cloudjukebox.network.OpenJukeboxGuiPayload;
import com.cloudjukebox.network.SyncJukeboxPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

@Environment(EnvType.CLIENT)
public class CloudJukeboxClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CloudJukebox.LOGGER.info("Initializing Cloud Jukebox client networking and listeners...");

        // S2C: Sync Jukebox playback (start/stop audio streams)
        ClientPlayNetworking.registerGlobalReceiver(SyncJukeboxPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                ClientJukeboxManager.INSTANCE.handleSync(payload);
            });
        });

        // S2C: Open Jukebox GUI
        ClientPlayNetworking.registerGlobalReceiver(OpenJukeboxGuiPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                String currentlyPlaying = payload.isPlaying() ? payload.currentTitle() + " - " + payload.currentArtist() : "";
                context.client().setScreen(new JukeboxScreen(payload.pos(), currentlyPlaying, payload.isPlaying()));
            });
        });

        // Client Tick: note particles around active jukeboxes
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientJukeboxManager.INSTANCE.tick();
        });

        // Disconnect: Stop all active OpenAL audio sources cleanly
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientJukeboxManager.INSTANCE.stopAll();
        });

        CloudJukebox.LOGGER.info("Cloud Jukebox client initialized successfully!");
    }
}
