package com.carddemo.accounts;

import com.carddemo.online.Aid;
import com.carddemo.online.Conversation;
import com.carddemo.online.Message;
import com.carddemo.online.Outcome;
import com.carddemo.online.Program;
import com.carddemo.online.ScreenProgram;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** COACTVWC. It only reads, so it keeps nothing between screens: every key but PF3 looks up the typed id. */
@Component
class AccountView implements ScreenProgram {

    private static final String PROMPT = "Enter or update id of account to display";
    private static final String ID = "ACCTSID";

    private final AccountLookup lookup;

    AccountView(AccountLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Program program() {
        return Program.COACTVWC;
    }

    @Override
    public Outcome arrive(Conversation conversation) {
        return show(new LinkedHashMap<>(), null);
    }

    @Override
    public Outcome receive(Conversation conversation, Aid aid, Map<String, String> fields) {
        if (aid == Aid.PF3) {
            return new Outcome.Transfer(conversation.from().orElse(Program.COMEN01C));
        }
        String typed = fields.getOrDefault(ID, "");
        Map<String, String> shown = new LinkedHashMap<>();
        if (typed.isEmpty() || typed.equals("*")) {
            shown.put(ID, "*");
            return show(shown, Message.error("No input received"));
        }
        shown.put(ID, typed);
        if (!typed.matches("\\d{11}") || Long.parseLong(typed) == 0) {
            return show(shown, Message.error("Account Filter must  be a non-zero 11 digit number"));
        }
        return switch (lookup.find(Long.parseLong(typed))) {
            case AccountLookup.Found found -> {
                account(shown, found.account());
                customer(shown, found.customer());
                yield show(shown, null);
            }
            case AccountLookup.AccountOnly only -> {
                account(shown, only.account());
                yield show(shown, only.message());
            }
            case AccountLookup.Failed failed -> show(shown, failed.message());
        };
    }

    private static Outcome show(Map<String, String> fields, Message message) {
        fields.put("INFOMSG", PROMPT);
        return new Outcome.Show(fields, message, ID);
    }

    private static void account(Map<String, String> fields, Account a) {
        fields.put("ACSTTUS", a.activeStatus());
        fields.put("ACURBAL", Pictures.amount(a.currentBalance()));
        fields.put("ACRDLIM", Pictures.amount(a.creditLimit()));
        fields.put("ACSHLIM", Pictures.amount(a.cashCreditLimit()));
        fields.put("ACRCYCR", Pictures.amount(a.currentCycleCredit()));
        fields.put("ACRCYDB", Pictures.amount(a.currentCycleDebit()));
        fields.put("ADTOPEN", date(a.openDate()));
        fields.put("AEXPDT", date(a.expirationDate()));
        fields.put("AREISDT", date(a.reissueDate()));
        fields.put("AADDGRP", a.groupId());
    }

    private static void customer(Map<String, String> fields, Customer c) {
        String ssn = String.format("%09d", c.ssn());
        fields.put("ACSTNUM", Pictures.customerId(c.id()));
        fields.put("ACSTSSN", ssn.substring(0, 3) + "-" + ssn.substring(3, 5) + "-" + ssn.substring(5));
        fields.put("ACSTFCO", String.format("%03d", c.ficoScore()));
        fields.put("ACSTDOB", date(c.dateOfBirth()));
        fields.put("ACSFNAM", c.firstName());
        fields.put("ACSMNAM", c.middleName());
        fields.put("ACSLNAM", c.lastName());
        fields.put("ACSADL1", c.addressLine1());
        fields.put("ACSADL2", c.addressLine2());
        fields.put("ACSCITY", c.addressLine3());
        fields.put("ACSSTTE", c.stateCode());
        fields.put("ACSZIPC", c.zip());
        fields.put("ACSCTRY", c.countryCode());
        fields.put("ACSPHN1", c.phone1());
        fields.put("ACSPHN2", c.phone2());
        fields.put("ACSGOVT", c.governmentId());
        fields.put("ACSEFTC", c.eftAccountId());
        fields.put("ACSPFLG", c.primaryCardHolder());
    }

    private static String date(LocalDate date) {
        return date == null ? "" : date.toString();
    }
}
