package com.elertan.panel.screens.main;

import com.elertan.AccountConfigurationService;
import com.elertan.FromScratchModeUtils;
import com.elertan.GameRulesService;
import com.elertan.MemberService;
import com.elertan.data.FromScratchUnlockedItemsDataProvider;
import com.elertan.data.GameRulesDataProvider;
import com.elertan.data.UnlockedItemsDataProvider;
import com.elertan.models.GameRules;
import com.elertan.models.ISOOffsetDateTime;
import com.elertan.models.Member;
import com.elertan.models.MemberRole;
import com.elertan.models.UnlockedItem;
import com.elertan.panel.components.GameRulesEditorViewModel;
import com.elertan.ui.Property;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.swing.JOptionPane;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

@Slf4j
public class ConfigScreenViewModel {

    public final Property<GameRulesEditorViewModel.Props> gameRulesEditorViewModelPropsProperty;
    public final Property<Boolean> isSubmittingProperty = new Property<>(false);
    public final Property<String> errorMessageProperty = new Property<>(null);

    private final AccountConfigurationService accountConfigurationService;
    private final MemberService memberService;
    private final GameRulesService gameRulesService;
    private final GameRulesDataProvider gameRulesDataProvider;
    private final UnlockedItemsDataProvider unlockedItemsDataProvider;
    private final FromScratchUnlockedItemsDataProvider fromScratchUnlockedItemsDataProvider;
    private final Runnable navigateToMainScreen;

    private GameRules gameRules;
    private Supplier<GameRulesEditorViewModel.Props> propsSupplier;

    private ConfigScreenViewModel(Client client,
        AccountConfigurationService accountConfigurationService, GameRulesService gameRulesService,
        GameRulesDataProvider gameRulesDataProvider,
        UnlockedItemsDataProvider unlockedItemsDataProvider,
        FromScratchUnlockedItemsDataProvider fromScratchUnlockedItemsDataProvider,
        MemberService memberService,
        Runnable navigateToMainScreen) {
        this.accountConfigurationService = accountConfigurationService;
        this.memberService = memberService;
        this.gameRulesService = gameRulesService;
        this.gameRulesDataProvider = gameRulesDataProvider;
        this.unlockedItemsDataProvider = unlockedItemsDataProvider;
        this.fromScratchUnlockedItemsDataProvider = fromScratchUnlockedItemsDataProvider;
        this.navigateToMainScreen = navigateToMainScreen;
        propsSupplier = () -> {
            GameRules gameRules = gameRulesService.getGameRules().get();
            Member member = null;
            try {
                member = memberService.getMyMember();
            } catch (Exception ignored) {
            }
            boolean isViewOnlyMode = member == null || member.getRole() != MemberRole.Owner;

            return new GameRulesEditorViewModel.Props(
                client.getAccountHash(),
                gameRules,
                (newGameRules) -> setGameRules(newGameRules),
                isViewOnlyMode
            );
        };

        gameRulesEditorViewModelPropsProperty = new Property<>(propsSupplier.get());
        gameRulesService.waitUntilGameRulesReady(null)
            .whenComplete((__, throwable) -> {
                if (throwable != null) {
                    log.error("error waiting for game rules to be ready", throwable);
                    return;
                }
                setGameRules(gameRulesService.getGameRules().get());
                gameRulesEditorViewModelPropsProperty.set(propsSupplier.get());
            });
    }

    public void onBackButtonClick() {
        // Reset game rules to the last saved game rules.
        gameRulesEditorViewModelPropsProperty.set(propsSupplier.get());

        navigateToMainScreen.run();
    }

