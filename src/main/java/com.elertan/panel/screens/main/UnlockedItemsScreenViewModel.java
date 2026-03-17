package com.elertan.panel.screens.main;

import com.elertan.GameRulesService;
import com.elertan.data.FromScratchUnlockedItemsDataProvider;
import com.elertan.data.UnlockedItemsDataProvider;
import com.elertan.models.GameRules;
import com.elertan.models.UnlockedItem;
import com.elertan.panel.BaseViewModel;
import com.elertan.ui.Property;
import com.elertan.utils.Subscription;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UnlockedItemsScreenViewModel extends BaseViewModel {

    public final Property<List<UnlockedItem>> allUnlockedItems;
    public final Property<String> searchText = new Property<>("");
    public final Property<SortedBy> sortedBy = new Property<>(SortedBy.UNLOCKED_AT_DESC);
    public final Property<Long> unlockedByAccountHash = new Property<>(null);
    private final UnlockedItemsDataProvider unlockedItemsDataProvider;
    private final FromScratchUnlockedItemsDataProvider fromScratchUnlockedItemsDataProvider;
    private final GameRulesService gameRulesService;
    private final UnlockedItemsDataProvider.UnlockedItemsMapListener normalMapListener;
    private final FromScratchUnlockedItemsDataProvider.UnlockedItemsMapListener fromScratchMapListener;
    private Subscription gameRulesSubscription;
    private Subscription unlockedItemsStateSubscription;
    private Subscription fromScratchUnlockedItemsStateSubscription;
    private final PropertyChangeListener sortedByListener = this::sortedByListener;

    private UnlockedItemsScreenViewModel(
        UnlockedItemsDataProvider unlockedItemsDataProvider,
        FromScratchUnlockedItemsDataProvider fromScratchUnlockedItemsDataProvider,
        GameRulesService gameRulesService
    ) {
        this.unlockedItemsDataProvider = unlockedItemsDataProvider;
        this.fromScratchUnlockedItemsDataProvider = fromScratchUnlockedItemsDataProvider;
        this.gameRulesService = gameRulesService;

        allUnlockedItems = new Property<>(getListFromActiveProvider());

        normalMapListener = new UnlockedItemsDataProvider.UnlockedItemsMapListener() {
            @Override
            public void onUpdate(UnlockedItem unlockedItem) {
                refreshAllUnlockedItems();
            }

            @Override
            public void onDelete(UnlockedItem unlockedItem) {
                refreshAllUnlockedItems();
            }
        };
        fromScratchMapListener = new FromScratchUnlockedItemsDataProvider.UnlockedItemsMapListener() {
            @Override
            public void onUpdate(UnlockedItem unlockedItem) {
                refreshAllUnlockedItems();
            }

            @Override
            public void onDelete(UnlockedItem unlockedItem) {
                refreshAllUnlockedItems();
            }
        };
        unlockedItemsDataProvider.addUnlockedItemsMapListener(normalMapListener);
        fromScratchUnlockedItemsDataProvider.addUnlockedItemsMapListener(fromScratchMapListener);

        gameRulesSubscription = gameRulesService.getGameRules().subscribe((newRules, oldRules) -> refreshAllUnlockedItems());

        unlockedItemsStateSubscription = unlockedItemsDataProvider.getState().subscribeImmediate(
            (newState, oldState) -> refreshAllUnlockedItems()
        );
        fromScratchUnlockedItemsStateSubscription = fromScratchUnlockedItemsDataProvider.getState().subscribeImmediate(
            (newState, oldState) -> refreshAllUnlockedItems()
        );

        Duration awaitTimeout = Duration.ofSeconds(30);
        unlockedItemsDataProvider.await(awaitTimeout).whenComplete((__, t1) -> refreshAllUnlockedItems());
        fromScratchUnlockedItemsDataProvider.await(awaitTimeout).whenComplete((__, t2) -> refreshAllUnlockedItems());

        addListener(sortedBy, sortedByListener);
    }

    private List<UnlockedItem> getListFromActiveProvider() {
        GameRules gameRules = gameRulesService.getGameRules().get();
        boolean fromScratch = gameRules != null && gameRules.isFromScratch();
        Map<Integer, UnlockedItem> map = fromScratch
            ? fromScratchUnlockedItemsDataProvider.getUnlockedItemsMap()
            : unlockedItemsDataProvider.getUnlockedItemsMap();
        if (map == null) {
            return null;
        }
        return new ArrayList<>(map.values());
    }

    private void refreshAllUnlockedItems() {
        allUnlockedItems.set(getListFromActiveProvider());
    }

    @Override
    public void close() throws Exception {
        super.close();
        unlockedItemsDataProvider.removeUnlockedItemsMapListener(normalMapListener);
        fromScratchUnlockedItemsDataProvider.removeUnlockedItemsMapListener(fromScratchMapListener);
        if (gameRulesSubscription != null) {
            gameRulesSubscription.dispose();
            gameRulesSubscription = null;
        }
        if (unlockedItemsStateSubscription != null) {
            unlockedItemsStateSubscription.dispose();
            unlockedItemsStateSubscription = null;
        }
        if (fromScratchUnlockedItemsStateSubscription != null) {
            fromScratchUnlockedItemsStateSubscription.dispose();
            fromScratchUnlockedItemsStateSubscription = null;
        }
    }

    private void sortedByListener(PropertyChangeEvent event) {
        SortedBy sortedByValue = (SortedBy) event.getNewValue();
        log.debug("sorted by changed to: {}", sortedByValue);
    }

    public enum Screen {
        LOADING,
        ITEMS
    }

    public enum SortedBy {
        UNLOCKED_AT_ASC,
        ALPHABETICAL_ASC,
        PLAYER_ASC,
        UNLOCKED_AT_DESC,
        ALPHABETICAL_DESC,
        PLAYER_DESC,
    }

    @ImplementedBy(FactoryImpl.class)
    public interface Factory {

        UnlockedItemsScreenViewModel create();
    }

    @Singleton
    private static final class FactoryImpl implements Factory {

        @Inject
        private UnlockedItemsDataProvider unlockedItemsDataProvider;
        @Inject
        private FromScratchUnlockedItemsDataProvider fromScratchUnlockedItemsDataProvider;
        @Inject
        private GameRulesService gameRulesService;

        @Override
        public UnlockedItemsScreenViewModel create() {
            return new UnlockedItemsScreenViewModel(
                unlockedItemsDataProvider,
                fromScratchUnlockedItemsDataProvider,
                gameRulesService
            );
        }
    }
}
