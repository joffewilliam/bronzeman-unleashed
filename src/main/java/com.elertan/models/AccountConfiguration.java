package com.elertan.models;

import com.elertan.remote.firebase.FirebaseRealtimeDatabaseURL;
import javax.annotation.Nullable;
import lombok.Value;

@Value
public class AccountConfiguration {

    /**
     * How Bronzeman data is stored for this account (local files or Firebase).
     */
    public enum StorageMode {
        /** Data in JSON files under ~/.runelite/bronzeman-unleashed/&lt;account-hash&gt;/. No group sync. */
        LOCAL,
        /** Data in Firebase Realtime Database. Enables group sync and shared rules. */
        FIREBASE
    }

    StorageMode storageMode;
    /**
     * Non-null when storageMode is FIREBASE; null when LOCAL.
     */
    FirebaseRealtimeDatabaseURL firebaseRealtimeDatabaseURL;
    /**
     * When storageMode is LOCAL, the account hash whose data directory to use.
     * Stored so we always read/write the same path regardless of when config is applied.
     * Null for older persisted configs; callers should fall back to client.getAccountHash().
     */
    @Nullable
    Long localAccountHash;
}
