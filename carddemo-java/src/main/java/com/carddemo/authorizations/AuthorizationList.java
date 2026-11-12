package com.carddemo.authorizations;

import com.carddemo.accounts.Account;
import com.carddemo.accounts.AccountRepository;
import com.carddemo.accounts.CardXref;
import com.carddemo.accounts.CardXrefRepository;
import com.carddemo.accounts.Customer;
import com.carddemo.accounts.CustomerRepository;
import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * COPAUS0C (CPVS): an account's customer, limits and pending totals, and its authorizations five a page, newest
 * first, with S to open one in COPAUS1C. PF7 goes back a page and PF8 forward.
 */
@Component
class AuthorizationList implements ScreenProgram {

    private static final int ROWS = 5;
    private static final String NOT_FOUND = " Resp:000000013 Reas:000000080";

    private final CardXrefRepository xrefs;
    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final PendingAuthorizations pending;

    AuthorizationList(CardXrefRepository xrefs, AccountRepository accounts, CustomerRepository customers,
                      PendingAuthorizations pending) {
        this.xrefs = xrefs;
        this.accounts = accounts;
        this.customers = customers;
        this.pending = pending;
    }

    @Override
    public Program program() {
        return Program.COPAUS0C;
    }

    /** Lists page 1 of the account last chosen here, as the legacy CDEMO-ACCT-ID would; none shows an empty list. */
    @Override
    public Outcome arrive(Conversation conversation) {
        conversation.take(AuthorizationSelection.class);
        return conversation.get(AuthorizationListPage.class)
                .map(page -> firstPage(conversation, page.accountId(), null))
                .orElseGet(() -> empty(null));
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        Optional<AuthorizationListPage> current = conversation.get(AuthorizationListPage.class);
        return switch (aid) {
            case ENTER -> enter(conversation, current, fields);
            case PF3 -> new Outcome.Transfer(Program.COMEN01C);
            case PF7 -> backward(conversation, current);
            case PF8 -> forward(conversation, current);
            default -> again(conversation, current, Message.INVALID_KEY, false);
        };
    }

    /** The first marked row wins, and a row with no authorization on it is no selection. */
    private Outcome enter(Conversation conversation, Optional<AuthorizationListPage> current, Map<String, String> fields) {
        String typed = fields.getOrDefault("ACCTID", "");
        if (typed.isBlank()) {
            return empty(Message.error("Please enter Acct Id..."));
        }
        if (!typed.matches("\\d{11}")) {
            return empty(Message.error("Acct Id must be Numeric ..."));
        }
        long accountId = Long.parseLong(typed);
        Message invalid = null;
        for (int row = 1; row <= ROWS; row++) {
            String mark = fields.getOrDefault("SEL%04d".formatted(row), "");
            if (mark.isBlank()) {
                continue;
            }
            List<AuthorizationKey> keys = current.map(AuthorizationListPage::keys).orElse(List.of());
            if (keys.size() < row) {
                break;
            }
            if (mark.equalsIgnoreCase("S")) {
                conversation.put(new AuthorizationSelection(accountId, current.get().customerId(), keys.get(row - 1)));
                return new Outcome.Transfer(Program.COPAUS1C);
            }
            invalid = Message.error("Invalid selection. Valid value is S");
            break;
        }
        return firstPage(conversation, accountId, invalid);
    }

    private Outcome backward(Conversation conversation, Optional<AuthorizationListPage> current) {
        if (current.isEmpty() || current.get().pageNumber() <= 1) {
            return again(conversation, current, Message.info("You are already at the top of the page..."), false);
        }
        AuthorizationListPage page = current.get();
        List<AuthorizationKey> starts = page.pageStarts().subList(0, page.pageNumber() - 1);
        return show(conversation, header(page.accountId()), page.customerId(), starts,
                pending.from(page.accountId(), starts.getLast(), true, ROWS + 1), null);
    }

    /** A next page that turns out empty keeps the current one, where the legacy program blanked the list. */
    private Outcome forward(Conversation conversation, Optional<AuthorizationListPage> current) {
        Message bottom = Message.info("You are already at the bottom of the page...");
        if (current.isEmpty() || !current.get().hasNext() || current.get().last().isEmpty()) {
            return again(conversation, current, bottom, true);
        }
        AuthorizationListPage page = current.get();
        List<Authorization> rows = pending.from(page.accountId(), page.last().get(), false, ROWS + 1);
        if (rows.isEmpty()) {
            return again(conversation, current, bottom, true);
        }
        List<AuthorizationKey> starts = new ArrayList<>(page.pageStarts());
        starts.add(rows.getFirst().key());
        return show(conversation, header(page.accountId()), page.customerId(), starts, rows, null);
    }

    /** The current page again under a message; on the last page PF8 has nowhere to go. */
    private Outcome again(Conversation conversation, Optional<AuthorizationListPage> current, Message message,
                          boolean lastPage) {
        if (current.isEmpty()) {
            return empty(message);
        }
        AuthorizationListPage page = current.get();
        List<Authorization> rows = page.keys().isEmpty() ? List.of()
                : pending.from(page.accountId(), page.keys().getFirst(), true, lastPage ? ROWS : ROWS + 1);
        return show(conversation, header(page.accountId()), page.customerId(), page.pageStarts(), rows, message);
    }

    /** GATHER-DETAILS: page 1 of the account; a lookup's not-found message replaces any other. */
    private Outcome firstPage(Conversation conversation, long accountId, Message message) {
        Header header = header(accountId);
        List<Authorization> rows = header.summaryFound() ? pending.first(accountId, ROWS + 1) : List.of();
        List<AuthorizationKey> starts = rows.isEmpty() ? List.of() : List.of(rows.getFirst().key());
        return show(conversation, header, header.customerId(), starts, rows,
                header.message() != null ? header.message() : message);
    }

