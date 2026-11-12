package com.carddemo.transactions;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.Screens;
import com.carddemo.online.SignedOnUser;
import com.carddemo.online.TestDatabase;
import com.carddemo.online.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestDatabase.class)
class BillPaymentTest {

    private static final String PAID = "Payment successful.  Your Transaction ID is 0000000000000023.";
    private static final String NOTHING_TO_PAY = "You have nothing to pay...";
    private static final String LOCKED = "Unable to lookup Account...";

    @Autowired
    Screens screens;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void accountOwing1500() {
        jdbc.execute("TRUNCATE accounts, card_xref, transactions");
        jdbc.update("INSERT INTO accounts VALUES (1, 'Y', 1500.75, 5000.00, 500.00, '2014-11-20', '2025-05-20',"
                + " '2025-05-20', 0, 0, '10001', 'A000000000')");
        jdbc.update("INSERT INTO card_xref VALUES ('4111111111111111', 1, 1)");
        jdbc.update("INSERT INTO transactions VALUES ('0000000000000022', '01', 1, 'POS TERM', 'PURCHASE', 22.00,"
                + " 1, 'SHOP', 'CITY', '00000', '4111111111111111', '2026-11-01 00:00:00', '2026-11-01 00:00:00')");
    }

    @Test
    void twoConfirmedPaymentsOnOneAccountPayItOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdAccountLock(release);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(this::confirmPayment);
        CompletableFuture<String> second = CompletableFuture.supplyAsync(this::confirmPayment);
        awaitLockWaiters(2);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        List<String> messages = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

        assertThat(messages).filteredOn(PAID::equals).hasSize(1);
        assertThat(messages).filteredOn(m -> !m.equals(PAID)).singleElement().isIn(NOTHING_TO_PAY, LOCKED);
        assertThat(payments()).containsExactly(new BigDecimal("1500.75"));
        assertThat(balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void aFailedBalanceRewriteLeavesNoPaymentBehind() {
        jdbc.execute("CREATE FUNCTION refuse_account_update() RETURNS trigger LANGUAGE plpgsql AS"
                + " $$ BEGIN RAISE EXCEPTION 'accounts are read-only'; END $$");
        jdbc.execute("CREATE TRIGGER refuse_account_update BEFORE UPDATE ON accounts"
                + " FOR EACH ROW EXECUTE FUNCTION refuse_account_update()");
        String message;
        try {
            message = confirmPayment();
        } finally {
            jdbc.execute("DROP TRIGGER refuse_account_update ON accounts");
            jdbc.execute("DROP FUNCTION refuse_account_update()");
        }

        assertThat(message).isEqualTo("Unable to Update Account...");
        assertThat(payments()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1500.75");
    }

    @Test
    void anAccountLockedByAnotherWriterIsNotPaid() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdAccountLock(release);
        String message;
        try {
            message = confirmPayment();
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(message).isEqualTo(LOCKED);
        assertThat(payments()).isEmpty();
        assertThat(balance()).isEqualByComparingTo("1500.75");
    }

    private String confirmPayment() {
        Conversation conversation = new Conversation(new SignedOnUser("USER0001", UserType.USER), Program.COMEN01C);
        Outcome outcome = screens.receive(Program.COBIL00C, conversation, Aid.ENTER,
                Map.of("ACTIDIN", "00000000001", "CONFIRM", "Y"));
        return ((Outcome.Show) outcome).message().text();
    }

    /** Another writer's READ UPDATE on account 1, held until {@code release}. */
    private CompletableFuture<Void> holdAccountLock(CountDownLatch release) throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT acct_id FROM accounts WHERE acct_id = 1 FOR UPDATE");
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).as("holder has the account lock").isTrue();
        return holder;
    }

    private void awaitLockWaiters(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(count + " payments never queued behind the held lock");
    }

    private List<BigDecimal> payments() {
        return jdbc.queryForList("SELECT tran_amt FROM transactions WHERE tran_type_cd = '02'", BigDecimal.class);
    }

    private BigDecimal balance() {
        return jdbc.queryForObject("SELECT acct_curr_bal FROM accounts WHERE acct_id = 1", BigDecimal.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
