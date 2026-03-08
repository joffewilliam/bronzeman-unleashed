package com.elertan.remote.local;

import com.elertan.remote.KeyListStoragePort;
import com.elertan.remote.KeyValueStoragePort;
import com.elertan.remote.ObjectStoragePort;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * Local (file-backed) storage adapters for solo mode. All three port types in one place to keep
 * local storage in fewer files. Writes are atomic (temp file + rename); file access is serialized
 * where needed to avoid concurrent-write corruption.
 */
public final class LocalStorageAdapters {

    private LocalStorageAdapters() {
    }

    /** Key-value storage backed by a single JSON file. */
    @Slf4j
    public static final class LocalKeyValueStorageAdapter<K, V> implements KeyValueStoragePort<K, V> {

        private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

        private final Path filePath;
        private final Gson gson;
        private final Function<K, String> keyToString;
        private final Function<String, K> stringToKey;
        private final Type valueType;
        private final ConcurrentLinkedQueue<Listener<K, V>> listeners = new ConcurrentLinkedQueue<>();
        private final ReentrantLock fileLock = new ReentrantLock();

        public LocalKeyValueStorageAdapter(
            Path filePath,
            Gson gson,
            Function<K, String> keyToString,
            Function<String, K> stringToKey,
            Type valueType
        ) {
            this.filePath = filePath;
            this.gson = gson;
            this.keyToString = keyToString;
            this.stringToKey = stringToKey;
            this.valueType = valueType;
        }

        @Override
        public CompletableFuture<V> read(K key) {
            return readAll().thenApply(map -> map.get(key));
        }

        @Override
        public CompletableFuture<Map<K, V>> readAll() {
            return CompletableFuture.supplyAsync(() -> {
                fileLock.lock();
                try {
                    if (!Files.exists(filePath)) {
                        return new HashMap<>();
                    }
                    String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
                    Type mapType = TypeToken.getParameterized(Map.class, String.class, valueType).getType();
                    Map<String, V> stringKeyMap;
                    try (JsonReader reader = new JsonReader(new StringReader(json))) {
                        reader.setLenient(true);
                        stringKeyMap = gson.fromJson(reader, mapType);
                    } catch (JsonSyntaxException e) {
                        log.warn("Malformed JSON in {} (e.g. concurrent write); using empty map: {}", filePath.getFileName(), e.getMessage());
                        return new HashMap<>();
                    }
                    if (stringKeyMap == null) {
                        return new HashMap<>();
                    }
                    Map<K, V> result = new HashMap<>();
                    for (Map.Entry<String, V> e : stringKeyMap.entrySet()) {
                        try {
                            K k = stringToKey.apply(e.getKey());
                            result.put(k, e.getValue());
                        } catch (Exception ex) {
                            log.warn("Skip invalid key in {}: {}", filePath.getFileName(), e.getKey(), ex);
                        }
                    }
                    return result;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read " + filePath, e);
                } finally {
                    fileLock.unlock();
                }
            });
        }

        @Override
        public CompletableFuture<Void> update(K key, V value) {
            return readAll()
                .thenCompose(map -> {
                    // Copy before mutate: readAll() may return an unmodifiable map (e.g. from Gson)
                    Map<K, V> mutable = new HashMap<>(map);
                    mutable.put(key, value);
                    return writeAll(mutable);
                })
                .thenRun(() -> {
                    for (Listener<K, V> listener : listeners) {
                        try {
                            listener.onUpdate(key, value);
                        } catch (Exception e) {
                            log.error("Listener error on update", e);
                        }
                    }
                });
        }

        @Override
        public CompletableFuture<Void> updateAll(Map<K, V> map) {
            return writeAll(map).thenRun(() -> {
                for (Listener<K, V> listener : listeners) {
                    try {
                        listener.onFullUpdate(map);
                    } catch (Exception e) {
                        log.error("Listener error on full update", e);
                    }
                }
            });
        }

        @Override
        public CompletableFuture<Void> delete(K key) {
            return readAll()
                .thenCompose(map -> {
                    Map<K, V> mutable = new HashMap<>(map);
                    mutable.remove(key);
                    return writeAll(mutable);
                })
                .thenRun(() -> {
                    for (Listener<K, V> listener : listeners) {
                        try {
                            listener.onDelete(key);
                        } catch (Exception e) {
                            log.error("Listener error on delete", e);
                        }
                    }
                });
        }

