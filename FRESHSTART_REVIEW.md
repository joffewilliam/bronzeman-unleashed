# Freshstart Review Notes

## What This Update Does

- Adds a new "From Scratch" mode.
- Tracks a player's baseline bank and unlocked items for that mode.
- Tightens Withdraw-X handling so players cannot bypass item locks.
- Improves startup/sync behavior so data providers wait for storage to be ready.
- Updates setup and config screens so the mode is easier to use and understand.
- Adds an explicit activation guidance message: open bank once to capture the first baseline snapshot.
- Adds a Resume vs Start New flow when re-enabling From Scratch.
- Adds rollback safety so unlock-list changes are restored if game-rules save fails (mostly a firebase safety measure).
- Wires From Scratch unlock/baseline storage through unified storage paths for both local and online modes.

## How From Scratch Mode Works (End-to-End)

1. When From Scratch mode starts, the plugin checks the player's inventory and equipped items.
2. If the player is carrying or wearing anything, they are prompted to bank those items first.
3. Until that setup is complete, those carried/worn items are treated as locked.
4. On the first bank open, the plugin saves a baseline snapshot of the bank.
5. Items that only exist in the baseline are shown as unavailable (greyed out) and their shown quantity is forced to `0`.
6. As the player earns new drops, those items are recorded in the unlocked-items storage.
7. Once an item is unlocked, depositing it into the bank increases its usable quantity based on unlocked progress.
8. Withdraw actions are limited to unlocked quantities, and Withdraw-X is clamped so it cannot bypass locks.
9. Baseline and unlocked state are saved and reloaded through the selected storage mode (local or online), with startup guards so providers wait until storage is ready.

## Bugs We Hit and How We Fixed Them

- Bug: Players could attempt Withdraw-X amounts that bypassed the intended lock limits.
  - Fix: Added clamp/guard logic so withdraw actions are capped to allowed unlocked amounts.
- Bug: Some startup paths ran before storage was ready, leading to missing or incorrect state.
  - Fix: Added readiness signaling and provider wait logic so initialization runs only after storage is ready.
- Bug: Early From Scratch setup could leave confusion around carried/worn items and baseline state.
  - Fix: Added explicit setup flow (onboarding) that blocks progress until items are banked, then captures baseline on first bank open, with a direct chat reminder to open bank for snapshot capture.
- Bug: Bank display and usable quantities could drift from unlocked progress.
  - Fix: Baseline-only items are shown as unavailable, and usable quantity now follows unlocked-items state.
- Bug: Local/online mode branching in setup/config screens was inconsistent in some paths.
  - Fix: Updated setup/config/game-rules view model flow so mode decisions are applied consistently.
- Bug: Re-enabling From Scratch always started a new run and cleared progress.
  - Fix: Added explicit Resume previous run vs Start new run choice when turning From Scratch back on.
- Bug: Transition actions could partially apply (clear/merge happened even if game-rules save failed).
  - Fix: Added rollback logic to restore prior unlock-list state when save fails.
- Bug: Historical bank baselines could accumulate across multiple old runs.
  - Fix: Keep only the active run baseline and prune old run baseline entries.

## Biggest Code Areas Changed

- Policy logic (largest impact):
  - src/main/java/com.elertan/policies/FromScratchPolicy.java
- Unlock and sync behavior:
  - src/main/java/com.elertan/ItemUnlockService.java
- Storage readiness and storage wiring:
  - src/main/java/com.elertan/remote/StorageService.java
  - src/main/java/com.elertan/remote/StorageSession.java
  - src/main/java/com.elertan/remote/firebase/FirebaseStorageSession.java
  - src/main/java/com.elertan/remote/local/LocalStorageSession.java
  - src/main/java/com.elertan/remote/StorageStateSource.java
- Setup/config/game rules UI flow:
  - src/main/java/com.elertan/panel/screens/SetupScreenViewModel.java
  - src/main/java/com.elertan/panel/screens/main/ConfigScreenViewModel.java
  - src/main/java/com.elertan/panel/screens/setup/GameRulesStepViewViewModel.java

## New Pieces Added

- From Scratch data providers:
  - FromScratchBankBaselineDataProvider
  - FromScratchUnlockedItemsDataProvider
- New From Scratch storage adapters for Firebase.
- New mode utility helper and a test:
  - FromScratchModeUtils
  - FromScratchModeUtilsTest

## What needs tested/added from this point

- Run Start New and Resume flows in both local and online storage modes.
- Simulate save failure paths to confirm rollback restores prior unlock-list state.
- Confirm old baseline entries are pruned and only current-run baseline is retained.
- Finalize the plan for whether to track GP and other containers (seed bank/death bank/POH/etc).

## Scope Snapshot

- Around 30 files changed.
- Mostly feature additions plus UI/storage wiring updates.
- Squashed into one commit so review focuses on final behavior.
