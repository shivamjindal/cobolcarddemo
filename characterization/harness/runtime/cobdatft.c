/*
 * C transliteration of app/asm/COBDATFT.asm, the date reformatter CALLed by
 * CBACT01C. Field offsets follow CODATECN-REC. Only the bytes the assembler
 * moves are written; the rest of the output date is left as the caller set it.
 */
#include <string.h>

int COBDATFT(unsigned char *rec) {
    unsigned char *intype = rec, *in = rec + 1, *outtype = rec + 21, *out = rec + 22;
    unsigned char *errmsg = rec + 42;
    if (*intype == '1' && in[4] != '-' && *outtype != '2') {
        memcpy(out, in, 4);
        out[4] = '-';
        memcpy(out + 5, in + 4, 2);
        out[7] = '-';
        memcpy(out + 8, in + 6, 2);
    } else if (*intype == '2' && *outtype != '1') {
        memcpy(out, in, 4);
        memcpy(out + 4, in + 5, 2);
        memcpy(out + 6, in + 8, 2);
    } else {
        /* The assembler MVCs 38 bytes from a 13-byte literal and so copies whatever
           follows the literal in storage; the harness pads with blanks instead. */
        memset(errmsg, ' ', 38);
        memcpy(errmsg, "INVALID INPUT", 13);
    }
    return 0;
}