    /** Rows holds up to one authorization past the page, which tells whether PF8 has a next page. */
    private Outcome.Show show(Conversation conversation, Header header, int customerId, List<AuthorizationKey> starts,
                              List<Authorization> rows, Message message) {
        List<Authorization> shown = rows.subList(0, Math.min(ROWS, rows.size()));
        conversation.put(new AuthorizationListPage(header.accountId(), customerId, starts,
                shown.stream().map(Authorization::key).toList(), rows.size() > ROWS));
        Map<String, String> fields = new HashMap<>(header.fields());
        for (int i = 0; i < shown.size(); i++) {
            String row = "%02d".formatted(i + 1);
            Authorization a = shown.get(i);
            fields.put("TRNID" + row, a.request().transactionId());
            fields.put("PDATE" + row, Pictures.date(a.request().date()));
            fields.put("PTIME" + row, Pictures.time(a.request().time()));
            fields.put("PTYPE" + row, a.request().authType());
            fields.put("PAPRV" + row, a.approved() ? "A" : "D");
            fields.put("PSTAT" + row, a.matchStatus());
            fields.put("PAMT0" + row, Pictures.minusAmount(a.approvedAmount(), 12));
        }
        return new Outcome.Show(fields, message, "ACCTID");
    }

    private static Outcome.Show empty(Message message) {
        return new Outcome.Show(Map.of(), message, "ACCTID");
    }

    private record Header(long accountId, Map<String, String> fields, Message message, int customerId,
                          boolean summaryFound) {
    }

    /**
     * GATHER-ACCOUNT-DETAILS: CXACAIX by the account, ACCTDAT and CUSTDAT by the xref, the summary by the account.
     * A record not found leaves its fields blank, and the last not-found message is the one shown.
     */
    private Header header(long accountId) {
        Map<String, String> fields = new HashMap<>();
        fields.put("ACCTID", "%011d".formatted(accountId));
        Message message = null;
        int customerId = 0;
        Optional<CardXref> xref = xrefs.findByAccount(accountId);
        if (xref.isEmpty()) {
            message = Message.error("Account:%011d not found in XREF file.".formatted(accountId) + NOT_FOUND);
        } else {
            customerId = xref.get().customerId();
            Optional<Account> account = accounts.find(xref.get().accountId());
            account.ifPresent(a -> {
                fields.put("CREDLIM", Pictures.minusAmount(a.creditLimit(), 12));
                fields.put("CASHLIM", Pictures.minusAmount(a.cashCreditLimit(), 9));
            });
            if (account.isEmpty()) {
                message = Message.error("Account:%011d not found in ACCT file.".formatted(xref.get().accountId()) + NOT_FOUND);
            }
            Optional<Customer> customer = customers.find(customerId);
            customer.ifPresent(c -> fields.putAll(customerFields(c)));
            if (customer.isEmpty()) {
                message = Message.error("Customer:%09d not found in CUST file.".formatted(customerId) + NOT_FOUND);
            }
        }
        Optional<AuthorizationSummary> summary = pending.summary(accountId);
        if (summary.isPresent()) {
            AuthorizationSummary s = summary.get();
            fields.put("APPRCNT", "%03d".formatted(s.approvedCount()));
            fields.put("DECLCNT", "%03d".formatted(s.declinedCount()));
            fields.put("CREDBAL", Pictures.minusAmount(s.creditBalance(), 12));
            fields.put("CASHBAL", Pictures.minusAmount(s.cashBalance(), 9));
            fields.put("APPRAMT", Pictures.minusAmount(s.approvedAmount(), 9));
            fields.put("DECLAMT", Pictures.minusAmount(s.declinedAmount(), 9));
        } else {
            // MOVE ZERO to the alphanumeric map fields fills each with zeros.
            fields.put("APPRCNT", "000");
            fields.put("DECLCNT", "000");
            fields.put("CREDBAL", "0".repeat(12));
            fields.put("CASHBAL", "0".repeat(9));
            fields.put("APPRAMT", "0".repeat(10));
            fields.put("DECLAMT", "0".repeat(10));
        }
        return new Header(accountId, fields, message, customerId, summary.isPresent());
    }

    /** STRINGs of the customer's name and address, cut to the 25-character map fields. */
    private static Map<String, String> customerFields(Customer c) {
        String middle = c.middleName().isEmpty() ? " " : c.middleName().substring(0, 1);
        String name = firstWord(c.firstName()) + " " + middle + " " + firstWord(c.lastName());
        String line1 = upToDoubleSpace(c.addressLine1()) + "," + upToDoubleSpace(c.addressLine2());
        String line2 = upToDoubleSpace(c.addressLine3()) + "," + Pictures.pad(c.stateCode(), 2) + ","
                + Pictures.pad(c.zip(), 5);
        return Map.of("CNAME", cut(name, 25), "CUSTID", "%09d".formatted(c.id()), "ADDR001", cut(line1, 25),
                "ADDR002", cut(line2, 25), "PHONE1", cut(c.phone1(), 13));
    }

    private static String firstWord(String value) {
        return value.split(" ", 2)[0];
    }

    private static String upToDoubleSpace(String value) {
        int end = value.indexOf("  ");
        return end < 0 ? value : value.substring(0, end);
    }

    private static String cut(String value, int width) {
        return value.length() > width ? value.substring(0, width) : value;
    }
}
