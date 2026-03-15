package com.elertan.remote.firebase.storageAdapters;

import com.elertan.models.FromScratchBankBaselineEntry;
import com.elertan.remote.firebase.FirebaseKeyValueStorageAdapterBase;
import com.elertan.remote.firebase.FirebaseRealtimeDatabase;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Function;

public class FromScratchBankBaselineFirebaseKeyValueStorageAdapter
    extends FirebaseKeyValueStorageAdapterBase<String, FromScratchBankBaselineEntry> {

    private static final String BASE_PATH = "/FromScratchBankBaseline";

    /** Encode key for Firebase path (keys cannot contain . $ # [ ] / or :). */
    private static String encodeKeyForPath(String key) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeKeyFromPath(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static final Function<String, String> stringToKey = FromScratchBankBaselineFirebaseKeyValueStorageAdapter::decodeKeyFromPath;
    private static final Function<String, String> keyToString = FromScratchBankBaselineFirebaseKeyValueStorageAdapter::encodeKeyForPath;

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
