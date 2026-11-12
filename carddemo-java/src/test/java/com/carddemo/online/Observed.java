package com.carddemo.online;

import java.util.List;
import java.util.Map;

/**
 * What the app did for one replayed case, in the spec's terms. Transfer reads "XCTL COADM01C",
 * "RETURN-TRANSID CU00" or "SEND-TEXT null"; a sign-off or a 401 reads as "XCTL COSGN00C".
 */
record Observed(int status, String transfer, String message, String cursor, Map<String, String> fields,
                Conversation conversation, List<Datasets.Effect> fileEffects) {
}
