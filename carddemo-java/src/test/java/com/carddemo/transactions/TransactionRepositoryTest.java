package com.carddemo.transactions;

import com.carddemo.online.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestDatabase.class)
class TransactionRepositoryTest {

    @Autowired
    TransactionRepository transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void lastIdIs22() {
        jdbc.execute("TRUNCATE transactions");
        tx.executeWithoutResult(s -> transactions.add(id -> record("0000000000000022")));
    }

    @Test
    void anAddOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> transactions.add(TransactionRepositoryTest::record))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theNewIdIsTheHighestNumericIdPlusOne() {
        jdbc.update("INSERT INTO transactions SELECT 'BATCH-ID-NOT-NUM', tran_type_cd, tran_cat_cd, tran_source, tran_desc,"
                + " tran_amt, tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip, tran_card_num,"
                + " tran_orig_ts, tran_proc_ts FROM transactions");

        Transaction added = tx.execute(s -> transactions.add(TransactionRepositoryTest::record));

        assertThat(added.id()).isEqualTo("0000000000000023");
        assertThat(transactions.find("0000000000000023")).contains(added);
    }

    @Test
    void concurrentAddsGetDistinctConsecutiveIds() throws Exception {
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Transaction>> adds = IntStream.range(0, writers).mapToObj(i -> pool.submit(() -> {
                start.await();
                return tx.execute(s -> transactions.add(TransactionRepositoryTest::record));
            })).toList();
            start.countDown();
            for (Future<Transaction> add : adds) {
                add.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(jdbc.queryForList("SELECT tran_id FROM transactions ORDER BY tran_id", String.class))
                .containsExactlyElementsOf(IntStream.rangeClosed(22, 22 + writers).mapToObj("%016d"::formatted).toList());
    }

    private static Transaction record(String id) {
        return new Transaction(id, "01", 1, "POS TERM", "TEST", new BigDecimal("1.00"), 1, "SHOP", "CITY", "00000",
                "4111111111111111", LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0));
    }
}
