package com.elertan;

import com.elertan.resource.BUImageUtil;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.ImageIcon;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

@Slf4j
@Singleton
public class BUResourceService implements BUPluginLifecycle {

    private static final String ICON_FILE_PATH = "/icons/bu-icon.png";
    private static final String CHECKMARK_ICON_FILE_PATH = "/icons/bu-checkmark-icon.png";
    private static final String CONFIGURE_ICON_FILE_PATH = "/icons/bu-configure-icon.png";
    private static final String LOADING_SPINNER_FILE_PATH = "/icons/bu-loading-spinner.gif";

//    static {
//        URL u1 = BUPlugin.class.getResource(ICON_FILE_PATH);
//        URL u2 = BUPlugin.class.getResource(CHECKMARK_ICON_FILE_PATH);
//        URL u3 = BUPlugin.class.getResource(CONFIGURE_ICON_FILE_PATH);
//        URL u4 = BUPlugin.class.getResource(LOADING_SPINNER_FILE_PATH);
//        log.info("BU icons resolved: icon={} check={} cfg={} spin={}", u1, u2, u3, u4);
//        if (u1 == null || u2 == null || u3 == null || u4 == null) {
//            throw new IllegalStateException(
//                "BUResourceService: icon resource not found relative to com/elertan");
//        }
//    }

    @Getter
    private final BufferedImage iconBufferedImage = ImageUtil.loadImageResource(
        BUPlugin.class,
        ICON_FILE_PATH
    );
    @Getter
    private final BufferedImage checkmarkIconBufferedImage = ImageUtil.loadImageResource(
        BUPlugin.class,
        CHECKMARK_ICON_FILE_PATH
    );
    @Getter
    private final BufferedImage configureIconBufferedImage = ImageUtil.loadImageResource(
        BUPlugin.class,
        CONFIGURE_ICON_FILE_PATH
    );
    @Getter
    private final ImageIcon loadingSpinnerImageIcon = new ImageIcon(Objects.requireNonNull(
        BUPlugin.class.getResource(
            LOADING_SPINNER_FILE_PATH)));

    @Getter
    private final BufferedImage joinPartyIconBufferedImage = createJoinPartyIconPlaceholder(24, 24);

    private static BufferedImage createJoinPartyIconPlaceholder(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(java.awt.Color.LIGHT_GRAY);
            g.setStroke(new BasicStroke(2f));
            int pad = 4;
            int r = Math.min(w, h) / 2 - pad;
            int cx = w / 2;
            int cy = h / 2;
            g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
            g.drawLine(cx - r / 2, cy, cx + r / 2, cy);
            g.drawLine(cx, cy - r / 2, cx, cy + r / 2);
        } finally {
            g.dispose();
        }
        return img;
    }

    private final ConcurrentHashMap<Integer, Integer> itemImageModIconIdCache = new ConcurrentHashMap<>();
    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;
    @Inject
    private ItemManager itemManager;
    @Getter
    private BUModIcons buModIcons;

    @Override
    public void startUp() {
        this.initializeModIcons();
    }

    @Override
    public void shutDown() {

    }

    private void initializeModIcons() {
        IndexedSprite[] modIcons = client.getModIcons();
        if (modIcons == null) {
            // Retry later when is initialized
            clientThread.invokeLater(this::initializeModIcons);
            return;
        }

        // Mod icons
        BufferedImage chatIcon = BUImageUtil.resizeNearest(iconBufferedImage, 13, 13, 0, 0);
        IndexedSprite chatIconSprite = ImageUtil.getImageIndexedSprite(chatIcon, client);

        int chatIconId = modIcons.length;

        IndexedSprite[] newModIcons = Arrays.copyOf(modIcons, modIcons.length + 1);
        newModIcons[chatIconId] = chatIconSprite;
        client.setModIcons(newModIcons);

        this.buModIcons = new BUModIcons(chatIconId);
        log.debug("BUResourceService: mod icons and sprites initialized");
    }

    public CompletableFuture<Integer> getOrSetupItemImageModIconId(int itemId) {
        CompletableFuture<Integer> future = new CompletableFuture<>();

        if (itemImageModIconIdCache.containsKey(itemId)) {
            Integer modIconId = itemImageModIconIdCache.get(itemId);
            if (modIconId != null) {
                future.complete(modIconId);
                return future;
            }
        }

        clientThread.invokeLater(() -> {
            AsyncBufferedImage asyncBufferedImage = itemManager.getImage(itemId);
            // AsyncBufferedImage.onLoaded() callback runs on client thread - safe to use client methods
            asyncBufferedImage.onLoaded(() -> {
                int size = 14;
                BufferedImage resized = BUImageUtil.resizeNearest(
                    asyncBufferedImage,
                    size,
                    size,
                    0,
                    0
                );
                IndexedSprite sprite = ImageUtil.getImageIndexedSprite(resized, client);
                sprite.setOffsetX(1);
                sprite.setOffsetY(2);
                IndexedSprite[] modIcons = client.getModIcons();
                int lastIdx = modIcons.length;
                IndexedSprite[] newModIcons = Arrays.copyOf(
                    modIcons,
                    lastIdx + 1
                );
                newModIcons[lastIdx] = sprite;
                client.setModIcons(newModIcons);

                future.complete(lastIdx);
            });
        });

        return future;
    }

    @Value
    public static class BUModIcons {

        int chatIconId;
    }
}
