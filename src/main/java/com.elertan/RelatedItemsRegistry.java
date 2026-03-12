package com.elertan;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

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

    /**
     * Upper bound for scanning item IDs when auto-discovering potion dose families. Chosen to be
     * comfortably above the current live item ID range while remaining cheap to iterate once at
     * startup.
     */
    private static final int MAX_ITEM_ID_SCAN = 40_000;

    /**
     * Matches names like "Saradomin brew(4)" or "Stamina potion(1)" and captures the shared family
     * name and the numeric dose count.
     *
     * group(1) = base name (e.g. "Saradomin brew")
     * group(2) = dose number (e.g. "4")
     */
    private static final Pattern DOSE_PATTERN = Pattern.compile("^(.*)\\((\\d)\\)$");

    private final ConcurrentHashMap<Integer, Set<Integer>> equivalenceGroups;
    private final Set<RecipeRule> recipeRules;
    private final Map<Integer, Integer> recipeResultCraftingLevels;
    private volatile boolean potionsRegistered = false;

    public RelatedItemsRegistry(
        Map<Integer, Set<Integer>> equivalenceGroups,
        Set<RecipeRule> recipeRules,
        Map<Integer, Integer> recipeResultCraftingLevels
    ) {
        this.equivalenceGroups = new ConcurrentHashMap<>(equivalenceGroups);
        this.recipeRules = recipeRules;
        this.recipeResultCraftingLevels = recipeResultCraftingLevels == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(recipeResultCraftingLevels));
    }

    /**
     * Creates a default registry with curated equivalence groups and recipe rules for common
     * relationships:
     * - clean↔grimy herbs
     * - broken/degraded↔repaired armor (Barrows, Moons of Peril)
     * - high-value crafted upgrades (e.g. amulet of torture + Araxyte fang → amulet of rancour)
     *
     * Potion dose families are NOT registered here because they require live item cache data
     * from {@link ItemManager} which is only available on the client thread after the game
     * cache loads. Call {@link #ensurePotionsRegistered(ItemManager)} on the client thread
     * before querying potion equivalence.
     */
    public static RelatedItemsRegistry createDefault() {
        Map<Integer, Set<Integer>> groups = new HashMap<>();
        Set<RecipeRule> recipes = new HashSet<>();
        Map<Integer, Integer> recipeCraftingLevels = new HashMap<>();

        registerHerbs(groups);
        registerBarrowsEquipment(groups);
        registerMoonsEquipment(groups);
        registerRecipes(recipes, recipeCraftingLevels);

        return new RelatedItemsRegistry(
            groups,
            Collections.unmodifiableSet(recipes),
            recipeCraftingLevels
        );
    }

    /**
     * Lazily discovers and registers all potion dose families from the live item cache.
     * Must be called on the client thread. Safe to call multiple times — only runs once.
     */
    public synchronized void ensurePotionsRegistered(ItemManager itemManager) {
        if (potionsRegistered) {
            return;
        }
        registerPotionsAutomatically(this.equivalenceGroups, itemManager);
        potionsRegistered = true;
    }

    // ── Herbs (clean ↔ grimy) ──────────────────────────────────────────────────

    private static void registerHerbs(Map<Integer, Set<Integer>> groups) {
        registerGroup(groups, ItemID.GUAM_LEAF, ItemID.UNIDENTIFIED_GUAM);                 // Guam leaf
        registerGroup(groups, ItemID.MARENTILL, ItemID.UNIDENTIFIED_MARENTILL);           // Marrentill
        registerGroup(groups, ItemID.TARROMIN, ItemID.UNIDENTIFIED_TARROMIN);             // Tarromin
        registerGroup(groups, ItemID.HARRALANDER, ItemID.UNIDENTIFIED_HARRALANDER);       // Harralander
        registerGroup(groups, ItemID.RANARR_WEED, ItemID.UNIDENTIFIED_RANARR);            // Ranarr weed
        registerGroup(groups, ItemID.TOADFLAX, ItemID.UNIDENTIFIED_TOADFLAX);             // Toadflax
        registerGroup(groups, ItemID.IRIT_LEAF, ItemID.UNIDENTIFIED_IRIT);                // Irit leaf
        registerGroup(groups, ItemID.AVANTOE, ItemID.UNIDENTIFIED_AVANTOE);               // Avantoe
        registerGroup(groups, ItemID.KWUARM, ItemID.UNIDENTIFIED_KWUARM);                 // Kwuarm
        registerGroup(groups, ItemID.TRAIL_ELITE_RIDDLE_EXP13, ItemID.TRAIL_ELITE_RIDDLE_EXP12); // Huasca
        registerGroup(groups, ItemID.SNAPDRAGON, ItemID.UNIDENTIFIED_SNAPDRAGON);         // Snapdragon
        registerGroup(groups, ItemID.CADANTINE, ItemID.UNIDENTIFIED_CADANTINE);           // Cadantine
        registerGroup(groups, ItemID.LANTADYME, ItemID.UNIDENTIFIED_LANTADYME);           // Lantadyme
        registerGroup(groups, ItemID.DWARF_WEED, ItemID.UNIDENTIFIED_DWARF_WEED);         // Dwarf weed
        registerGroup(groups, ItemID.TORSTOL, ItemID.UNIDENTIFIED_TORSTOL);               // Torstol
    }

    /**
     * Automatically discovers potion dose families from live item metadata using RuneLite's
     * {@link ItemManager}. This scans the item ID space once, finds items whose names end in
     * "(1)"–"(4)" and which have a "Drink" inventory action, and groups them by base name.
     *
     * For example, this will group:
     * - "Saradomin brew(1)"–"(4)"
     * - "Anti-venom+(1)"–"(4)"
     * - "Ancient brew(1)"–"(4)"
     * - all divine potions, future potions, etc.
     */
    public static void registerPotionsAutomatically(
        Map<Integer, Set<Integer>> groups,
        ItemManager itemManager
    ) {
        Map<String, Set<Integer>> families = new HashMap<>();

        for (int id = 0; id < MAX_ITEM_ID_SCAN; id++) {
            ItemComposition item;
            try {
                item = itemManager.getItemComposition(id);
            } catch (Exception ex) {
                continue;
            }
            if (item == null) {
                continue;
            }

            // Skip placeholder variants – we only care about actual items. Noted variants are fine;
            // they share the same name pattern and will end up in the same family.
            if (item.getPlaceholderTemplateId() != -1) {
                continue;
            }

            String name = item.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }

            Matcher matcher = DOSE_PATTERN.matcher(name);
            if (!matcher.matches()) {
                continue;
            }

            // Only consider 1–4 dose variants.
            int dose;
            try {
                dose = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (dose < 1 || dose > 4) {
                continue;
            }

            // Ensure the item is actually drinkable.
            String[] actions = item.getInventoryActions();
            if (actions == null
                || Arrays.stream(actions).noneMatch("Drink"::equals)) {
                continue;
            }

            String family = matcher.group(1).trim().toLowerCase();
            if (family.isEmpty()) {
                continue;
            }

            families
                .computeIfAbsent(family, k -> new HashSet<>())
                .add(id);
        }

        // Register discovered families as equivalence groups.
        for (Set<Integer> ids : families.values()) {
            if (ids.size() <= 1) {
                continue;
            }
            registerGroup(groups, ids.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    // ── Barrows equipment (base + 100/75/50/25/0 degradation states) ───────────
    //
    // Each Barrows piece has a base (fully repaired, tradeable) item and five
    // degraded variants. A player who unlocks any of these should be considered
    // to have unlocked the item.

    private static void registerBarrowsEquipment(Map<Integer, Set<Integer>> groups) {
        // Ahrim's
        registerBarrowsPiece(groups, ItemID.BARROWS_AHRIM_HEAD, ItemID.BARROWS_AHRIM_HEAD_100);       // Hood
        registerBarrowsPiece(groups, ItemID.BARROWS_AHRIM_WEAPON, ItemID.BARROWS_AHRIM_WEAPON_100);   // Staff
        registerBarrowsPiece(groups, ItemID.BARROWS_AHRIM_BODY, ItemID.BARROWS_AHRIM_BODY_100);       // Robetop
        registerBarrowsPiece(groups, ItemID.BARROWS_AHRIM_LEGS, ItemID.BARROWS_AHRIM_LEGS_100);       // Robeskirt

        // Dharok's
        registerBarrowsPiece(groups, ItemID.BARROWS_DHAROK_HEAD, ItemID.BARROWS_DHAROK_HEAD_100);     // Helm
        registerBarrowsPiece(groups, ItemID.BARROWS_DHAROK_WEAPON, ItemID.BARROWS_DHAROK_WEAPON_100); // Greataxe
        registerBarrowsPiece(groups, ItemID.BARROWS_DHAROK_BODY, ItemID.BARROWS_DHAROK_BODY_100);     // Platebody
        registerBarrowsPiece(groups, ItemID.BARROWS_DHAROK_LEGS, ItemID.BARROWS_DHAROK_LEGS_100);     // Platelegs

        // Guthan's
        registerBarrowsPiece(groups, ItemID.BARROWS_GUTHAN_HEAD, ItemID.BARROWS_GUTHAN_HEAD_100);     // Helm
        registerBarrowsPiece(groups, ItemID.BARROWS_GUTHAN_WEAPON, ItemID.BARROWS_GUTHAN_WEAPON_100); // Warspear
        registerBarrowsPiece(groups, ItemID.BARROWS_GUTHAN_BODY, ItemID.BARROWS_GUTHAN_BODY_100);     // Platebody
        registerBarrowsPiece(groups, ItemID.BARROWS_GUTHAN_LEGS, ItemID.BARROWS_GUTHAN_LEGS_100);     // Chainskirt

        // Karil's
        registerBarrowsPiece(groups, ItemID.BARROWS_KARIL_HEAD, ItemID.BARROWS_KARIL_HEAD_100);       // Coif
        registerBarrowsPiece(groups, ItemID.BARROWS_KARIL_WEAPON, ItemID.BARROWS_KARIL_WEAPON_100);   // Crossbow
        registerBarrowsPiece(groups, ItemID.BARROWS_KARIL_BODY, ItemID.BARROWS_KARIL_BODY_100);       // Leathertop
        registerBarrowsPiece(groups, ItemID.BARROWS_KARIL_LEGS, ItemID.BARROWS_KARIL_LEGS_100);       // Leatherskirt

        // Torag's
        registerBarrowsPiece(groups, ItemID.BARROWS_TORAG_HEAD, ItemID.BARROWS_TORAG_HEAD_100);       // Helm
        registerBarrowsPiece(groups, ItemID.BARROWS_TORAG_WEAPON, ItemID.BARROWS_TORAG_WEAPON_100);   // Hammers
        registerBarrowsPiece(groups, ItemID.BARROWS_TORAG_BODY, ItemID.BARROWS_TORAG_BODY_100);       // Platebody
        registerBarrowsPiece(groups, ItemID.BARROWS_TORAG_LEGS, ItemID.BARROWS_TORAG_LEGS_100);       // Platelegs

        // Verac's
        registerBarrowsPiece(groups, ItemID.BARROWS_VERAC_HEAD, ItemID.BARROWS_VERAC_HEAD_100);       // Helm
        registerBarrowsPiece(groups, ItemID.BARROWS_VERAC_WEAPON, ItemID.BARROWS_VERAC_WEAPON_100);   // Flail
        registerBarrowsPiece(groups, ItemID.BARROWS_VERAC_BODY, ItemID.BARROWS_VERAC_BODY_100);       // Brassard
        registerBarrowsPiece(groups, ItemID.BARROWS_VERAC_LEGS, ItemID.BARROWS_VERAC_LEGS_100);       // Plateskirt
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
        registerGroup(groups, ItemID.ECLIPSE_MOON_CHESTPLATE, ItemID.ECLIPSE_MOON_CHESTPLATE_BROKEN); // Chestplate / Broken
        registerGroup(groups, ItemID.ECLIPSE_MOON_TASSETS, ItemID.ECLIPSE_MOON_TASSETS_BROKEN);       // Tassets / Broken
        registerGroup(groups, ItemID.ECLIPSE_MOON_HELM, ItemID.ECLIPSE_MOON_HELM_BROKEN);             // Helm / Broken

        // Frost (Blue) Moon
        registerGroup(groups, ItemID.FROST_MOON_CHESTPLATE, ItemID.FROST_MOON_CHESTPLATE_BROKEN);     // Chestplate / Broken
        registerGroup(groups, ItemID.FROST_MOON_TASSETS, ItemID.FROST_MOON_TASSETS_BROKEN);           // Tassets / Broken
        registerGroup(groups, ItemID.FROST_MOON_HELM, ItemID.FROST_MOON_HELM_BROKEN);                 // Helm / Broken

        // Blood Moon
        registerGroup(groups, ItemID.BLOOD_MOON_CHESTPLATE, ItemID.BLOOD_MOON_CHESTPLATE_BROKEN);     // Chestplate / Broken
        registerGroup(groups, ItemID.BLOOD_MOON_TASSETS, ItemID.BLOOD_MOON_TASSETS_BROKEN);           // Tassets / Broken
        registerGroup(groups, ItemID.BLOOD_MOON_HELM, ItemID.BLOOD_MOON_HELM_BROKEN);                 // Helm / Broken
    }

    // ── Recipe rules (tertiary / upgrade unlocks) ─────────────────────────────
    //
    // These are relationships where owning all ingredient items should also unlock the crafted
    // result, even if the player has never physically created or obtained the result yet.
    // Crafting levels are used for chat messages (e.g. "Recipe unlock: X (Crafting 93)").

    private static void registerRecipes(Set<RecipeRule> recipes, Map<Integer, Integer> craftingLevels) {
        // Amulet of torture + Araxyte fang (or test: maple longbow u) → Amulet of rancour (Crafting 93)
        recipes.add(new RecipeRule(
            IntStream.of(ItemID.ZENYTE_AMULET_ENCHANTED, ItemID.UNSTRUNG_MAPLE_LONGBOW)
                .boxed()
                .collect(Collectors.toUnmodifiableSet()),
            Collections.singleton(ItemID.AMULET_OF_RANCOUR)
        ));
        craftingLevels.put(ItemID.AMULET_OF_RANCOUR, 93);

        // Future recipes: add rule and crafting level here.
    }

    /**
     * Returns the Crafting level required to make the given recipe result item, if known.
     * Used for chat messages on recipe unlocks.
     */
    public OptionalInt getRequiredCraftingLevel(int recipeResultItemId) {
        Integer level = recipeResultCraftingLevels.get(recipeResultItemId);
        return level == null ? OptionalInt.empty() : OptionalInt.of(level);
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

