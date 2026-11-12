package com.carddemo.accounts;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.SignedOnUser;
import com.carddemo.online.TestDatabase;
import com.carddemo.online.UserType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** COACTUPC from fetch to F5 against the database, where the specs can only start from a given state. */
@SpringBootTest
@Import(TestDatabase.class)
class AccountUpdateTest {

    @Autowired
    AccountUpdate program;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CustomerRepository customers;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void oneAccountWithItsCustomer() {
        jdbc.execute("TRUNCATE accounts, customers, card_xref");
        jdbc.update("INSERT INTO accounts VALUES (1, 'Y', 1940.00, 2020.00, 1020.00, '2014-11-20', '2025-05-20',"
                + " '2025-05-20', 0, 0, '10001', 'A000000000')");
        jdbc.update("INSERT INTO customers VALUES (1, 'Immanuel', 'Madeline', 'Kessler', '618 Deshaun Route',"
                + " 'Apt. 802', 'Altenwerthshire', 'NY', 'USA', '10001', '(212)555-0100', '(212)555-0101', 20973888,"
                + " '00000000000049368437', '1961-06-08', '0053581756', 'Y', 750)");
        jdbc.update("INSERT INTO card_xref VALUES ('0500024453765740', 1, 1)");
    }

    @AfterEach
    void dropFault() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_customer_update ON customers");
    }

    @Test
    void anEditConfirmedWithF5IsSavedToBothRecords() {
        Conversation conversation = confirmed();

        Outcome.Show saved = show(program.receive(conversation, Aid.PF5, Map.of()));

        assertThat(saved.message()).isNull();
        assertThat(saved.fields()).containsEntry("INFOMSG", "Changes committed to database");
        Account account = accounts.find(1).orElseThrow();
        assertThat(account.creditLimit()).isEqualByComparingTo("2500.00");
        assertThat(account.groupId()).isEqualTo("B000000000");
        assertThat(account.addressZip()).isEqualTo("10001");
        assertThat(customers.find(1).orElseThrow().lastName()).isEqualTo("Kesler");
    }

    @Test
    void anAccountLockedElsewhereFailsTheSaveAndWritesNothing() throws Exception {
        Conversation conversation = confirmed();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            accounts.lock(1);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Outcome.Show failed = show(program.receive(conversation, Aid.PF5, Map.of()));
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(failed.message()).isEqualTo(Message.error("Could not lock account record for update"));
        assertThat(failed.fields()).containsEntry("INFOMSG", "Changes unsuccessful. Please try again");
        assertThat(accounts.find(1).orElseThrow().creditLimit()).isEqualByComparingTo("2020.00");
    }

    @Test
    void aFailedCustomerWriteRollsBackTheAccountWrite() {
        Conversation conversation = confirmed();
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_update() RETURNS trigger LANGUAGE plpgsql AS"
                + " $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER fail_customer_update BEFORE UPDATE ON customers"
                + " FOR EACH ROW EXECUTE FUNCTION fail_update()");

        Outcome.Show failed = show(program.receive(conversation, Aid.PF5, Map.of()));

        assertThat(failed.message()).isEqualTo(Message.error("Update of record failed"));
        assertThat(accounts.find(1).orElseThrow().creditLimit()).isEqualByComparingTo("2020.00");
        assertThat(customers.find(1).orElseThrow().lastName()).isEqualTo("Kessler");
    }

    /** Fetches account 1, changes credit limit, group and last name, and returns the conversation in CONFIRM. */
    private Conversation confirmed() {
        Conversation conversation = new Conversation(new SignedOnUser("ADMIN001", UserType.ADMIN), Program.COMEN01C);
        program.arrive(conversation);
        Map<String, String> screen = new HashMap<>(
                show(program.receive(conversation, Aid.ENTER, Map.of("ACCTSID", "00000000001"))).fields());
        screen.put("ACRDLIM", "$2,500");
        screen.put("AADDGRP", "B000000000");
        screen.put("ACSLNAM", "Kesler");
        Outcome.Show confirm = show(program.receive(conversation, Aid.ENTER, screen));
        assertThat(confirm.fields()).containsEntry("INFOMSG", "Changes validated.Press F5 to save")
                .containsEntry("ACRDLIM", "+      2,500.00");
        return conversation;
    }

    private static Outcome.Show show(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Show.class);
        return (Outcome.Show) outcome;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
