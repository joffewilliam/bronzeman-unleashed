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
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptEvent;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
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
    /** Transparency for item widgets (0 = opaque, 255 = fully transparent). Placeholder-style grey for locked. */
    private static final int LOCKED_ITEM_TRANSPARENCY = 120;
    private static final int UNLOCKED_ITEM_TRANSPARENCY = 0;
    /** Chatbox group 162, child 44: layer button that contains the typed amount (e.g. "300*"). */
    private static final int CHATBOX_AMOUNT_INPUT_WIDGET_ID = (162 << 16) | 44;
    /** Same as POH: script that runs on each key typed in chatbox/amount layer (not in ScriptID). */
    private static final int CHATBOX_INPUT_SCRIPT_ID = 112;
    /** Enter in meslayer_onkey calls meslayer_enter (proc 681), which is the actual submit path. */
    private static final int MESLAYER_ENTER_SCRIPT_ID = 681;

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
    @Inject
    private ClientThread clientThread;

    private volatile boolean bankOpen = false;
    private volatile int lastDepositPromptTick = -PROMPT_COOLDOWN_TICKS;
    private volatile int lastBankQtyLogTick = -999;
    private volatile ISOOffsetDateTime announcedFromScratchStartedAt = null;
    private volatile PendingWithdrawX pendingWithdrawX = null;
    /** Mirrored from ScriptPreFired(112) when amount prompt is open, so we don't rely on widget/varc. */
    private volatile String withdrawXMirroredAmount = null;
    private KeyListener withdrawXKeyListener;

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
        withdrawXKeyListener = new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {
                // Keep typing behavior native; enforcement happens at submit scripts.
            }

            @Override
            public void keyPressed(KeyEvent e) {
                // Do not call client/widget APIs here — KeyListener runs on AWT thread.
                // Enforcement is done on client thread in ScriptPreFired(681) and in updateWithdrawXPromptHintAndPrefill (cap displayed value).
            }

            @Override
            public void keyReleased(KeyEvent e) {
                // Keep typing behavior native; enforcement happens at submit scripts.
            }
        };
        keyManager.registerKeyListener(withdrawXKeyListener);
    }

    @Override
    public void shutDown() throws Exception {
        bankOpen = false;
        lastDepositPromptTick = -PROMPT_COOLDOWN_TICKS;
        announcedFromScratchStartedAt = null;
        pendingWithdrawX = null;
        if (withdrawXKeyListener != null) {
            keyManager.unregisterKeyListener(withdrawXKeyListener);
            withdrawXKeyListener = null;
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
        withdrawXMirroredAmount = null;
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
            if (itemUnlockService.isSuppressFromScratchInventoryAndWornUnlocking()) {
                itemUnlockService.setSuppressFromScratchInventoryAndWornUnlocking(false);
            }
            return;
        }

        updateWithdrawXPromptHintAndPrefill();
        maybeAnnounceFromScratchActivation();
        if (bankOpen) {
            captureBankBaselineIfNeeded();
            applyBankDisplayQuantities();
        }
        applyLockedItemTransparency();
        evaluateDepositCleanupState();
    }

    public void onScriptPostFired(ScriptPostFired event) {
        if (!accountConfigurationService.isBronzemanEnabled()) {
            return;
        }
        if (!bankOpen || !isFromScratchActive()) {
            return;
        }
        int scriptId = event.getScriptId();
        if (scriptId == ScriptID.BANKMAIN_FINISHBUILDING
            || scriptId == ScriptID.BANKMAIN_SEARCH_REFRESH) {
            applyBankDisplayQuantities();
            applyLockedItemTransparency();
        }
    }

    /**
     * Mirror amount input from script 112 (POH-style) and handle submit/close.
     * When amount prompt is submitted we cap; when we block Enter we run MESSAGE_LAYER_CLOSE instead.
     */
    public void onScriptPreFired(ScriptPreFired event) {
        if (!accountConfigurationService.isBronzemanEnabled()) {
            return;
        }
        if (!isFromScratchActive()) {
            return;
        }
        int scriptId = event.getScriptId();
        if (scriptId == CHATBOX_INPUT_SCRIPT_ID) {
            mirrorWithdrawXAmountFromScript(event);
            return;
        }
        if (scriptId == ScriptID.MESSAGE_LAYER_CLOSE) {
            withdrawXMirroredAmount = null;
        }
        // Only 681 / 3750 / 4818 actually read the amount and submit; MESSAGE_LAYER_CLOSE just closes.
        boolean isAmountSubmit =
            scriptId == MESLAYER_ENTER_SCRIPT_ID
                || scriptId == ScriptID.POTIONSTORE_DOSES
                || scriptId == ScriptID.POTIONSTORE_WITHDRAW_DOSES;
        if (!isAmountSubmit) {
            return;
        }
        if (pendingWithdrawX != null) {
            log.info("From Scratch: amount submit script pre-fired scriptId={}", scriptId);
        }
        enforceWithdrawXSubmitCap();
        pendingWithdrawX = null;
        withdrawXMirroredAmount = null;
    }

    /**
     * Called right before submit scripts run (meslayer_enter/681 or potion store reuse scripts).
     * Ensures the value the client will read is never above withdrawable (cap varc + widget).
     * Do not require isEnterAmountPromptOpen() — by the time submit runs the prompt may already be closing.
     */
    private void enforceWithdrawXSubmitCap() {
        PendingWithdrawX pending = pendingWithdrawX;
        if (pending == null) {
            return;
        }
        Integer requested = parsePositiveInt(getAmountForValidation());
        if (requested == null) {
            requested = parsePositiveInt(getAmountInputRaw());
        }
        if (requested == null || requested <= pending.withdrawableQuantity) {
            return;
        }
        int capped = Math.max(1, pending.withdrawableQuantity);
        log.info("From Scratch: Withdraw-X cap applied requested={} withdrawable={} capped={}",
            requested, pending.withdrawableQuantity, capped);
        setAmountInputRaw(String.valueOf(capped));
        buChatService.sendErrorMessage(
            "From Scratch: amount capped to " + capped + " unlocked item(s)."
        );
    }

    /** POH-style: mirror typed chars from script 112 so we have a reliable amount for validation. */
    private void mirrorWithdrawXAmountFromScript(ScriptPreFired event) {
        if (pendingWithdrawX == null || !isEnterAmountPromptOpen()) {
            return;
        }
        ScriptEvent scriptEvent = event.getScriptEvent();
        int typedChar = scriptEvent.getTypedKeyChar();
        if (typedChar == 0) {
            return;
        }
        if (typedChar == 8) {
            if (withdrawXMirroredAmount != null && !withdrawXMirroredAmount.isEmpty()) {
                withdrawXMirroredAmount = withdrawXMirroredAmount.substring(
                    0, withdrawXMirroredAmount.length() - 1);
                if (withdrawXMirroredAmount.isEmpty()) {
                    withdrawXMirroredAmount = null;
                }
            }
            return;
        }
        if (typedChar == 10) {
            return;
        }
        if (Character.isDigit((char) typedChar)) {
            String base = withdrawXMirroredAmount == null ? "" : withdrawXMirroredAmount;
            withdrawXMirroredAmount = base + (char) typedChar;
        }
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
                withdrawXMirroredAmount = null;
                pendingWithdrawX = new PendingWithdrawX(itemId, withdrawable);
            } else {
                pendingWithdrawX = null;
                withdrawXMirroredAmount = null;
            }
            return;
        }
        if (!isEnterAmountPromptOpen()) {
            pendingWithdrawX = null;
            withdrawXMirroredAmount = null;
        }

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
        int withdrawable = FromScratchModeUtils.getWithdrawableQuantity(currentQuantity, baselineQuantity);

        boolean unlocked = isItemUnlocked(itemId);
        if (!unlocked) {
            event.consume();
            buChatService.sendErrorMessage(
                "From Scratch: this bank item is locked until you unlock it through live gameplay."
            );
            return false;
        }
        if (withdrawable <= 0) {
            event.consume();
            buChatService.sendErrorMessage(
                "From Scratch: you have none of this item unlocked to withdraw."
            );
            return false;
        }

        if (isWithdrawXOption(menuOption)) {
            return true;
        }

        boolean withinCap = FromScratchModeUtils.canWithdraw(menuOption, currentQuantity, baselineQuantity);
        if (withinCap) {
            return true;
        }

        // Requested amount exceeds withdrawable (e.g. Withdraw-All on 5k stack with 300 unlocked).
        // Consume and replay as Withdraw-X with the capped amount so they get exactly withdrawable.
        event.consume();
        int param0 = event.getParam0();
        int param1 = event.getParam1();
        int widgetId = event.getWidget() != null ? event.getWidget().getId() : 0;
        String target = event.getMenuTarget() != null ? event.getMenuTarget() : "";
        int amountToWithdraw = withdrawable;
        clientThread.invokeLater(() -> {
            client.menuAction(
                param0,
                param1,
                MenuAction.CC_OP,
                widgetId,
                itemId,
                "Withdraw-X",
                target
            );
            clientThread.invokeLater(() -> {
                setAmountInputRaw(String.valueOf(amountToWithdraw));
                client.runScript(ScriptID.POTIONSTORE_DOSES);
            });
        });
        return false;
    }

    /**
     * After any key is typed into the amount prompt, if the value exceeds the cap we immediately
     * overwrite MESLAYERINPUT and the widget so the client never sees more than the limit.
     */
    private void capWithdrawXInputIfOverLimit() {
        PendingWithdrawX pending = pendingWithdrawX;
        if (pending == null || !isEnterAmountPromptOpen()) {
            return;
        }
        Integer requested = parsePositiveInt(getAmountInputRaw());
        if (requested != null && requested > pending.withdrawableQuantity) {
            int capped = Math.max(1, pending.withdrawableQuantity);
            setAmountInputRaw(String.valueOf(capped));
        }
    }

    /** True when the amount prompt is open and typed amount exceeds From Scratch limit (block Enter). */
    private boolean shouldBlockWithdrawXSubmit() {
        if (!accountConfigurationService.isBronzemanEnabled() || !isFromScratchActive()) {
            return false;
        }
        PendingWithdrawX pending = pendingWithdrawX;
        if (pending == null || !isEnterAmountPromptOpen()) {
            return false;
        }
        String amountSource = getAmountForValidation();
        Integer requested = parsePositiveInt(amountSource);
        return requested != null && requested > pending.withdrawableQuantity;
    }

    /** Prefer mirrored amount (from script 112) when available, else widget/varc. */
    private String getAmountForValidation() {
        if (withdrawXMirroredAmount != null && !withdrawXMirroredAmount.isEmpty()) {
            return withdrawXMirroredAmount;
        }
        return getAmountInputRaw();
    }

    private boolean shouldValidateWithdrawXSubmit(MenuOptionClicked event) {
        if (pendingWithdrawX == null) {
            return false;
        }
        if (!isEnterAmountPromptOpen()) {
            pendingWithdrawX = null;
            return false;
        }
        return event.getMenuAction().ordinal() == MenuAction.WIDGET_CONTINUE.ordinal();
    }

    private boolean enforcePendingWithdrawXAmount(MenuOptionClicked event) {
        PendingWithdrawX pending = pendingWithdrawX;
        if (pending == null) {
            return true;
        }

        Integer requested = parsePositiveInt(getAmountInputRaw());
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

    /**
     * Update the withdraw-X prompt text and enforce cap: while the prompt is open, keep the
     * input vars capped to withdrawable so that when the client submits (Enter or click) it
     * never sees more than the allowed amount.
     */
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

        Integer requested = parsePositiveInt(getAmountInputRaw());
        if (requested != null && requested > pending.withdrawableQuantity) {
            int capped = Math.max(1, pending.withdrawableQuantity);
            setAmountInputRaw(String.valueOf(capped));
        }
    }

    /**
     * Runs every render frame (~60fps / ~16ms). Prevents the typed amount from ever exceeding the
     * From Scratch cap so the client can never read an over-limit value when Enter is pressed.
     */
    private void enforceWithdrawXCapEveryFrame() {
        PendingWithdrawX pending = pendingWithdrawX;
        if (pending == null) {
            return;
        }
        if (!isEnterAmountPromptOpen()) {
            return;
        }
        Integer requested = parsePositiveInt(getAmountInputRaw());
        if (requested != null && requested > pending.withdrawableQuantity) {
            int capped = Math.max(1, pending.withdrawableQuantity);
            setAmountInputRaw(String.valueOf(capped));
        }
    }

    private void evaluateDepositCleanupState() {
        if (!itemUnlockService.isFromScratchUnlockedItemsDataProviderReady()) {
            if (itemUnlockService.isSuppressFromScratchInventoryAndWornUnlocking()) {
                itemUnlockService.setSuppressFromScratchInventoryAndWornUnlocking(false);
            }
            return;
        }

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
        buChatService.sendMessage(
            "From Scratch: open your bank once to capture your baseline snapshot."
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

    /**
     * Set each bank item widget's displayed quantity to the withdrawable amount (current minus
     * baseline). Uses Bankmain.ITEMS (12.12) dynamic children (D 12.12[...]) which are item slots
     * in this interface layout.
     */
    private void applyBankDisplayQuantities() {
        Widget itemsWidget = client.getWidget(InterfaceID.Bankmain.ITEMS);
        if (itemsWidget == null) {
            log.info("From Scratch: bank qty patch — widget 12.12 (ITEMS) is null");
            return;
        }
        Widget[] children = itemsWidget.getDynamicChildren();
        if (children == null) {
            log.info("From Scratch: bank qty patch — getDynamicChildren() is null");
            return;
        }

        int tick = client.getTickCount();
        boolean shouldLog = (tick - lastBankQtyLogTick) >= 100;
        if (shouldLog) {
            lastBankQtyLogTick = tick;
            log.info("From Scratch: bank qty patch entered widgetId={} children={}", itemsWidget.getId(), children.length);
        }

        // Track remaining withdrawable per item id so we split across multiple slots of same item
        Map<Integer, Integer> withdrawableRemaining = new HashMap<>();
        int loggedItems = 0;
        final int maxLogItems = 3;
        for (Widget child : children) {
            if (child == null || child.isHidden()) {
                continue;
            }
            int itemId = child.getItemId();
            if (itemId <= 1) {
                continue;
            }
            int slotQty = child.getItemQuantity();
            int cur = getCurrentBankQuantity(itemId);
            Integer base = getBaselineQuantity(itemId);
            int totalWithdrawable = FromScratchModeUtils.getWithdrawableQuantity(cur, base);
            int remaining = withdrawableRemaining.computeIfAbsent(itemId, id -> totalWithdrawable);
            int show = Math.min(slotQty, remaining);

            if (shouldLog && loggedItems < maxLogItems) {
                log.info("From Scratch: bank qty itemId={} slotQty={} cur={} base={} withdraw={} set={} childId={}",
                    itemId, slotQty, cur, base, totalWithdrawable, show, child.getId());
                loggedItems++;
            }

            child.setItemQuantity(show);
            withdrawableRemaining.put(itemId, remaining - show);
        }
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

    /**
     * Read the current amount from the prompt: first the layer widget (e.g. "300*"), then varcs.
     * The widget text has * at the end for the cursor; we strip it and parse digits.
     */
    private String getAmountInputRaw() {
        Widget amountWidget = client.getWidget(CHATBOX_AMOUNT_INPUT_WIDGET_ID);
        if (amountWidget != null && !amountWidget.isHidden()) {
            String text = amountWidget.getText();
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        String input = client.getVarcStrValue(VarClientID.MESLAYERINPUT);
        if (input != null && !input.trim().isEmpty()) {
            return input;
        }
        input = client.getVarcStrValue(VarClientID.CHATINPUT);
        return input;
    }

    /**
     * Write the amount into both the layer widget and the varcs so the client submits this value.
     * Widget shows e.g. "123*"; we set that so the cursor stays at end.
     */
    private void setAmountInputRaw(String value) {
        String withCursor = value + "*";
        Widget amountWidget = client.getWidget(CHATBOX_AMOUNT_INPUT_WIDGET_ID);
        if (amountWidget != null && !amountWidget.isHidden()) {
            amountWidget.setText(withCursor);
        }
        client.setVarcStrValue(VarClientID.MESLAYERINPUT, value);
        client.setVarcStrValue(VarClientID.CHATINPUT, value);
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

    /**
     * Apply placeholder-style transparency for locked items (inv, equipment, bank).
     * Called from GameTick and after bank script so opacity is set before the client draws,
     * avoiding a frame of "normal" then "dulled" when opening bank or switching tabs.
     */
    private void applyLockedItemTransparency() {
        if (!isFromScratchActive()) {
            return;
        }
        applyTransparencyToContainer(client.getWidget(InterfaceID.Inventory.ITEMS), false);
        applyTransparencyToContainer(client.getWidget(InterfaceID.Wornitems.EQUIPMENT), false);
        if (bankOpen) {
            applyTransparencyToContainer(client.getWidget(InterfaceID.Bankmain.ITEMS), true);
        }
    }

    /**
     * Lightweight: only inv + equipment (fixed ~42 slots). Used every overlay frame.
     * Bank is done in GameTick/script only to avoid O(bankSlots * bankSize) per frame and FPS drop.
     */
    private void applyLockedItemTransparencyInvAndEquipmentOnly() {
        if (!isFromScratchActive()) {
            return;
        }
        applyTransparencyToContainer(client.getWidget(InterfaceID.Inventory.ITEMS), false);
        applyTransparencyToContainer(client.getWidget(InterfaceID.Wornitems.EQUIPMENT), false);
    }

    /**
     * Set widget transparency (0 = opaque, 255 = full transparent) per item so locked items
     * look placeholder-style grey without a box.
     */
    private void applyTransparencyToContainer(Widget containerWidget, boolean bankContainer) {
        if (containerWidget == null) {
            return;
        }
        Widget[] children = containerWidget.getDynamicChildren();
        if (children == null || children.length == 0) {
            return;
        }
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
            child.setOpacity(locked ? LOCKED_ITEM_TRANSPARENCY : UNLOCKED_ITEM_TRANSPARENCY);
        }
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
            applyLockedItemTransparencyInvAndEquipmentOnly();
            enforceWithdrawXCapEveryFrame();
            return null;
        }
    }
}
