package com.elertan.remote.local;

import com.elertan.remote.KeyValueStoragePort;
import com.elertan.remote.ObjectListStoragePort;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * No-op storage adapters for features not used in local/solo mode (e.g. Members, LastEvent).
 */
public final class NoOpAdapters {

    private NoOpAdapters() {
    }

    public static final class NoOpKeyValueStorageAdapter<K, V> implements KeyValueStoragePort<K, V> {

        @Override
        public CompletableFuture<V> read(K key) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Map<K, V>> readAll() {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        @Override
        public CompletableFuture<Void> update(K key, V value) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> updateAll(Map<K, V> map) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> delete(K key) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void addListener(Listener<K, V> listener) {
        }

        @Override
        public void removeListener(Listener<K, V> listener) {
        }

        @Override
        public void close() {
        }
    }

    public static final class NoOpObjectListStorageAdapter<V> implements ObjectListStoragePort<V> {

        @Override
        public CompletableFuture<Map<String, V>> readAll() {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        @Override
        public CompletableFuture<String> add(V value) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> remove(String entryKey) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void addListener(Listener<V> listener) {
        }

        @Override
        public void removeListener(Listener<V> listener) {
        }

        @Override
        public void close() {
        }
    }
}
