/*
 * CICS stand-in for running CardDemo online programs under GnuCOBOL.
 *
 * One process runs one CICS task: it calls the start program, follows XCTLs,
 * and stops at RETURN or ABEND. Input is <session>/task.in, file definitions
 * are <session>/files.cfg, and every observable effect is appended to
 * <session>/events.jsonl as it happens, so an abend mid-task keeps the trail.
 *
 * Files are fixed-length text records, one per line, kept sorted by key.
 * CSD defines every CardDemo file RECOVERY(NONE), so writes go straight to
 * disk and neither SYNCPOINT ROLLBACK nor ABEND backs them out. IMS, MQ and
 * AUTHFRDS changes (region.h) commit at SYNCPOINT or normal task end and are
 * discarded by SYNCPOINT ROLLBACK or an abend.
 */
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <libcob.h>
#include "region.h"

/* sqlstub.c: ends the Db2 unit of work (commit 1, rollback 0). */
void sql_end(int commit);

#pragma pack(push, 1)
struct eib {
    int32_t time, date;
    char trnid[4];
    int32_t taskn;
    char trmid[4];
    int16_t eigdi, cposn, calen;
    char aid;
    char fn[2], rcode[6], ds[8], reqid[8], rsrce[8];
    char sync, free_, recv, send, att, eoc, fmh, compl_, sig, conf, err;
    char errcd[4];
    char synrb, nodat;
    int32_t resp, resp2;
    char rldbk;
};
struct req {
    char fn[16], name[8], mapset[8], transid[4];
    int32_t len, keylen;
    char flags[80], argnames[80];
    int32_t arglen[8];
    int32_t resp, resp2;
};
#pragma pack(pop)

enum { NORMAL = 0, NOTFND = 13, DUPREC = 14, DUPKEY = 15, INVREQ = 16, NOTOPEN = 19,
       ENDFILE = 20, LENGERR = 22, PGMIDERR = 27, ENDDATA = 29, MAPFAIL = 36 };

static int all_high_values(const char *key, int len) {
    for (int i = 0; i < len; i++)
        if ((unsigned char)key[i] != 0xFF) return 0;
    return 1;
}

#define MAXREC 4096
#define COMMAREA_MAX 32767

typedef struct {
    char name[9], path[512];
    int reclen, keyoff, keylen, aix;  /* aix: alternate-index path over a base file */
    int baseoff, baselen;
    /* browse and read-for-update state */
    int browsing, pos, last, dir;
    char held[256];
    int held_set;
} vfile;

static char session[512];
static FILE *events;
static vfile files[32];
static int nfiles;
static unsigned char task_map[COMMAREA_MAX];
static int task_map_len = -1;
static char xctl_program[9];
static unsigned char xctl_commarea[COMMAREA_MAX];
static int xctl_len = -1;
static int returned;
static int link_depth;
static unsigned char start_data[COMMAREA_MAX];
static int start_len = -1;

static void hex(FILE *f, const unsigned char *p, int n) {
    for (int i = 0; i < n; i++) fprintf(f, "%02x", p[i]);
}

static void trim_copy(char *dst, const char *src, int n) {
    memcpy(dst, src, n);
    dst[n] = 0;
    for (int i = n - 1; i >= 0 && (dst[i] == ' ' || dst[i] == 0); i--) dst[i] = 0;
}

static void event(const char *kind, const char *fmt, ...) {
    fprintf(events, "{\"ev\":\"%s\"", kind);
    if (fmt) {
        va_list ap;
        va_start(ap, fmt);
        fputc(',', events);
        vfprintf(events, fmt, ap);
        va_end(ap);
    }
    fputs("}\n", events);
    fflush(events);
}

static void event_data(const char *kind, const char *meta, const unsigned char *p, int n) {
    fprintf(events, "{\"ev\":\"%s\",%s\"data\":\"", kind, meta);
    hex(events, p, n);
    fputs("\"}\n", events);
    fflush(events);
}

static int unhex(const char *s, unsigned char *out) {
    int n = 0;
    while (s[0] && s[1] && s[0] != '\n') {
        unsigned v;
        sscanf(s, "%2x", &v);
        out[n++] = (unsigned char)v;
        s += 2;
    }
    return n;
}

