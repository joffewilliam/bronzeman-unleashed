package com.elertan.remote;

import com.elertan.AccountConfigurationService;
import com.elertan.BUPluginLifecycle;
import com.elertan.event.BUEvent;
import com.elertan.models.AccountConfiguration;
import com.elertan.models.GameRules;
import com.elertan.models.GroundItemOwnedByData;
import com.elertan.models.GroundItemOwnedByKey;
import com.elertan.models.Member;
import com.elertan.models.AccountConfiguration.StorageMode;
import com.elertan.models.UnlockedItem;
import com.elertan.remote.firebase.FirebaseRealtimeDatabase;
import com.elertan.remote.firebase.FirebaseRealtimeDatabaseURL;
import com.elertan.remote.firebase.FirebaseSSEStream;
import com.elertan.remote.firebase.storageAdapters.GameRulesFirebaseObjectStorageAdapter;
import com.elertan.remote.firebase.storageAdapters.GroundItemOwnedByKeyListStorageAdapter;
import com.elertan.remote.firebase.storageAdapters.LastEventFirebaseObjectListStorageAdapter;
import com.elertan.remote.firebase.storageAdapters.MembersFirebaseKeyValueStorageAdapter;
import com.elertan.remote.firebase.storageAdapters.UnlockedItemsFirebaseKeyValueStorageAdapter;
import com.elertan.remote.local.LocalStorageAdapters;
import com.elertan.remote.local.NoOpAdapters;
import com.elertan.utils.Observable;
import com.elertan.utils.Subscription;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import okhttp3.OkHttpClient;

@Slf4j
@Singleton
public class StorageService implements BUPluginLifecycle {

    private static final String RELATIVE_DIR = ".runelite";
    private static final String PLUGIN_DIR = "bronzeman-unleashed";

