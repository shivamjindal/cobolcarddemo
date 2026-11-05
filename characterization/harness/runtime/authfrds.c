/*
 * Db2 table CARDDEMO.AUTHFRDS stand-in for COPAUS2C's two statements, over the keyed
 * text dataset <run>/AUTHFRDS.dat (layout characterization/fixtures/copy/AUTHFRDS.cpy,
 * key CARD_NUM + AUTH_TS = bytes 1-42). authfrds_sql.py builds the row with COBOL MOVEs
 * and calls AUTHFRDS(op, row, sqlcode, sqlstate):
 *
 *   INSERT  the row; SQLCODE -803 / 23505 when the key exists
 *   UPDATE  bytes of the row that are not low-values replace the stored row's bytes
 *           (the statement's SET columns and WHERE key); +100 / 02000 when no row matches
 *
 * AUTH_TS arrives as the host variable text, TIMESTAMP_FORMAT(:AUTH-TS,
 * 'YY-MM-DD HH24.MI.SSNNNNNN'), and is stored as the Db2 external timestamp
 * 20YY-MM-DD-HH.MI.SS.NNNNNN. Changes commit with the unit of work.
 */
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "region.h"

#define RECLEN 223
#define KEYLEN 42
#define TS_OFF 16
#define TS_LEN 26

static char (*rows)[RECLEN];
static int nrows, loaded, dirty;

static void load(void) {
    if (loaded) return;
    char path[600], line[1024];
    snprintf(path, sizeof path, "%s/AUTHFRDS.dat", region_dir);
    FILE *in = fopen(path, "r");
    nrows = 0;
    while (in && fgets(line, sizeof line, in)) {
        int l = (int)strcspn(line, "\r\n");
        if (l == 0) continue;
        rows = realloc(rows, sizeof *rows * (nrows + 1));
        memset(rows[nrows], ' ', RECLEN);
        memcpy(rows[nrows], line, l < RECLEN ? l : RECLEN);
        nrows++;
    }
    if (in) fclose(in);
    loaded = 1;
    dirty = 0;
}

static int cmp_row(const void *a, const void *b) { return memcmp(a, b, KEYLEN); }

void afr_commit(void) {
    if (!loaded || !dirty) return;
    qsort(rows, nrows, sizeof *rows, cmp_row);
    char path[600];
    snprintf(path, sizeof path, "%s/AUTHFRDS.dat", region_dir);
    FILE *out = fopen(path, "w");
    for (int i = 0; i < nrows; i++) {
        int l = RECLEN;
        while (l > 0 && rows[i][l - 1] == ' ') l--;
        fwrite(rows[i], 1, l, out);
        fputc('\n', out);
    }
    fclose(out);
    dirty = 0;
}

void afr_rollback(void) {
    free(rows);
    rows = NULL;
    nrows = 0;
    loaded = 0;
    dirty = 0;
}

static int digits(const char *p, int n) {
    for (int i = 0; i < n; i++)
        if (!isdigit((unsigned char)p[i])) return 0;
    return 1;
}

/* 'YY-MM-DD HH24.MI.SSNNNNNN' -> '20YY-MM-DD-HH.MI.SS.NNNNNN' */
static int timestamp_format(char *ts) {
    char in[TS_LEN + 1], out[TS_LEN + 1];
    memcpy(in, ts, TS_LEN);
    in[TS_LEN] = 0;
    int l = TS_LEN;
    while (l > 0 && in[l - 1] == ' ') l--;
    if (l != 23 || in[2] != '-' || in[5] != '-' || in[8] != ' ' || in[11] != '.' || in[14] != '.' ||
        !digits(in, 2) || !digits(in + 3, 2) || !digits(in + 6, 2) || !digits(in + 9, 2) ||
        !digits(in + 12, 2) || !digits(in + 15, 8))
        return 0;
    snprintf(out, sizeof out, "20%.2s-%.2s-%.2s-%.2s.%.2s.%.2s.%.6s",
             in, in + 3, in + 6, in + 9, in + 12, in + 15, in + 17);
    memcpy(ts, out, TS_LEN);
    return 1;
}

static int find(const char *key) {
    for (int i = 0; i < nrows; i++)
        if (!memcmp(rows[i], key, KEYLEN)) return i;
    return -1;
}

int AUTHFRDS(const char *op, char *row, unsigned char *sqlcode, char *sqlstate) {
    int code = 0;
    const char *state = "00000";
    load();
    if (!timestamp_format(row + TS_OFF)) {
        code = -20448;
        state = "22007";
    } else if (!memcmp(op, "INSERT", 6)) {
        if (find(row) >= 0) {
            code = -803;
            state = "23505";
        } else {
            rows = realloc(rows, sizeof *rows * (nrows + 1));
            memcpy(rows[nrows++], row, RECLEN);
            dirty = 1;
        }
    } else if (!memcmp(op, "UPDATE", 6)) {
        int i = find(row);
        if (i < 0) {
            code = 100;
            state = "02000";
        } else {
            for (int b = 0; b < RECLEN; b++)
                if (row[b]) rows[i][b] = row[b];
            dirty = 1;
        }
    } else {
        code = -104;
        state = "42601";
    }
    memcpy(sqlcode, &code, 4);
    memcpy(sqlstate, state, 5);
    return 0;
}
