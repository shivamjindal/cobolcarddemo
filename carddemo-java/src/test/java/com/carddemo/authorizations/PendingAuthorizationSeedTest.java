package com.carddemo.authorizations;

import com.carddemo.online.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestDatabase.class)
class PendingAuthorizationSeedTest {

    static final Path UNLOAD = Path.of(System.getProperty("carddemo.legacy-app"), "app-authorization-ims-db2-mq",
            "data", "EBCDIC", "AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PendingAuthorizations pending;

    @Autowired
    TransactionTemplate tx;

    @Test
    void theModuleUnloadSeedsEverySegmentThatDecodes() throws IOException {
        ImsUnload.Contents contents = new PendingAuthorizationSeed(jdbc, pending, tx, UNLOAD.toString()).load(UNLOAD);

        assertThat(contents.skipped()).as("the last summary is all spaces").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_auth_summaries", Integer.class)).isEqualTo(21);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_auth_details", Integer.class)).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_auth_details WHERE pa_acct_id = 7", Integer.class))
                .isEqualTo(50);
    }

    @Test
    void summaryAndDetailFieldsRoundTrip() throws IOException {
        new PendingAuthorizationSeed(jdbc, pending, tx, UNLOAD.toString()).load(UNLOAD);

        assertThat(pending.summary(1)).contains(new AuthorizationSummary(1, 1, "", "        00", amount("2022.00"),
                amount("1020.00"), amount("9.44"), amount("0.00"), 6, 0, amount("9.44"), amount("0.00")));
        Authorization newest = pending.first(1, 1).getFirst();
        assertThat(newest.key()).isEqualTo(new AuthorizationKey(76699, 998747444));
        assertThat(newest.request()).isEqualTo(new AuthorizationRequest("231027", "041252", "9680294154603697", "0100",
                "1122", "1234", "102030", 0, amount("1.24"), "5442", "USA", 0, "123501000675423", "Amazon.com",
                "Wilmington", "DE", "19801", "960b9b5480d045a"));
        assertThat(newest).extracting(Authorization::authIdCode, Authorization::responseCode, Authorization::responseReason,
                        Authorization::approvedAmount, Authorization::matchStatus, Authorization::fraud)
                .containsExactly("041252", "00", "0000", amount("1.24"), "P", "");
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