        @Override
        public void addListener(Listener<K, V> listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(Listener<K, V> listener) {
            listeners.remove(listener);
        }

        @Override
        public void close() {
            listeners.clear();
        }

        private CompletableFuture<Void> writeAll(Map<K, V> map) {
            return CompletableFuture.runAsync(() -> {
                fileLock.lock();
                try {
                    Map<String, V> stringKeyMap = new HashMap<>();
                    for (Map.Entry<K, V> e : map.entrySet()) {
                        stringKeyMap.put(keyToString.apply(e.getKey()), e.getValue());
                    }
                    String json = PRETTY_GSON.toJson(gson.toJsonTree(stringKeyMap));
                    Path dir = filePath.getParent();
                    if (dir != null) {
                        Files.createDirectories(dir);
                    }
                    Path temp = filePath.resolveSibling(filePath.getFileName().toString() + ".tmp");
                    Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
                    Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write " + filePath, e);
                } finally {
                    fileLock.unlock();
                }
            });
        }
    }

    /** Single-object storage backed by a JSON file. */
    @Slf4j
    public static final class LocalObjectStorageAdapter<T> implements ObjectStoragePort<T> {

        private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

        private final Path filePath;
        private final Gson gson;
        private final Type type;
        private final ConcurrentLinkedQueue<Listener<T>> listeners = new ConcurrentLinkedQueue<>();

        public LocalObjectStorageAdapter(Path filePath, Gson gson, Type type) {
            this.filePath = filePath;
            this.gson = gson;
            this.type = type;
        }

        @Override
        public CompletableFuture<T> read() {
            return CompletableFuture.supplyAsync(() -> {
                if (!Files.exists(filePath)) {
                    return null;
                }
                try {
                    String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
                    return gson.fromJson(json, type);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read " + filePath, e);
                }
            });
        }

        @Override
        public CompletableFuture<Void> update(T value) {
            if (value == null) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                f.completeExceptionally(new IllegalArgumentException("value must not be null, call delete instead"));
                return f;
            }
            return CompletableFuture.runAsync(() -> {
                String json = PRETTY_GSON.toJson(gson.toJsonTree(value));
                Path dir = filePath.getParent();
                try {
                    if (dir != null) {
                        Files.createDirectories(dir);
                    }
                    Path temp = filePath.resolveSibling(filePath.getFileName().toString() + ".tmp");
                    Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
                    Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write " + filePath, e);
                }
            }).thenRun(() -> {
                for (Listener<T> listener : listeners) {
                    try {
                        listener.onUpdate(value);
                    } catch (Exception e) {
                        log.error("Listener error on update", e);
                    }
                }
            });
        }

