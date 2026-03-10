package com.elertan;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Resolves relationships between items for unlocking.
 *
 * - Equivalent item groups (e.g. all doses of a potion, clean/grimy herb variants, broken/normal armor).
 * - Recipe-style relationships (e.g. if ingredients A and B are unlocked, unlock result C).
 *
 * Item IDs referenced below are taken from RuneLite's net.runelite.api.ItemID constants (numeric
 * values) and stored as raw integers to avoid relying on a particular gameval ItemID API surface.
 */
public final class RelatedItemsRegistry {

    private final Map<Integer, Set<Integer>> equivalenceGroups;
    private final Set<RecipeRule> recipeRules;

    public RelatedItemsRegistry(
        Map<Integer, Set<Integer>> equivalenceGroups,
        Set<RecipeRule> recipeRules
    ) {
        this.equivalenceGroups = equivalenceGroups;
        this.recipeRules = recipeRules;
    }

    /**
     * Creates a default registry with curated equivalence groups for common item variants:
     * clean↔grimy herbs, and broken/degraded↔repaired armor (Barrows, Moons of Peril).
     */
    public static RelatedItemsRegistry createDefault() {
        Map<Integer, Set<Integer>> groups = new HashMap<>();

        registerHerbs(groups);
        registerBarrowsEquipment(groups);
        registerMoonsEquipment(groups);

        return new RelatedItemsRegistry(
            Collections.unmodifiableMap(groups),
            Collections.emptySet()
        );
    }

    // ── Herbs (clean ↔ grimy) ──────────────────────────────────────────────────

    private static void registerHerbs(Map<Integer, Set<Integer>> groups) {
        registerGroup(groups, 249, 199);   // Guam leaf
        registerGroup(groups, 251, 201);   // Marrentill
        registerGroup(groups, 253, 203);   // Tarromin
        registerGroup(groups, 255, 205);   // Harralander
        registerGroup(groups, 257, 207);   // Ranarr weed
        registerGroup(groups, 2998, 3049); // Toadflax
        registerGroup(groups, 259, 209);   // Irit leaf
        registerGroup(groups, 261, 211);   // Avantoe
        registerGroup(groups, 263, 213);   // Kwuarm
        registerGroup(groups, 12152, 12151); // Huasca
        registerGroup(groups, 3000, 3051); // Snapdragon
        registerGroup(groups, 265, 215);   // Cadantine
        registerGroup(groups, 2481, 2485); // Lantadyme
        registerGroup(groups, 267, 217);   // Dwarf weed
        registerGroup(groups, 269, 219);   // Torstol
    }

    // ── Barrows equipment (base + 100/75/50/25/0 degradation states) ───────────
    //
    // Each Barrows piece has a base (fully repaired, tradeable) item and five
    // degraded variants. A player who unlocks any of these should be considered
    // to have unlocked the item.

    private static void registerBarrowsEquipment(Map<Integer, Set<Integer>> groups) {
        // Ahrim's
        registerBarrowsPiece(groups, 4708, 4856); // Hood
        registerBarrowsPiece(groups, 4710, 4862); // Staff
        registerBarrowsPiece(groups, 4712, 4868); // Robetop
        registerBarrowsPiece(groups, 4714, 4874); // Robeskirt

        // Dharok's
        registerBarrowsPiece(groups, 4716, 4880); // Helm
        registerBarrowsPiece(groups, 4718, 4886); // Greataxe
        registerBarrowsPiece(groups, 4720, 4892); // Platebody
        registerBarrowsPiece(groups, 4722, 4898); // Platelegs

        // Guthan's
        registerBarrowsPiece(groups, 4724, 4904); // Helm
        registerBarrowsPiece(groups, 4726, 4910); // Warspear
        registerBarrowsPiece(groups, 4728, 4916); // Platebody
        registerBarrowsPiece(groups, 4730, 4922); // Chainskirt

        // Karil's
        registerBarrowsPiece(groups, 4732, 4928); // Coif
        registerBarrowsPiece(groups, 4734, 4934); // Crossbow
        registerBarrowsPiece(groups, 4736, 4940); // Leathertop
        registerBarrowsPiece(groups, 4738, 4946); // Leatherskirt

        // Torag's
        registerBarrowsPiece(groups, 4745, 4952); // Helm
        registerBarrowsPiece(groups, 4747, 4958); // Hammers
        registerBarrowsPiece(groups, 4749, 4964); // Platebody
        registerBarrowsPiece(groups, 4751, 4970); // Platelegs

        // Verac's
        registerBarrowsPiece(groups, 4753, 4976); // Helm
        registerBarrowsPiece(groups, 4755, 4982); // Flail
        registerBarrowsPiece(groups, 4757, 4988); // Brassard
        registerBarrowsPiece(groups, 4759, 4994); // Plateskirt
    }

