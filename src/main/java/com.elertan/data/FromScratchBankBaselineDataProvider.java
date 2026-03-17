package com.elertan.data;

import com.elertan.models.FromScratchBankBaselineEntry;
import com.elertan.remote.KeyValueStoragePort;
import com.elertan.remote.StorageService;
import com.elertan.remote.StorageStateSource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class FromScratchBankBaselineDataProvider extends AbstractDataProvider {

    @Inject
    private StorageService storageService;

    private KeyValueStoragePort<String, FromScratchBankBaselineEntry> keyValueStoragePort;
    private KeyValueStoragePort.Listener<String, FromScratchBankBaselineEntry> storagePortListener;
    private ConcurrentHashMap<String, FromScratchBankBaselineEntry> map;

    @Override
    protected StorageStateSource getStorageService() {
        return storageService;
    }

    @Override
    public void startUp() throws Exception {
        storagePortListener = new KeyValueStoragePort.Listener<String, FromScratchBankBaselineEntry>() {
            @Override
            public void onFullUpdate(Map<String, FromScratchBankBaselineEntry> newMap) {
                if (map == null) {
                    return;
                }
                if (newMap == null) {
                    log.debug("from-scratch bank baseline provider -> full update with null map; resetting to empty");
                    map = new ConcurrentHashMap<>();
                    return;
                }
                map = new ConcurrentHashMap<>(newMap);
            }

            @Override
            public void onUpdate(String key, FromScratchBankBaselineEntry value) {
                if (map == null) {
                    return;
                }
                map.put(key, value);
            }

            @Override
            public void onDelete(String key) {
                if (map == null) {
                    return;
                }
                map.remove(key);
            }
        };
        super.startUp();
    }

    @Override
    protected void onRemoteStorageReady() {
        keyValueStoragePort = storageService.getFromScratchBankBaselineStoragePort();
        if (keyValueStoragePort == null) {
            log.error("FromScratchBankBaselineDataProvider: baseline storage port is null during onRemoteStorageReady");
            return;
        }
        keyValueStoragePort.addListener(storagePortListener);

        keyValueStoragePort.readAll().whenComplete((newMap, throwable) -> {
            if (throwable != null) {
                log.error("FromScratchBankBaselineDataProvider storageport read all failed", throwable);
                return;
            }
            if (newMap == null) {
                log.debug("FromScratchBankBaselineDataProvider: readAll returned null map; initializing empty map");
                map = new ConcurrentHashMap<>();
            } else {
                map = new ConcurrentHashMap<>(newMap);
            }
            setState(State.Ready);
        });
    }

    @Override
    protected void onRemoteStorageNotReady() {
        map = null;
        if (keyValueStoragePort != null) {
            keyValueStoragePort.removeListener(storagePortListener);
            keyValueStoragePort = null;
        }
    }

    public Map<String, FromScratchBankBaselineEntry> getMap() {
        if (map == null) {
            return null;
        }
        return Collections.unmodifiableMap(map);
    }

    public Integer getQuantity(String key) {
        if (map == null) {
            return null;
        }
        FromScratchBankBaselineEntry entry = map.get(key);
        if (entry == null) {
            return null;
        }
        return entry.getQuantity();
    }

    public CompletableFuture<Void> putIfAbsent(String key, int quantity) {
        if (getState().get() != State.Ready) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("State is not ready"));
            return future;
        }
        if (map.containsKey(key)) {
            return CompletableFuture.completedFuture(null);
        }
        FromScratchBankBaselineEntry value = new FromScratchBankBaselineEntry(quantity);
        map.put(key, value);
        return keyValueStoragePort.update(key, value);
    }

    public CompletableFuture<Void> put(String key, int quantity) {
        if (getState().get() != State.Ready) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("State is not ready"));
            return future;
        }
        FromScratchBankBaselineEntry value = new FromScratchBankBaselineEntry(quantity);
        map.put(key, value);
        return keyValueStoragePort.update(key, value);
    }

    public CompletableFuture<Void> deleteKeys(List<String> keys) {
        if (getState().get() != State.Ready) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("State is not ready"));
            return future;
        }
        if (keys == null || keys.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // Remove duplicates and null/empty keys before issuing deletes.
        List<String> filtered = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isEmpty() || filtered.contains(key)) {
                continue;
            }
            filtered.add(key);
        }
        if (filtered.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        ConcurrentHashMap<String, FromScratchBankBaselineEntry> localMap = map;
        KeyValueStoragePort<String, FromScratchBankBaselineEntry> localPort = keyValueStoragePort;
        for (String key : filtered) {
            if (localMap != null) {
                localMap.remove(key);
            }
            future = future.thenCompose(__ -> {
                if (localPort == null) {
                    return CompletableFuture.completedFuture(null);
                }
                return localPort.delete(key);
            });
        }
        return future;
    }
}