    public void updateGameRulesClick() {
        GameRules currentGameRules = gameRulesService.getGameRules().get();
        if (currentGameRules == null || gameRules == null) {
            errorMessageProperty.set("Game rules are not ready yet.");
            return;
        }

        GameRules targetGameRules = gameRules;
        CompletableFuture<Void> preSaveFuture = CompletableFuture.completedFuture(null);

        boolean transitionToEnabled = FromScratchModeUtils.isTransitionToEnabled(
            currentGameRules,
            targetGameRules
        );
        boolean transitionToDisabled = FromScratchModeUtils.isTransitionToDisabled(
            currentGameRules,
            targetGameRules
        );

        if (transitionToEnabled) {
            // Only when turning From Scratch ON do we clear the list (new run).
            int enableResult = JOptionPane.showConfirmDialog(
                null,
                "Enable From Scratch for the entire group?\n"
                    + "This starts a new run, clears the From Scratch unlock list, and applies to all members.",
                "Confirm Start From Scratch",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            if (enableResult != JOptionPane.OK_OPTION) {
                return;
            }

            targetGameRules = targetGameRules.toBuilder()
                .fromScratch(true)
                .fromScratchStartedAt(new ISOOffsetDateTime(OffsetDateTime.now()))
                .build();

            preSaveFuture = fromScratchUnlockedItemsDataProvider.clearAll();
        } else if (transitionToDisabled) {
            StopFromScratchChoice stopChoice = promptStopFromScratchChoice();
            if (stopChoice == StopFromScratchChoice.Cancel) {
                return;
            }

            targetGameRules = targetGameRules.toBuilder()
                .fromScratch(false)
                .fromScratchStartedAt(null)
                .build();

            if (stopChoice == StopFromScratchChoice.StopAndMerge) {
                preSaveFuture = mergeFromScratchUnlocksIntoMainList();
            }
            // StopKeepSeparate: do NOT clear From Scratch list in Firebase; leave it preserved
            // so the user can turn From Scratch back on later and still see their list.
        } else {
            int result = JOptionPane.showConfirmDialog(
                null,
                "Are you sure you want to update the game rules?",
                "Confirm update game rules",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            if (result != JOptionPane.OK_OPTION) {
                return;
            }

            if (targetGameRules.isFromScratch()) {
                ISOOffsetDateTime startedAt = targetGameRules.getFromScratchStartedAt();
                if (startedAt == null) {
                    targetGameRules = targetGameRules.toBuilder()
                        .fromScratchStartedAt(currentGameRules.getFromScratchStartedAt())
                        .build();
                }
            }
        }

        final GameRules gameRulesToPersist = targetGameRules;
        isSubmittingProperty.set(true);

        preSaveFuture.thenCompose(__ -> gameRulesDataProvider.updateGameRules(gameRulesToPersist))
            .whenComplete((__, throwable) -> {
                try {
                    if (throwable != null) {
                        log.error(
                            "An error occurred while trying to save the game rules.",
                            throwable
                        );
                        errorMessageProperty.set(
                            "An error occurred while trying to save the game rules.");
                        return;
                    }

                    errorMessageProperty.set(null);
                    setGameRules(gameRulesToPersist);
                    navigateToMainScreen.run();
                } finally {
                    isSubmittingProperty.set(false);
                }
            });
    }

    private void setGameRules(GameRules gameRules) {
        this.gameRules = gameRules;
        log.debug("config screen set game rules: {}", gameRules);
    }

    private StopFromScratchChoice promptStopFromScratchChoice() {
        Object[] options = new Object[] {
            "Cancel",
            "Stop (keep list – do not delete)",
            "Stop + Merge into main list"
        };

        int result = JOptionPane.showOptionDialog(
            null,
            "Stop From Scratch for the group.\n\n"
                + "• \"Keep list\" = Your From Scratch unlock list stays in the group (nothing is deleted).\n"
                + "• \"Merge\" = Copy those items into the main unlock list, then you can use them in normal mode.",
            "Stop From Scratch",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]
        );

        if (result == 1) {
            return StopFromScratchChoice.StopKeepSeparate;
        }
        if (result == 2) {
            return StopFromScratchChoice.StopAndMerge;
        }
        return StopFromScratchChoice.Cancel;
    }

    private CompletableFuture<Void> mergeFromScratchUnlocksIntoMainList() {
        Map<Integer, UnlockedItem> fromScratchMap = fromScratchUnlockedItemsDataProvider.getUnlockedItemsMap();
        Map<Integer, UnlockedItem> unlockedItemsMap = unlockedItemsDataProvider.getUnlockedItemsMap();

        if (fromScratchMap == null || unlockedItemsMap == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Unlock lists are not ready for merging")
            );
        }

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (Map.Entry<Integer, UnlockedItem> entry : fromScratchMap.entrySet()) {
            int itemId = entry.getKey();
            if (unlockedItemsMap.containsKey(itemId)) {
                continue;
            }

            UnlockedItem unlockedItem = entry.getValue();
            future = future.thenCompose(__ -> unlockedItemsDataProvider.addUnlockedItem(unlockedItem));
        }
        return future;
    }

