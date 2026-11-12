package com.carddemo.accounts;

import com.carddemo.online.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestDatabase.class)
class AccountLockingTest {

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void oneAccount() {
        jdbc.execute("TRUNCATE accounts");
        jdbc.update("INSERT INTO accounts VALUES (1, 'Y', 100.00, 1000.00, 500.00, '2014-11-20', '2025-05-20',"
                + " '2025-05-20', 0, 0, '10001', 'A000000000')");
    }

    @Test
    void readUpdateOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> accounts.lock(1)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> accounts.rewrite(accounts.find(1).orElseThrow()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aHeldLockMakesASecondWriterGiveUpInsteadOfOverwriting() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            Account a = accounts.lock(1).orElseThrow();
            locked.countDown();
            await(release);
            accounts.rewrite(withBalance(a, "150.00"));
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> accounts.lock(1)))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(1500), Duration.ofSeconds(5));

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        assertThat(accounts.find(1).orElseThrow().currentBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void aWriterWaitingOnTheLockSeesTheCommittedRowNotItsStaleCopy() throws Exception {
        Account stale = accounts.find(1).orElseThrow();
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            Account a = accounts.lock(1).orElseThrow();
            locked.countDown();
            sleep(500);
            accounts.rewrite(withBalance(a, "175.00"));
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Account seen = tx.execute(s -> accounts.lock(1).orElseThrow());
        batch.get(10, TimeUnit.SECONDS);
        assertThat(seen).isNotEqualTo(stale);
        assertThat(seen.currentBalance()).isEqualByComparingTo("175.00");
    }

    @Test
    void rewritingAMissingAccountFailsAndRollsBack() {
        Account ghost = withBalance(accounts.find(1).orElseThrow(), "1.00");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            accounts.rewrite(ghost);
            accounts.rewrite(new Account(2, "Y", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, "", ""));
        })).isInstanceOf(DataAccessException.class);
        assertThat(accounts.find(1).orElseThrow().currentBalance()).isEqualByComparingTo("100.00");
    }

    private static Account withBalance(Account a, String balance) {
        return new Account(a.id(), a.activeStatus(), new BigDecimal(balance), a.creditLimit(), a.cashCreditLimit(),
                a.openDate(), a.expirationDate(), a.reissueDate(), a.currentCycleCredit(), a.currentCycleDebit(),
                a.addressZip(), a.groupId());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
