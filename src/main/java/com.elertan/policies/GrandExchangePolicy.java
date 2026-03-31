package com.elertan.policies;

import com.elertan.AccountConfigurationService;
import com.elertan.BUChatService;
import com.elertan.GameRulesService;
import com.elertan.ItemUnlockService;
import com.elertan.PolicyService;
import com.elertan.WorldTypeService;
import com.elertan.chat.ChatMessageProvider.MessageKey;
import com.elertan.models.GameRules;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

@Slf4j
@Singleton
public class GrandExchangePolicy extends PolicyBase {

    private final static int GE_SEARCH_BUILD_SCRIPT_ID = 751;
    @Inject
    private Client client;
    @Inject
    private ItemUnlockService itemUnlockService;
    @Inject
    private BUChatService buChatService;

    private boolean geInterfaceOpen;

    @Inject
    public GrandExchangePolicy(
        AccountConfigurationService accountConfigurationService,
        GameRulesService gameRulesService, PolicyService policyService,
        WorldTypeService worldTypeService
    ) {
        super(accountConfigurationService, gameRulesService, policyService, worldTypeService);
    }

    @Override
    public void shutDown() throws Exception {
        geInterfaceOpen = false;
    }

    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGIN_SCREEN) {
            geInterfaceOpen = false;
        }
    }

    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() == InterfaceID.GE_OFFERS) {
            geInterfaceOpen = true;
        }
    }

    public void onWidgetClosed(WidgetClosed event) {
        if (event.getGroupId() == InterfaceID.GE_OFFERS) {
            geInterfaceOpen = false;
        }
    }

    public void onScriptPostFired(ScriptPostFired event) {
        if (!accountConfigurationService.isBronzemanEnabled()) {
            return;
        }

        if (!geInterfaceOpen)
        {
            return;
        }

        int scriptId = event.getScriptId();
        if (scriptId == GE_SEARCH_BUILD_SCRIPT_ID) {
            onSearchBuild();
        }
    }

    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!accountConfigurationService.isBronzemanEnabled()) {
            return;
        }

        MenuAction action = event.getMenuAction();
        if (action != MenuAction.CC_OP && action != MenuAction.CC_OP_LOW_PRIORITY) {
            return;
        }

        String option = event.getMenuOption();
        if (option == null || !option.startsWith("Buy")) {
            return;
        }

        int itemId = event.getId();
        if (itemId <= 0) {
            return;
        }

        PolicyContext context = createContext();
        boolean preventLocked = context.shouldApplyForRules(GameRules::isPreventGrandExchangeBuyOffers);
        boolean preventGear = context.shouldApplyForRules(GameRules::isPreventGrandExchangeGearBuyOffers);
        if (!preventLocked && !preventGear) {
            return;
        }

        try {
            if (preventLocked && !itemUnlockService.hasUnlockedItem(itemId)) {
                event.consume();
                buChatService.sendRestrictionMessage(MessageKey.GE_BUY_LOCKED_ITEM_RESTRICTION);
                return;
            }
            if (preventGear && isGearItem(itemId)) {
                event.consume();
                buChatService.sendRestrictionMessage(MessageKey.GE_BUY_GEAR_RESTRICTION);
            }
        } catch (Exception e) {
            log.warn("Failed GE buy restriction check for item {}", itemId, e);
        }
    }

    private void onSearchBuild() {
        PolicyContext context = createContext();
        boolean preventLocked = context.shouldApplyForRules(GameRules::isPreventGrandExchangeBuyOffers);
        boolean preventGear = context.shouldApplyForRules(GameRules::isPreventGrandExchangeGearBuyOffers);
        if (!preventLocked && !preventGear) {
            return;
        }

        Widget searchResultsWidget = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        if (searchResultsWidget == null) {
            log.error("Search results widget is null onGrandExchangeSearchBuild");
            return;
        }

        final Widget[] children = searchResultsWidget.getDynamicChildren();
        if (children == null || children.length < 2 || children.length % 3 != 0) {
            return;
        }

        for (int i = 0; i < children.length; i += 3) {
            final Widget itemWidget = children[i + 2];
            final int itemId = itemWidget.getItemId();
            boolean block = false;

            if (preventLocked) {
                try {
                    block = !itemUnlockService.hasUnlockedItem(itemId);
                } catch (Exception e) {
                    log.error(
                        "Failed to check hasUnlockedItem({}) in onGrandExchangeSearchBuild",
                        itemId,
                        e
                    );
                    return;
                }
            }

            if (!block && preventGear && isGearItem(itemId)) {
                block = true;
            }

            if (block) {
                // Make not clickable
                children[i].setHidden(true);

                // Make transparent to indicate not clickable
                children[i + 1].setOpacity(120);
                children[i + 2].setOpacity(120);
            }
        }
    }

    private boolean isGearItem(int itemId) {
        ItemComposition itemComposition = client.getItemDefinition(itemId);
        if (itemComposition == null) {
            return false;
        }

        String[] actions = itemComposition.getInventoryActions();
        if (actions == null) {
            return false;
        }

        for (String action : actions) {
            if (action == null) {
                continue;
            }

            String normalizedAction = action.toLowerCase();
            if (normalizedAction.contains("wear")
                || normalizedAction.contains("equip")
                || normalizedAction.contains("wield")) {
                return true;
            }
        }

        return false;
    }
}
