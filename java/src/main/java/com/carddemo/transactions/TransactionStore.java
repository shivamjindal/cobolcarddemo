package com.carddemo.transactions;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.carddemo.accounts.KeyedFile;
import com.carddemo.accounts.Read;
import com.carddemo.cics.CicsFile;
import com.carddemo.cics.CicsRegion;
import com.carddemo.ksds.KeyedBrowse;

/** TRANSACT, table {@code transactions}. Every call runs in the request's transaction, which is the CICS task. */
@Component
public final class TransactionStore {

    private static final Logger log = LoggerFactory.getLogger(TransactionStore.class);

    private static final String COLUMNS = "tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt, "
            + "tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip, tran_card_num, "
            + "tran_orig_ts, tran_proc_ts";

    private static final KeyedBrowse.Table<TransactionRecord> TABLE = new KeyedBrowse.Table<>(CicsFile.TRANSACT,
            "transactions", "tran_id", COLUMNS, TransactionStore::row, TransactionRecord::id);

    private final JdbcClient jdbc;
    private final CicsRegion region;
    private final KeyedFile<TransactionRecord> file;

    public TransactionStore(JdbcClient jdbc, CicsRegion region) {
        this.jdbc = jdbc;
        this.region = region;
        this.file = new KeyedFile<>(jdbc, region, CicsFile.TRANSACT, "transactions", COLUMNS, "tran_id",
                TransactionStore::row);
    }

    /** RESP of a WRITE: DUPLICATE is DUPREC, which the programs test together with DUPKEY; NOTOPEN is FAILED. */
    public enum Insert { INSERTED, DUPLICATE, FAILED }

    /** {@code EXEC CICS READ DATASET(TRANSACT) RIDFLD(tranId)}, the id without trailing blanks. */
    public Read<TransactionRecord> find(String tranId) {
        return file.read(tranId);
    }

    /** A browse for {@code STARTBR DATASET(TRANSACT)}. */
    public KeyedBrowse<TransactionRecord> browse() {
        return new KeyedBrowse<>(jdbc, region, TABLE);
    }

    /** {@code EXEC CICS WRITE DATASET(TRANSACT) RIDFLD(TRAN-ID)}. */
    public Insert write(TransactionRecord record) {
        try {
            if (!region.isOpen(CicsFile.TRANSACT)) {
                return Insert.FAILED;
            }
            int rows = jdbc.sql("insert into transactions (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " on conflict (tran_id) do nothing")
                    .params(record.id(), record.typeCd(), record.catCd(), record.source(), record.desc(), record.amount(),
                            record.merchantId(), record.merchantName(), record.merchantCity(), record.merchantZip(),
                            record.cardNum(), record.origTs(), record.procTs())
                    .update();
            return rows == 1 ? Insert.INSERTED : Insert.DUPLICATE;
        } catch (DataAccessException e) {
            log.warn("WRITE TRANSACT {} failed", record.id(), e);
            return Insert.FAILED;
        }
    }

    private static TransactionRecord row(ResultSet rs, int rowNum) throws SQLException {
        return new TransactionRecord(rs.getString("tran_id"), rs.getString("tran_type_cd"), rs.getInt("tran_cat_cd"),
                rs.getString("tran_source"), rs.getString("tran_desc"), rs.getBigDecimal("tran_amt"),
                rs.getInt("tran_merchant_id"), rs.getString("tran_merchant_name"), rs.getString("tran_merchant_city"),
                rs.getString("tran_merchant_zip"), rs.getString("tran_card_num"), rs.getString("tran_orig_ts"),
                rs.getString("tran_proc_ts"));
    }
}