static int has_flag(struct req *r, const char *flag) {
    char buf[84], pat[32];
    memcpy(buf, r->flags, 80);
    buf[80] = 0;
    snprintf(pat, sizeof pat, " %s ", flag);
    return strstr(buf, pat) != NULL;
}

/* Index of the data argument with this option name, or -1. */
static int argidx(struct req *r, const char *opt) {
    char buf[84];
    memcpy(buf, r->argnames, 80);
    buf[80] = 0;
    int idx = 0;
    for (char *tok = strtok(buf, " "); tok; tok = strtok(NULL, " "), idx++)
        if (!strcmp(tok, opt)) return idx;
    return -1;
}

/* ---------- files ---------- */

typedef struct { int n; char **rec; } table;

static vfile *find_file(struct req *r) {
    char name[9];
    trim_copy(name, r->name, 8);
    for (int i = 0; i < nfiles; i++)
        if (!strcmp(files[i].name, name)) return &files[i];
    return NULL;
}

static table load(vfile *f) {
    table t = {0, NULL};
    FILE *in = fopen(f->path, "r");
    if (!in) return t;
    char line[MAXREC];
    while (fgets(line, sizeof line, in)) {
        int l = strcspn(line, "\r\n");
        char *rec = malloc(f->reclen + 1);
        memset(rec, ' ', f->reclen);
        memcpy(rec, line, l < f->reclen ? l : f->reclen);
        rec[f->reclen] = 0;
        t.rec = realloc(t.rec, sizeof(char *) * (t.n + 1));
        t.rec[t.n++] = rec;
    }
    fclose(in);
    return t;
}

static vfile *sort_file;
static int cmp_rec(const void *a, const void *b) {
    const char *x = *(char *const *)a, *y = *(char *const *)b;
    int c = memcmp(x + sort_file->keyoff, y + sort_file->keyoff, sort_file->keylen);
    if (c || !sort_file->aix) return c;
    return memcmp(x + sort_file->baseoff, y + sort_file->baseoff, sort_file->baselen);
}

static void sort_table(vfile *f, table *t) {
    sort_file = f;
    qsort(t->rec, t->n, sizeof(char *), cmp_rec);
}

static void save(vfile *f, table *t) {
    sort_table(f, t);
    FILE *out = fopen(f->path, "w");
    for (int i = 0; i < t->n; i++) {
        int l = f->reclen;
        while (l > 0 && t->rec[i][l - 1] == ' ') l--;
        fwrite(t->rec[i], 1, l, out);
        fputc('\n', out);
    }
    fclose(out);
}

static void free_table(table *t) {
    for (int i = 0; i < t->n; i++) free(t->rec[i]);
    free(t->rec);
}

static int keycmp(vfile *f, const char *rec, const char *key, int klen) {
    return memcmp(rec + f->keyoff, key, klen);
}

/* First index whose key is >= key (compared over klen bytes). */
static int lower_bound(vfile *f, table *t, const char *key, int klen) {
    int i = 0;
    while (i < t->n && keycmp(f, t->rec[i], key, klen) < 0) i++;
    return i;
}

static void copy_out(char *dst, int dstlen, const char *src, int srclen) {
    memcpy(dst, src, dstlen < srclen ? dstlen : srclen);
}

