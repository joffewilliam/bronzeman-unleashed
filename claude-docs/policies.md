# Policies

Game rule enforcement. Each policy handles specific game mechanics and can block/modify player actions.

## Base Class

All policies extend `PolicyBase`:

```java
public class PolicyBase implements BUPluginLifecycle {
    protected PolicyContext createContext();  // Check if rules should apply
}
```

`PolicyContext.shouldApplyForRules(r -> r.isXxxEnabled())` - Returns true if:
- Game rules not loaded yet (strict mode) OR
- Specific rule is enabled

## Policies

| Policy | File | Responsibility |
|--------|------|----------------|
| `GrandExchangePolicy` | `policies/GrandExchangePolicy.java` | Block GE buy offers for locked items |
| `TradePolicy` | `policies/TradePolicy.java` | Block trades with non-group members |
| `ShopPolicy` | `policies/ShopPolicy.java` | Block buying locked items from shops |
| `GroundItemsPolicy` | `policies/GroundItemsPolicy.java` | Block picking up locked items from ground |
| `PlayerOwnedHousePolicy` | `policies/PlayerOwnedHousePolicy.java` | Block POH entry for non-group |
| `PlayerVersusPlayerPolicy` | `policies/PlayerVersusPlayerPolicy.java` | Handle PvP loot restrictions |
| `FaladorPartyRoomPolicy` | `policies/FaladorPartyRoomPolicy.java` | Block party room balloon drops |

## Grand Exchange Buy Policy Modes

The GE policy uses one mode selection in the game rules UI:

- `Off` - no GE buy restriction.
- `Unlocked items only` - all GE buys require item unlock.
- `Allow supplies before unlock` - non-gear supplies can be bought before unlock; gear still requires unlock.

### Gear vs Supplies Classification

The plugin classifies an item as **gear** when its inventory actions include one of:

- `wear`
- `wield`
- `equip`

If none of those actions exist, the item is treated as a **supply/non-gear** item for this policy.

## Event Handlers

| Policy | Events Handled |
|--------|----------------|
| `GrandExchangePolicy` | `onScriptPostFired` |
| `TradePolicy` | `onMenuOptionClicked` |
| `ShopPolicy` | `onWidgetLoaded`, `onWidgetClosed` |
| `GroundItemsPolicy` | `onMenuOptionClicked`, `onItemSpawned`, `onItemDespawned` |
| `PlayerOwnedHousePolicy` | `onMenuOptionClicked`, `onScriptPreFired` |
| `PlayerVersusPlayerPolicy` | `onMenuOptionClicked`, `onActorDeath`, `onPlayerLootReceived` |
| `FaladorPartyRoomPolicy` | `onMenuOptionClicked` |

## Adding a New Policy

See [workflows/add-policy.md](workflows/add-policy.md)

## GameRules Integration

Policies check `GameRules` model for enabled/disabled state:

```java
PolicyContext ctx = createContext();
if (!ctx.shouldApplyForRules(rules -> rules.isRestrictGroundItems())) {
    return; // Rule disabled, don't enforce
}
// Enforce policy...
```
