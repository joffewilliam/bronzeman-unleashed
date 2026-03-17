package com.elertan.panel.screens.main;

import com.elertan.AccountConfigurationService;
import com.elertan.FromScratchModeUtils;
import com.elertan.GameRulesService;
import com.elertan.MemberService;
import com.elertan.data.FromScratchUnlockedItemsDataProvider;
import com.elertan.data.FromScratchBankBaselineDataProvider;
import com.elertan.data.GameRulesDataProvider;
import com.elertan.data.UnlockedItemsDataProvider;
import com.elertan.data.MembersDataProvider;
import com.elertan.models.AccountConfiguration;
import com.elertan.models.GameRules;
import com.elertan.models.ISOOffsetDateTime;
import com.elertan.models.Member;
import com.elertan.models.MemberRole;
import com.elertan.models.UnlockedItem;
import com.elertan.models.AccountConfiguration.StorageMode;
import com.elertan.panel.components.GameRulesEditorViewModel;
import com.elertan.ui.Property;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.JOptionPane;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

@Slf4j
public class ConfigScreenViewModel implements AutoCloseable {

    public final Property<GameRulesEditorViewModel.Props> gameRulesEditorViewModelPropsProperty;
    public final Property<Boolean> isSubmittingProperty = new Property<>(false);
    public final Property<String> errorMessageProperty = new Property<>(null);

    private final Client client;
    private final AccountConfigurationService accountConfigurationService;
    private final MemberService memberService;
    private final GameRulesService gameRulesService;
    private final GameRulesDataProvider gameRulesDataProvider;
    private final UnlockedItemsDataProvider unlockedItemsDataProvider;
    private final FromScratchUnlockedItemsDataProvider fromScratchUnlockedItemsDataProvider;
    private final FromScratchBankBaselineDataProvider fromScratchBankBaselineDataProvider;
    private final MembersDataProvider membersDataProvider;
    private final Runnable navigateToMainScreen;
    private final MembersDataProvider.MemberMapListener memberMapListener;

    private GameRules gameRules;
    private Supplier<GameRulesEditorViewModel.Props> propsSupplier;
    private volatile boolean closed;

    private ConfigScreenViewModel(Client client,
        AccountConfigurationService accountConfigurationService, GameRulesService gameRulesService,
        GameRulesDataProvider gameRulesDataProvider,
        UnlockedItemsDataProvider unlockedItemsDataProvider,
        FromScratchUnlockedItemsDataProvider fromScratchUnlockedItemsDataProvider,
        FromScratchBankBaselineDataProvider fromScratchBankBaselineDataProvider,
        MembersDataProvider membersDataProvider,
        MemberService memberService,
        Runnable navigateToMainScreen) {
        this.client = client;
        this.accountConfigurationService = accountConfigurationService;
        this.memberService = memberService;
        this.gameRulesService = gameRulesService;
        this.gameRulesDataProvider = gameRulesDataProvider;
        this.unlockedItemsDataProvider = unlockedItemsDataProvider;
        this.fromScratchUnlockedItemsDataProvider = fromScratchUnlockedItemsDataProvider;
        this.fromScratchBankBaselineDataProvider = fromScratchBankBaselineDataProvider;
        this.membersDataProvider = membersDataProvider;
        this.navigateToMainScreen = navigateToMainScreen;
        propsSupplier = () -> {
            GameRules gameRules = gameRulesService.getGameRules().get();
            Member member = null;
            try {
                member = memberService.getMyMember();
            } catch (Exception ignored) {
            }
            boolean isViewOnlyMode = member == null || member.getRole() != MemberRole.Owner;
            boolean isLocalMode = false;
            AccountConfiguration accountConfiguration =
                accountConfigurationService.currentAccountConfiguration().get();
            if (accountConfiguration != null) {
                isLocalMode = accountConfiguration.getStorageMode() == StorageMode.LOCAL;
            }

            return new GameRulesEditorViewModel.Props(
                client.getAccountHash(),
                gameRules,
                (newGameRules) -> setGameRules(newGameRules),
                isViewOnlyMode,
                isLocalMode
            );
        };

        gameRulesEditorViewModelPropsProperty = new Property<>(propsSupplier.get());
        memberMapListener = new MembersDataProvider.MemberMapListener() {
            @Override
            public void onUpdate(Member newMember, Member oldMember) {
                refreshGameRulesEditorProps();
            }

            @Override
            public void onDelete(Member member) {
                refreshGameRulesEditorProps();
            }
        };
        membersDataProvider.addMemberMapListener(memberMapListener);
        membersDataProvider.await(null)
            .whenComplete((__, throwable) -> {
                if (throwable != null) {
                    log.error("error waiting for members data to be ready", throwable);
                    return;
                }
                refreshGameRulesEditorProps();
            });

        gameRulesService.waitUntilGameRulesReady(null)
            .whenComplete((__, throwable) -> {
                if (throwable != null) {
                    log.error("error waiting for game rules to be ready", throwable);
                    return;
                }
                setGameRules(gameRulesService.getGameRules().get());
                refreshGameRulesEditorProps();
            });
    }

