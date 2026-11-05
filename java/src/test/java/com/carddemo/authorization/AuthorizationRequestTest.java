package com.carddemo.authorization;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class AuthorizationRequestTest {

    private static final String APPROVED = "261105,093000,9680294154603697,0100,1128,0100,POS001,000000,25.00,5411,"
            + "USA,01,MER000000000001,FRESH MART,SEATTLE,WA,98101,TXN000000000001";

    @Test
    void unstringsEighteenFieldsIntoTheirStorage() {
        AuthorizationRequest request = new AuthorizationRequest();
        request.unstring(APPROVED.getBytes(ISO_8859_1));

        assertEquals("9680294154603697", request.text(AuthorizationRequest.CARD_NUM));
        assertEquals("FRESH MART            ", request.text(AuthorizationRequest.MERCHANT_NAME));
        assertEquals("FRESH MART", request.segmentText(AuthorizationRequest.MERCHANT_NAME));
        assertEquals(1, request.number(AuthorizationRequest.POS_ENTRY_MODE));
        assertEquals(new BigDecimal("25.00"), request.transactionAmt());
    }

    @Test
    void fieldsARequestDoesNotReachKeepTheirLastValue() {
        AuthorizationRequest request = new AuthorizationRequest();
        request.unstring("GARBAGE REQUEST".getBytes(ISO_8859_1));
        assertEquals("GARBAG", request.text(AuthorizationRequest.AUTH_DATE));
        assertEquals("\0".repeat(16), request.text(AuthorizationRequest.CARD_NUM));
        assertEquals(BigDecimal.ZERO.setScale(2), request.transactionAmt());

        request.unstring(APPROVED.getBytes(ISO_8859_1));
        request.unstring("261106,101500,4444333322221111".getBytes(ISO_8859_1));
        assertEquals("4444333322221111", request.text(AuthorizationRequest.CARD_NUM));
        assertEquals("TXN000000000001", request.text(AuthorizationRequest.TRANSACTION_ID));
        assertEquals(new BigDecimal("25.00"), request.transactionAmt());
    }

    @Test
    void numvalReadsSignsAndBlanksAndIsZeroWhenInvalid() {
        assertEquals(new BigDecimal("12.5"), AuthorizationRequest.numval("  12.5  "));
        assertEquals(new BigDecimal("-3.00"), AuthorizationRequest.numval("-3.00"));
        assertEquals(new BigDecimal("-3.00"), AuthorizationRequest.numval("3.00-"));
        assertEquals(new BigDecimal("-7"), AuthorizationRequest.numval("7 CR"));
        assertEquals(BigDecimal.ZERO, AuthorizationRequest.numval("\0\0\0"));
        assertEquals(BigDecimal.ZERO, AuthorizationRequest.numval("12A"));
    }

    @Test
    void theReplyAmountIsTheFourteenCharacterEditPicture() {
        assertEquals("         25.00", AuthPicture.signed(new BigDecimal("25.00"), 10));
        assertEquals("          0.00", AuthPicture.signed(BigDecimal.ZERO, 10));
        assertEquals("-         5.00", AuthPicture.signed(new BigDecimal("-5.00"), 10));
    }
}
