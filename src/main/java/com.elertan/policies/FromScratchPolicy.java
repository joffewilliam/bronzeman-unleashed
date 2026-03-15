package com.elertan.policies;

import com.elertan.AccountConfigurationService;
import com.elertan.BUChatService;
import com.elertan.FromScratchModeUtils;
import com.elertan.GameRulesService;
import com.elertan.ItemUnlockService;
import com.elertan.PolicyService;
import com.elertan.WorldTypeService;
import com.elertan.data.AbstractDataProvider;
import com.elertan.data.FromScratchBankBaselineDataProvider;
import com.elertan.models.GameRules;
import com.elertan.models.ISOOffsetDateTime;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.Locale;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

@Slf4j
@Singleton
public class FromScratchPolicy extends PolicyBase {

    private static final int PROMPT_COOLDOWN_TICKS = 100;
    private static final Color LOCKED_OVERLAY_COLOR = new Color(0, 0, 0, 180);

    private final FromScratchLockedItemsOverlay lockedItemsOverlay = new FromScratchLockedItemsOverlay();

    @Inject
    private Client client;
    @Inject
    private ItemManager itemManager;
    @Inject
    private ItemUnlockService itemUnlockService;
    @Inject
    private FromScratchBankBaselineDataProvider fromScratchBankBaselineDataProvider;
    @Inject
    private BUChatService buChatService;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private KeyManager keyManager;

    private volatile boolean bankOpen = false;
    private volatile int lastDepositPromptTick = -PROMPT_COOLDOWN_TICKS;
    private volatile ISOOffsetDateTime announcedFromScratchStartedAt = null;
    private volatile PendingWithdrawX pendingWithdrawX = null;
    private volatile boolean pendingWithdrawXPrefilled = false;
    private KeyListener keyListener;

    @AllArgsConstructor
    private static class PendingWithdrawX {
        private final int itemId;
        private final int withdrawableQuantity;
    }

    @Inject
    public FromScratchPolicy(
        AccountConfigurationService accountConfigurationService,
        GameRulesService gameRulesService,
        PolicyService policyService,
        WorldTypeService worldTypeService
    ) {
        super(accountConfigurationService, gameRulesService, policyService, worldTypeService);
    }

