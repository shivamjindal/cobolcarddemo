package com.carddemo.characterization;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.TestDatabase;
import com.carddemo.characterization.BatchCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An eleventh transaction on a card is stored past the ten-row table and a later card overwrites it.
 * The expected lines are the legacy harness observation for that input.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"carddemo.batch.drivers.enabled=true", "carddemo.batch.reader.poll=PT0.05S"})
@DirtiesContext
class StatementOverflowIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CARD_A = "1111111111111111";
    private static final String CARD_B = "2222222222222222";
    private static final String OVERWRITTEN =
            "2222222222222222 010001POS TERM  Second card                      $         .00";
    private static final String TOTAL_55 = "Total EXP:                                                        $       55.00";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(LocalDateTime.of(2026, 11, 5, 10, 0).atZone(ZoneId.systemDefault()).toInstant(),
                    ZoneId.systemDefault());
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Clock clock;

    @Test
    void anEleventhTransactionIsOverwrittenByTheNextCard() throws Exception {
        Map<String, Object> observation = BatchCase.of(request()).run(dataSource, clock);

        assertEquals("RC=0", observation.get("completion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) observation.get("outputs");
        @SuppressWarnings("unchecked")
        List<String> text = (List<String>) outputs.get("STMTFILE");
        @SuppressWarnings("unchecked")
        List<String> html = (List<String>) outputs.get("HTMLFILE");
        assertTrue(text.contains(OVERWRITTEN), text.toString());
        assertTrue(text.contains(TOTAL_55), text.toString());
        assertFalse(text.stream().anyMatch(line -> line.contains("0000000000000011 Row 11")));
        assertTrue(html.contains("<p>2222222222222222</p>"));
        assertFalse(html.contains("<p>0000000000000011</p>"));
    }

    private static ObjectNode request() {
        ObjectNode request = JSON.createObjectNode();
        request.put("kind", "batch");
        request.put("feature", "batch-statements");
        request.put("datasets", java.nio.file.Path.of("..", "characterization", "fixtures", "datasets.json")
                .toAbsolutePath().normalize().toString());
        ObjectNode testCase = request.putObject("case");
        testCase.put("id", "stmt-eleven-then-next-card");
        ObjectNode setup = testCase.putObject("setup");
        ArrayNode transactions = setup.putObject("TRANSACT").putArray("replace");
        for (int i = 1; i <= 11; i++) {
            transactions.add(posted(i, CARD_A, i + ".00", "Row " + i, "2022-03-01 11:00:00.000000"));
        }
        transactions.add(posted(12, CARD_B, "50.00", "Second card", "2022-03-02 11:00:00.000000"));
        ArrayNode xrefs = setup.putObject("CARDXREF").putArray("replace");
        xrefs.add(xref(CARD_A, 1, 1));
        xrefs.add(xref(CARD_B, 2, 2));
        ObjectNode job = testCase.putObject("job");
        job.put("program", "CBSTM03A");
        ObjectNode dd = job.putObject("dd");
        dd.putObject("TRNXFILE").put("derive", "creastmt-trxfl");
        dd.put("XREFFILE", "CARDXREF");
        dd.put("CUSTFILE", "CUSTDATA");
        dd.put("ACCTFILE", "ACCTDATA");
        dd.putObject("STMTFILE").put("output", "STMTFILE").put("reclen", 80);
        dd.putObject("HTMLFILE").put("output", "HTMLFILE").put("reclen", 100);
        return request;
    }

    private static ObjectNode posted(int id, String card, String amount, String description, String processed) {
        ObjectNode row = JSON.createObjectNode();
        row.put("TRAN-ID", "%016d".formatted(id));
        row.put("TRAN-TYPE-CD", "01");
        row.put("TRAN-CAT-CD", 1);
        row.put("TRAN-SOURCE", "POS TERM");
        row.put("TRAN-DESC", description);
        row.put("TRAN-AMT", amount);
        row.put("TRAN-MERCHANT-ID", 800000001);
        row.put("TRAN-MERCHANT-NAME", "Harness Store");
        row.put("TRAN-MERCHANT-CITY", "Springfield");
        row.put("TRAN-MERCHANT-ZIP", "12345");
        row.put("TRAN-CARD-NUM", card);
        row.put("TRAN-ORIG-TS", processed);
        row.put("TRAN-PROC-TS", processed);
        return row;
    }

    private static ObjectNode xref(String card, int customer, int account) {
        ObjectNode row = JSON.createObjectNode();
        row.put("XREF-CARD-NUM", card);
        row.put("XREF-CUST-ID", customer);
        row.put("XREF-ACCT-ID", account);
        return row;
    }
}
