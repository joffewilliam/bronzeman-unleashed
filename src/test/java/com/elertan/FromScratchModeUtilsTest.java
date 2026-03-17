package com.elertan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.elertan.models.GameRules;
import com.elertan.models.ISOOffsetDateTime;
import java.time.OffsetDateTime;
import org.junit.Test;

public class FromScratchModeUtilsTest {

    @Test
    public void transitionToEnabledDetected() {
        GameRules oldRules = GameRules.builder().fromScratch(false).build();
        GameRules newRules = GameRules.builder().fromScratch(true).build();

        assertTrue(FromScratchModeUtils.isTransitionToEnabled(oldRules, newRules));
        assertFalse(FromScratchModeUtils.isTransitionToDisabled(oldRules, newRules));
    }

    @Test
    public void transitionToDisabledDetected() {
        GameRules oldRules = GameRules.builder().fromScratch(true).build();
        GameRules newRules = GameRules.builder().fromScratch(false).build();

        assertTrue(FromScratchModeUtils.isTransitionToDisabled(oldRules, newRules));
        assertFalse(FromScratchModeUtils.isTransitionToEnabled(oldRules, newRules));
    }

    @Test
    public void bankBaselineKeyIncludesRunAndAccount() {
        ISOOffsetDateTime startedAt = new ISOOffsetDateTime(OffsetDateTime.parse("2026-01-02T03:04:05Z"));
        String key = FromScratchModeUtils.bankBaselineKey(12345L, startedAt, 4151);

        assertTrue(key.contains("12345"));
        assertTrue(key.contains("4151"));
        assertTrue(key.contains("2026-01-02T03:04:05Z"));
    }

    @Test
    public void bankBaselineSnapshotKeyIncludesRunAndAccount() {
        ISOOffsetDateTime startedAt = new ISOOffsetDateTime(OffsetDateTime.parse("2026-01-02T03:04:05Z"));
        String key = FromScratchModeUtils.bankBaselineSnapshotKey(12345L, startedAt);

        assertTrue(key.contains("12345"));
        assertTrue(key.contains("2026-01-02T03:04:05Z"));
        assertTrue(key.contains("__snapshot__"));
    }

    @Test
    public void withdrawableQuantityUsesBaselineDelta() {
        assertEquals(0, FromScratchModeUtils.getWithdrawableQuantity(10, 10));
        assertEquals(2, FromScratchModeUtils.getWithdrawableQuantity(12, 10));
        assertEquals(7, FromScratchModeUtils.getWithdrawableQuantity(7, null));
    }

    @Test
    public void canWithdrawForCommonBankOptions() {
        assertTrue(FromScratchModeUtils.canWithdraw("Withdraw-1", 12, 10));
        assertFalse(FromScratchModeUtils.canWithdraw("Withdraw-5", 12, 10));
        assertTrue(FromScratchModeUtils.canWithdraw("Withdraw-2", 12, 10));
        assertFalse(FromScratchModeUtils.canWithdraw("Withdraw-3", 12, 10));
        assertFalse(FromScratchModeUtils.canWithdraw("Withdraw-150", 12, 10));
        assertTrue(FromScratchModeUtils.canWithdraw("Withdraw-X", 12, 10));
        assertFalse(FromScratchModeUtils.canWithdraw("<col=ff9040>Withdraw-All</col>", 12, 10));
        assertFalse(FromScratchModeUtils.canWithdraw("Withdraw-All-but-1", 12, 10));
    }
}