static int file_op(const char *fn, struct req *r, struct eib *e, char **a) {
    vfile *f = find_file(r);
    if (!f) return NOTOPEN;
    int iinto = argidx(r, "INTO"), ifrom = argidx(r, "FROM"), irid = argidx(r, "RIDFLD");
    int klen = r->keylen > 0 ? r->keylen : f->keylen;
    if (klen > f->keylen) klen = f->keylen;
    table t = load(f);
    sort_table(f, &t);
    int rc = NORMAL, dirty = 0;
    char ev[160];
    snprintf(ev, sizeof ev, "\"file\":\"%s\",", f->name);

    if (!strcmp(fn, "READ")) {
        const char *key = a[irid];
        int i = lower_bound(f, &t, key, klen);
        if (has_flag(r, "GTEQ")) {
            if (i >= t.n) rc = NOTFND;
        } else if (i >= t.n || keycmp(f, t.rec[i], key, klen) != 0) {
            rc = NOTFND;
        }
        if (rc == NORMAL) {
            copy_out(a[iinto], r->arglen[iinto], t.rec[i], f->reclen);
            if (f->aix && i + 1 < t.n && keycmp(f, t.rec[i + 1], t.rec[i] + f->keyoff, f->keylen) == 0)
                rc = DUPKEY;
            if (has_flag(r, "UPDATE")) {
                memcpy(f->held, t.rec[i] + f->keyoff, f->keylen);
                f->held_set = 1;
            }
        }
    } else if (!strcmp(fn, "WRITE")) {
        const char *rec = a[ifrom];
        int i = lower_bound(f, &t, rec + f->keyoff, f->keylen);
        if (i < t.n && keycmp(f, t.rec[i], rec + f->keyoff, f->keylen) == 0) {
            rc = DUPREC;
        } else {
            char *nr = malloc(f->reclen + 1);
            memset(nr, ' ', f->reclen);
            memcpy(nr, rec, r->arglen[ifrom] < f->reclen ? r->arglen[ifrom] : f->reclen);
            nr[f->reclen] = 0;
            t.rec = realloc(t.rec, sizeof(char *) * (t.n + 1));
            t.rec[t.n++] = nr;
            dirty = 1;
        }
    } else if (!strcmp(fn, "REWRITE")) {
        const char *rec = a[ifrom];
        if (!f->held_set) {
            rc = INVREQ;
        } else {
            int i = lower_bound(f, &t, f->held, f->keylen);
            if (i >= t.n || keycmp(f, t.rec[i], f->held, f->keylen) != 0) {
                rc = NOTFND;
            } else {
                memset(t.rec[i], ' ', f->reclen);
                memcpy(t.rec[i], rec, r->arglen[ifrom] < f->reclen ? r->arglen[ifrom] : f->reclen);
                dirty = 1;
            }
            f->held_set = 0;
        }
    } else if (!strcmp(fn, "DELETE")) {
        const char *key = irid >= 0 ? a[irid] : (f->held_set ? f->held : NULL);
        if (!key) {
            rc = INVREQ;
        } else {
            int i = lower_bound(f, &t, key, klen);
            if (i >= t.n || keycmp(f, t.rec[i], key, klen) != 0) {
                rc = NOTFND;
            } else {
                free(t.rec[i]);
                memmove(&t.rec[i], &t.rec[i + 1], sizeof(char *) * (t.n - i - 1));
                t.n--;
                dirty = 1;
            }
            f->held_set = 0;
        }
    } else if (!strcmp(fn, "STARTBR")) {
        const char *key = a[irid];
        int i = lower_bound(f, &t, key, klen);
        if (has_flag(r, "EQUAL") && (i >= t.n || keycmp(f, t.rec[i], key, klen) != 0)) {
            rc = NOTFND;
        } else if (i >= t.n && !all_high_values(key, klen)) {
            rc = NOTFND;
        } else {
            /* Only an all-high-values key may start past the last record (CICS positions at
               end of file so READPREV returns the last record); any other such key is NOTFND. */
            f->browsing = 1;
            f->pos = i;
            f->last = -1;
            f->dir = 0;
        }
    } else if (!strcmp(fn, "READNEXT") || !strcmp(fn, "READPREV")) {
        int next = !strcmp(fn, "READNEXT");
        if (!f->browsing) {
            rc = INVREQ;
        } else {
            int i;
            if (f->last < 0) {
                i = next ? f->pos : (f->pos < t.n ? f->pos : t.n - 1);
            } else if ((next && f->dir < 0) || (!next && f->dir > 0)) {
                /* CICS re-reads the current record when the browse changes direction. */
                i = f->last;
            } else {
                i = next ? f->last + 1 : f->last - 1;
            }
            if (i < 0 || i >= t.n) {
                rc = ENDFILE;
            } else {
                copy_out(a[iinto], r->arglen[iinto], t.rec[i], f->reclen);
                if (irid >= 0) copy_out(a[irid], r->arglen[irid], t.rec[i] + f->keyoff, f->keylen);
                f->last = i;
                f->dir = next ? 1 : -1;
            }
        }
    } else if (!strcmp(fn, "ENDBR")) {
        if (!f->browsing) rc = INVREQ;
        f->browsing = 0;
    }
    if (dirty) save(f, &t);
    free_table(&t);
    if (irid >= 0 && strcmp(fn, "WRITE") && strcmp(fn, "REWRITE")) {
        fprintf(events, "{\"ev\":\"file\",%s\"op\":\"%s\",\"resp\":%d,\"key\":\"", ev, fn, rc);
        hex(events, (unsigned char *)a[irid], klen);
        fputs("\"}\n", events);
    } else {
        event("file", "%s\"op\":\"%s\",\"resp\":%d", ev, fn, rc);
    }
    fflush(events);
    (void)e;
    return rc;
}

