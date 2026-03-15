package com.elertan;

import com.elertan.models.GameRules;
import com.elertan.models.ISOOffsetDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FromScratchModeUtils {

    private static final int WITHDRAW_OPTION_UNKNOWN = -1;
    private static final Pattern WITHDRAW_N_PATTERN = Pattern.compile("withdraw-(\\d+)");

    private FromScratchModeUtils() {
    }

    public static boolean isTransitionToEnabled(GameRules oldRules, GameRules newRules) {
        return oldRules != null
            && newRules != null
            && !oldRules.isFromScratch()
            && newRules.isFromScratch();
    }

    public static boolean isTransitionToDisabled(GameRules oldRules, GameRules newRules) {
        return oldRules != null
            && newRules != null
            && oldRules.isFromScratch()
            && !newRules.isFromScratch();
    }

    public static String bankBaselineKey(long accountHash, ISOOffsetDateTime startedAt, int itemId) {
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt must not be null");
        }
        return accountHash + "|" + startedAt.toString() + "|" + itemId;
    }

    public static String bankBaselineSnapshotKey(long accountHash, ISOOffsetDateTime startedAt) {
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt must not be null");
        }
        return accountHash + "|" + startedAt.toString() + "|__snapshot__";
    }

    public static int getWithdrawableQuantity(int currentQuantity, Integer baselineQuantity) {
        int baseline = baselineQuantity == null ? 0 : baselineQuantity;
        return Math.max(0, currentQuantity - baseline);
    }

    public static boolean canWithdraw(
        String menuOption,
        int currentQuantity,
        Integer baselineQuantity
    ) {
        int withdrawable = getWithdrawableQuantity(currentQuantity, baselineQuantity);
        if (withdrawable <= 0) {
            return false;
        }

        int requestedQuantity = parseRequestedWithdrawQuantity(menuOption, currentQuantity);
        if (requestedQuantity == WITHDRAW_OPTION_UNKNOWN) {
            return false;
        }

        return requestedQuantity <= withdrawable;
    }

    private static int parseRequestedWithdrawQuantity(String menuOption, int currentQuantity) {
        if (menuOption == null) {
            return WITHDRAW_OPTION_UNKNOWN;
        }

        String normalized = menuOption
            .replaceAll("<[^>]*>", "")
            .trim()
            .toLowerCase(Locale.ROOT);

        if (!normalized.startsWith("withdraw")) {
            return WITHDRAW_OPTION_UNKNOWN;
        }
        if (normalized.contains("-all-but-1")) {
            return Math.max(0, currentQuantity - 1);
        }
        if (normalized.contains("-all")) {
            return currentQuantity;
        }
        if (normalized.contains("-x")) {
            // Player chooses quantity in follow-up input; only allow if at least one can be withdrawn.
            return 1;
        }
        Matcher matcher = WITHDRAW_N_PATTERN.matcher(normalized);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return WITHDRAW_OPTION_UNKNOWN;
            }
        }
        return WITHDRAW_OPTION_UNKNOWN;
    }
}
