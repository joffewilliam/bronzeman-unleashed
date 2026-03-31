package com.elertan.panel.components;

import com.elertan.models.GameRules;
import com.elertan.models.ISOOffsetDateTime;
import com.elertan.panel.BaseViewModel;
import com.elertan.ui.Property;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import java.beans.PropertyChangeListener;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GameRulesEditorViewModel extends BaseViewModel {

    public final Property<Boolean> onlyForTradeableItemsProperty;
    public final Property<Boolean> restrictGroundItemsProperty;
    public final Property<Boolean> preventTradeOutsideGroupProperty;
    public final Property<Boolean> preventTradeLockedItemsProperty;
    public final Property<GrandExchangeBuyPolicyMode> grandExchangeBuyPolicyModeProperty;
    public final Property<List<GrandExchangeBuyPolicyMode>> grandExchangeBuyPolicyModeOptionsProperty;
    public final Property<Map<GrandExchangeBuyPolicyMode, String>> grandExchangeBuyPolicyModeLabelMapProperty;
    public final Property<Boolean> preventPlayedOwnedHouseProperty;
    public final Property<Boolean> restrictPlayerVersusPlayerLootProperty;
    public final Property<Boolean> restrictFaladorPartyRoomBalloonsProperty;
    public final Property<Boolean> shareAchievementNotificationsProperty;
    public final Property<Integer> valuableLootNotificationThresholdProperty;
    public final Property<String> partyPasswordProperty;
    public final Property<Boolean> isViewOnlyModeProperty;
    public final Property<Boolean> isLocalModeProperty;
    private Props props;
    private final PropertyChangeListener updateListener = evt -> {
        log.debug("{} changed to: {}", evt.getPropertyName(), evt.getNewValue());
        tryUpdateGameRules();
    };

    private GameRulesEditorViewModel(Props initialProps) {
        this.props = initialProps;

        boolean setGameRules = false;
        GameRules gameRules = initialProps.getGameRules();
        if (gameRules == null) {
            ISOOffsetDateTime now = new ISOOffsetDateTime(OffsetDateTime.now());
            gameRules = GameRules.createWithDefaults(initialProps.getAccountHash(), now);
            setGameRules = true;
        }

        onlyForTradeableItemsProperty = new Property<>(gameRules.isOnlyForTradeableItems());
        restrictGroundItemsProperty = new Property<>(gameRules.isRestrictGroundItems());
        preventTradeOutsideGroupProperty = new Property<>(gameRules.isPreventTradeOutsideGroup());
        preventTradeLockedItemsProperty = new Property<>(gameRules.isPreventTradeLockedItems());
        grandExchangeBuyPolicyModeProperty = new Property<>(toGrandExchangeBuyPolicyMode(gameRules));
        grandExchangeBuyPolicyModeOptionsProperty = new Property<>(
            Arrays.asList(GrandExchangeBuyPolicyMode.values())
        );
        grandExchangeBuyPolicyModeLabelMapProperty = new Property<>(createGrandExchangeModeLabels());
        preventPlayedOwnedHouseProperty = new Property<>(gameRules.isPreventPlayerOwnedHouse());
        restrictPlayerVersusPlayerLootProperty = new Property<>(gameRules.isRestrictPlayerVersusPlayerLoot());
        restrictFaladorPartyRoomBalloonsProperty = new Property<>(gameRules.isRestrictFaladorPartyRoomBalloons());
        shareAchievementNotificationsProperty = new Property<>(gameRules.isShareAchievementNotifications());
        valuableLootNotificationThresholdProperty = new Property<>(gameRules.getValuableLootNotificationThreshold());
        partyPasswordProperty = new Property<>(gameRules.getPartyPassword());

        isViewOnlyModeProperty = new Property<>(initialProps.isViewOnlyMode());
        isLocalModeProperty = new Property<>(initialProps.isLocalMode());
//        isValid = Property.deriveMany(
//                Arrays.asList(
//                        preventTradeOutsideGroup,
//                        preventTradeLockedItems,
//                        preventGrandExchangeBuyOffers,
//                        shareAchievementNotifications,
//                        partyPassword
//                ),
//                (list) -> {
//                    Boolean preventTradeOutsideGroupValue = (Boolean) list.get(0);
//                    Boolean preventTradeLockedItemsValue = (Boolean) list.get(1);
//                    Boolean preventGrandExchangeBuyOffersValue = (Boolean) list.get(2);
//                    Boolean shareAchievementNotificationsValue = (Boolean) list.get(3);
//                    String partyPasswordValue = (String) list.get(4);
//
//                    return partyPasswordValue == null || partyPasswordValue.length() <= 20;
//                }
//        );
//        isValid = partyPassword.derive((partyPasswordValue) -> partyPasswordValue == null || partyPasswordValue.length() <= 20);

        addListener(onlyForTradeableItemsProperty, updateListener);
        addListener(restrictGroundItemsProperty, updateListener);
        addListener(preventTradeOutsideGroupProperty, updateListener);
        addListener(preventTradeLockedItemsProperty, updateListener);
        addListener(grandExchangeBuyPolicyModeProperty, updateListener);
        addListener(preventPlayedOwnedHouseProperty, updateListener);
        addListener(restrictPlayerVersusPlayerLootProperty, updateListener);
        addListener(restrictFaladorPartyRoomBalloonsProperty, updateListener);
        addListener(shareAchievementNotificationsProperty, updateListener);
        addListener(valuableLootNotificationThresholdProperty, updateListener);
        if (setGameRules) {
            initialProps.onGameRulesChanged.accept(gameRules);
        }
    }


    public void setProps(Props props) {
        this.props = props;

        GameRules gameRules = props.getGameRules();
        if (gameRules == null) {
            ISOOffsetDateTime now = new ISOOffsetDateTime(OffsetDateTime.now());
            gameRules = GameRules.createWithDefaults(props.getAccountHash(), now);
        }

        onlyForTradeableItemsProperty.set(gameRules.isOnlyForTradeableItems());
        restrictGroundItemsProperty.set(gameRules.isRestrictGroundItems());
        preventTradeOutsideGroupProperty.set(gameRules.isPreventTradeOutsideGroup());
        preventTradeLockedItemsProperty.set(gameRules.isPreventTradeLockedItems());
        grandExchangeBuyPolicyModeProperty.set(toGrandExchangeBuyPolicyMode(gameRules));
        preventPlayedOwnedHouseProperty.set(gameRules.isPreventPlayerOwnedHouse());
        restrictPlayerVersusPlayerLootProperty.set(gameRules.isRestrictPlayerVersusPlayerLoot());
        restrictFaladorPartyRoomBalloonsProperty.set(gameRules.isRestrictFaladorPartyRoomBalloons());
        shareAchievementNotificationsProperty.set(gameRules.isShareAchievementNotifications());
        partyPasswordProperty.set(gameRules.getPartyPassword());
        valuableLootNotificationThresholdProperty.set(gameRules.getValuableLootNotificationThreshold());

        isViewOnlyModeProperty.set(props.isViewOnlyMode());
        isLocalModeProperty.set(props.isLocalMode());
    }

    private boolean isValid() {
        Integer valuableLootNotificationThreshold = valuableLootNotificationThresholdProperty.get();
        if (valuableLootNotificationThreshold != null && valuableLootNotificationThreshold < 0) {
            return false;
        }
        return true;
    }

    private void tryUpdateGameRules() {
        if (!isValid()) {
            props.onGameRulesChanged.accept(null);
            return;
        }

        GameRules currentGameRules = props.getGameRules();
        String partyPassword = currentGameRules == null ? null : currentGameRules.getPartyPassword();
        GrandExchangeBuyPolicyMode geMode = grandExchangeBuyPolicyModeProperty.get();

        GameRules newGameRules = GameRules.builder()
            .lastUpdatedByAccountHash(props.getAccountHash())
            .lastUpdatedAt(new ISOOffsetDateTime(OffsetDateTime.now()))
            .onlyForTradeableItems(onlyForTradeableItemsProperty.get())
            .restrictGroundItems(restrictGroundItemsProperty.get())
            .preventTradeOutsideGroup(preventTradeOutsideGroupProperty.get())
            .preventTradeLockedItems(preventTradeLockedItemsProperty.get())
            .preventGrandExchangeBuyOffers(geMode.preventLocked())
            .preventGrandExchangeGearBuyOffers(geMode.preventGear())
            .preventPlayerOwnedHouse(preventPlayedOwnedHouseProperty.get())
            .restrictPlayerVersusPlayerLoot(restrictPlayerVersusPlayerLootProperty.get())
            .restrictFaladorPartyRoomBalloons(restrictFaladorPartyRoomBalloonsProperty.get())
            .shareAchievementNotifications(shareAchievementNotificationsProperty.get())
            .valuableLootNotificationThreshold(valuableLootNotificationThresholdProperty.get())
            .partyPassword(partyPassword)
            .build();
        props.onGameRulesChanged.accept(newGameRules);
    }

    private static GrandExchangeBuyPolicyMode toGrandExchangeBuyPolicyMode(GameRules rules) {
        boolean preventLocked = rules.isPreventGrandExchangeBuyOffers();
        boolean preventGear = rules.isPreventGrandExchangeGearBuyOffers();
        if (preventGear) {
            return GrandExchangeBuyPolicyMode.ALLOW_SUPPLIES_BEFORE_UNLOCK;
        }
        if (preventLocked) {
            return GrandExchangeBuyPolicyMode.UNLOCKED_ITEMS_ONLY;
        }
        return GrandExchangeBuyPolicyMode.OFF;
    }

    private static Map<GrandExchangeBuyPolicyMode, String> createGrandExchangeModeLabels() {
        Map<GrandExchangeBuyPolicyMode, String> labels = new LinkedHashMap<>();
        labels.put(GrandExchangeBuyPolicyMode.OFF, "Off (No GE buy restrictions)");
        labels.put(
            GrandExchangeBuyPolicyMode.UNLOCKED_ITEMS_ONLY,
            "Unlocked items only (All items require unlock)"
        );
        labels.put(
            GrandExchangeBuyPolicyMode.ALLOW_SUPPLIES_BEFORE_UNLOCK,
            "Allow supplies before unlock (Gear still requires unlock)"
        );
        return labels;
    }

    public enum GrandExchangeBuyPolicyMode {
        OFF(false, false),
        UNLOCKED_ITEMS_ONLY(true, false),
        ALLOW_SUPPLIES_BEFORE_UNLOCK(true, true);

        private final boolean preventLocked;
        private final boolean preventGear;

        GrandExchangeBuyPolicyMode(boolean preventLocked, boolean preventGear) {
            this.preventLocked = preventLocked;
            this.preventGear = preventGear;
        }

        public boolean preventLocked() {
            return preventLocked;
        }

        public boolean preventGear() {
            return preventGear;
        }
    }

    @ImplementedBy(FactoryImpl.class)
    public interface Factory {

        GameRulesEditorViewModel create(Props initialProps);
    }

    @Value
    public static class Props {

        long accountHash;
        GameRules gameRules;
        Consumer<GameRules> onGameRulesChanged;
        boolean isViewOnlyMode;
        boolean isLocalMode;
    }

    @Singleton
    private static final class FactoryImpl implements Factory {

        @Override
        public GameRulesEditorViewModel create(Props initialProps) {
            return new GameRulesEditorViewModel(initialProps);
        }
    }
}
