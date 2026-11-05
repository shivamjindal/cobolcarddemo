package com.carddemo.account;

import static com.carddemo.account.Moves.move;
import static com.carddemo.online.maps.CACTVWA.AADDGRP;
import static com.carddemo.online.maps.CACTVWA.ACCTSID;
import static com.carddemo.online.maps.CACTVWA.ACRCYCR;
import static com.carddemo.online.maps.CACTVWA.ACRCYDB;
import static com.carddemo.online.maps.CACTVWA.ACRDLIM;
import static com.carddemo.online.maps.CACTVWA.ACSADL1;
import static com.carddemo.online.maps.CACTVWA.ACSADL2;
import static com.carddemo.online.maps.CACTVWA.ACSCITY;
import static com.carddemo.online.maps.CACTVWA.ACSCTRY;
import static com.carddemo.online.maps.CACTVWA.ACSEFTC;
import static com.carddemo.online.maps.CACTVWA.ACSFNAM;
import static com.carddemo.online.maps.CACTVWA.ACSGOVT;
import static com.carddemo.online.maps.CACTVWA.ACSHLIM;
import static com.carddemo.online.maps.CACTVWA.ACSLNAM;
import static com.carddemo.online.maps.CACTVWA.ACSMNAM;
import static com.carddemo.online.maps.CACTVWA.ACSPFLG;
import static com.carddemo.online.maps.CACTVWA.ACSPHN1;
import static com.carddemo.online.maps.CACTVWA.ACSPHN2;
import static com.carddemo.online.maps.CACTVWA.ACSSTTE;
import static com.carddemo.online.maps.CACTVWA.ACSTDOB;
import static com.carddemo.online.maps.CACTVWA.ACSTFCO;
import static com.carddemo.online.maps.CACTVWA.ACSTNUM;
import static com.carddemo.online.maps.CACTVWA.ACSTSSN;
import static com.carddemo.online.maps.CACTVWA.ACSTTUS;
import static com.carddemo.online.maps.CACTVWA.ACSZIPC;
import static com.carddemo.online.maps.CACTVWA.ACURBAL;
import static com.carddemo.online.maps.CACTVWA.ADTOPEN;
import static com.carddemo.online.maps.CACTVWA.AEXPDT;
import static com.carddemo.online.maps.CACTVWA.AREISDT;
import static com.carddemo.online.maps.CACTVWA.ERRMSG;
import static com.carddemo.online.maps.CACTVWA.INFOMSG;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.carddemo.LegacyDefect;
import com.carddemo.accounts.AccountRecord;
import com.carddemo.accounts.AccountStore;
import com.carddemo.accounts.CardXrefStore;
import com.carddemo.accounts.CustomerRecord;
import com.carddemo.accounts.CustomerStore;
import com.carddemo.accounts.Read;
import com.carddemo.accounts.XrefRecord;
import com.carddemo.menu.MainMenuScreen;
import com.carddemo.online.Access;
import com.carddemo.online.Arrival;
import com.carddemo.online.Color;
import com.carddemo.online.FieldAttribute;
import com.carddemo.online.Key;
import com.carddemo.online.NoHandoff;
import com.carddemo.online.Outcome;
import com.carddemo.online.ProgramName;
import com.carddemo.online.Screen;
import com.carddemo.online.ScreenAction;
import com.carddemo.online.ScreenRef;
import com.carddemo.online.ScreenState;
import com.carddemo.online.SymbolicMap;
import com.carddemo.online.maps.CACTVWA;

/**
 * COACTVWC. ENTER finds the account's customer through the card cross-reference, then reads the
 * account and the customer; every key but PF3 does the same.
 */
@Component
final class AccountViewScreen extends Screen<CACTVWA, AccountViewScreen.Action, AccountViewScreen.State, NoHandoff> {

    static final ScreenRef<NoHandoff> REF = ScreenRef.of("COACTVWC", "CAVW", "account-view", NoHandoff.class);

    private static final String PROMPT = "Enter or update id of account to display";
    private static final Pattern ELEVEN_DIGITS = Pattern.compile("\\d{11}");

    enum Action implements ScreenAction {
        LOOKUP(Key.ENTER), BACK(Key.PF3),
        /** CSSTRPFY folds PF13 to PF24 onto PF1 to PF12. */
        BACK_SHIFTED(Key.PF15);

        private final Key key;

        Action(Key key) {
            this.key = key;
        }

        @Override
        public Key key() {
            return key;
        }
    }

