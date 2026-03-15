package com.elertan.panel.screens;

import com.elertan.AccountConfigurationService;
import com.elertan.BUPanelService;
import com.elertan.models.AccountConfiguration;
import com.elertan.models.GameRules;
import com.elertan.models.ISOOffsetDateTime;
import com.elertan.remote.firebase.FirebaseRealtimeDatabase;
import com.elertan.remote.firebase.FirebaseRealtimeDatabaseURL;
import com.elertan.remote.firebase.storageAdapters.GameRulesFirebaseObjectStorageAdapter;
import com.elertan.ui.Property;
import com.google.gson.Gson;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import javax.swing.JOptionPane;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import okhttp3.OkHttpClient;

@Slf4j
public final class SetupScreenViewModel implements AutoCloseable {

    public final Property<Step> step = new Property<>(Step.REMOTE);
    public final Property<Boolean> gameRulesAreViewOnly = new Property<>(null);
    public final Property<GameRules> gameRules = new Property<>(null);
    public final Property<Boolean> requiresGroupRuleAcknowledgement = new Property<>(false);
    public final Property<Boolean> groupRuleAcknowledged = new Property<>(false);
    private final Client client;
    private final BUPanelService buPanelService;
    private final AccountConfigurationService accountConfigurationService;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private FirebaseRealtimeDatabase firebaseRealtimeDatabase;
    private GameRulesFirebaseObjectStorageAdapter gameRulesStoragePort;

    private SetupScreenViewModel(
        Client client,
        BUPanelService buPanelService,
        AccountConfigurationService accountConfigurationService,
        OkHttpClient httpClient,
        Gson gson
    ) {
        this.client = client;
        this.buPanelService = buPanelService;
        this.accountConfigurationService = accountConfigurationService;
        this.httpClient = httpClient;
        this.gson = gson;
    }

    @Override
    public void close() throws Exception {
        if (gameRulesStoragePort != null) {
            gameRulesStoragePort.close();
            gameRulesStoragePort = null;
        }
        if (firebaseRealtimeDatabase != null) {
            firebaseRealtimeDatabase.close();
            firebaseRealtimeDatabase = null;
        }
    }

    public void onDontAskMeAgainButtonClick() {
        int result = JOptionPane.showConfirmDialog(
            null,
            "We won't ask you again to set up bronzeman mode for this account.\n"
                + "You can set up bronzeman mode at any time by re-opening this panel.",
            "Confirm setup choice",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        buPanelService.closePanel();
        accountConfigurationService.addCurrentAccountHashToAutoOpenConfigurationDisabled();
    }

    public CompletableFuture<Void> onRemoteStepFinished(FirebaseRealtimeDatabaseURL url) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        // We also want to grab the game rules from the remote database, if they exist
        firebaseRealtimeDatabase = new FirebaseRealtimeDatabase(httpClient, gson, url);
        gameRulesStoragePort = new GameRulesFirebaseObjectStorageAdapter(
            firebaseRealtimeDatabase,
            gson
        );
        gameRulesStoragePort.read().whenComplete((gameRules, throwable) -> {
            if (throwable != null) {
                future.completeExceptionally(throwable);
                return;
            }

            if (gameRules == null) {
                gameRulesAreViewOnly.set(false);
                requiresGroupRuleAcknowledgement.set(false);
                groupRuleAcknowledged.set(true);
            } else {
                gameRulesAreViewOnly.set(true);
                requiresGroupRuleAcknowledgement.set(true);
                groupRuleAcknowledged.set(false);
            }

            this.gameRules.set(gameRules);

            step.set(Step.GAME_RULES);
            future.complete(null);
        });

        return future;
    }

    public void onGameRulesStepBack() {
        step.set(Step.REMOTE);
        gameRules.set(null);
        requiresGroupRuleAcknowledgement.set(false);
        groupRuleAcknowledged.set(false);
    }

    public CompletableFuture<Void> onGameRulesStepFinish() {
        CompletableFuture<Void> future = new CompletableFuture<>();

        if (gameRulesStoragePort == null) {
            Exception ex = new IllegalStateException("The Firebase URL is not set yet");
            future.completeExceptionally(ex);
            return future;
        }

        Boolean gameRulesAreViewOnlyValue = this.gameRulesAreViewOnly.get();
        if (gameRulesAreViewOnlyValue == null) {
            Exception ex = new IllegalStateException("Game rules are view only not set");
            future.completeExceptionally(ex);
            return future;
        }

        Runnable finalize = () -> {
            step.set(Step.REMOTE);
            gameRulesAreViewOnly.set(null);
            gameRules.set(null);
            requiresGroupRuleAcknowledgement.set(false);
            groupRuleAcknowledged.set(false);

            long accountHash = client.getAccountHash();
            AccountConfiguration accountConfiguration = new AccountConfiguration(
                firebaseRealtimeDatabase.getDatabaseURL());
            accountConfigurationService.setAccountConfiguration(accountConfiguration, accountHash);

            try {
                gameRulesStoragePort.close();
                gameRulesStoragePort = null;
            } catch (Exception ex) {
                future.completeExceptionally(ex);
                return;
            }

            try {
                firebaseRealtimeDatabase.close();
                firebaseRealtimeDatabase = null;
            } catch (Exception ex) {
                future.completeExceptionally(ex);
                return;
            }

            future.complete(null);
        };

        Boolean requiresAck = requiresGroupRuleAcknowledgement.get();
        Boolean acknowledged = groupRuleAcknowledged.get();
        if (requiresAck != null && requiresAck && (acknowledged == null || !acknowledged)) {
            Exception ex = new IllegalStateException("You must acknowledge that group rules are shared.");
            future.completeExceptionally(ex);
            return future;
        }

        if (gameRulesAreViewOnlyValue) {
            finalize.run();
            return future;
        }

        GameRules gameRulesValue = this.gameRules.get();
        if (gameRulesValue == null) {
            Exception ex = new IllegalStateException("Game rules are not set");
            future.completeExceptionally(ex);
            return future;
        }
        GameRules normalizedGameRulesValue = gameRulesValue;
        if (normalizedGameRulesValue.isFromScratch()
            && normalizedGameRulesValue.getFromScratchStartedAt() == null) {
            normalizedGameRulesValue = normalizedGameRulesValue.toBuilder()
                .fromScratchStartedAt(new ISOOffsetDateTime(OffsetDateTime.now()))
                .build();
            this.gameRules.set(normalizedGameRulesValue);
        }

        gameRulesStoragePort.update(normalizedGameRulesValue).whenComplete((__, throwable) -> {
            if (throwable != null) {
                future.completeExceptionally(throwable);
                return;
            }

            finalize.run();
        });

        return future;
    }

    public enum Step {
        REMOTE,
        GAME_RULES,
    }

    @ImplementedBy(FactoryImpl.class)
    public interface Factory {

        SetupScreenViewModel create();
    }

    @Singleton
    private static final class FactoryImpl implements Factory {

        @Inject
        private Client client;
        @Inject
        private BUPanelService buPanelService;
        @Inject
        private AccountConfigurationService accountConfigurationService;
        @Inject
        private OkHttpClient httpClient;
        @Inject
        private Gson gson;

        @Override
        public SetupScreenViewModel create() {
            return new SetupScreenViewModel(
                client,
                buPanelService,
                accountConfigurationService,
                httpClient,
                gson
            );
        }
    }
}