    /**
     * One-click start from scratch: enables From Scratch for the group, clears the unlock list,
     * and saves. Use this when the Game Rules "From Scratch" option is not visible or you want a
     * single action instead of toggling the checkbox and updating.
     */
    public void startFromScratchClick() {
        GameRules currentGameRules = gameRulesService.getGameRules().get();
        if (currentGameRules == null) {
            errorMessageProperty.set("Game rules are not ready yet.");
            return;
        }
        if (currentGameRules.isFromScratch()) {
            errorMessageProperty.set("From Scratch is already enabled for this group.");
            return;
        }

        int enableResult = JOptionPane.showConfirmDialog(
            null,
            "Enable From Scratch for the entire group?\n"
                + "This starts a new run, clears the From Scratch unlock list, and applies to all members.",
            "Confirm Start From Scratch",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (enableResult != JOptionPane.OK_OPTION) {
            return;
        }

        GameRules targetGameRules = currentGameRules.toBuilder()
            .fromScratch(true)
            .fromScratchStartedAt(new ISOOffsetDateTime(OffsetDateTime.now()))
            .build();

        final GameRules gameRulesToPersist = targetGameRules;
        isSubmittingProperty.set(true);

        fromScratchUnlockedItemsDataProvider.clearAll()
            .thenCompose(__ -> gameRulesDataProvider.updateGameRules(gameRulesToPersist))
            .whenComplete((__, throwable) -> {
                try {
                    if (throwable != null) {
                        log.error(
                            "An error occurred while trying to start from scratch.",
                            throwable
                        );
                        errorMessageProperty.set(
                            "An error occurred while trying to save the game rules.");
                        return;
                    }
                    errorMessageProperty.set(null);
                    setGameRules(gameRulesToPersist);
                    gameRulesEditorViewModelPropsProperty.set(propsSupplier.get());
                    navigateToMainScreen.run();
                } finally {
                    isSubmittingProperty.set(false);
                }
            });
    }

    public void leaveButtonClick() {
        boolean isPlayingAlone = memberService.isPlayingAlone();
        Member member = memberService.getMyMember();

        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("Are you sure you want to leave?\n");
        messageBuilder.append(
            "You will no longer be able to access the unlocked items panel and Bronzeman mode will be deactivated for your account.\n\n");
        if (!isPlayingAlone) {
            messageBuilder.append("You will also be removed from the group");
            if (member.getRole() == MemberRole.Owner) {
                messageBuilder.append(
                    ", and will pass on the ownership of the group to the member who has been in the group the longest");
            }
            messageBuilder.append(".\n\n");
        }
        messageBuilder.append(
            "This will NOT delete the data associated with your progress, you can simply re-open the panel and get going through the setup again.");

        // TODO: More stuff
        int result = JOptionPane.showConfirmDialog(
            null,
            messageBuilder.toString(),
            "Confirm Leave Bronzeman",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        isSubmittingProperty.set(true);

        CompletableFuture<Void> future = new CompletableFuture<>();

        if (!isPlayingAlone) {
            memberService.leaveGroupAndPromoteOldestMember().whenComplete((__, throwable) -> {
                if (throwable != null) {
                    future.completeExceptionally(throwable);
                    return;
                }
                future.complete(null);
            });
        } else {
            future.complete(null);
        }

        future.whenComplete((__, throwable) -> {
            if (throwable != null) {
                log.error("error leaving group", throwable);
                errorMessageProperty.set("An error occurred while trying to leave the group.");
                return;
            }
            errorMessageProperty.set(null);

            navigateToMainScreen.run();
            accountConfigurationService.setCurrentAccountConfiguration(null);
            isSubmittingProperty.set(false);
        });

    }

    @ImplementedBy(FactoryImpl.class)
    public interface Factory {

        ConfigScreenViewModel create(Runnable navigateToMainScreen);
    }

    @Singleton
    private static final class FactoryImpl implements Factory {

        @Inject
        private Client client;
        @Inject
        private AccountConfigurationService accountConfigurationService;
        @Inject
        private GameRulesService gameRulesService;
        @Inject
        private GameRulesDataProvider gameRulesDataProvider;
        @Inject
        private UnlockedItemsDataProvider unlockedItemsDataProvider;
        @Inject
        private FromScratchUnlockedItemsDataProvider fromScratchUnlockedItemsDataProvider;
        @Inject
        private MemberService memberService;

        @Override
        public ConfigScreenViewModel create(Runnable navigateToMainScreen) {
            return new ConfigScreenViewModel(
                client,
                accountConfigurationService,
                gameRulesService,
                gameRulesDataProvider,
                unlockedItemsDataProvider,
                fromScratchUnlockedItemsDataProvider,
                memberService,
                navigateToMainScreen
            );
        }
    }

    private enum StopFromScratchChoice {
        Cancel,
        StopKeepSeparate,
        StopAndMerge
    }
}
