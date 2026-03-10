package com.elertan;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.runelite.api.ItemID;

/**
 * Resolves relationships between items for unlocking.
 *
 * - Equivalent item groups (e.g. all doses of a potion, clean/grimy herb variants, broken/normal armor).
 * - Recipe-style relationships (e.g. if ingredients A and B are unlocked, unlock result C).
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
     * Creates a default registry with curated equivalence groups for common item variants and
     * explicit recipe mappings.
     */
    public static RelatedItemsRegistry createDefault() {
        // Item IDs are taken from RuneLite numeric game item IDs.
        Map<Integer, Set<Integer>> equivalenceGroups = Map.ofEntries(
            // Standard herbs
            herbPair(249, 199),   // GUAM_LEAF / GRIMY_GUAM_LEAF
            herbPair(251, 201),   // MARRENTILL / GRIMY_MARRENTILL
            herbPair(253, 203),   // TARROMIN / GRIMY_TARROMIN
            herbPair(255, 205),   // HARRALANDER / GRIMY_HARRALANDER
            herbPair(257, 207),   // RANARR_WEED / GRIMY_RANARR_WEED
            herbPair(259, 209),   // IRIT_LEAF / GRIMY_IRIT_LEAF
            herbPair(261, 211),   // AVANTOE / GRIMY_AVANTOE
            herbPair(263, 213),   // KWUARM / GRIMY_KWUARM
            herbPair(265, 215),   // CADANTINE / GRIMY_CADANTINE
            herbPair(267, 217),   // DWARF_WEED / GRIMY_DWARF_WEED
            herbPair(269, 219),   // TORSTOL / GRIMY_TORSTOL

            // Other notable herbs
            herbPair(2481, 2485), // LANTADYME / GRIMY_LANTADYME
            herbPair(2998, 3049), // TOADFLAX / GRIMY_TOADFLAX
            herbPair(1526, 1525)  // SNAKE_WEED / GRIMY_SNAKE_WEED
        );

        Set<RecipeRule> recipes = new HashSet<>();
        registerRecipes(recipes);

        return new RelatedItemsRegistry(
            equivalenceGroups,
            Collections.unmodifiableSet(recipes)
        );
    }

    private static Map.Entry<Integer, Set<Integer>> herbPair(int cleanId, int grimyId) {
        Set<Integer> group = new HashSet<>();
        group.add(cleanId);
        group.add(grimyId);
        return Map.entry(cleanId, Collections.unmodifiableSet(group));
    }

    private static void registerRecipes(Set<RecipeRule> recipes) {
        // Note: RuneLite does not expose a stable ItemID constant for amulet of torture in this
        // API version, so we use the known game item ID directly.
        recipes.add(new RecipeRule(
            IntStream.of(ItemID.ARAXYTE_FANG, 19553)
                .boxed()
                .collect(Collectors.toUnmodifiableSet()),
            Collections.singleton(ItemID.AMULET_OF_RANCOUR)
        ));
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
