package com.cloudjukebox.client.gui;

import com.cloudjukebox.CloudJukebox;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CoverArtManager {
    private static final Map<String, ResourceLocation> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Returns a registered DynamicTexture ResourceLocation for the given artwork URL,
     * or null if still loading / unavailable. If not loaded, starts an asynchronous download.
     */
    public static ResourceLocation getOrLoad(String artworkUrl) {
        if (artworkUrl == null || artworkUrl.trim().isEmpty()) {
            return null;
        }

        ResourceLocation cached = CACHE.get(artworkUrl);
        if (cached != null) {
            return cached;
        }

        if (LOADING.add(artworkUrl)) {
            // Asynchronously fetch cover image
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(artworkUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(6))
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200 && response.body() != null && response.body().length > 0) {
                            byte[] data = response.body();
                            Minecraft.getInstance().execute(() -> {
                                try {
                                    java.awt.image.BufferedImage bImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
                                    if (bImg != null) {
                                        java.awt.image.BufferedImage resized = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                                        java.awt.Graphics2D g2d = resized.createGraphics();
                                        g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                                        g2d.drawImage(bImg, 0, 0, 32, 32, null);
                                        g2d.dispose();

                                        java.io.ByteArrayOutputStream pngOut = new java.io.ByteArrayOutputStream();
                                        javax.imageio.ImageIO.write(resized, "png", pngOut);
                                        NativeImage image = NativeImage.read(pngOut.toByteArray());
                                        DynamicTexture dynamicTexture = new DynamicTexture(image);
                                        String id = "cover_" + Math.abs(artworkUrl.hashCode());
                                        ResourceLocation loc = Minecraft.getInstance().getTextureManager().register(id, dynamicTexture);
                                        CACHE.put(artworkUrl, loc);
                                    }
                                } catch (Exception e) {
                                    CloudJukebox.LOGGER.warn("Failed to decode cover image for {}: {}", artworkUrl, e.getMessage());
                                } finally {
                                    LOADING.remove(artworkUrl);
                                }
                            });
                        } else {
                            LOADING.remove(artworkUrl);
                        }
                    })
                    .exceptionally(ex -> {
                        LOADING.remove(artworkUrl);
                        return null;
                    });
        }

        return null;
    }

    public static void clear() {
        CACHE.clear();
        LOADING.clear();
    }
}
