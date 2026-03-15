# Ground Items Ownership – Area Reload / Key Mismatch

Documentation of the issue where a player’s own dropped item (e.g. prayer potion) cannot be picked up after an area transition (e.g. light puzzle: drop item, go down stairs, return), and the plugin shows: *"You cannot take this item due to Group Bronzeman ground item restrictions. Only items of your group may be taken."*

---

## 1. Summary

- **Observed:** Player drops an item (e.g. prayer potion), leaves the area (e.g. down stairs in the light puzzle), returns, then tries to Take. Pickup is **blocked** and the Group Bronzeman ground item restriction message appears (often repeatedly when retrying).
- **Expected:** As long as the ground item is assigned to the local player (or group), Take should be allowed.
- **Cause:** Our “allow take” logic depends on finding a **stored ownership entry** keyed by `(itemId, world, worldViewId, plane, x, y)`. After an area reload (stairs, instance change, etc.) the **key can change** (e.g. `worldViewId` or how the tile is identified), so we no longer find the entry and treat the item as “not our group’s” and block.

---

## 2. How We Enforce Ground Item Takes

**Relevant code:** [GroundItemsPolicy.java](../src/main/java/com.elertan/policies/GroundItemsPolicy.java)

**Flow:**

1. **On spawn** (`onItemSpawned`): We only record an item if `tileItem.getOwnership() == OWNERSHIP_SELF || OWNERSHIP_GROUP`. We then store an entry keyed by `GroundItemOwnedByKey.of(itemId, client.getWorld(), worldView.getId(), worldPoint)` in `GroundItemOwnedByDataProvider`.
2. **On Take/Cast** (`onMenuOptionClicked` → `enforceItemTakePolicyWhereNecessary`):
   - If `tileItem.getOwnership() == OWNERSHIP_NONE` → allow.
   - Build the same key from current `worldPoint` and `worldView.getId()`.
   - If we **have entries** for that key in `groundItemOwnedByMap` → allow (subject to PvP loot checks).
   - If `tileItem.getOwnership() == OWNERSHIP_SELF` → allow (see code around line 303).
   - Otherwise → **block** and send the ground item restriction message.

So we **require** either “no owner” or “self” at click time, or a **match on our stored key**. If the key changes after a reload (e.g. new `worldViewId`, or world/instance change), we don’t find the entry and block even when the game still considers the item yours.

**Key definition:** [GroundItemOwnedByKey.java](../src/main/java/com.elertan/models/GroundItemOwnedByKey.java) — `(itemId, world, worldViewId, plane, worldX, worldY)`.

---

## 3. How RuneLite Ground Items Plugin Does It (for comparison)

**Source:** RuneLite `GroundItemsPlugin` (display only; it does **not** block pickup).

- **Key:** `(WorldPoint, itemId)` — from `tile.getWorldLocation()` and `item.getId()`. No `worldViewId`, no world number.
- **Ownership:** Read from `item.getOwnership()` at spawn and stored on the `GroundItem` object. Used only for **display filtering** (All / Takeable / Drops), not for allowing or blocking actions.
- **At click time:** They do not check ownership to block Take; the game handles that.

So they do not rely on a persistent “we recorded this as ours” store that must survive area reloads. We do, which is why key stability across reloads matters for us.

---

## 4. Root Cause (concise)

- **Key instability:** After going down/up stairs (or similar transitions), the same physical tile/item may be represented with a different `worldViewId` (or other key fields). Our lookup uses the **current** key at click time; the entry was stored with the **old** key at spawn, so the lookup fails.
- **Over-reliance on stored key:** We block whenever we don’t find an entry, even if the game still reports `OWNERSHIP_SELF` or `OWNERSHIP_GROUP` for that `TileItem`. In the prayer pot case, the game likely still considered the item the player’s, but we had already “lost” it due to the key mismatch.

---

## 5. Possible Directions for a Fix (no implementation here)

- **Trust game ownership at click time:** If `tileItem.getOwnership() == OWNERSHIP_SELF || OWNERSHIP_GROUP`, allow Take (and only use the stored map for PvP loot rules or extra group semantics). That would prevent blocking “your own” items after a key change.
- **Softer key:** Consider whether the key can be made stable across area reloads (e.g. avoid `worldViewId` if it changes when the view is recreated) or add a fallback (e.g. same tile + itemId + “recently had self/group ownership”) — requires care to avoid allowing wrong items.
- **Quest / instance edge cases:** Same key/ownership logic may affect other quests or instances where areas reload; any fix should be tested there too.

---

## 6. Testing Steps

### 6.1 Reproduce the light puzzle / stairs case

1. Enable Bronzeman and ensure **Restrict ground items** is on (Game Rules).
2. Go to the **light puzzle** area (e.g. Meiyerditch light puzzle or similar that has stairs down/up).
3. **Drop** a stackable item you can afford to lose (e.g. 1 prayer potion) on the ground in the puzzle area.
4. **Go down the stairs** (leave the area).
5. **Return** (go back up the stairs to the same area).
6. Try to **Take** the item.
   - **Before fix:** Pickup is blocked; chat shows: *"You cannot take this item due to Group Bronzeman ground item restrictions. Only items of your group may be taken."*
   - **After fix (if we trust game ownership):** Pickup should succeed when the game still reports the item as yours.

### 6.2 Sanity: normal drop (no transition)

1. Same setup (Bronzeman, restrict ground items on).
2. Drop an item (e.g. 1 prayer potion) and **do not** change area.
3. Take the item immediately.
   - **Expected:** Pickup is allowed (no restriction message).

### 6.3 Group / PvP (regression)

1. With a group member or in a PvP context where another player’s loot is present, ensure you still **cannot** take items that are not your group’s when the restriction is intended.
2. Ensure PvP loot rules (if enabled) still apply where expected.

### 6.4 Optional: log key at spawn and at click

To confirm key mismatch in logs, temporarily log `GroundItemOwnedByKey` (e.g. `key.toKey()`) in:

- `GroundItemsPolicy.onItemSpawned` when adding an entry,
- `GroundItemsPolicy.enforceItemTakePolicyWhereNecessary` when building the key for the clicked tile,

and compare the two when the bug occurs (same tile, same item, different key after stairs).

---

## 7. Related Files

| File | Role |
|------|------|
| [GroundItemsPolicy.java](../src/main/java/com.elertan/policies/GroundItemsPolicy.java) | Spawn/despawn recording; Take/Cast allow/block logic |
| [GroundItemOwnedByKey.java](../src/main/java/com.elertan/models/GroundItemOwnedByKey.java) | Key format and serialization |
| [GroundItemOwnedByDataProvider.java](../src/main/java/com.elertan/data/GroundItemOwnedByDataProvider.java) | In-memory map and Firebase sync for ownership entries |
| [GroundItemOwnedByData.java](../src/main/java/com.elertan/models/GroundItemOwnedByData.java) | Stored value (accountHash, despawnsAt, droppedByPlayerName for PvP) |
| [ChatMessageProvider.java](../src/main/java/com.elertan/chat/ChatMessageProvider.java) | Message key for ground item take/cast restriction text |

---

*Indexed for ground items ownership / area reload / key mismatch.*
