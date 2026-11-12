package com.carddemo.authorizations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Replaces DBPAUTP0 with the module's unload when the app seeds its sample data. This is what PAUDBUNL and
 * DBUNLDGS (unload) and PAUDBLOD (reload) are for on the mainframe, so those programs are not ported.
 */
@Component
@ConditionalOnProperty("carddemo.seed.dir")
class PendingAuthorizationSeed implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PendingAuthorizationSeed.class);

    private final JdbcTemplate jdbc;
    private final PendingAuthorizations pending;
    private final TransactionTemplate transaction;
    private final String unload;

    // Bound as a String for the same reason as carddemo.seed.dir in CardDemoApplication.
    PendingAuthorizationSeed(JdbcTemplate jdbc, PendingAuthorizations pending, TransactionTemplate transaction,
                             @Value("${carddemo.seed.pending-authorizations:${carddemo.seed.dir}/../../"
                                     + "app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat}")
                             String unload) {
        this.jdbc = jdbc;
        this.pending = pending;
        this.transaction = transaction;
        this.unload = unload;
    }

    @Override
    public void run(String... args) throws IOException {
        Path file = Path.of(unload).normalize();
        if (!Files.exists(file)) {
            log.info("No pending authorization unload at {}", file);
            return;
        }
        load(file);
    }

    ImsUnload.Contents load(Path file) throws IOException {
        ImsUnload.Contents contents = ImsUnload.read(Files.readAllBytes(file));
        transaction.executeWithoutResult(status -> {
            jdbc.execute("TRUNCATE pending_auth_summaries, pending_auth_details");
            contents.summaries().forEach(pending::save);
            contents.details().forEach(pending::insert);
        });
        log.info("Loaded {} pending authorization summaries and {} details ({} undecodable segments skipped)",
                contents.summaries().size(), contents.details().size(), contents.skipped());
        return contents;
    }
}