    /**
     * @param returnTo CDEMO-FROM-PROGRAM, where PF3 goes; the main menu's XCTL makes COACTVWC
     *                 initialize the COMMAREA, so from the menu it is the menu again
     */
    record State(ProgramName returnTo) implements ScreenState {}

    private final CardXrefStore xrefs;
    private final AccountStore accounts;
    private final CustomerStore customers;

    AccountViewScreen(CardXrefStore xrefs, AccountStore accounts, CustomerStore customers) {
        super(REF, CACTVWA.MAP, Action.class, State.class, Access.SIGNED_ON);
        this.xrefs = xrefs;
        this.accounts = accounts;
        this.customers = customers;
    }

    @Override
    protected Outcome enter(Arrival<NoHandoff> arrival) {
        return sendMap(SymbolicMap.lowValues(CACTVWA.MAP), new ReturnMessage(),
                new State(arrival.from().orElse(MainMenuScreen.REF.program())));
    }

    @Override
    protected Outcome act(Action action, SymbolicMap<CACTVWA> map, State state) {
        return switch (action) {
            case LOOKUP -> processInputs(map, state);
            case BACK, BACK_SHIFTED -> transfer(state.returnTo());
        };
    }

    @LegacyDefect(id = "COACTVWC-any-key-looks-up", goldens = "account-view-invalid-key-acts-as-enter",
            behavior = "a key other than ENTER and PF3 is taken as ENTER and looks the typed account up",
            fix = "redisplay the screen with the invalid key message, as the menus do")
    @Override
    protected Outcome invalidKey(SymbolicMap<CACTVWA> map, State state) {
        return processInputs(map, state);
    }

    private Outcome processInputs(SymbolicMap<CACTVWA> received, State state) {
        SymbolicMap<CACTVWA> map = SymbolicMap.lowValues(CACTVWA.MAP);
        ReturnMessage message = new ReturnMessage();
        String typed = received.text(ACCTSID);
        if (typed.isEmpty() || typed.equals("*")) {
            message.replace(blankAccountMessage());
            return sendMap(map.set(ACCTSID, "*").color(ACCTSID, Color.RED), message, state);
        }
        map.set(ACCTSID, typed);
        if (!ELEVEN_DIGITS.matcher(typed).matches() || Long.parseLong(typed) == 0) {
            message.offer(notAnAccountNumber());
            return sendMap(map.color(ACCTSID, Color.RED), message, state);
        }
        return switch (xrefs.findByAccount(Long.parseLong(typed))) {
            case Read.Found<XrefRecord> found -> readAccountAndCustomer(map, found.record(), message, state);
            case Read.Missing<XrefRecord> missing -> {
                message.offer("Account:" + typed + " not found in Cross ref file.  Resp:"
                        + ReturnMessage.code(ReturnMessage.NOTFND) + " Reas:" + ReturnMessage.code(0));
                yield sendMap(map.color(ACCTSID, Color.RED), message, state);
            }
            case Read.Failed<XrefRecord> failed -> {
                message.replace(ReturnMessage.fileError("READ", "CXACAIX", failed.resp(), failed.resp2()));
                yield sendMap(map.color(ACCTSID, Color.RED), message, state);
            }
        };
    }

    @LegacyDefect(id = "COACTVWC-customer-without-account", goldens = "account-view-not-in-acctdat",
            behavior = "an account missing from ACCTDAT, or whose READ fails, still reads and shows its customer, "
                    + "because 9000-READ-ACCT tests a message 88 that nothing sets; COACTVWC also shows account "
                    + "fields from a record it never read, which this port leaves blank",
            fix = "stop after the failed account READ")
    private Outcome readAccountAndCustomer(SymbolicMap<CACTVWA> map, XrefRecord xref, ReturnMessage message,
            State state) {
        Optional<AccountRecord> account = switch (accounts.find(xref.acctId())) {
            case Read.Found<AccountRecord> found -> Optional.of(found.record());
            case Read.Missing<AccountRecord> missing -> {
                message.offer("Account:" + map.text(ACCTSID) + " not found in Acct Master file.Resp:"
                        + ReturnMessage.code(ReturnMessage.NOTFND) + " Reas:" + ReturnMessage.code(0));
                map.color(ACCTSID, Color.RED);
                yield Optional.empty();
            }
            case Read.Failed<AccountRecord> failed -> {
                message.replace(ReturnMessage.fileError("READ", "ACCTDAT", failed.resp(), failed.resp2()));
                map.color(ACCTSID, Color.RED);
                yield Optional.empty();
            }
        };
        Optional<CustomerRecord> customer = switch (customers.find(xref.custId())) {
            case Read.Found<CustomerRecord> found -> Optional.of(found.record());
            case Read.Missing<CustomerRecord> missing -> {
                message.offer("CustId:" + String.format("%09d", xref.custId()) + " not found in customer master.Resp: "
                        + ReturnMessage.code(ReturnMessage.NOTFND) + " REAS:" + ReturnMessage.code(0));
                yield Optional.empty();
            }
            case Read.Failed<CustomerRecord> failed -> {
                message.replace(ReturnMessage.fileError("READ", "CUSTDAT", failed.resp(), failed.resp2()));
                yield Optional.empty();
            }
        };
        account.ifPresent(found -> showAccount(map, found));
        customer.ifPresent(found -> showCustomer(map, found));
        return sendMap(map, message, state);
    }

