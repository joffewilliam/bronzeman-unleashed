package com.elertan.data;

import com.elertan.models.FromScratchBankBaselineEntry;
import com.elertan.remote.KeyValueStoragePort;
import com.elertan.remote.RemoteStorageService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class FromScratchBankBaselineDataProvider extends AbstractDataProvider {

    @Inject
    private RemoteStorageService remoteStorageService;

    private KeyValueStoragePort<String, FromScratchBankBaselineEntry> keyValueStoragePort;
    private KeyValueStoragePort.Listener<String, FromScratchBankBaselineEntry> storagePortListener;
    private ConcurrentHashMap<String, FromScratchBankBaselineEntry> map;

    @Override
    protected RemoteStorageService getRemoteStorageService() {
        return remoteStorageService;
    }

    @Override
    public void startUp() throws Exception {
        storagePortListener = new KeyValueStoragePort.Listener<String, FromScratchBankBaselineEntry>() {
            @Override
            public void onFullUpdate(Map<String, FromScratchBankBaselineEntry> newMap) {
                if (map == null) {
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
        keyValueStoragePort = remoteStorageService.getFromScratchBankBaselineStoragePort();
        keyValueStoragePort.addListener(storagePortListener);

        keyValueStoragePort.readAll().whenComplete((newMap, throwable) -> {
            if (throwable != null) {
                log.error("FromScratchBankBaselineDataProvider storageport read all failed", throwable);
                return;
            }
            map = new ConcurrentHashMap<>(newMap);
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
}