/* ---------- time ---------- */

static struct tm fixed_now(void) {
    struct tm tm = {0};
    const char *s = getenv("COB_CURRENT_DATE");
    int y = 2024, mo = 1, d = 1, h = 0, mi = 0, se = 0;
    if (s) sscanf(s, "%d/%d/%d %d:%d:%d", &y, &mo, &d, &h, &mi, &se);
    tm.tm_year = y - 1900; tm.tm_mon = mo - 1; tm.tm_mday = d;
    tm.tm_hour = h; tm.tm_min = mi; tm.tm_sec = se;
    return tm;
}

static void put_packed(unsigned char *p, int len, long long v) {
    memset(p, 0, len);
    p[len - 1] = (unsigned char)(((v % 10) << 4) | 0x0C);
    v /= 10;
    for (int i = len - 2; i >= 0; i--) {
        p[i] = (unsigned char)(v % 10);
        v /= 10;
        p[i] |= (unsigned char)((v % 10) << 4);
        v /= 10;
    }
}

static void put_text(char *dst, int len, const char *s) {
    memset(dst, ' ', len);
    memcpy(dst, s, (int)strlen(s) < len ? (int)strlen(s) : len);
}

static int optchar(struct req *r, char **a, const char *opt, char dflt) {
    int i = argidx(r, opt);
    if (i >= 0) return a[i][0];
    return has_flag(r, opt) ? dflt : 0;
}

/* ---------- entry points called from translated COBOL ---------- */

int CICSABND(struct req *r);


