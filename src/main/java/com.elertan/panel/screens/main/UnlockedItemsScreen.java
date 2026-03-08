package com.elertan.panel.screens.main;

import com.elertan.panel.screens.main.unlockedItems.ItemsScreen;
import com.elertan.panel.screens.main.unlockedItems.LoadingScreen;
import com.elertan.ui.Bindings;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.CardLayout;
import javax.swing.JPanel;

public class UnlockedItemsScreen extends JPanel implements AutoCloseable {

    private final UnlockedItemsScreenViewModel viewModel;
    private final LoadingScreen.Factory loadingScreenFactory;
    private final ItemsScreen.Factory itemsScreenFactory;
    private final Runnable navigateToConfiguration;
    private final Runnable rejoinLastParty;
    private final AutoCloseable cardLayoutBinding;

    private UnlockedItemsScreen(
        UnlockedItemsScreenViewModel viewModel,
        LoadingScreen.Factory loadingScreenFactory,
        ItemsScreen.Factory itemsScreenFactory,
        Runnable navigateToConfiguration,
        Runnable rejoinLastParty
    ) {
        this.viewModel = viewModel;
        this.loadingScreenFactory = loadingScreenFactory;
        this.itemsScreenFactory = itemsScreenFactory;
        this.navigateToConfiguration = navigateToConfiguration;
        this.rejoinLastParty = rejoinLastParty;

        CardLayout cardLayout = new CardLayout();
        setLayout(cardLayout);

        cardLayoutBinding = Bindings.bindCardLayout(
            this,
            cardLayout,
            viewModel.allUnlockedItems.derive(list -> list == null
                ? UnlockedItemsScreenViewModel.Screen.LOADING
                : UnlockedItemsScreenViewModel.Screen.ITEMS),
            this::buildScreen
        );
    }

    @Override
    public void close() throws Exception {
        cardLayoutBinding.close();
    }

    private JPanel buildScreen(UnlockedItemsScreenViewModel.Screen screen) {
        switch (screen) {
            case LOADING:
                return loadingScreenFactory.create();
            case ITEMS:
                return itemsScreenFactory.create(
                    viewModel.allUnlockedItems,
                    viewModel.searchText,
                    viewModel.sortedBy,
                    viewModel.unlockedByAccountHash,
                    navigateToConfiguration,
                    rejoinLastParty
                );
        }

        throw new IllegalStateException("Unknown view state: " + screen);
    }


    @ImplementedBy(FactoryImpl.class)
    public interface Factory {

        UnlockedItemsScreen create(UnlockedItemsScreenViewModel viewModel,
            Runnable navigateToConfiguration,
            Runnable rejoinLastParty);
    }

    @Singleton
    private static final class FactoryImpl implements Factory {

        @Inject
        private LoadingScreen.Factory loadingScreenFactory;
        @Inject
        private ItemsScreen.Factory itemsScreenFactory;

        @Override
        public UnlockedItemsScreen create(UnlockedItemsScreenViewModel viewModel,
            Runnable navigateToConfiguration,
            Runnable rejoinLastParty) {
            return new UnlockedItemsScreen(
                viewModel,
                loadingScreenFactory,
                itemsScreenFactory,
                navigateToConfiguration,
                rejoinLastParty
            );
        }
    }

}
