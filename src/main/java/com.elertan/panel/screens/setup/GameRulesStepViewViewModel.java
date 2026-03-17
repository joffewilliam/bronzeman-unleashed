package com.elertan.panel.screens.setup;

import com.elertan.models.GameRules;
import com.elertan.ui.Property;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GameRulesStepViewViewModel {

    public final Property<GameRules> gameRules;
    public final Property<Boolean> requiresGroupRuleAcknowledgement;
    public final Property<Boolean> groupRuleAcknowledged;
    public final Property<Boolean> isLocalMode;
    public final Property<Boolean> isSubmitting = new Property<>(false);
    public final Property<String> errorMessage = new Property<>(null);
    private final Listener listener;

    private GameRulesStepViewViewModel(
        Property<GameRules> gameRules,
Property<Boolean> requiresGroupRuleAcknowledgement,
        Property<Boolean> groupRuleAcknowledged,
        Property<Boolean> isLocalMode,
        Listener listener
    ) {
        this.gameRules = gameRules;
        this.requiresGroupRuleAcknowledgement = requiresGroupRuleAcknowledgement;
        this.groupRuleAcknowledged = groupRuleAcknowledged;
        this.isLocalMode = isLocalMode;
        this.listener = listener;
    }

    public void onBackButtonClicked() {
        listener.onBack();
    }

    public void onFinishButtonClicked() {
        if (Boolean.TRUE.equals(isSubmitting.get())) {
            return;
        }

        Boolean requiresAcknowledgement = requiresGroupRuleAcknowledgement.get();
        Boolean acknowledged = groupRuleAcknowledged.get();
        if (requiresAcknowledgement != null
            && requiresAcknowledgement
            && (acknowledged == null || !acknowledged)) {
            errorMessage.set("You must acknowledge that group rules apply to all group members.");
            return;
        }

        isSubmitting.set(true);

        listener.onFinish().whenComplete((__, throwable) -> {
            try {
                if (throwable != null) {
                    log.error("error saving game rules", throwable);
                    errorMessage.set("An error occurred while trying to save the game rules.");
                    return;
                }

                errorMessage.set(null);
            } finally {
                isSubmitting.set(false);
            }
        });
    }

    @ImplementedBy(FactoryImpl.class)
    public interface Factory {

        GameRulesStepViewViewModel create(
            Property<GameRules> gameRules,
            Property<Boolean> requiresGroupRuleAcknowledgement,
            Property<Boolean> groupRuleAcknowledged,
            Property<Boolean> isLocalMode,
            Listener listener
        );
    }

    public interface Listener {

        void onBack();

        CompletableFuture<Void> onFinish();
    }

    @Singleton
    private static final class FactoryImpl implements Factory {

        @Override
        public GameRulesStepViewViewModel create(
            Property<GameRules> gameRules,
            Property<Boolean> requiresGroupRuleAcknowledgement,
            Property<Boolean> groupRuleAcknowledged,
            Property<Boolean> isLocalMode,
            Listener listener
        ) {
            return new GameRulesStepViewViewModel(
                gameRules,
                requiresGroupRuleAcknowledgement,
                groupRuleAcknowledged,
                isLocalMode,
                listener
            );
        }
    }
}
