package com.carddemo.online;

import com.carddemo.cards.CardSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A field the map protects renders read-only and comes back as shown, whatever the client submits. */
@SpringBootTest
@Import(TestDatabase.class)
class ProtectedFieldsTest {

    private static final SignedOnUser ADMIN = new SignedOnUser("ADMIN001", UserType.ADMIN);

    @Autowired
    Screens screens;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void oneAccountWithItsCustomerAndTwoCards() {
        jdbc.execute("TRUNCATE accounts, customers, card_xref, cards");
        for (int id = 1; id <= 2; id++) {
            jdbc.update("INSERT INTO accounts VALUES (?, 'Y', 1940.00, 2020.00, 1020.00, '2014-11-20', '2025-05-20',"
                    + " '2025-05-20', 0, 0, '10001', 'A000000000')", id);
            jdbc.update("INSERT INTO customers VALUES (?, 'Immanuel', 'Madeline', 'Kessler', '618 Deshaun Route',"
                    + " 'Apt. 802', 'Altenwerthshire', 'NY', 'USA', '10001', '(212)555-0100', '(212)555-0101',"
                    + " 20973888, '00000000000049368437', '1961-06-08', '0053581756', 'Y', 750)", id);
            jdbc.update("INSERT INTO card_xref VALUES (?, ?, ?)", "400000000000000" + id, id, id);
            jdbc.update("INSERT INTO cards VALUES (?, ?, 123, 'John Smith', '2027-05-31', 'Y')",
                    "400000000000000" + id, id);
        }
    }

    @Test
    void accountUpdateIgnoresTheKeysAndCountryTheClientSends() {
        Conversation conversation = new Conversation(ADMIN, Program.COADM01C);
        screens.arrive(Program.COACTUPC, conversation);
        Outcome.Show shown = show(screens.receive(Program.COACTUPC, conversation, Aid.ENTER,
                Map.of("ACCTSID", "00000000001")));
        assertThat(shown.readOnly()).containsExactlyInAnyOrder("ACCTSID", "ACSTNUM", "ACSCTRY");

        Map<String, String> edited = new HashMap<>(shown.fields());
        edited.putAll(Map.of("ACSLNAM", "Kesler", "ACCTSID", "00000000002", "ACSTNUM", "000000002", "ACSCTRY", "CAN"));
        Outcome.Show confirm = show(screens.receive(Program.COACTUPC, conversation, Aid.ENTER, edited));
        assertThat(confirm.fields()).containsEntry("INFOMSG", "Changes validated.Press F5 to save")
                .containsEntry("ACCTSID", "00000000001").containsEntry("ACSCTRY", "USA");
        assertThat(confirm.readOnly()).contains("ACSLNAM", "AADDGRP");

        edited.put("ACSLNAM", "Tampered");
        screens.receive(Program.COACTUPC, conversation, Aid.PF5, edited);
        assertThat(jdbc.queryForList("SELECT cust_id, cust_last_name, cust_addr_country_cd FROM customers ORDER BY 1"))
                .extracting(row -> row.get("cust_last_name") + "|" + row.get("cust_addr_country_cd"))
                .containsExactly("Kesler|USA", "Kessler|USA");
    }

    @Test
    void cardUpdateIgnoresTheKeysAndExpiryDayTheClientSends() {
        Conversation conversation = new Conversation(ADMIN, Program.COCRDLIC);
        conversation.put(new CardSelection(1, "4000000000000001"));
        Outcome.Show shown = show(screens.arrive(Program.COCRDUPC, conversation));
        assertThat(shown.readOnly()).containsExactlyInAnyOrder("ACCTSID", "CARDSID", "EXPDAY");

        Outcome.Show confirm = show(screens.receive(Program.COCRDUPC, conversation, Aid.ENTER, Map.of(
                "ACCTSID", "00000000002", "CARDSID", "4000000000000002", "EXPDAY", "01",
                "CRDNAME", "Jane Doe", "CRDSTCD", "Y", "EXPMON", "05", "EXPYEAR", "2027")));
        assertThat(confirm.fields()).containsEntry("CARDSID", "4000000000000001").containsEntry("EXPDAY", "31");
        assertThat(confirm.readOnly()).contains("CRDNAME");

        screens.receive(Program.COCRDUPC, conversation, Aid.PF5, Map.of("CRDNAME", "Tampered"));
        assertThat(jdbc.queryForList("SELECT card_embossed_name || '|' || card_expiraion_date FROM cards ORDER BY card_num",
                String.class)).containsExactly("Jane Doe|2027-05-31", "John Smith|2027-05-31");
    }

    @Test
    void cardDetailOpenedFromTheListKeepsItsKeys() {
        Conversation conversation = new Conversation(ADMIN, Program.COCRDLIC);
        conversation.put(new CardSelection(1, "4000000000000001"));
        assertThat(show(screens.arrive(Program.COCRDSLC, conversation)).readOnly())
                .containsExactlyInAnyOrder("ACCTSID", "CARDSID");

        Outcome.Show again = show(screens.receive(Program.COCRDSLC, conversation, Aid.ENTER,
                Map.of("ACCTSID", "00000000002", "CARDSID", "4000000000000002")));
        assertThat(again.fields()).containsEntry("CARDSID", "4000000000000001");
    }

    private static Outcome.Show show(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Show.class);
        return (Outcome.Show) outcome;
    }
}