    @Override
    public void close() {
        closed = true;
        membersDataProvider.removeMemberMapListener(memberMapListener);
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
        Supplier<CompletableFuture<Void>> rollbackPreSaveChanges = CompletableFuture::completedFuture;

        boolean transitionToEnabled = FromScratchModeUtils.isTransitionToEnabled(
            currentGameRules,
            targetGameRules
        );
        boolean transitionToDisabled = FromScratchModeUtils.isTransitionToDisabled(
            currentGameRules,
            targetGameRules
        );

        if (transitionToEnabled) {
            EnableFromScratchChoice enableChoice = promptEnableFromScratchChoice();
            if (enableChoice == EnableFromScratchChoice.Cancel) {
                return;
            }

            if (enableChoice == EnableFromScratchChoice.ResumePreviousRun) {
                ISOOffsetDateTime resumeStartedAt = findLatestFromScratchStartedAtForCurrentAccount();
                if (resumeStartedAt == null) {
                    errorMessageProperty.set(
                        "Could not find a previous From Scratch run to resume. Please choose Start New Run."
                    );
                    return;
                }

                targetGameRules = targetGameRules.toBuilder()
                    .fromScratch(true)
                    .fromScratchStartedAt(resumeStartedAt)
                    .build();
            } else {
                // Start New Run: clear From Scratch unlock list and create a new run timestamp.
                targetGameRules = targetGameRules.toBuilder()
                    .fromScratch(true)
                    .fromScratchStartedAt(new ISOOffsetDateTime(OffsetDateTime.now()))
                    .build();

                Map<Integer, UnlockedItem> previousFromScratchUnlocks = snapshotUnlockedItemsMap(
                    fromScratchUnlockedItemsDataProvider.getUnlockedItemsMap()
                );
                preSaveFuture = fromScratchUnlockedItemsDataProvider.clearAll();
                rollbackPreSaveChanges = () -> restoreFromScratchUnlockList(previousFromScratchUnlocks);
            }
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
                MergeFromScratchResult mergeResult = mergeFromScratchUnlocksIntoMainList();
                preSaveFuture = mergeResult.applyFuture;
                rollbackPreSaveChanges = () -> removeMergedItemsFromMainUnlockList(mergeResult.addedItemIds);
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
        final Supplier<CompletableFuture<Void>> rollbackAction = rollbackPreSaveChanges;
        final AtomicBoolean preSaveApplied = new AtomicBoolean(false);
        isSubmittingProperty.set(true);

        preSaveFuture
            .thenRun(() -> preSaveApplied.set(true))
            .thenCompose(__ -> gameRulesDataProvider.updateGameRules(gameRulesToPersist))
            .whenComplete((__, throwable) -> {
                if (throwable == null) {
                    errorMessageProperty.set(null);
                    setGameRules(gameRulesToPersist);
                    navigateToMainScreen.run();
                    isSubmittingProperty.set(false);
                    return;
                }

                log.error("An error occurred while trying to save the game rules.", throwable);

                if (!preSaveApplied.get()) {
                    errorMessageProperty.set("An error occurred while trying to save the game rules.");
                    isSubmittingProperty.set(false);
                    return;
                }

                rollbackAction.get().whenComplete((___, rollbackThrowable) -> {
                    if (rollbackThrowable != null) {
                        log.error("Failed to rollback pre-save game-rules transition changes.", rollbackThrowable);
                        errorMessageProperty.set(
                            "An error occurred while saving game rules, and rollback also failed. Please retry."
                        );
                    } else {
                        errorMessageProperty.set(
                            "An error occurred while saving game rules. Previous unlock-list data was restored."
                        );
                    }
                    isSubmittingProperty.set(false);
                });
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

    private EnableFromScratchChoice promptEnableFromScratchChoice() {
        ISOOffsetDateTime latestRunStartedAt = findLatestFromScratchStartedAtForCurrentAccount();
        if (latestRunStartedAt == null) {
            int enableResult = JOptionPane.showConfirmDialog(
                null,
                "Enable From Scratch for the entire group?\n"
                    + "This starts a new run, clears the From Scratch unlock list, and applies to all members.",
                "Confirm Start From Scratch",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            return enableResult == JOptionPane.OK_OPTION
                ? EnableFromScratchChoice.StartNewRun
                : EnableFromScratchChoice.Cancel;
        }

        Object[] options = new Object[] {
            "Cancel",
            "Resume previous From Scratch run",
            "Start new From Scratch run"
        };

        int result = JOptionPane.showOptionDialog(
            null,
            "Enable From Scratch for the group.\n\n"
                + "- Resume: keep your previous From Scratch unlock list and continue that run.\n"
                + "- Start new: clear the From Scratch unlock list and begin a fresh run.",
            "Enable From Scratch",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]
        );

        if (result == 1) {
            return EnableFromScratchChoice.ResumePreviousRun;
        }
        if (result == 2) {
            return EnableFromScratchChoice.StartNewRun;
        }
        return EnableFromScratchChoice.Cancel;
    }

    private ISOOffsetDateTime findLatestFromScratchStartedAtForCurrentAccount() {
        Map<String, com.elertan.models.FromScratchBankBaselineEntry> map =
            fromScratchBankBaselineDataProvider.getMap();
        if (map == null || map.isEmpty()) {
            return null;
        }

        long accountHash = client.getAccountHash();
        if (accountHash <= 0) {
            return null;
        }

        ISOOffsetDateTime latest = null;
        for (String key : map.keySet()) {
            BaselineSnapshotKeyParts keyParts = tryParseSnapshotMarkerKey(key);
            if (keyParts == null || keyParts.accountHash != accountHash) {
                continue;
            }

            if (latest == null || keyParts.startedAt.getValue().isAfter(latest.getValue())) {
                latest = keyParts.startedAt;
            }
        }

        return latest;
    }

    private BaselineSnapshotKeyParts tryParseSnapshotMarkerKey(String key) {
        if (key == null) {
            return null;
        }

        String[] parts = key.split("\\|", 3);
        if (parts.length != 3 || !"__snapshot__".equals(parts[2])) {
            return null;
        }

        try {
            long accountHash = Long.parseLong(parts[0]);
            ISOOffsetDateTime startedAt = new ISOOffsetDateTime(OffsetDateTime.parse(parts[1]));
            return new BaselineSnapshotKeyParts(accountHash, startedAt);
        } catch (NumberFormatException | DateTimeParseException ex) {
            return null;
        }
    }

    private static class BaselineSnapshotKeyParts {
        private final long accountHash;
        private final ISOOffsetDateTime startedAt;

        private BaselineSnapshotKeyParts(long accountHash, ISOOffsetDateTime startedAt) {
            this.accountHash = accountHash;
            this.startedAt = startedAt;
        }
    }

    private MergeFromScratchResult mergeFromScratchUnlocksIntoMainList() {
        Map<Integer, UnlockedItem> fromScratchMap = fromScratchUnlockedItemsDataProvider.getUnlockedItemsMap();
        Map<Integer, UnlockedItem> unlockedItemsMap = unlockedItemsDataProvider.getUnlockedItemsMap();

        if (fromScratchMap == null || unlockedItemsMap == null) {
            return new MergeFromScratchResult(
                CompletableFuture.failedFuture(
                    new IllegalStateException("Unlock lists are not ready for merging")
                ),
                new HashMap<>()
            );
        }

        Map<Integer, UnlockedItem> addedItems = new HashMap<>();
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (Map.Entry<Integer, UnlockedItem> entry : fromScratchMap.entrySet()) {
            int itemId = entry.getKey();
            if (unlockedItemsMap.containsKey(itemId)) {
                continue;
            }

            UnlockedItem unlockedItem = entry.getValue();
            addedItems.put(itemId, unlockedItem);
            future = future.thenCompose(__ -> unlockedItemsDataProvider.addUnlockedItem(unlockedItem));
        }
        return new MergeFromScratchResult(future, addedItems);
    }

    private CompletableFuture<Void> removeMergedItemsFromMainUnlockList(Map<Integer, UnlockedItem> addedItems) {
        if (addedItems == null || addedItems.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (Integer itemId : addedItems.keySet()) {
            future = future.thenCompose(__ -> unlockedItemsDataProvider.removeUnlockedItemById(itemId));
        }
        return future;
    }

    private Map<Integer, UnlockedItem> snapshotUnlockedItemsMap(Map<Integer, UnlockedItem> source) {
        if (source == null || source.isEmpty()) {
            return new HashMap<>();
        }
        return new HashMap<>(source);
    }

    private CompletableFuture<Void> restoreFromScratchUnlockList(Map<Integer, UnlockedItem> snapshot) {
        CompletableFuture<Void> future = fromScratchUnlockedItemsDataProvider.clearAll();
        if (snapshot == null || snapshot.isEmpty()) {
            return future;
        }

        for (UnlockedItem unlockedItem : snapshot.values()) {
            future = future.thenCompose(__ -> fromScratchUnlockedItemsDataProvider.addUnlockedItem(unlockedItem));
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
        Map<Integer, UnlockedItem> previousFromScratchUnlocks = snapshotUnlockedItemsMap(
            fromScratchUnlockedItemsDataProvider.getUnlockedItemsMap()
        );
        final AtomicBoolean preSaveApplied = new AtomicBoolean(false);
        isSubmittingProperty.set(true);

        fromScratchUnlockedItemsDataProvider.clearAll()
            .thenRun(() -> preSaveApplied.set(true))
            .thenCompose(__ -> gameRulesDataProvider.updateGameRules(gameRulesToPersist))
            .whenComplete((__, throwable) -> {
                if (throwable == null) {
                    errorMessageProperty.set(null);
                    setGameRules(gameRulesToPersist);
                    gameRulesEditorViewModelPropsProperty.set(propsSupplier.get());
                    navigateToMainScreen.run();
                    isSubmittingProperty.set(false);
                    return;
                }

                log.error("An error occurred while trying to start from scratch.", throwable);

                if (!preSaveApplied.get()) {
                    errorMessageProperty.set("An error occurred while trying to save the game rules.");
                    isSubmittingProperty.set(false);
                    return;
                }

                restoreFromScratchUnlockList(previousFromScratchUnlocks)
                    .whenComplete((___, rollbackThrowable) -> {
                        if (rollbackThrowable != null) {
                            log.error("Failed to rollback one-click Start From Scratch changes.", rollbackThrowable);
                            errorMessageProperty.set(
                                "An error occurred while saving game rules, and rollback also failed. Please retry."
                            );
                        } else {
                            errorMessageProperty.set(
                                "An error occurred while saving game rules. Previous unlock-list data was restored."
                            );
                        }
                        isSubmittingProperty.set(false);
                    });
            });
    }

    private void refreshGameRulesEditorProps() {
        if (closed) {
            return;
        }
        gameRulesEditorViewModelPropsProperty.set(propsSupplier.get());
    }

    public void leaveButtonClick() {
        boolean isPlayingAlone = memberService.isPlayingAlone();
        Member member = memberService.getMyMember();
        int result = JOptionPane.showConfirmDialog(
            null,
            buildLeaveConfirmationMessage(isPlayingAlone, member),
            "Leave Bronzeman for this account?",
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

    private String buildLeaveConfirmationMessage(boolean isPlayingAlone, Member member) {
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("This will turn off Bronzeman for this account.\n");

        if (!isPlayingAlone) {
            messageBuilder.append("You will also be removed from the group.\n");
            if (member != null && member.getRole() == MemberRole.Owner) {
                messageBuilder.append(
                    "Group ownership will be passed to the member who has been in the group the longest.\n"
                );
            }
        }

        StorageMode storageMode = null;
        AccountConfiguration accountConfiguration =
            accountConfigurationService.currentAccountConfiguration().get();
        if (accountConfiguration != null) {
            storageMode = accountConfiguration.getStorageMode();
        }

        if (storageMode == StorageMode.LOCAL) {
            messageBuilder.append("Your local progress will stay saved on this computer.\n");
        } else {
            messageBuilder.append("Your saved progress will not be deleted.\n");
        }

        messageBuilder.append("You can set Bronzeman up again later from this panel.");
        return messageBuilder.toString();
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
        private FromScratchBankBaselineDataProvider fromScratchBankBaselineDataProvider;
        @Inject
        private MembersDataProvider membersDataProvider;
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
                fromScratchBankBaselineDataProvider,
                membersDataProvider,
                memberService,
                navigateToMainScreen
            );
        }
    }

    private enum EnableFromScratchChoice {
        Cancel,
        StartNewRun,
        ResumePreviousRun
    }

    private static class MergeFromScratchResult {
        private final CompletableFuture<Void> applyFuture;
        private final Map<Integer, UnlockedItem> addedItemIds;

        private MergeFromScratchResult(
            CompletableFuture<Void> applyFuture,
            Map<Integer, UnlockedItem> addedItemIds
        ) {
            this.applyFuture = applyFuture;
            this.addedItemIds = addedItemIds;
        }
    }

    private enum StopFromScratchChoice {
        Cancel,
        StopKeepSeparate,
        StopAndMerge
    }
}
