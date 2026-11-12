package com.carddemo.accounts;

import com.carddemo.vsam.RecordLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/** CUSTDAT over the customers table; locks per {@link RecordLock}, after the account. */
@Repository
public class CustomerRepository {

    private static final String COLUMNS = "cust_id, cust_first_name, cust_middle_name, cust_last_name,"
            + " cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, cust_addr_country_cd,"
            + " cust_addr_zip, cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id,"
            + " cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind, cust_fico_credit_score";

    private static final RowMapper<Customer> ROW = (rs, n) -> new Customer(rs.getInt(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getInt(13), rs.getString(14),
            rs.getObject(15, LocalDate.class), rs.getString(16), rs.getString(17), rs.getInt(18));

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> find(int id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customers WHERE cust_id = ?", ROW, id).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Customer> lock(int id) {
        return RecordLock.read(jdbc, "SELECT " + COLUMNS + " FROM customers WHERE cust_id = ? FOR UPDATE", ROW, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rewrite(Customer c) {
        RecordLock.expectOne(jdbc.update("UPDATE customers SET cust_first_name = ?, cust_middle_name = ?,"
                        + " cust_last_name = ?, cust_addr_line_1 = ?, cust_addr_line_2 = ?, cust_addr_line_3 = ?,"
                        + " cust_addr_state_cd = ?, cust_addr_country_cd = ?, cust_addr_zip = ?, cust_phone_num_1 = ?,"
                        + " cust_phone_num_2 = ?, cust_ssn = ?, cust_govt_issued_id = ?, cust_dob_yyyy_mm_dd = ?,"
                        + " cust_eft_account_id = ?, cust_pri_card_holder_ind = ?, cust_fico_credit_score = ?"
                        + " WHERE cust_id = ?",
                c.firstName(), c.middleName(), c.lastName(), c.addressLine1(), c.addressLine2(), c.addressLine3(),
                c.stateCode(), c.countryCode(), c.zip(), c.phone1(), c.phone2(), c.ssn(), c.governmentId(),
                c.dateOfBirth(), c.eftAccountId(), c.primaryCardHolder(), c.ficoScore(), c.id()), "customer " + c.id());
    }
}
