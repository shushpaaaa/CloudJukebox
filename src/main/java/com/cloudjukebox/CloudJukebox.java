package com.cloudjukebox;

import com.cloudjukebox.network.OpenJukeboxGuiPayload;
import com.cloudjukebox.network.PlayTrackPayload;
import com.cloudjukebox.network.StopTrackPayload;
import com.cloudjukebox.network.SyncJukeboxPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudJukebox implements ModInitializer {
    public static final String MOD_ID = "cloudjukebox";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Cloud Jukebox networking and events...");

        // Register Payloads with Fabric Networking
        PayloadTypeRegistry.playC2S().register(PlayTrackPayload.TYPE, PlayTrackPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(StopTrackPayload.TYPE, StopTrackPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncJukeboxPayload.TYPE, SyncJukeboxPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenJukeboxGuiPayload.TYPE, OpenJukeboxGuiPayload.CODEC);

        // Register Server Packet Handlers
        ServerPlayNetworking.registerGlobalReceiver(PlayTrackPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerLevel level = player.serverLevel();
            BlockPos pos = payload.pos();

            // Distance check: ensure player is within reasonable interaction distance
            if (player.blockPosition().distSqr(pos) <= 256) {
                context.server().execute(() -> {
                    LOGGER.info("Server: playing track '{}' by '{}' at Jukebox {}", payload.title(), payload.artist(), pos);
                    ServerJukeboxManager.INSTANCE.playTrack(
                            level, pos, payload.trackUrl(), payload.title(), payload.artist(), payload.durationMs()
                    );
                });
            } else {
                LOGGER.warn("Server: player {} too far from Jukebox at {} (distSqr={})", player.getName().getString(), pos, player.blockPosition().distSqr(pos));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(StopTrackPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerLevel level = player.serverLevel();
            BlockPos pos = payload.pos();

            if (player.blockPosition().distSqr(pos) <= 256) {
                context.server().execute(() -> {
                    LOGGER.info("Server: stopping track at Jukebox {}", pos);
                    ServerJukeboxManager.INSTANCE.stopTrack(level, pos);
                });
            }
        });

        // Register Jukebox Block Right-Click Interaction
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            BlockPos pos = hitResult.getBlockPos();
            BlockState state = level.getBlockState(pos);

            if (state.is(Blocks.JUKEBOX)) {
                ItemStack heldItem = player.getItemInHand(hand);

                // If player is holding a music disc and NOT sneaking, let vanilla disc insertion work
                if (heldItem.has(DataComponents.JUKEBOX_PLAYABLE) && !player.isShiftKeyDown()) {
                    return InteractionResult.PASS;
                }

                // If empty hand or sneaking, open the Cloud Jukebox screen!
                if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                    ServerJukeboxManager.INSTANCE.openGuiForPlayer(serverPlayer, pos);
                }

                return InteractionResult.sidedSuccess(level.isClientSide());
            }

            return InteractionResult.PASS;
        });

        // Detect when a player breaks a Jukebox block
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (state.is(Blocks.JUKEBOX) && !level.isClientSide() && level instanceof ServerLevel serverLevel) {
                LOGGER.info("Jukebox at {} was broken by player {}, stopping music", pos, player.getName().getString());
                ServerJukeboxManager.INSTANCE.stopTrack(serverLevel, pos);
            }
        });

        // Periodically verify that active playing jukeboxes still exist in the world
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (world.getGameTime() % 20 == 0) {
                for (ServerJukeboxManager.JukeboxKey key : ServerJukeboxManager.INSTANCE.getActiveKeys()) {
                    if (key.dimension().equals(world.dimension())) {
                        if (!world.getBlockState(key.pos()).is(Blocks.JUKEBOX)) {
                            LOGGER.info("Jukebox at {} in {} no longer exists, stopping music", key.pos(), world.dimension().location());
                            ServerJukeboxManager.INSTANCE.stopTrack(world, key.pos());
                        }
                    }
                }
            }
        });

        LOGGER.info("Cloud Jukebox common initialization complete!");
    }
}
