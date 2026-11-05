/*
 * Stand-in for Language Environment CEEDAYS, the only LE service CardDemo's
 * online path calls (through CSUTLDTC). Supports pictures built from YYYY, MM,
 * DD and literal separators, and returns the condition tokens CSUTLDTC tests.
 * Edge behavior of real LE (blank padding, era pictures) is not reproduced.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#pragma pack(push, 1)
struct vstring { uint8_t len_be[2]; char text[256]; };
#pragma pack(pop)

static const unsigned char CEE_FACILITY[4] = {0x59, 0xC3, 0xC5, 0xC5};

static void feedback(unsigned char *fc, int msgno) {
    memset(fc, 0, 12);
    if (!msgno) return;
    fc[1] = 3;
    fc[2] = (unsigned char)(msgno >> 8);
    fc[3] = (unsigned char)msgno;
    memcpy(fc + 4, CEE_FACILITY, 4);
}

static int vlen(const struct vstring *v) { return (v->len_be[0] << 8) | v->len_be[1]; }

static int leap(int y) { return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0; }

static long lilian(int y, int m, int d) {
    static const int cum[] = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
    long days = 0;
    for (int yy = 1583; yy < y; yy++) days += leap(yy) ? 366 : 365;
    long doy = cum[m - 1] + d + (m > 2 && leap(y));
    /* Day 1 is 15 October 1582; 1582 contributes the days after 14 October. */
    if (y == 1582) return doy - 287;
    return 78 + days + doy;
}

int CEEDAYS(struct vstring *date, struct vstring *pic, unsigned char *out, unsigned char *fc) {
    int dl = vlen(date), pl = vlen(pic);
    int y = -1, m = -1, d = -1;
    memset(out, 0, 4);
    while (pl > 0 && pic->text[pl - 1] == ' ') pl--;
    if (pl == 0) { feedback(fc, 0x09D6); return 0; }
    if (dl < pl) { feedback(fc, 0x09CB); return 0; }
    for (int i = 0; i < pl;) {
        int *slot = NULL, width = 0;
        if (!strncmp(pic->text + i, "YYYY", 4)) { slot = &y; width = 4; }
        else if (!strncmp(pic->text + i, "MM", 2)) { slot = &m; width = 2; }
        else if (!strncmp(pic->text + i, "DD", 2)) { slot = &d; width = 2; }
        if (!slot) {
            if (pic->text[i] >= 'A' && pic->text[i] <= 'Z') { feedback(fc, 0x09D6); return 0; }
            i++;
            continue;
        }
        int v = 0;
        for (int k = 0; k < width; k++) {
            char c = date->text[i + k];
            if (c < '0' || c > '9') { feedback(fc, 0x09D8); return 0; }
            v = v * 10 + (c - '0');
        }
        *slot = v;
        i += width;
    }
    if (y < 0 || m < 0 || d < 0) { feedback(fc, 0x09CB); return 0; }
    if (m < 1 || m > 12) { feedback(fc, 0x09D5); return 0; }
    static const int mdays[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    int maxd = mdays[m - 1] + (m == 2 && leap(y));
    if (d < 1 || d > maxd) { feedback(fc, 0x09CC); return 0; }
    if (y < 1582 || (y == 1582 && (m < 10 || (m == 10 && d < 15)))) { feedback(fc, 0x09D1); return 0; }
    long l = lilian(y, m, d);
    out[0] = (unsigned char)(l >> 24); out[1] = (unsigned char)(l >> 16);
    out[2] = (unsigned char)(l >> 8);  out[3] = (unsigned char)l;
    feedback(fc, 0);
    return 0;
}

extern int cob_get_num_params(void);

/* CEE3ABD(abend code, timing): end the step with a user abend. The batch runner
   reads the code from stderr and reports it as the step's completion. A call with
   no arguments (CBSTM03A) has no defined code on z/OS, so none is reported. */
int CEE3ABD(unsigned char *code, unsigned char *timing) {
    (void)timing;
    fflush(stdout);
    if (cob_get_num_params() < 1) {
        fprintf(stderr, "HARNESS-ABEND ABEND-NO-CODE\n");
    } else {
        int c = (code[0] << 24) | (code[1] << 16) | (code[2] << 8) | code[3];
        fprintf(stderr, "HARNESS-ABEND U%04d\n", c);
    }
    exit(64);
}
