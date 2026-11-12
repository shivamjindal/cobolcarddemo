package com.carddemo.transactions;

import com.carddemo.accounts.CardXrefRepository;
import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** COTRN02C. Each ENTER checks the whole screen again; nothing is kept between sends. */
@Component
class TransactionAdd implements ScreenProgram {

    private static final String ACCOUNT = "ACTIDIN";

    private final TransactionRepository transactions;
    private final TransactionTemplate tx;
    private final TransactionEntry.Reads reads;

    TransactionAdd(CardXrefRepository xrefs, TransactionRepository transactions, TransactionTemplate tx) {
        this.transactions = transactions;
        this.tx = tx;
        this.reads = new TransactionEntry.Reads() {
            @Override
            public Optional<String> cardFor(long accountId) {
                try {
                    return xrefs.findByAccount(accountId).map(xref -> xref.cardNumber());
                } catch (DataAccessException e) {
                    throw new TransactionEntry.Unavailable();
                }
            }

            @Override
            public Optional<Long> accountFor(String cardNumber) {
                try {
                    return xrefs.find(cardNumber).map(xref -> xref.accountId());
                } catch (DataAccessException e) {
                    throw new TransactionEntry.Unavailable();
                }
            }
        };
    }

    @Override
    public Program program() {
        return Program.COTRN02C;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return new Outcome.Show(Map.of(), null, ACCOUNT);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        return switch (aid) {
            case ENTER -> submit(fields);
            case PF3 -> new Outcome.Transfer(conversation.from().orElse(Program.COMEN01C));
            case PF4 -> new Outcome.Show(Map.of(), null, ACCOUNT);
            case PF5 -> copyLast(fields);
            default -> new Outcome.Show(new LinkedHashMap<>(fields), Message.INVALID_KEY, ACCOUNT);
        };
    }

    private Outcome submit(Map<String, String> fields) {
        return switch (TransactionEntry.check(fields, reads)) {
            case TransactionEntry.Reject reject -> show(reject);
            case TransactionEntry.Accept accept -> add(accept);
        };
    }

    private Outcome copyLast(Map<String, String> fields) {
        Map<String, String> screen = new LinkedHashMap<>(fields);
        Optional<TransactionEntry.Reject> keyed = TransactionEntry.normalizeKeys(screen, reads);
        if (keyed.isPresent()) {
            return show(keyed.get());
        }
        Optional<Transaction> last;
        try {
            last = transactions.last();
        } catch (DataAccessException e) {
            return error(screen, "Unable to lookup Transaction...");
        }
        // An empty TRANSACT makes the HIGH-VALUES browse NOTFND. The ENDFILE-then-id-1 reading is unverified.
        if (last.isEmpty()) {
            return error(screen, "Transaction ID NOT found...");
        }
        return submit(TransactionEntry.copied(screen, last.get()));
    }

    private Outcome add(TransactionEntry.Accept accepted) {
        TransactionEntry.Draft draft = accepted.draft();
        try {
            Transaction saved = tx.execute(status -> transactions.add(id -> new Transaction(id, draft.typeCode(),
                    draft.categoryCode(), draft.source(), draft.description(), draft.amount(), draft.merchantId(),
                    draft.merchantName(), draft.merchantCity(), draft.merchantZip(), draft.cardNumber(),
                    draft.originated().atStartOfDay(), draft.processed().atStartOfDay())));
            return new Outcome.Show(Map.of(), Message.success(
                    "Transaction added successfully.  Your Tran ID is " + saved.id() + "."), ACCOUNT);
        } catch (DuplicateKeyException e) {
            return new Outcome.Show(accepted.screen(), Message.error("Tran ID already exist..."), ACCOUNT);
        } catch (DataAccessException e) {
            return new Outcome.Show(accepted.screen(), Message.error("Unable to Add Transaction..."), ACCOUNT);
        }
    }

    private static Outcome show(TransactionEntry.Reject reject) {
        return new Outcome.Show(reject.screen(), Message.error(reject.message()), reject.cursor());
    }

    private static Outcome error(Map<String, String> screen, String message) {
        return new Outcome.Show(screen, Message.error(message), ACCOUNT);
    }
}