    /**
     * Returns the directory for a given account's local data. Does not create it.
     */
    public static Path getAccountStorageDir(long accountHash) {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isEmpty()) {
            userHome = ".";
        }
        return Paths.get(userHome, RELATIVE_DIR, PLUGIN_DIR, String.valueOf(accountHash));
    }

    /**
     * Ensures the account storage directory exists. Returns the path or null on failure.
     */
    public static Path ensureAccountStorageDir(long accountHash) {
        Path dir = getAccountStorageDir(accountHash);
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    @Getter
    private final Observable<State> state = Observable.of(State.NotReady);
    private Subscription accountConfigSubscription;
    @Inject
    private OkHttpClient httpClient;
    @Inject
    private Client client;
    @Inject
    private Gson gson;
    @Inject
    private AccountConfigurationService accountConfigurationService;
    private FirebaseRealtimeDatabase firebaseRealtimeDatabase;
    @Getter
    private KeyValueStoragePort<Long, Member> membersStoragePort;
    @Getter
    private KeyValueStoragePort<Integer, UnlockedItem> unlockedItemsStoragePort;
    @Getter
    private ObjectStoragePort<GameRules> gameRulesStoragePort;
    @Getter
    private ObjectListStoragePort<BUEvent> lastEventStoragePort;
    @Getter
    private KeyListStoragePort<GroundItemOwnedByKey, GroundItemOwnedByData> groundItemOwnedByStoragePort;

    @Override
    public void startUp() {
        accountConfigSubscription = accountConfigurationService.currentAccountConfiguration()
            .subscribe(this::useAccountConfiguration);
        if (accountConfigurationService.isReady() && client.getGameState() == GameState.LOGGED_IN) {
            useAccountConfiguration(accountConfigurationService.getCurrentAccountConfiguration());
        }
    }

    @Override
    public void shutDown() throws Exception {
        clearCurrentDataport();
        if (accountConfigSubscription != null) {
            accountConfigSubscription.dispose();
            accountConfigSubscription = null;
        }
    }

    /**
     * Wait until storage is ready (state == State.Ready).
     */
    public CompletableFuture<State> await(Duration timeout) {
        return waitForValue(state, State.Ready, timeout);
    }

    private static <T> CompletableFuture<T> waitForValue(Observable<T> observable, T targetValue, Duration timeout) {
        CompletableFuture<T> future = new CompletableFuture<>();

        if (observable.get() == targetValue) {
            future.complete(targetValue);
            return future;
        }

        Subscription[] subscriptionHolder = new Subscription[1];
        subscriptionHolder[0] = observable.subscribe((newValue, oldValue) -> {
            if (newValue == targetValue && !future.isDone()) {
                subscriptionHolder[0].dispose();
                future.complete(newValue);
            }
        });

        if (observable.get() == targetValue && !future.isDone()) {
            subscriptionHolder[0].dispose();
            future.complete(targetValue);
        }

        if (timeout != null) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.schedule(() -> {
                if (!future.isDone()) {
                    subscriptionHolder[0].dispose();
                    future.completeExceptionally(new TimeoutException("Timeout waiting for value"));
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            future.whenComplete((result, ex) -> scheduler.shutdown());
        }

        return future;
    }

    private void useAccountConfiguration(AccountConfiguration accountConfiguration) {
        try {
            clearCurrentDataport();
        } catch (Exception e) {
            log.error("Failed to clear current data port", e);
        }
        if (accountConfiguration == null) {
            return;
        }

        if (accountConfiguration.getStorageMode() == StorageMode.LOCAL) {
            configureFromLocalStorage(accountConfiguration);
        } else {
            FirebaseRealtimeDatabaseURL url = accountConfiguration.getFirebaseRealtimeDatabaseURL();
            if (url == null) {
                log.warn("Account configuration has no storage mode and no Firebase URL; skipping storage setup");
                return;
            }
            configureFromFirebaseRealtimeDatabase(url);
        }

        state.set(State.Ready);
    }

    private void configureFromLocalStorage(AccountConfiguration accountConfiguration) {
        // Use hash stored with LOCAL config so we always use the same path (avoids wrong/empty path
        // when config is applied off client thread or before client is ready after leave+new local).
        Long storedHash = accountConfiguration.getLocalAccountHash();
        long accountHash = storedHash != null ? storedHash : client.getAccountHash();
        Path dir = ensureAccountStorageDir(accountHash);
        if (dir == null) {
            log.error("Failed to create local storage directory for account {}", accountHash);
            return;
        }

        Path unlockedItemsFile = dir.resolve("UnlockedItems.json");
        Path gameRulesFile = dir.resolve("GameRules.json");
        Path groundItemOwnedByFile = dir.resolve("GroundItemOwnedBy.json");

        unlockedItemsStoragePort = new LocalStorageAdapters.LocalKeyValueStorageAdapter<>(
            unlockedItemsFile,
            gson,
            Object::toString,
            Integer::valueOf,
            UnlockedItem.class
        );
        gameRulesStoragePort = new LocalStorageAdapters.LocalObjectStorageAdapter<>(
            gameRulesFile,
            gson,
            GameRules.class
        );
        groundItemOwnedByStoragePort = new LocalStorageAdapters.LocalKeyListStorageAdapter<>(
            groundItemOwnedByFile,
            gson,
            GroundItemOwnedByKey::toKey,
            GroundItemOwnedByKey::fromKey,
            GroundItemOwnedByData.class
        );
        membersStoragePort = new NoOpAdapters.NoOpKeyValueStorageAdapter<>();
        lastEventStoragePort = new NoOpAdapters.NoOpObjectListStorageAdapter<>();
    }

    private void clearCurrentDataport() throws Exception {
        state.set(State.NotReady);

        if (groundItemOwnedByStoragePort != null) {
            groundItemOwnedByStoragePort.close();
            groundItemOwnedByStoragePort = null;
        }
        if (lastEventStoragePort != null) {
            lastEventStoragePort.close();
            lastEventStoragePort = null;
        }
        if (membersStoragePort != null) {
            membersStoragePort.close();
            membersStoragePort = null;
        }
        if (unlockedItemsStoragePort != null) {
            unlockedItemsStoragePort.close();
            unlockedItemsStoragePort = null;
        }
        if (gameRulesStoragePort != null) {
            gameRulesStoragePort.close();
            gameRulesStoragePort = null;
        }

        if (firebaseRealtimeDatabase != null) {
            FirebaseSSEStream stream = firebaseRealtimeDatabase.getStream();
            stream.stop();

            firebaseRealtimeDatabase = null;
        }

        log.debug("Dataport has been cleared");
    }

    private void configureFromFirebaseRealtimeDatabase(FirebaseRealtimeDatabaseURL url) {
        firebaseRealtimeDatabase = new FirebaseRealtimeDatabase(httpClient, gson, url);

        groundItemOwnedByStoragePort = new GroundItemOwnedByKeyListStorageAdapter(
            firebaseRealtimeDatabase,
            gson
        );
        lastEventStoragePort = new LastEventFirebaseObjectListStorageAdapter(
            firebaseRealtimeDatabase,
            gson
        );
        membersStoragePort = new MembersFirebaseKeyValueStorageAdapter(
            firebaseRealtimeDatabase,
            gson
        );
        unlockedItemsStoragePort = new UnlockedItemsFirebaseKeyValueStorageAdapter(
            firebaseRealtimeDatabase,
            gson
        );
        gameRulesStoragePort = new GameRulesFirebaseObjectStorageAdapter(
            firebaseRealtimeDatabase,
            gson
        );

        FirebaseSSEStream stream = firebaseRealtimeDatabase.getStream();
        stream.start();
    }

    public enum State {
        NotReady,
        Ready
    }
}