int CICSAPI(struct req *r, struct eib *e, char *a0, char *a1, char *a2, char *a3,
            char *a4, char *a5, char *a6, char *a7) {
    char *a[8] = {a0, a1, a2, a3, a4, a5, a6, a7};
    char fn[17], name[9], mapset[9], transid[5];
    trim_copy(fn, r->fn, 16);
    trim_copy(name, r->name, 8);
    trim_copy(mapset, r->mapset, 8);
    trim_copy(transid, r->transid, 4);
    int rc = NORMAL;

    if (!strcmp(fn, "SEND MAP")) {
        char meta[200], flags[81];
        trim_copy(flags, r->flags, 80);
        snprintf(meta, sizeof meta, "\"map\":\"%s\",\"mapset\":\"%s\",\"flags\":\"%s\",", name, mapset, flags + (flags[0] == ' '));
        int i = argidx(r, "FROM");
        event_data("send_map", meta, i >= 0 ? (unsigned char *)a[i] : (unsigned char *)"", i >= 0 ? r->arglen[i] : 0);
    } else if (!strcmp(fn, "RECEIVE MAP")) {
        int i = argidx(r, "INTO");
        if (task_map_len < 0) {
            rc = MAPFAIL;
        } else {
            copy_out(a[i], r->arglen[i], (char *)task_map, task_map_len);
        }
        event("receive_map", "\"map\":\"%s\",\"resp\":%d", name, rc);
    } else if (!strcmp(fn, "SEND TEXT") || !strcmp(fn, "SEND FROM")) {
        int i = argidx(r, "FROM");
        int n = r->len > 0 ? r->len : r->arglen[i];
        event_data("send_text", "", (unsigned char *)a[i], n);
    } else if (!strcmp(fn, "RETURN") && link_depth > 0) {
        event("link_return", "\"depth\":%d", link_depth);
    } else if (!strcmp(fn, "RETURN")) {
        int i = argidx(r, "COMMAREA");
        int n = i < 0 ? 0 : (r->len > 0 ? r->len : r->arglen[i]);
        char meta[64];
        snprintf(meta, sizeof meta, "\"transid\":\"%s\",", transid);
        event_data("return", meta, i >= 0 ? (unsigned char *)a[i] : (unsigned char *)"", n);
        returned = 1;
    } else if (!strcmp(fn, "XCTL")) {
        if (!cob_resolve(name)) {
            rc = PGMIDERR;
            event("xctl_failed", "\"program\":\"%s\"", name);
        } else {
            int i = argidx(r, "COMMAREA");
            xctl_len = i < 0 ? 0 : (r->len > 0 ? r->len : r->arglen[i]);
            if (i >= 0) memcpy(xctl_commarea, a[i], xctl_len);
            strcpy(xctl_program, name);
            event("xctl", "\"program\":\"%s\"", name);
        }
    } else if (!strcmp(fn, "LINK")) {
        /* The linked program runs on the caller's commarea storage, so its changes are
           what the caller sees after the LINK. */
        if (!cob_resolve(name)) {
            rc = PGMIDERR;
            event("link_failed", "\"program\":\"%s\"", name);
        } else {
            static char empty[1];
            int i = argidx(r, "COMMAREA");
            struct eib child = *e;
            child.calen = (int16_t)(i < 0 ? 0 : (r->len > 0 ? r->len : r->arglen[i]));
            void *args[2] = {&child, i < 0 ? empty : a[i]};
            event("link", "\"program\":\"%s\",\"calen\":%d", name, child.calen);
            link_depth++;
            cob_call(name, 2, args);
            link_depth--;
            cob_cancel(name);
        }
    } else if (!strcmp(fn, "RETRIEVE")) {
        int i = argidx(r, "INTO");
        if (start_len < 0) {
            rc = ENDDATA;
        } else {
            copy_out(a[i], r->arglen[i], (char *)start_data, start_len);
            if (start_len > r->arglen[i]) rc = LENGERR;
        }
        event("retrieve", "\"resp\":%d", rc);
    } else if (!strcmp(fn, "INQUIRE")) {
        int i = argidx(r, "PROGRAM");
        char prog[9];
        trim_copy(prog, i >= 0 ? a[i] : r->name, 8);
        rc = cob_resolve(prog) ? NORMAL : PGMIDERR;
    } else if (!strcmp(fn, "ASSIGN")) {
        int i;
        if ((i = argidx(r, "APPLID")) >= 0) put_text(a[i], r->arglen[i], "CARDDEMO");
        if ((i = argidx(r, "SYSID")) >= 0) put_text(a[i], r->arglen[i], "CICS");
        if ((i = argidx(r, "USERID")) >= 0) put_text(a[i], r->arglen[i], "CICSUSER");
    } else if (!strcmp(fn, "ASKTIME")) {
        struct tm tm = fixed_now();
        long long secs = (long long)timegm(&tm) + 2208988800LL;  /* ABSTIME counts from 1900 */
        int i = argidx(r, "ABSTIME");
        if (i >= 0) put_packed((unsigned char *)a[i], r->arglen[i], secs * 1000);
    } else if (!strcmp(fn, "FORMATTIME")) {
        struct tm tm = fixed_now();
        char buf[32];
        int ds = optchar(r, a, "DATESEP", '/'), ts = optchar(r, a, "TIMESEP", ':');
        int i;
        if ((i = argidx(r, "YYYYMMDD")) >= 0) {
            if (ds) snprintf(buf, sizeof buf, "%04d%c%02d%c%02d", tm.tm_year + 1900, ds, tm.tm_mon + 1, ds, tm.tm_mday);
            else snprintf(buf, sizeof buf, "%04d%02d%02d", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday);
            put_text(a[i], r->arglen[i], buf);
        }
        if ((i = argidx(r, "MMDDYYYY")) >= 0) {
            if (ds) snprintf(buf, sizeof buf, "%02d%c%02d%c%04d", tm.tm_mon + 1, ds, tm.tm_mday, ds, tm.tm_year + 1900);
            else snprintf(buf, sizeof buf, "%02d%02d%04d", tm.tm_mon + 1, tm.tm_mday, tm.tm_year + 1900);
            put_text(a[i], r->arglen[i], buf);
        }
        if ((i = argidx(r, "YYMMDD")) >= 0) {
            if (ds) snprintf(buf, sizeof buf, "%02d%c%02d%c%02d", tm.tm_year % 100, ds, tm.tm_mon + 1, ds, tm.tm_mday);
            else snprintf(buf, sizeof buf, "%02d%02d%02d", tm.tm_year % 100, tm.tm_mon + 1, tm.tm_mday);
            put_text(a[i], r->arglen[i], buf);
        }
        if ((i = argidx(r, "YYDDD")) >= 0) {
            time_t t = timegm(&tm);
            struct tm day;
            gmtime_r(&t, &day);
            if (ds) snprintf(buf, sizeof buf, "%02d%c%03d", day.tm_year % 100, ds, day.tm_yday + 1);
            else snprintf(buf, sizeof buf, "%02d%03d", day.tm_year % 100, day.tm_yday + 1);
            put_text(a[i], r->arglen[i], buf);
        }
        if ((i = argidx(r, "MILLISECONDS")) >= 0)
            memset(a[i], 0, r->arglen[i]);
        if ((i = argidx(r, "MMDDYY")) >= 0) {
            if (ds) snprintf(buf, sizeof buf, "%02d%c%02d%c%02d", tm.tm_mon + 1, ds, tm.tm_mday, ds, tm.tm_year % 100);
            else snprintf(buf, sizeof buf, "%02d%02d%02d", tm.tm_mon + 1, tm.tm_mday, tm.tm_year % 100);
            put_text(a[i], r->arglen[i], buf);
        }
        if ((i = argidx(r, "TIME")) >= 0) {
            if (ts) snprintf(buf, sizeof buf, "%02d%c%02d%c%02d", tm.tm_hour, ts, tm.tm_min, ts, tm.tm_sec);
            else snprintf(buf, sizeof buf, "%02d%02d%02d", tm.tm_hour, tm.tm_min, tm.tm_sec);
            put_text(a[i], r->arglen[i], buf);
        }
    } else if (!strcmp(fn, "WRITEQ TD") || !strcmp(fn, "WRITEQ")) {
        int i = argidx(r, "FROM");
        int n = r->len > 0 ? r->len : r->arglen[i];
        char meta[64];
        snprintf(meta, sizeof meta, "\"queue\":\"%s\",", name);
        event_data("writeq_td", meta, (unsigned char *)a[i], n);
    } else if (!strcmp(fn, "SYNCPOINT")) {
        /* A syncpoint ends the unit of work and, under DBCTL, releases the PSB. */
        if (has_flag(r, "ROLLBACK")) uow_rollback();
        else uow_commit();
        dli_unschedule();
        event("syncpoint", "\"rollback\":%s", has_flag(r, "ROLLBACK") ? "true" : "false");
        sql_end(!has_flag(r, "ROLLBACK"));
    } else if (!strcmp(fn, "ABEND")) {
        event("abend", "\"code\":\"%s\"", name);
        sql_end(0);
        cob_stop_run(0);
    } else if (!strcmp(fn, "READ") || !strcmp(fn, "WRITE") || !strcmp(fn, "REWRITE") ||
               !strcmp(fn, "DELETE") || !strcmp(fn, "STARTBR") || !strcmp(fn, "READNEXT") ||
               !strcmp(fn, "READPREV") || !strcmp(fn, "ENDBR")) {
        rc = file_op(fn, r, e, a);
    } else {
        event("unsupported", "\"command\":\"%s\"", fn);
        r->resp = -1;
        CICSABND(r);
    }
    r->resp = rc;
    r->resp2 = 0;
    e->resp = rc;
    e->resp2 = 0;
    return 0;
}

