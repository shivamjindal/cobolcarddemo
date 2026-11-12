package com.carddemo.cards;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Map;

/** COCRDSLC: one card by account and card number, opened from the list or searched for. */
@Component
class CardDetail implements ScreenProgram {

    private final CardRepository cards;

    CardDetail(CardRepository cards) {
        this.cards = cards;
    }

    @Override
    public Program program() {
        return Program.COCRDSLC;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return conversation.take(CardSelection.class)
                .<Outcome>map(selected -> show(selected.accountId(), selected.cardNumber()))
                .orElseGet(() -> new Outcome.Show(Map.of("INFOMSG", CardKeys.SEARCH_PROMPT), null, "ACCTSID"));
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        if (aid == Aid.PF3) {
            return new Outcome.Transfer(conversation.from().orElse(Program.COMEN01C));
        }
        return switch (CardKeys.search(fields)) {
            case CardKeys.Search.Valid keys -> show(keys.accountId(), keys.cardNumber());
            case CardKeys.Search.Invalid invalid -> invalid.screen();
        };
    }

    private Outcome.Show show(long accountId, String cardNumber) {
        try {
            return CardKeys.find(cards, accountId, cardNumber)
                    .map(card -> new Outcome.Show(Map.of("ACCTSID", CardKeys.account(accountId), "CARDSID", cardNumber,
                            "CRDNAME", card.embossedName(), "CRDSTCD", card.activeStatus(),
                            "EXPMON", CardKeys.month(card.expirationDate()), "EXPYEAR", CardKeys.year(card.expirationDate()),
                            "INFOMSG", "   Displaying requested details"), null, "ACCTSID"))
                    .orElseGet(() -> CardKeys.notFound(accountId, cardNumber, CardKeys.NOT_FOUND));
        } catch (DataAccessException e) {
            return CardKeys.notFound(accountId, cardNumber, CardKeys.fileError("READ", e));
        }
    }
}
