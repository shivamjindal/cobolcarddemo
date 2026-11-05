package com.carddemo.accounts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** CUSTDAT, table {@code customers}. */
@Component
public final class CustomerStore {

    private static final String COLUMNS = "cust_id, cust_first_name, cust_middle_name, cust_last_name, "
            + "cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, cust_addr_country_cd, "
            + "cust_addr_zip, cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id, cust_dob_yyyy_mm_dd, "
            + "cust_eft_account_id, cust_pri_card_holder_ind, cust_fico_credit_score, cust_filler";

    private final KeyedFile<CustomerRecord> file;

    public CustomerStore(JdbcClient jdbc) {
        this.file = new KeyedFile<>(jdbc, "CUSTDAT", "customers", COLUMNS, "cust_id", CustomerStore::row);
    }

    public Read<CustomerRecord> find(int id) {
        return file.read(id);
    }

    public ForUpdate<CustomerRecord> findForUpdate(int id) {
        return file.readForUpdate(id);
    }

    public Write rewrite(Held<CustomerRecord> held, CustomerRecord edited) {
        if (edited.id() != held.record().id()) {
            throw new IllegalArgumentException("REWRITE changes the key " + held.record().id() + " to " + edited.id());
        }
        return file.rewrite(edited.id(), "cust_first_name = ?, cust_middle_name = ?, cust_last_name = ?, "
                        + "cust_addr_line_1 = ?, cust_addr_line_2 = ?, cust_addr_line_3 = ?, cust_addr_state_cd = ?, "
                        + "cust_addr_country_cd = ?, cust_addr_zip = ?, cust_phone_num_1 = ?, cust_phone_num_2 = ?, "
                        + "cust_ssn = ?, cust_govt_issued_id = ?, cust_dob_yyyy_mm_dd = ?, cust_eft_account_id = ?, "
                        + "cust_pri_card_holder_ind = ?, cust_fico_credit_score = ?, cust_filler = ?",
                edited.firstName(), edited.middleName(), edited.lastName(), edited.addrLine1(), edited.addrLine2(),
                edited.addrLine3(), edited.stateCd(), edited.countryCd(), edited.addrZip(), edited.phoneNum1(),
                edited.phoneNum2(), edited.ssn(), edited.govtIssuedId(), edited.dob(), edited.eftAccountId(),
                edited.priCardHolderInd(), edited.ficoCreditScore(), edited.filler());
    }

    private static CustomerRecord row(ResultSet rs, int rowNum) throws SQLException {
        return new CustomerRecord(rs.getInt("cust_id"), rs.getString("cust_first_name"),
                rs.getString("cust_middle_name"), rs.getString("cust_last_name"), rs.getString("cust_addr_line_1"),
                rs.getString("cust_addr_line_2"), rs.getString("cust_addr_line_3"), rs.getString("cust_addr_state_cd"),
                rs.getString("cust_addr_country_cd"), rs.getString("cust_addr_zip"), rs.getString("cust_phone_num_1"),
                rs.getString("cust_phone_num_2"), rs.getInt("cust_ssn"), rs.getString("cust_govt_issued_id"),
                rs.getObject("cust_dob_yyyy_mm_dd", LocalDate.class), rs.getString("cust_eft_account_id"),
                rs.getString("cust_pri_card_holder_ind"), rs.getInt("cust_fico_credit_score"),
                rs.getString("cust_filler"));
    }
}
