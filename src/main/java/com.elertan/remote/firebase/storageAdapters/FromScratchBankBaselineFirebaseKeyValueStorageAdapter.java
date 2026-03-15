package com.elertan.remote.firebase.storageAdapters;

import com.elertan.models.FromScratchBankBaselineEntry;
import com.elertan.remote.firebase.FirebaseKeyValueStorageAdapterBase;
import com.elertan.remote.firebase.FirebaseRealtimeDatabase;
import com.google.gson.Gson;
import java.util.function.Function;

public class FromScratchBankBaselineFirebaseKeyValueStorageAdapter
    extends FirebaseKeyValueStorageAdapterBase<String, FromScratchBankBaselineEntry> {

    private static final String BASE_PATH = "/FromScratchBankBaseline";
    private static final Function<String, String> stringToKey = (key) -> key;
    private static final Function<String, String> keyToString = (key) -> key;

    public FromScratchBankBaselineFirebaseKeyValueStorageAdapter(
        FirebaseRealtimeDatabase db,
        Gson gson
    ) {
        super(
            BASE_PATH, db, gson, stringToKey, keyToString, (jsonElement) -> {
                if (jsonElement == null || jsonElement.isJsonNull()) {
                    return null;
                }

                return gson.fromJson(jsonElement, FromScratchBankBaselineEntry.class);
            }
        );
    }
}