    private static void showAccount(SymbolicMap<CACTVWA> map, AccountRecord account) {
        move(map, ACSTTUS, account.activeStatus());
        map.set(ACURBAL, AmountPicture.edit(account.currBal()))
                .set(ACRDLIM, AmountPicture.edit(account.creditLimit()))
                .set(ACSHLIM, AmountPicture.edit(account.cashCreditLimit()))
                .set(ACRCYCR, AmountPicture.edit(account.currCycCredit()))
                .set(ACRCYDB, AmountPicture.edit(account.currCycDebit()))
                .set(ADTOPEN, account.openDate().toString())
                .set(AEXPDT, account.expirationDate().toString())
                .set(AREISDT, account.reissueDate().toString());
        move(map, AADDGRP, account.groupId());
    }

    private static void showCustomer(SymbolicMap<CACTVWA> map, CustomerRecord customer) {
        String ssn = String.format("%09d", customer.ssn());
        map.set(ACSTNUM, String.format("%09d", customer.id()))
                .set(ACSTSSN, ssn.substring(0, 3) + "-" + ssn.substring(3, 5) + "-" + ssn.substring(5))
                .set(ACSTFCO, String.format("%03d", customer.ficoCreditScore()))
                .set(ACSTDOB, customer.dob().toString());
        move(map, ACSFNAM, customer.firstName());
        move(map, ACSMNAM, customer.middleName());
        move(map, ACSLNAM, customer.lastName());
        move(map, ACSADL1, customer.addrLine1());
        move(map, ACSADL2, customer.addrLine2());
        move(map, ACSCITY, customer.addrLine3());
        move(map, ACSSTTE, customer.stateCd());
        move(map, ACSZIPC, customer.addrZip());
        move(map, ACSCTRY, customer.countryCd());
        move(map, ACSPHN1, customer.phoneNum1());
        move(map, ACSPHN2, customer.phoneNum2());
        move(map, ACSGOVT, customer.govtIssuedId());
        move(map, ACSEFTC, customer.eftAccountId());
        move(map, ACSPFLG, customer.priCardHolderInd());
    }

    private Outcome sendMap(SymbolicMap<CACTVWA> map, ReturnMessage message, State state) {
        return show(map.set(ERRMSG, message.text())
                .set(INFOMSG, infoLine())
                .color(INFOMSG, Color.NEUTR)
                .attribute(ACCTSID, FieldAttribute.BMFSE)
                .cursor(ACCTSID), state);
    }

    @LegacyDefect(id = "COACTVWC-blank-account-message", goldens = {"account-view-blank-account", "account-view-star-account"},
            behavior = "a blank or '*' account sets 'Account number not provided', then the cross-field edit "
                    + "overwrites it with 'No input received'",
            fix = "keep 'Account number not provided'")
    private static String blankAccountMessage() {
        return "No input received";
    }

    @LegacyDefect(id = "COACTVWC-filter-message-double-space",
            goldens = {"account-view-non-numeric", "account-view-zero-account", "account-view-short-account"},
            behavior = "the message for an account id that is not 11 digits has two spaces in 'must  be'",
            fix = "one space")
    private static String notAnAccountNumber() {
        return "Account Filter must  be a non-zero 11 digit number";
    }

    @LegacyDefect(id = "COACTVWC-info-line-never-informs", goldens = "account-view-success",
            behavior = "the info line keeps asking for an account id after a lookup that found it; 9000-READ-ACCT "
                    + "clears WS-INFO-MSG and nothing sets 'Displaying details of given Account'",
            fix = "show 'Displaying details of given Account' when the account was found")
    private static String infoLine() {
        return PROMPT;
    }
}
