package com.elertan;

import com.elertan.models.GameRules;
import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.party.PartyService;

@Slf4j
public class BUPartyService implements BUPluginLifecycle {

    @Inject
    private GameRulesService gameRulesService;
    @Inject
    private PartyService partyService;
    @Inject
    private BUChatService buChatService;

    @Override
    public void startUp() throws Exception {
    }

    @Override
    public void shutDown() throws Exception {
    }

    public void onGameStateChanged(GameStateChanged event) {
        // Auto-join on login removed to reduce Party API load; use "Rejoin last party" button instead.
    }

    /**
     * Joins the party using the saved party password from game rules (e.g. from the "Rejoin last party" button).
     */
    public void rejoinLastParty() {
        GameRules gameRules = gameRulesService.getGameRules().get();
        String partyPassword = gameRules != null ? gameRules.getPartyPassword() : null;
        if (partyPassword == null || partyPassword.isEmpty()) {
            log.debug("No party password set, not attempting to rejoin party");
            ChatMessageBuilder builder = new ChatMessageBuilder();
            builder.append("No party password is set in your game rules. Set one, then update game rules to rejoin a group.");
            buChatService.sendMessage(builder.build());
            return;
        }
        String trimmedPartyPassword = partyPassword.trim();
        if (trimmedPartyPassword.isEmpty()) {
            log.debug("Party password is empty, not attempting to rejoin party");
            ChatMessageBuilder builder = new ChatMessageBuilder();
            builder.append("Party password in game rules is empty. Set one in the Game Rules config to rejoin a group.");
            buChatService.sendMessage(builder.build());
            return;
        }
        log.debug("Rejoining party with password from game rules");
        partyService.changeParty(trimmedPartyPassword);
        ChatMessageBuilder builder = new ChatMessageBuilder();
        builder.append("Rejoined party using game rules configuration.");
        buChatService.sendMessage(builder.build());
    }
}
