package com.carddemo.authorization;

import java.io.Serializable;
import java.util.Arrays;

import com.carddemo.dli.AuthKey;

/**
 * CDEMO-CPVS-INFO, which COPAUS1C reads as CDEMO-CPVD-INFO: the summary's paging and selection, carried through
 * both programs' COMMAREA. A key is {@code null} where the COMMAREA holds SPACES or LOW-VALUES. One task owns a
 * copy and changes it; the stored copy is never shared.
 */
final class AuthorizationPaging implements Serializable {

    static final int PAGES = 20;
    static final int ROWS = 5;

    String selFlag = "";
    AuthKey selected;
    final AuthKey[] prevPages = new AuthKey[PAGES];
    AuthKey last;
    int pageNum;
    boolean nextPage;
    final AuthKey[] authKeys = new AuthKey[ROWS];

    AuthorizationPaging copy() {
        AuthorizationPaging copy = new AuthorizationPaging();
        copy.selFlag = selFlag;
        copy.selected = selected;
        System.arraycopy(prevPages, 0, copy.prevPages, 0, PAGES);
        copy.last = last;
        copy.pageNum = pageNum;
        copy.nextPage = nextPage;
        System.arraycopy(authKeys, 0, copy.authKeys, 0, ROWS);
        return copy;
    }

    @Override
    public String toString() {
        return "AuthorizationPaging[selected=" + selected + ", page=" + pageNum + ", next=" + nextPage + ", last=" + last
                + ", keys=" + Arrays.toString(authKeys) + "]";
    }
}
