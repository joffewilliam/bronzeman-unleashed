package com.elertan.models;

import com.elertan.gson.AccountHashJsonAdapter;
import com.google.gson.annotations.JsonAdapter;
import java.util.Map;
import lombok.Value;

@Value
public class UnlockedItem {

    int id;
    String name;
    @JsonAdapter(AccountHashJsonAdapter.class)
    long acquiredByAccountHash;
    ISOOffsetDateTime acquiredAt;
    Integer droppedByNPCId;
    /**
     * True if this unlock was granted via a recipe (all ingredients owned). Used for overlay
     * title ("Recipe unlock") and chat formatting. Null/absent in older saves is treated as false.
     */
    Boolean recipeUnlock;
    /**
     * Crafting level required to make this item; only set when recipe unlock. Shown in chat.
     * Null if unknown or not applicable.
     *
     * Kept for backwards-compatibility with older saves; new code should prefer
     * {@link #skillRequirements} which can represent multiple skills.
     */
    Integer requiredCraftingLevel;
    /**
     * Per-skill level requirements for this unlock, keyed by skill name
     * (e.g. "Fletching" → 53, "Crafting" → 86). May be null for older saves or
     * when requirements are unknown.
     */
    Map<String, Integer> skillRequirements;

    /** Null-safe: returns false when recipeUnlock is null (e.g. from older saves). */
    public boolean isRecipeUnlock() {
        return Boolean.TRUE.equals(recipeUnlock);
    }
}