    @Override
    public void startUp() throws Exception {
        overlayManager.add(lockedItemsOverlay);
        keyListener = new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == '\n' && shouldBlockPendingWithdrawXEnter()) {
                    e.consume();
                }
            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER
                    && shouldBlockPendingWithdrawXEnter()) {
                    e.consume();
                    PendingWithdrawX pending = pendingWithdrawX;
                    if (pending != null) {
                        buChatService.sendErrorMessage(
                            "From Scratch: you can only withdraw " + pending.withdrawableQuantity
                                + " currently earned item(s) from this stack."
                        );
                    }
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER
                    && shouldBlockPendingWithdrawXEnter()) {
                    e.consume();
                }
            }
        };
        keyManager.registerKeyListener(keyListener);
    }

    @Override
    public void shutDown() throws Exception {
        bankOpen = false;
        lastDepositPromptTick = -PROMPT_COOLDOWN_TICKS;
        announcedFromScratchStartedAt = null;
        pendingWithdrawX = null;
        pendingWithdrawXPrefilled = false;
        if (keyListener != null) {
            keyManager.unregisterKeyListener(keyListener);
            keyListener = null;
        }
        itemUnlockService.setSuppressFromScratchInventoryAndWornUnlocking(false);
        overlayManager.remove(lockedItemsOverlay);
    }

    public void onWidgetLoaded(WidgetLoaded event) {
        if (!accountConfigurationService.isBronzemanEnabled()) {
            return;
        }
        if (event.getGroupId() != InterfaceID.BANKMAIN) {
            return;
        }

        bankOpen = true;
        captureBankBaselineIfNeeded();
        evaluateDepositCleanupState();
    }

    public void onWidgetClosed(WidgetClosed event) {
        if (!accountConfigurationService.isBronzemanEnabled()) {
            return;
        }
        if (event.getGroupId() != InterfaceID.BANKMAIN) {
            return;
        }

        bankOpen = false;
        pendingWithdrawX = null;
        pendingWithdrawXPrefilled = false;
    }

    public void onItemContainerChanged(ItemContainerChanged event) {
        if (!accountConfigurationService.isBronzemanEnabled()) {
            return;
        }
        if (!isFromScratchActive()) {
            return;
        }

        int containerId = event.getContainerId();
        if (containerId == InventoryID.BANK && bankOpen) {
            captureBankBaselineIfNeeded();
        }
        if (containerId == InventoryID.INV || containerId == InventoryID.WORN) {
            evaluateDepositCleanupState();
        }
    }

    public void onGameTick(GameTick event) {
        if (!accountConfigurationService.isBronzemanEnabled()) {
            return;
        }
        if (!isFromScratchActive()) {
            announcedFromScratchStartedAt = null;
            pendingWithdrawX = null;
            pendingWithdrawXPrefilled = false;
            if (itemUnlockService.isSuppressFromScratchInventoryAndWornUnlocking()) {
                itemUnlockService.setSuppressFromScratchInventoryAndWornUnlocking(false);
            }
            return;
        }

        updateWithdrawXPromptHintAndPrefill();
        maybeAnnounceFromScratchActivation();
        if (bankOpen) {
            captureBankBaselineIfNeeded();
        }
        evaluateDepositCleanupState();
    }

    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!accountConfigurationService.isBronzemanEnabled()) {
            return;
        }
        if (!isFromScratchActive()) {
            return;
        }

        if (shouldValidateWithdrawXSubmit(event)) {
            boolean allowed = enforcePendingWithdrawXAmount(event);
            if (allowed) {
                pendingWithdrawX = null;
                pendingWithdrawXPrefilled = false;
            }
            return;
        }

        int itemId = resolveItemId(event);
        if (bankOpen && isWithdrawOption(event.getMenuOption())) {
            boolean allowed = enforceBankWithdrawPolicy(event, itemId);
            if (allowed && isWithdrawXOption(event.getMenuOption())) {
                int currentQuantity = getCurrentBankQuantity(itemId);
                Integer baselineQuantity = getBaselineQuantity(itemId);
                int withdrawable = FromScratchModeUtils.getWithdrawableQuantity(
                    currentQuantity,
                    baselineQuantity
                );
                pendingWithdrawX = new PendingWithdrawX(itemId, withdrawable);
                pendingWithdrawXPrefilled = false;
            } else {
                pendingWithdrawX = null;
                pendingWithdrawXPrefilled = false;
            }
            return;
        }
        pendingWithdrawX = null;
        pendingWithdrawXPrefilled = false;

        int widgetGroupId = resolveWidgetGroupId(event);
        if (widgetGroupId == InterfaceID.INVENTORY) {
            enforceInventoryEquipPolicy(event, itemId);
        }
    }

    private void enforceInventoryEquipPolicy(MenuOptionClicked event, int itemId) {
        if (itemId <= 1) {
            return;
        }
        if (!isEquipAttempt(event.getMenuOption())) {
            return;
        }

        if (isItemUnlocked(itemId)) {
            return;
        }

        event.consume();
        buChatService.sendErrorMessage("From Scratch: this item is still locked and cannot be equipped.");
    }

    private boolean enforceBankWithdrawPolicy(MenuOptionClicked event, int itemId) {
        if (itemId <= 1) {
            return false;
        }
        String menuOption = event.getMenuOption();
        if (!isWithdrawOption(menuOption)) {
            return false;
        }

        int currentQuantity = getCurrentBankQuantity(itemId);
        Integer baselineQuantity = getBaselineQuantity(itemId);

        boolean unlocked = isItemUnlocked(itemId);
        boolean canWithdraw = unlocked
            && FromScratchModeUtils.canWithdraw(menuOption, currentQuantity, baselineQuantity);
        if (canWithdraw) {
            return true;
        }

        event.consume();
        int withdrawable = FromScratchModeUtils.getWithdrawableQuantity(currentQuantity, baselineQuantity);
        if (!unlocked) {
            buChatService.sendErrorMessage(
                "From Scratch: this bank item is locked until you unlock it through live gameplay."
            );
            return false;
        }
        buChatService.sendErrorMessage(
            "From Scratch: you can only withdraw " + withdrawable + " currently earned item(s) from this stack."
        );
        return false;
    }

    private boolean shouldValidateWithdrawXSubmit(MenuOptionClicked event) {
        if (pendingWithdrawX == null) {
            return false;
        }
        if (!isEnterAmountPromptOpen()) {
            pendingWithdrawX = null;
            pendingWithdrawXPrefilled = false;
            return false;
        }
        return event.getMenuAction().ordinal() == MenuAction.WIDGET_CONTINUE.ordinal();
    }

    private boolean enforcePendingWithdrawXAmount(MenuOptionClicked event) {
        PendingWithdrawX pending = pendingWithdrawX;
        if (pending == null) {
            return true;
        }

        String input = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
        if (input == null || input.trim().isEmpty()) {
            input = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
        }
        Integer requested = parsePositiveInt(input);
        if (requested == null) {
            return true;
        }
        if (requested <= pending.withdrawableQuantity) {
            return true;
        }

        event.consume();
        buChatService.sendErrorMessage(
            "From Scratch: you can only withdraw " + pending.withdrawableQuantity
                + " currently earned item(s) from this stack."
        );
        return false;
    }

    private boolean shouldBlockPendingWithdrawXEnter() {
        if (!accountConfigurationService.isBronzemanEnabled()) {
            return false;
        }
        if (!isFromScratchActive()) {
            return false;
        }
        PendingWithdrawX pending = pendingWithdrawX;
        if (pending == null) {
            return false;
        }
        if (!isEnterAmountPromptOpen()) {
            return false;
        }
        String input = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
        if (input == null || input.trim().isEmpty()) {
            input = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
        }
        Integer requested = parsePositiveInt(input);
        return requested != null && requested > pending.withdrawableQuantity;
    }

    private void updateWithdrawXPromptHintAndPrefill() {
        PendingWithdrawX pending = pendingWithdrawX;
        if (pending == null) {
            return;
        }
        if (!isEnterAmountPromptOpen()) {
            return;
        }

        Widget promptWidget = getEnterAmountPromptWidget();
        if (promptWidget != null) {
            promptWidget.setText("Enter amount (max " + pending.withdrawableQuantity + "):");
        }

        if (pendingWithdrawXPrefilled) {
            return;
        }

        String input = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
        if (input == null || input.trim().isEmpty()) {
            input = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
        }
        Integer typedAmount = parsePositiveInt(input);
        if (typedAmount != null) {
            pendingWithdrawXPrefilled = true;
            return;
        }

        String suggested = String.valueOf(Math.max(0, pending.withdrawableQuantity));
        client.setVarcStrValue(VarClientStr.INPUT_TEXT, suggested);
        client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, suggested);
        pendingWithdrawXPrefilled = true;
    }

    private void evaluateDepositCleanupState() {
        boolean hasLockedItemsInInventoryOrWorn = hasLockedItemsInInventoryOrWorn();

        if (hasLockedItemsInInventoryOrWorn) {
            itemUnlockService.setSuppressFromScratchInventoryAndWornUnlocking(true);

            int tick = client.getTickCount();
            if (tick - lastDepositPromptTick >= PROMPT_COOLDOWN_TICKS) {
                lastDepositPromptTick = tick;
                buChatService.sendErrorMessage(
                    "From Scratch: deposit all locked inventory/equipment items to begin tracking new unlocks."
                );
            }
            return;
        }

        if (itemUnlockService.isSuppressFromScratchInventoryAndWornUnlocking()) {
            itemUnlockService.setSuppressFromScratchInventoryAndWornUnlocking(false);
            buChatService.sendMessage("From Scratch: inventory/equipment cleanup complete.");
        }
    }

    private void maybeAnnounceFromScratchActivation() {
        ISOOffsetDateTime startedAt = getFromScratchStartedAt();
        if (startedAt == null || startedAt.equals(announcedFromScratchStartedAt)) {
            return;
        }
        announcedFromScratchStartedAt = startedAt;
        buChatService.sendMessage(
            "From Scratch is now active for this group. Pre-existing bank items are locked until earned in live gameplay."
        );
    }

    private boolean hasLockedItemsInInventoryOrWorn() {
        return hasLockedItemsInContainer(client.getItemContainer(InventoryID.INV))
            || hasLockedItemsInContainer(client.getItemContainer(InventoryID.WORN));
    }

    private boolean hasLockedItemsInContainer(ItemContainer container) {
        if (container == null) {
            return false;
        }

        for (Item item : container.getItems()) {
            if (item == null || item.getId() <= 1 || item.getQuantity() <= 0) {
                continue;
            }
            if (!isItemUnlocked(item.getId())) {
                return true;
            }
        }
        return false;
    }

    private void captureBankBaselineIfNeeded() {
        if (!isFromScratchActive()) {
            return;
        }
        if (fromScratchBankBaselineDataProvider.getState().get() != AbstractDataProvider.State.Ready) {
            return;
        }

        ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
        if (bankContainer == null) {
            return;
        }

        ISOOffsetDateTime startedAt = getFromScratchStartedAt();
        if (startedAt == null) {
            return;
        }
        long accountHash = client.getAccountHash();
        String snapshotKey = FromScratchModeUtils.bankBaselineSnapshotKey(accountHash, startedAt);
        if (fromScratchBankBaselineDataProvider.getQuantity(snapshotKey) != null) {
            return;
        }

        for (Item item : bankContainer.getItems()) {
            if (item == null || item.getId() <= 1 || item.getQuantity() <= 0) {
                continue;
            }
            int canonicalItemId = canonicalizeItemId(item.getId());
            if (canonicalItemId <= 1) {
                continue;
            }

            String key = FromScratchModeUtils.bankBaselineKey(accountHash, startedAt, canonicalItemId);
            fromScratchBankBaselineDataProvider.putIfAbsent(key, item.getQuantity())
                .whenComplete((__, throwable) -> {
                    if (throwable != null) {
                        log.error("Failed to persist from-scratch bank baseline for key {}", key, throwable);
                    }
                });
        }
        fromScratchBankBaselineDataProvider.putIfAbsent(snapshotKey, 1)
            .whenComplete((__, throwable) -> {
                if (throwable != null) {
                    log.error("Failed to persist from-scratch bank baseline snapshot key {}", snapshotKey, throwable);
                }
            });
    }

    private int getCurrentBankQuantity(int itemId) {
        ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
        if (bankContainer == null) {
            return 0;
        }
        int canonicalTargetItemId = canonicalizeItemId(itemId);
        int quantity = 0;
        for (Item bankItem : bankContainer.getItems()) {
            if (bankItem == null || bankItem.getId() <= 1 || bankItem.getQuantity() <= 0) {
                continue;
            }
            if (canonicalizeItemId(bankItem.getId()) == canonicalTargetItemId) {
                quantity += bankItem.getQuantity();
            }
        }
        return quantity;
    }

    private Integer getBaselineQuantity(int itemId) {
        ISOOffsetDateTime startedAt = getFromScratchStartedAt();
        if (startedAt == null) {
            return null;
        }

        int canonicalItemId = canonicalizeItemId(itemId);
        String key = FromScratchModeUtils.bankBaselineKey(
            client.getAccountHash(),
            startedAt,
            canonicalItemId
        );
        return fromScratchBankBaselineDataProvider.getQuantity(key);
    }

    private int canonicalizeItemId(int itemId) {
        return itemManager.canonicalize(itemId);
    }

    private boolean isEnterAmountPromptOpen() {
        Widget promptWidget = getEnterAmountPromptWidget();
        if (promptWidget == null || promptWidget.isHidden()) {
            return false;
        }
        String text = promptWidget.getText();
        if (text == null) {
            return false;
        }
        String normalized = text.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
        return normalized.equals("enter amount:")
            || normalized.startsWith("enter amount (max ");
    }

    private Widget getEnterAmountPromptWidget() {
        Widget mesText2Widget = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
        if (mesText2Widget != null && !mesText2Widget.isHidden()) {
            String text = mesText2Widget.getText();
            if (text != null && text.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT)
                .startsWith("enter amount")) {
                return mesText2Widget;
            }
        }

        Widget mesTextWidget = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
        if (mesTextWidget != null && !mesTextWidget.isHidden()) {
            String text = mesTextWidget.getText();
            if (text != null && text.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT)
                .startsWith("enter amount")) {
                return mesTextWidget;
            }
        }
        return null;
    }

    private Integer parsePositiveInt(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replaceAll("[^0-9]", "");
        if (sanitized.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(sanitized);
            if (parsed <= 0) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int resolveWidgetGroupId(MenuOptionClicked event) {
        Widget widget = event.getWidget();
        if (widget != null) {
            return widget.getId() >>> 16;
        }
        int param1 = event.getParam1();
        if (param1 <= 0) {
            return -1;
        }
        return param1 >>> 16;
    }

    private int resolveItemId(MenuOptionClicked event) {
        Widget widget = event.getWidget();
        if (widget != null && widget.getItemId() > 1) {
            return widget.getItemId();
        }

        int widgetItemId = resolveItemIdFromWidget(event.getParam1(), event.getParam0());
        if (widgetItemId > 1) {
            return widgetItemId;
        }

        return event.getId();
    }

    private int resolveItemIdFromWidget(int widgetId, int childIndex) {
        if (widgetId <= 0) {
            return -1;
        }
        Widget container = client.getWidget(widgetId);
        if (container == null) {
            return -1;
        }
        if (container.getItemId() > 1) {
            return container.getItemId();
        }

        Widget[] dynamicChildren = container.getDynamicChildren();
        if (dynamicChildren != null
            && childIndex >= 0
            && childIndex < dynamicChildren.length) {
            Widget child = dynamicChildren[childIndex];
            if (child != null && child.getItemId() > 1) {
                return child.getItemId();
            }
        }

        Widget[] children = container.getChildren();
        if (children != null
            && childIndex >= 0
            && childIndex < children.length) {
            Widget child = children[childIndex];
            if (child != null && child.getItemId() > 1) {
                return child.getItemId();
            }
        }
        return -1;
    }

    private boolean isItemUnlocked(int itemId) {
        try {
            return itemUnlockService.hasUnlockedItem(itemId);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isFromScratchActive() {
        GameRules gameRules = gameRulesService.getGameRules().get();
        return gameRules != null && gameRules.isFromScratch();
    }

    private ISOOffsetDateTime getFromScratchStartedAt() {
        GameRules gameRules = gameRulesService.getGameRules().get();
        if (gameRules == null || !gameRules.isFromScratch()) {
            return null;
        }
        return gameRules.getFromScratchStartedAt();
    }

    private static boolean isWithdrawOption(String menuOption) {
        if (menuOption == null) {
            return false;
        }
        return sanitizeMenuOption(menuOption).startsWith("withdraw");
    }

    private static boolean isWithdrawXOption(String menuOption) {
        if (menuOption == null) {
            return false;
        }
        return sanitizeMenuOption(menuOption).contains("-x");
    }

    private static boolean isEquipAttempt(String menuOption) {
        if (menuOption == null) {
            return false;
        }
        String normalized = sanitizeMenuOption(menuOption);
        return normalized.startsWith("wear")
            || normalized.startsWith("wield")
            || normalized.startsWith("equip");
    }

    private static String sanitizeMenuOption(String menuOption) {
        return menuOption.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
    }

    private class FromScratchLockedItemsOverlay extends Overlay {

        private FromScratchLockedItemsOverlay() {
            setPosition(OverlayPosition.DYNAMIC);
            setLayer(OverlayLayer.ABOVE_WIDGETS);
        }

        @Override
        public Dimension render(Graphics2D graphics) {
            if (!isFromScratchActive()) {
                return null;
            }

            drawLockedOverlays(graphics, client.getWidget(InterfaceID.Inventory.ITEMS), false);
            drawLockedOverlays(graphics, client.getWidget(InterfaceID.Wornitems.EQUIPMENT), false);

            if (bankOpen) {
                drawLockedOverlays(graphics, client.getWidget(InterfaceID.Bankmain.ITEMS), true);
            }
            return null;
        }

        private void drawLockedOverlays(Graphics2D graphics, Widget containerWidget, boolean bankContainer) {
            if (containerWidget == null) {
                return;
            }
            Widget[] children = containerWidget.getDynamicChildren();
            if (children == null || children.length == 0) {
                return;
            }

            Shape previousClip = graphics.getClip();
            Rectangle containerBounds = containerWidget.getBounds();
            graphics.setClip(containerBounds);

            Composite previousComposite = graphics.getComposite();
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setColor(LOCKED_OVERLAY_COLOR);

            for (Widget child : children) {
                if (child == null || child.isHidden()) {
                    continue;
                }

                int childItemId = child.getItemId();
                if (childItemId <= 1) {
                    continue;
                }

                boolean locked;
                if (bankContainer) {
                    int currentQuantity = getCurrentBankQuantity(childItemId);
                    Integer baselineQuantity = getBaselineQuantity(childItemId);
                    locked = !isItemUnlocked(childItemId) || !FromScratchModeUtils.canWithdraw(
                        "Withdraw-1",
                        currentQuantity,
                        baselineQuantity
                    );
                } else {
                    locked = !isItemUnlocked(childItemId);
                }
                if (!locked) {
                    continue;
                }

                Rectangle bounds = child.getBounds();
                graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }

            graphics.setComposite(previousComposite);
            graphics.setClip(previousClip);
        }
    }
}
