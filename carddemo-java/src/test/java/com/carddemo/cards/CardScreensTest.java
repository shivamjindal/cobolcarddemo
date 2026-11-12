package com.carddemo.cards;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.SignedOnUser;
import com.carddemo.online.TestDatabase;
import com.carddemo.online.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestDatabase.class)
class CardScreensTest {

    private static final SignedOnUser ADMIN = new SignedOnUser("ADMIN001", UserType.ADMIN);
    private static final SignedOnUser USER = new SignedOnUser("USER0001", UserType.USER);

    @Autowired
    CardList list;

    @Autowired
    CardUpdate update;

    @Autowired
    CardRepository cards;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cardsOfTwoAccounts() {
        jdbc.execute("TRUNCATE cards");
        insert("4000000000000001", 1, 111);
        insert("4000000000000002", 2, 456);
        insert("4000000000000003", 1, 333);
        insert("4000000000000004", 2, 444);
    }

    @Test
    void aRegularUserListsOnlyTheCardsOfTheAccountInTheFilter() {
        Conversation conversation = new Conversation(USER, Program.COMEN01C);
        Outcome.Show arrival = (Outcome.Show) list.arrive(conversation);
        assertThat(arrival.fields()).doesNotContainKey("CRDNUM1");
        assertThat(arrival.message().text()).isEqualTo("ENTER AN ACCOUNT NUMBER TO LIST ITS CARDS");

        Outcome.Show filtered = (Outcome.Show) list.receive(conversation, Aid.ENTER,
                Map.of("ACCTSID", "00000000002", "CARDSID", ""));
        assertThat(filtered.fields()).containsEntry("CRDNUM1", "4000000000000002")
                .containsEntry("CRDNUM2", "4000000000000004").doesNotContainKey("CRDNUM3");

        Outcome.Show admin = (Outcome.Show) list.arrive(new Conversation(ADMIN, Program.COADM01C));
        assertThat(admin.fields()).containsEntry("CRDNUM1", "4000000000000001").containsEntry("CRDNUM4", "4000000000000004");
    }

    @Test
    void returningFromDetailReshowsTheFilteredListTheUserLeft() {
        Conversation onList = new Conversation(USER, Program.COMEN01C);
        list.receive(onList, Aid.ENTER, Map.of("ACCTSID", "00000000001", "CARDSID", ""));
        assertThat(list.receive(onList, Aid.ENTER, Map.of("ACCTSID", "00000000001", "CARDSID", "", "CRDSEL2", "S")))
                .isEqualTo(new Outcome.Transfer(Program.COCRDSLC));

        Conversation back = new Conversation(USER, Program.COCRDSLC);
        back.put(onList.get(CardListPage.class).orElseThrow());
        Outcome.Show page = (Outcome.Show) list.arrive(back);
        assertThat(page.fields()).containsEntry("ACCTSID", "00000000001")
                .containsEntry("CRDNUM1", "4000000000000001").containsEntry("CRDNUM2", "4000000000000003")
                .doesNotContainKey("CRDNUM3");
        assertThat(back.user()).isEqualTo(USER);
    }

    @Test
    void savingAnUpdateKeepsTheStoredCvv() {
        Conversation conversation = new Conversation(ADMIN, Program.COCRDLIC);
        conversation.put(new CardSelection(2, "4000000000000002"));
        update.arrive(conversation);
        Outcome.Show confirm = (Outcome.Show) update.receive(conversation, Aid.ENTER, Map.of(
                "CRDNAME", "Jane Doe", "CRDSTCD", "N", "EXPMON", "06", "EXPYEAR", "2028"));
        assertThat(confirm.fields()).containsEntry("INFOMSG", "Changes validated.Press F5 to save");

        Outcome.Show saved = (Outcome.Show) update.receive(conversation, Aid.PF5, Map.of());
        assertThat(saved.fields()).containsEntry("INFOMSG", "Changes committed to database");
        assertThat(cards.find("4000000000000002")).contains(
                new Card("4000000000000002", 2, 456, "Jane Doe", LocalDate.of(2028, 6, 30), "N"));
    }

    private void insert(String number, long account, int cvv) {
        jdbc.update("INSERT INTO cards VALUES (?, ?, ?, 'John Smith', '2027-05-31', 'Y')", number, account, cvv);
    }
}