/* The abend CICS raises for a condition with no RESP or HANDLE. HUNS marks a command the stub
   does not implement; it is a harness gap and must never appear in a golden. */
static const char *condition_abend(int resp) {
    switch (resp) {
    case NOTFND: return "AEIM";
    case DUPREC: return "AEIN";
    case DUPKEY: return "AEIO";
    case INVREQ: return "AEIP";
    case NOTOPEN: return "AEIS";
    case ENDFILE: return "AEIT";
    case PGMIDERR: return "AEI0";
    case ENDDATA: return "AEI2";
    case LENGERR: return "AEIV";
    case MAPFAIL: return "AEI9";
    default: return "HUNS";
    }
}

int CICSABND(struct req *r) {
    char fn[17];
    trim_copy(fn, r->fn, 16);
    event("abend", "\"code\":\"%s\",\"command\":\"%s\",\"resp\":%d", condition_abend(r->resp), fn, r->resp);
    sql_end(0);
    cob_stop_run(0);
    return 0;
}

/* ---------- driver ---------- */

static void load_files(void) {
    char path[600], line[700];
    snprintf(path, sizeof path, "%s/files.cfg", session);
    FILE *in = fopen(path, "r");
    if (!in) return;
    while (fgets(line, sizeof line, in) && nfiles < 32) {
        vfile *f = &files[nfiles];
        memset(f, 0, sizeof *f);
        char rel[400];
        int n = sscanf(line, "%8s %399s %d %d %d %d %d", f->name, rel, &f->reclen, &f->keyoff,
                       &f->keylen, &f->baseoff, &f->baselen);
        if (n < 5) continue;
        f->aix = n == 7;
        snprintf(f->path, sizeof f->path, "%s/%s", session, rel);
        nfiles++;
    }
    fclose(in);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: cicsrun <session-dir>\n");
        return 2;
    }
    snprintf(session, sizeof session, "%s", argv[1]);
    char path[600], line[COMMAREA_MAX * 2 + 64];
    snprintf(path, sizeof path, "%s/events.jsonl", session);
    events = fopen(path, "a");
    load_files();

    char program[9] = "", trnid[5] = "";
    static unsigned char commarea[COMMAREA_MAX];
    int calen = 0, aid = 0x27;
    snprintf(path, sizeof path, "%s/task.in", session);
    FILE *in = fopen(path, "r");
    while (in && fgets(line, sizeof line, in)) {
        if (!strncmp(line, "program=", 8)) sscanf(line + 8, "%8s", program);
        else if (!strncmp(line, "trnid=", 6)) sscanf(line + 6, "%4s", trnid);
        else if (!strncmp(line, "aid=", 4)) sscanf(line + 4, "%x", (unsigned *)&aid);
        else if (!strncmp(line, "commarea=", 9)) calen = unhex(line + 9, commarea);
        else if (!strncmp(line, "map=", 4)) task_map_len = unhex(line + 4, task_map);
        else if (!strncmp(line, "start_data=", 11)) start_len = unhex(line + 11, start_data);
    }
    if (in) fclose(in);
    region_init(session);

    cob_init(0, NULL);
    struct eib eib;
    for (int hops = 0; hops < 16; hops++) {
        if (!cob_resolve(program)) {
            event("abend", "\"code\":\"APCT\",\"program\":\"%s\"", program);
            break;
        }
        static unsigned char area[COMMAREA_MAX];
        memset(&eib, 0, sizeof eib);
        memset(area, 0, sizeof area);
        memcpy(area, commarea, calen);
        memset(eib.trnid, ' ', 4);
        memcpy(eib.trnid, trnid, strlen(trnid));
        eib.calen = (int16_t)calen;
        eib.aid = (char)aid;
        memcpy(eib.trmid, "T001", 4);
        event("start", "\"program\":\"%s\",\"calen\":%d", program, calen);
        void *args[2] = {&eib, area};
        xctl_len = -1;
        cob_call(program, 2, args);
        cob_cancel(program);
        if (xctl_len >= 0) {
            snprintf(program, sizeof program, "%s", xctl_program);
            memcpy(commarea, xctl_commarea, xctl_len);
            calen = xctl_len;
            continue;
        }
        if (!returned) event("return", "\"transid\":\"\",\"data\":\"\",\"implicit\":true");
        break;
    }
    uow_commit();
    dli_unschedule();
    sql_end(1);
    fclose(events);
    cob_stop_run(0);
    return 0;
}
