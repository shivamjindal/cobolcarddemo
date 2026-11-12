package com.carddemo.batch;

import com.carddemo.vsam.Table;
import com.carddemo.vsam.VsamDataset;
import org.springframework.batch.core.JobParameters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * READCARD (CBACT02C), READXREF (CBACT03C) and READCUST (CBCUS01C): DISPLAY every record of a master file in
 * key order. CBACT03C and CBCUS01C display each record twice, once as they read it and again in their main loop.
 */
record MasterFileListing(String jobName, String programName, VsamDataset dataset, int copies, JdbcTemplate jdbc)
        implements JclJob {

    @Override
    public void run(JobParameters parameters, StepOutput out) {
        out.display("START OF EXECUTION OF PROGRAM " + programName);
        for (String record : Table.of(dataset).unload(jdbc)) {
            for (int i = 0; i < copies; i++) {
                out.display(record);
            }
        }
        out.display("END OF EXECUTION OF PROGRAM " + programName);
    }

    @Configuration(proxyBeanMethods = false)
    static class Jobs {

        @Bean
        MasterFileListing readCard(JdbcTemplate jdbc) {
            return new MasterFileListing("READCARD", "CBACT02C", VsamDataset.CARDS, 1, jdbc);
        }

        @Bean
        MasterFileListing readXref(JdbcTemplate jdbc) {
            return new MasterFileListing("READXREF", "CBACT03C", VsamDataset.CARD_XREF, 2, jdbc);
        }

        @Bean
        MasterFileListing readCust(JdbcTemplate jdbc) {
            return new MasterFileListing("READCUST", "CBCUS01C", VsamDataset.CUSTOMERS, 2, jdbc);
        }
    }
}
