package com.elertan.panel.screens.main.unlockedItems;

import com.elertan.models.UnlockedItem;
import com.elertan.panel.screens.main.UnlockedItemsScreenViewModel;
import com.elertan.panel.screens.main.unlockedItems.items.HeaderView;
import com.elertan.panel.screens.main.unlockedItems.items.HeaderViewViewModel;
import com.elertan.panel.screens.main.unlockedItems.items.MainView;
import com.elertan.panel.screens.main.unlockedItems.items.MainViewViewModel;
import com.elertan.ui.Property;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.BorderLayout;
import java.util.List;
import javax.swing.JPanel;

public class ItemsScreen extends JPanel {

    private ItemsScreen(
        HeaderViewViewModel headerViewViewModel,
        HeaderView.Factory headerViewFactory,
        MainViewViewModel mainViewViewModel,
        MainView.Factory mainViewFactory,
        Property<UnlockedItemsScreenViewModel.SortedBy> sortedBy
    ) {
        setLayout(new BorderLayout());

        HeaderView headerView = headerViewFactory.create(headerViewViewModel);
        add(headerView, BorderLayout.NORTH);

        MainView mainView = mainViewFactory.create(mainViewViewModel);
        add(mainView, BorderLayout.CENTER);
    }

    @ImplementedBy(FactoryImpl.class)
    public interface Factory {

        ItemsScreen create(
            Property<List<UnlockedItem>> allUnlockedItems,
            Property<String> searchText,
            Property<UnlockedItemsScreenViewModel.SortedBy> sortedBy,
            Property<Long> unlockedByAccountHash,
            Runnable navigateToConfiguration,
            Runnable rejoinLastParty
        );
    }

    @Singleton
    private static final class FactoryImpl implements Factory {

        @Inject
        private HeaderViewViewModel.Factory headerViewViewModelFactory;
        @Inject
        private HeaderView.Factory headerViewFactory;
        @Inject
        private MainViewViewModel.Factory mainViewViewModelFactory;
        @Inject
        private MainView.Factory mainViewFactory;
        
        @Override
        public ItemsScreen create(
            Property<List<UnlockedItem>> allUnlockedItems,
            Property<String> searchText,
            Property<UnlockedItemsScreenViewModel.SortedBy> sortedBy,
            Property<Long> unlockedByAccountHash,
            Runnable navigateToConfiguration,
            Runnable rejoinLastParty
        ) {
            HeaderViewViewModel headerViewViewModel = headerViewViewModelFactory.create(
                allUnlockedItems,
                searchText,
                sortedBy,
                unlockedByAccountHash,
                navigateToConfiguration,
                rejoinLastParty
            );
            MainViewViewModel mainViewViewModel = mainViewViewModelFactory.create(
                allUnlockedItems,
                searchText,
                sortedBy,
                unlockedByAccountHash
            );
            return new ItemsScreen(
                headerViewViewModel,
                headerViewFactory,
                mainViewViewModel,
                mainViewFactory,
                sortedBy
            );
        }
    }
}
