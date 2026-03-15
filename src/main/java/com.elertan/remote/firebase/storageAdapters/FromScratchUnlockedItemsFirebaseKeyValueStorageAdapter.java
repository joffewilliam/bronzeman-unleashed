package com.elertan.remote.firebase.storageAdapters;

import com.elertan.models.UnlockedItem;
import com.elertan.remote.firebase.FirebaseKeyValueStorageAdapterBase;
import com.elertan.remote.firebase.FirebaseRealtimeDatabase;
import com.google.gson.Gson;
import java.util.function.Function;

public class FromScratchUnlockedItemsFirebaseKeyValueStorageAdapter
    extends FirebaseKeyValueStorageAdapterBase<Integer, UnlockedItem> {

    private static final String BASE_PATH = "/FromScratchUnlockedItems";
    private static final Function<String, Integer> stringToKey = Integer::parseInt;
    private static final Function<Integer, String> keyToString = Object::toString;

    public FromScratchUnlockedItemsFirebaseKeyValueStorageAdapter(
        FirebaseRealtimeDatabase db,
        Gson gson
    ) {
        super(
            BASE_PATH, db, gson, stringToKey, keyToString, (jsonElement) -> {
                if (jsonElement == null || jsonElement.isJsonNull()) {
                    return null;
                }

                return gson.fromJson(jsonElement, UnlockedItem.class);
            }
        );
    }
}