        @Override
        public CompletableFuture<Void> delete() {
            return CompletableFuture.runAsync(() -> {
                try {
                    if (Files.exists(filePath)) {
                        Files.delete(filePath);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to delete " + filePath, e);
                }
            }).thenRun(() -> {
                for (Listener<T> listener : listeners) {
                    try {
                        listener.onDelete();
                    } catch (Exception e) {
                        log.error("Listener error on delete", e);
                    }
                }
            });
        }

        @Override
        public void addListener(Listener<T> listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(Listener<T> listener) {
            listeners.remove(listener);
        }

        @Override
        public void close() {
            listeners.clear();
        }
    }

    /** Key-list storage backed by a single JSON file. */
    @Slf4j
    public static final class LocalKeyListStorageAdapter<K, V> implements KeyListStoragePort<K, V> {

        private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

        private final Path filePath;
        private final Gson gson;
        private final Function<K, String> keyToString;
        private final Function<String, K> stringToKey;
        private final Type valueType;
        private final ConcurrentLinkedQueue<Listener<K, V>> listeners = new ConcurrentLinkedQueue<>();
        private final ReentrantLock fileLock = new ReentrantLock();

        public LocalKeyListStorageAdapter(
            Path filePath,
            Gson gson,
            Function<K, String> keyToString,
            Function<String, K> stringToKey,
            Type valueType
        ) {
            this.filePath = filePath;
            this.gson = gson;
            this.keyToString = keyToString;
            this.stringToKey = stringToKey;
            this.valueType = valueType;
        }

        @Override
        public CompletableFuture<Map<String, V>> read(K key) {
            return readAll().thenApply(full -> full.getOrDefault(key, Collections.emptyMap()));
        }

        @Override
        public CompletableFuture<Map<K, Map<String, V>>> readAll() {
            return CompletableFuture.supplyAsync(() -> {
                fileLock.lock();
                try {
                    if (!Files.exists(filePath)) {
                        return new HashMap<>();
                    }
                    String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
                    Type outerType = TypeToken.getParameterized(Map.class, String.class,
                        TypeToken.getParameterized(Map.class, String.class, valueType).getType()).getType();
                    Map<String, Map<String, V>> raw;
                    try (JsonReader reader = new JsonReader(new StringReader(json))) {
                        reader.setLenient(true);
                        raw = gson.fromJson(reader, outerType);
                    } catch (JsonSyntaxException e) {
                        log.warn("Malformed JSON in {} (e.g. concurrent write); using empty map: {}", filePath.getFileName(), e.getMessage());
                        return new HashMap<>();
                    }
                    if (raw == null) {
                        return new HashMap<>();
                    }
                    Map<K, Map<String, V>> result = new HashMap<>();
                    for (Map.Entry<String, Map<String, V>> e : raw.entrySet()) {
                        try {
                            K k = stringToKey.apply(e.getKey());
                            result.put(k, e.getValue() != null ? new HashMap<>(e.getValue()) : new HashMap<>());
                        } catch (Exception ex) {
                            log.warn("Skip invalid key in {}: {}", filePath.getFileName(), e.getKey(), ex);
                        }
                    }
                    return result;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read " + filePath, e);
                } finally {
                    fileLock.unlock();
                }
            });
        }

        @Override
        public CompletableFuture<String> add(K key, V value) {
            String entryKey = UUID.randomUUID().toString();
            return readAll()
                .thenCompose(full -> {
                    full.computeIfAbsent(key, k -> new HashMap<>()).put(entryKey, value);
                    return writeAll(full);
                })
                .thenApply(v -> {
                    for (Listener<K, V> listener : listeners) {
                        try {
                            listener.onAdd(key, entryKey, value);
                        } catch (Exception e) {
                            log.error("Listener error on add", e);
                        }
                    }
                    return entryKey;
                });
        }

        @Override
        public CompletableFuture<Void> removeOne(K key) {
            return readAll().thenCompose(full -> {
                Map<String, V> inner = full.get(key);
                if (inner == null || inner.isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }
                String firstKey = inner.keySet().iterator().next();
                return remove(key, firstKey);
            });
        }

        @Override
        public CompletableFuture<Void> remove(K key, String entryKey) {
            return readAll()
                .thenCompose(full -> {
                    Map<String, V> inner = full.get(key);
                    if (inner != null) {
                        inner.remove(entryKey);
                        if (inner.isEmpty()) {
                            full.remove(key);
                        }
                    }
                    return writeAll(full);
                })
                .thenRun(() -> {
                    for (Listener<K, V> listener : listeners) {
                        try {
                            listener.onRemove(key, entryKey);
                        } catch (Exception e) {
                            log.error("Listener error on remove", e);
                        }
                    }
                });
        }

        @Override
        public void addListener(Listener<K, V> listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(Listener<K, V> listener) {
            listeners.remove(listener);
        }

        @Override
        public void close() {
            listeners.clear();
        }

        private CompletableFuture<Void> writeAll(Map<K, Map<String, V>> full) {
            return CompletableFuture.runAsync(() -> {
                fileLock.lock();
                try {
                    Map<String, Map<String, V>> raw = new HashMap<>();
                    for (Map.Entry<K, Map<String, V>> e : full.entrySet()) {
                        raw.put(keyToString.apply(e.getKey()), e.getValue());
                    }
                    String json = PRETTY_GSON.toJson(gson.toJsonTree(raw));
                    Path dir = filePath.getParent();
                    if (dir != null) {
                        Files.createDirectories(dir);
                    }
                    Path temp = filePath.resolveSibling(filePath.getFileName().toString() + ".tmp");
                    Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
                    Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write " + filePath, e);
                } finally {
                    fileLock.unlock();
                }
            });
        }
    }
}