    /**
     * Registers a Barrows piece. Degraded variants are at consecutive IDs:
     * base100, base100+1 (75), base100+2 (50), base100+3 (25), base100+4 (0).
     */
    private static void registerBarrowsPiece(
        Map<Integer, Set<Integer>> groups,
        int baseId,
        int base100Id
    ) {
        registerGroup(groups,
            baseId,
            base100Id,      // 100
            base100Id + 1,  // 75
            base100Id + 2,  // 50
            base100Id + 3,  // 25
            base100Id + 4   // 0
        );
    }

    // ── Moons of Peril equipment (normal ↔ broken) ────────────────────────────

    private static void registerMoonsEquipment(Map<Integer, Set<Integer>> groups) {
        // Eclipse Moon
        registerGroup(groups, 29004, 29049); // Chestplate / Broken
        registerGroup(groups, 29007, 29052); // Tassets / Broken
        registerGroup(groups, 29010, 29055); // Helm / Broken

        // Blue Moon
        registerGroup(groups, 29013, 29058); // Chestplate / Broken
        registerGroup(groups, 29016, 29061); // Tassets / Broken
        registerGroup(groups, 29019, 29064); // Helm / Broken

        // Blood Moon
        registerGroup(groups, 29022, 29067); // Chestplate / Broken
        registerGroup(groups, 29025, 29070); // Tassets / Broken
        registerGroup(groups, 29028, 29073); // Helm / Broken
    }

    // ── Registration helpers ───────────────────────────────────────────────────

    private static void registerGroup(Map<Integer, Set<Integer>> groups, int... ids) {
        Set<Integer> group = IntStream.of(ids)
            .boxed()
            .collect(Collectors.toUnmodifiableSet());
        for (int id : ids) {
            groups.put(id, group);
        }
    }

    /**
     * Returns the full set of item IDs that are considered equivalent to the given item ID,
     * including the given ID itself. If no mapping exists, a singleton set containing only
     * {@code itemId} is returned.
     */
    public Set<Integer> getEquivalentItemIds(int itemId) {
        Set<Integer> group = equivalenceGroups.get(itemId);
        if (group == null || group.isEmpty()) {
            return Collections.singleton(itemId);
        }
        // Always include the queried ID to keep behavior predictable when the group was
        // registered under a different representative.
        if (group.contains(itemId)) {
            return Collections.unmodifiableSet(group);
        }
        Set<Integer> copy = new HashSet<>(group);
        copy.add(itemId);
        return Collections.unmodifiableSet(copy);
    }

    /**
     * Given the set of currently unlocked item IDs, returns the set of additional item IDs that
     * should become unlocked because all of their recipe ingredients are present in the unlocked
     * set.
     *
     * This method does not mutate the input. Callers are responsible for filtering out results
     * that are already unlocked if they want strictly new unlocks.
     */
    public Set<Integer> getRecipeResultItemIds(Set<Integer> unlockedItemIds) {
        if (recipeRules.isEmpty() || unlockedItemIds.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Integer> results = new HashSet<>();
        for (RecipeRule rule : recipeRules) {
            if (unlockedItemIds.containsAll(rule.ingredients)) {
                results.addAll(rule.results);
            }
        }
        return Collections.unmodifiableSet(results);
    }

    public static final class RecipeRule {

        private final Set<Integer> ingredients;
        private final Set<Integer> results;

        public RecipeRule(Set<Integer> ingredients, Set<Integer> results) {
            this.ingredients = Collections.unmodifiableSet(new HashSet<>(ingredients));
            this.results = Collections.unmodifiableSet(new HashSet<>(results));
        }

        public Set<Integer> getIngredients() {
            return ingredients;
        }

        public Set<Integer> getResults() {
            return results;
        }
    }
}

