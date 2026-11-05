/*
 * IMS DL/I stand-in: database DBPAUTP0 (HIDAM, root PAUTSUM0 keyed by packed ACCNTID,
 * child PAUTDTL1 keyed by PAUT9CTS) over the harness datasets PAUTSUM0.dat and
 * PAUTDTL1.dat, plus GSAM output PCBs. Two entry points:
 *
 *   DLIEXEC  translated EXEC DLI (cicsprep.py): request block, DIB, I/O area, WHERE values
 *   CBLTDLI  the call interface: function, PCB mask, I/O area, SSAs
 *
 * Segments are kept in hierarchical sequence, which for unique keys is the order of the
 * concatenated key with a root before its children. A PCB's position is the key of the
 * last segment it reached, so deletes and inserts by other calls never invalidate it.
 *
 * Hold: EXEC DLI gets always hold; CBLTDLI holds only for GHU/GHN/GHNP. REPL and DLET
 * need the immediately preceding call on the PCB to be a successful holding get of that
 * segment type (else DJ); any other call, including a get that fails, releases the hold.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libcob.h>
#include "region.h"

#define MAXPCB 16
#define SUMLEN 100
#define DTLLEN 200
#define KEYLEN 14

#pragma pack(push, 1)
struct dlireq {
    char fn[4];
    int16_t pcbno;
    char psb[8];
    char seg1[8], fld1[8], op1[2];
    char seg2[8], fld2[8], op2[2];
    int32_t iolen, w1len, w2len;
};
struct dib {
    char ver[2], stat[2], segm[8], fil01, fil02, seglv[2];
    unsigned char kfbl[2];
    char dbdnm[8], dborg[8], fil03[6];
};
#pragma pack(pop)

typedef struct {
    int level;
    unsigned char key[KEYLEN];
    unsigned char data[DTLLEN];
} seg_t;

typedef struct {
    int set, level, incl;
    unsigned char key[KEYLEN];
} where_t;

enum { PCB_IO, PCB_DB, PCB_GSAM };

typedef struct {
    int type;
    char dbd[9], procopt[5], dd[9];
    int keylen, reclen;
    unsigned char *mask;
    where_t pos, parent, held;
    FILE *gsam;
} pcb_t;

typedef struct {
    char seg[9], fld[9], op[3];
    const unsigned char *val;
} ssa_t;

static seg_t *db;
static int ndb, db_loaded, db_dirty;
static pcb_t pcbs[MAXPCB];
static int npcb, scheduled, batch;
static char psb_name[9];

/* ---------- database ---------- */

static int seg_len(int level) { return level == 1 ? SUMLEN : DTLLEN; }
static int key_len(int level) { return level == 1 ? 6 : KEYLEN; }

static int cmp_key(const unsigned char *ka, int la, const unsigned char *kb, int lb) {
    int c = memcmp(ka, kb, 6);
    if (c || la == lb) return c ? c : (la == 1 ? 0 : memcmp(ka + 6, kb + 6, 8));
    return la < lb ? -1 : 1;
}

static int cmp_seg(const void *a, const void *b) {
    const seg_t *x = a, *y = b;
    return cmp_key(x->key, x->level, y->key, y->level);
}

static void load_file(const char *name, int level) {
    char path[600], *line = malloc(4096);
    snprintf(path, sizeof path, "%s/%s.dat", region_dir, name);
    FILE *in = fopen(path, "r");
    while (in && fgets(line, 4096, in)) {
        unsigned char rec[DTLLEN + 6];
        int n = from_hex(line, rec, sizeof rec);
        if (n == 0) continue;
        db = realloc(db, sizeof(seg_t) * (ndb + 1));
        seg_t *s = &db[ndb++];
        memset(s, 0, sizeof *s);
        s->level = level;
        if (level == 1) {
            memset(s->data, ' ', SUMLEN);
            memcpy(s->data, rec, n < SUMLEN ? n : SUMLEN);
            memcpy(s->key, rec, 6);
        } else {
            memset(s->data, ' ', DTLLEN);
            memcpy(s->key, rec, 6);
            if (n > 6) memcpy(s->data, rec + 6, n - 6 < DTLLEN ? n - 6 : DTLLEN);
            memcpy(s->key + 6, s->data, 8);
        }
    }
    if (in) fclose(in);
    free(line);
}

static void db_load(void) {
    if (db_loaded) return;
    ndb = 0;
    load_file("PAUTSUM0", 1);
    load_file("PAUTDTL1", 2);
    qsort(db, ndb, sizeof(seg_t), cmp_seg);
    db_loaded = 1;
    db_dirty = 0;
}

static void db_save(void) {
    char path[600], hex[2 * (DTLLEN + 6) + 1];
    unsigned char rec[DTLLEN + 6];
    for (int level = 1; level <= 2; level++) {
        snprintf(path, sizeof path, "%s/%s.dat", region_dir, level == 1 ? "PAUTSUM0" : "PAUTDTL1");
        FILE *out = fopen(path, "w");
        for (int i = 0; i < ndb; i++) {
            if (db[i].level != level) continue;
            int n;
            if (level == 1) {
                memcpy(rec, db[i].data, SUMLEN);
                n = SUMLEN;
            } else {
                memcpy(rec, db[i].key, 6);
                memcpy(rec + 6, db[i].data, DTLLEN);
                n = DTLLEN + 6;
            }
            to_hex(hex, rec, n);
            fprintf(out, "%s\n", hex);
        }
        fclose(out);
    }
}

void dli_commit(void) {
    if (db_loaded && db_dirty) db_save();
    db_dirty = 0;
}

void dli_rollback(void) {
    free(db);
    db = NULL;
    ndb = 0;
    db_loaded = 0;
    db_dirty = 0;
}

static int seg_level(const char *name) {
    if (!strcmp(name, "PAUTSUM0")) return 1;
    if (!strcmp(name, "PAUTDTL1")) return 2;
    return 0;
}

static const char *seg_name(int level) { return level == 1 ? "PAUTSUM0" : "PAUTDTL1"; }

static int find_exact(const unsigned char *key, int level) {
    for (int i = 0; i < ndb; i++)
        if (db[i].level == level && cmp_key(db[i].key, level, key, level) == 0) return i;
    return -1;
}

static void insert_seg(const seg_t *s) {
    db = realloc(db, sizeof(seg_t) * (ndb + 1));
    int i = 0;
    while (i < ndb && cmp_seg(&db[i], s) < 0) i++;
    memmove(&db[i + 1], &db[i], sizeof(seg_t) * (ndb - i));
    db[i] = *s;
    ndb++;
    db_dirty = 1;
}

/* ---------- PSB ---------- */

static int load_psb(const char *name) {
    const char *lib = getenv("DLI_PSBLIB");
    FILE *in = lib ? fopen(lib, "r") : NULL;
    if (!in) return 0;
    char line[256], kind[16], a[32], b[32], c[32];
    int found = 0, cmpat = 0;
    npcb = 0;
    while (fgets(line, sizeof line, in)) {
        int n = sscanf(line, "%15s %31s %31s %31s", kind, a, b, c);
        if (n < 2) continue;
        if (!strcmp(kind, "PSB")) {
            if (found) break;
            found = !strcmp(a, name);
            cmpat = n > 2 && atoi(b);
            if (found && batch && cmpat) {
                memset(&pcbs[npcb], 0, sizeof(pcb_t));
                pcbs[npcb++].type = PCB_IO;
            }
            continue;
        }
        if (!found || npcb >= MAXPCB) continue;
        pcb_t *p = &pcbs[npcb++];
        memset(p, 0, sizeof *p);
        snprintf(p->dbd, sizeof p->dbd, "%s", a);
        if (!strcmp(kind, "DB")) {
            p->type = PCB_DB;
            snprintf(p->procopt, sizeof p->procopt, "%s", b);
            p->keylen = atoi(c);
        } else {
            p->type = PCB_GSAM;
            snprintf(p->dd, sizeof p->dd, "%s", b);
            p->reclen = atoi(c);
            snprintf(p->procopt, sizeof p->procopt, "LS");
        }
    }
    fclose(in);
    if (found) snprintf(psb_name, sizeof psb_name, "%s", name);
    return found;
}

static void init_mask(pcb_t *p) {
    unsigned char *m = p->mask;
    if (!m) return;
    memset(m, ' ', 36);
    if (p->type == PCB_IO) return;
    memcpy(m, p->dbd, strlen(p->dbd));
    memcpy(m + 8, "00", 2);
    memcpy(m + 12, p->procopt, strlen(p->procopt));
    put_be32(m + 16, 0);
    put_be32(m + 28, 0);
    put_be32(m + 32, p->type == PCB_DB ? 2 : 0);
    memset(m + 36, ' ', p->type == PCB_DB ? p->keylen : 0);
}

/* Batch region: schedule the job's PSB and hand back one mask per PCB. */
int dli_region(const char *psb, unsigned char **masks, int max) {
    batch = 1;
    if (!load_psb(psb)) return -1;
    scheduled = 1;
    for (int i = 0; i < npcb && i < max; i++) {
        pcbs[i].mask = masks[i];
        init_mask(&pcbs[i]);
    }
    return npcb;
}

void dli_unschedule(void) {
    if (batch) return;
    scheduled = 0;
    npcb = 0;
}

/* ---------- calls ---------- */

typedef struct {
    char stat[3];
    int level;
    unsigned char key[KEYLEN];
} result_t;

static void set_where(where_t *w, const seg_t *s) {
    w->set = 1;
    w->incl = 0;
    w->level = s->level;
    memcpy(w->key, s->key, KEYLEN);
}

static int field_matches(const seg_t *s, const ssa_t *q, int vlen) {
    if (!q->fld[0]) return 1;
    const unsigned char *k;
    int n;
    if (!strcmp(q->fld, "ACCNTID") && s->level == 1) { k = s->key; n = 6; }
    else if (!strcmp(q->fld, "PAUT9CTS") && s->level == 2) { k = s->key + 6; n = 8; }
    else return 0;
    if (vlen > 0 && vlen < n) n = vlen;
    int c = memcmp(k, q->val, n);
    const char *op = q->op;
    if (!strcmp(op, "=") || !strcmp(op, "EQ")) return c == 0;
    if (!strcmp(op, ">=") || !strcmp(op, "GE")) return c >= 0;
    if (!strcmp(op, "<=") || !strcmp(op, "LE")) return c <= 0;
    if (!strcmp(op, ">") || !strcmp(op, "GT")) return c > 0;
    if (!strcmp(op, "<") || !strcmp(op, "LT")) return c < 0;
    if (!strcmp(op, "!=") || !strcmp(op, "NE")) return c != 0;
    return 0;
}

/* Does segment i satisfy the SSA list (one SSA per level from the root down)? */
static int matches(int i, ssa_t *ssa, int nssa) {
    const seg_t *s = &db[i];
    if (nssa == 0) return 1;
    const ssa_t *last = &ssa[nssa - 1];
    if (seg_level(last->seg) != s->level) return 0;
    if (!field_matches(s, last, 0)) return 0;
    for (int j = 0; j < nssa - 1; j++) {
        int lv = seg_level(ssa[j].seg);
        if (lv >= s->level) return 0;
        int r = find_exact(s->key, lv);
        if (r < 0 || !field_matches(&db[r], &ssa[j], 0)) return 0;
    }
    return 1;
}

static int after(const seg_t *s, const where_t *w) {
    if (!w->set) return 1;
    int c = cmp_key(s->key, s->level, w->key, w->level);
    return w->incl ? c >= 0 : c > 0;
}

static int under(const seg_t *s, const where_t *parent) {
    return s->level > parent->level && memcmp(s->key, parent->key, key_len(parent->level)) == 0;
}

static void get(pcb_t *p, const char *fn, ssa_t *ssa, int nssa, unsigned char *io, int iolen,
                int hold, result_t *r) {
    int gu = !strncmp(fn, "GU", 2) || !strncmp(fn, "GHU", 3);
    int gnp = !strcmp(fn, "GNP") || !strcmp(fn, "GHNP");
    p->held.set = 0;
    if (gnp && !p->parent.set) {
        strcpy(r->stat, "GP");
        return;
    }
    int found = -1;
    for (int i = 0; i < ndb && found < 0; i++) {
        if (!gu && !after(&db[i], &p->pos)) continue;
        if (gnp && !under(&db[i], &p->parent)) {
            if (cmp_key(db[i].key, db[i].level, p->parent.key, p->parent.level) > 0) break;
            continue;
        }
        if (matches(i, ssa, nssa)) found = i;
    }
    if (found < 0) {
        if (gnp) {
            strcpy(r->stat, "GE");
        } else if (gu) {
            strcpy(r->stat, "GE");
        } else {
            strcpy(r->stat, "GB");
            p->pos.set = 0;
        }
        return;
    }
    seg_t *s = &db[found];
    int n = seg_len(s->level);
    if (iolen > 0 && iolen < n) n = iolen;
    memcpy(io, s->data, n);
    set_where(&p->pos, s);
    if (!gnp) set_where(&p->parent, s);
    if (hold) set_where(&p->held, s);
    strcpy(r->stat, "  ");
    r->level = s->level;
    memcpy(r->key, s->key, KEYLEN);
}

static int find_parent(pcb_t *p, ssa_t *ssa, int nssa) {
    for (int j = 0; j < nssa - 1; j++) {
        if (seg_level(ssa[j].seg) != 1) continue;
        for (int i = 0; i < ndb; i++)
            if (db[i].level == 1 && field_matches(&db[i], &ssa[j], 0)) return i;
        return -1;
    }
    if (!p->pos.set) return -1;
    return find_exact(p->pos.key, 1);
}

static void isrt(pcb_t *p, ssa_t *ssa, int nssa, const unsigned char *io, result_t *r) {
    p->held.set = 0;
    int level = nssa ? seg_level(ssa[nssa - 1].seg) : 0;
    seg_t s;
    memset(&s, 0, sizeof s);
    s.level = level;
    if (level == 1) {
        memset(s.data, ' ', DTLLEN);
        memcpy(s.data, io, SUMLEN);
        memcpy(s.key, io, 6);
    } else if (level == 2) {
        int parent = find_parent(p, ssa, nssa);
        if (parent < 0) {
            strcpy(r->stat, "GE");
            return;
        }
        memcpy(s.data, io, DTLLEN);
        memcpy(s.key, db[parent].key, 6);
        memcpy(s.key + 6, io, 8);
    } else {
        strcpy(r->stat, "AC");
        return;
    }
    if (find_exact(s.key, level) >= 0) {
        strcpy(r->stat, "II");
        return;
    }
    insert_seg(&s);
    set_where(&p->pos, &s);
    strcpy(r->stat, "  ");
    r->level = level;
    memcpy(r->key, s.key, KEYLEN);
}

static int key_changed(const where_t *h, const unsigned char *io) {
    return h->level == 1 ? memcmp(io, h->key, 6) != 0 : memcmp(io, h->key + 6, 8) != 0;
}

static void repl_dlet(pcb_t *p, int del, const char *segname, const unsigned char *io, result_t *r) {
    where_t h = p->held;
    p->held.set = 0;
    if (!h.set || (segname[0] && seg_level(segname) != h.level) || find_exact(h.key, h.level) < 0) {
        strcpy(r->stat, "DJ");
        return;
    }
    if (key_changed(&h, io)) {
        strcpy(r->stat, "DA");
        return;
    }
    int i = find_exact(h.key, h.level);
    if (del) {
        int j = i + 1;
        while (j < ndb && db[j].level > h.level && memcmp(db[j].key, h.key, key_len(h.level)) == 0) j++;
        memmove(&db[i], &db[j], sizeof(seg_t) * (ndb - j));
        ndb -= j - i;
    } else {
        memcpy(db[i].data, io, seg_len(h.level));
    }
    db_dirty = 1;
    strcpy(r->stat, "  ");
    r->level = h.level;
    memcpy(r->key, h.key, KEYLEN);
}

static void chkp(void) {
    uow_commit();
    for (int i = 0; i < npcb; i++) {
        pcbs[i].pos.set = pcbs[i].parent.set = pcbs[i].held.set = 0;
    }
}

static void gsam_isrt(pcb_t *p, const unsigned char *io, result_t *r) {
    if (!p->gsam) {
        char env[32];
        snprintf(env, sizeof env, "DD_%s", p->dd);
        const char *path = getenv(env);
        p->gsam = path ? fopen(path, "wb") : NULL;
    }
    if (!p->gsam) {
        strcpy(r->stat, "AO");
        return;
    }
    fwrite(io, 1, p->reclen, p->gsam);
    fflush(p->gsam);
    strcpy(r->stat, "  ");
}

static void dispatch(pcb_t *p, const char *fn, ssa_t *ssa, int nssa, unsigned char *io, int iolen,
                     int exec, result_t *r) {
    memset(r, 0, sizeof *r);
    strcpy(r->stat, "  ");
    if (p->type == PCB_GSAM) {
        if (!strcmp(fn, "ISRT")) gsam_isrt(p, io, r);
        else strcpy(r->stat, "AD");
        return;
    }
    if (p->type != PCB_DB || strcmp(p->dbd, "DBPAUTP0")) {
        strcpy(r->stat, "AD");
        return;
    }
    db_load();
    const char *segname = nssa ? ssa[nssa - 1].seg : "";
    if (!strcmp(fn, "GU") || !strcmp(fn, "GN") || !strcmp(fn, "GNP"))
        get(p, fn, ssa, nssa, io, iolen, exec, r);
    else if (!strcmp(fn, "GHU") || !strcmp(fn, "GHN") || !strcmp(fn, "GHNP"))
        get(p, fn, ssa, nssa, io, iolen, 1, r);
    else if (!strcmp(fn, "ISRT"))
        isrt(p, ssa, nssa, io, r);
    else if (!strcmp(fn, "REPL"))
        repl_dlet(p, 0, segname, io, r);
    else if (!strcmp(fn, "DLET"))
        repl_dlet(p, 1, segname, io, r);
    else
        strcpy(r->stat, "AD");
}

static void fill_mask(pcb_t *p, const result_t *r) {
    unsigned char *m = p->mask;
    if (!m) return;
    memcpy(m + 10, r->stat, 2);
    if (r->stat[0] != ' ' || p->type != PCB_DB || !r->level) return;
    char lv[3];
    snprintf(lv, sizeof lv, "%02d", r->level);
    memcpy(m + 8, lv, 2);
    memset(m + 20, ' ', 8);
    memcpy(m + 20, seg_name(r->level), 8);
    int kl = key_len(r->level);
    put_be32(m + 28, kl);
    memcpy(m + 36, r->key, kl);
}

/* ---------- EXEC DLI ---------- */

static void set_dib(struct dib *d, const char *stat, pcb_t *p, const result_t *r) {
    memcpy(d->ver, "01", 2);
    memcpy(d->stat, stat, 2);
    if (r && r->level && stat[0] == ' ') {
        memset(d->segm, ' ', 8);
        memcpy(d->segm, seg_name(r->level), 8);
        char lv[3];
        snprintf(lv, sizeof lv, "%02d", r->level);
        memcpy(d->seglv, lv, 2);
        put_be16(d->kfbl, (int16_t)key_len(r->level));
    }
    if (p && p->type == PCB_DB) {
        memset(d->dbdnm, ' ', 8);
        memcpy(d->dbdnm, p->dbd, strlen(p->dbd));
        memcpy(d->dborg, "HIDAM   ", 8);
    }
}

static void op_text(char *dst, const char *src) {
    trim(dst, src, 2);
}

int DLIEXEC(struct dlireq *q, struct dib *d, unsigned char *io, unsigned char *w1, unsigned char *w2) {
    char fn[5];
    trim(fn, q->fn, 4);
    if (!strcmp(fn, "SCHD")) {
        char name[9];
        trim(name, q->psb, 8);
        if (scheduled) set_dib(d, "TC", NULL, NULL);
        else if (!load_psb(name)) set_dib(d, "TE", NULL, NULL);
        else {
            scheduled = 1;
            set_dib(d, "  ", NULL, NULL);
        }
        return 0;
    }
    if (!strcmp(fn, "TERM")) {
        uow_commit();
        dli_unschedule();
        set_dib(d, "  ", NULL, NULL);
        return 0;
    }
    if (!scheduled) {
        set_dib(d, "TH", NULL, NULL);
        return 0;
    }
    if (!strcmp(fn, "CHKP")) {
        chkp();
        set_dib(d, "  ", NULL, NULL);
        return 0;
    }
    int n = q->pcbno;
    if (n < 1 || n > npcb) {
        set_dib(d, "AH", NULL, NULL);
        return 0;
    }
    pcb_t *p = &pcbs[n - 1];
    ssa_t ssa[2];
    int nssa = 0;
    memset(ssa, 0, sizeof ssa);
    if (q->seg1[0] != ' ' && q->seg1[0]) {
        trim(ssa[0].seg, q->seg1, 8);
        trim(ssa[0].fld, q->fld1, 8);
        op_text(ssa[0].op, q->op1);
        ssa[0].val = w1;
        nssa = 1;
    }
    if (q->seg2[0] != ' ' && q->seg2[0]) {
        trim(ssa[1].seg, q->seg2, 8);
        trim(ssa[1].fld, q->fld2, 8);
        op_text(ssa[1].op, q->op2);
        ssa[1].val = w2;
        nssa = 2;
    }
    result_t r;
    dispatch(p, fn, ssa, nssa, io, q->iolen, 1, &r);
    fill_mask(p, &r);
    set_dib(d, r.stat, p, &r);
    return 0;
}

/* ---------- CALL 'CBLTDLI' ---------- */

static void parse_ssa(ssa_t *s, const unsigned char *raw) {
    memset(s, 0, sizeof *s);
    trim(s->seg, raw, 8);
    if (raw[8] != '(') return;
    trim(s->fld, raw + 9, 8);
    trim(s->op, raw + 17, 2);
    s->val = raw + 19;
}

static pcb_t *pcb_of(unsigned char *mask) {
    for (int i = 0; i < npcb; i++)
        if (pcbs[i].mask == mask) return &pcbs[i];
    return NULL;
}

int CBLTDLI(char *fnarea, unsigned char *mask, unsigned char *io, unsigned char *s1,
            unsigned char *s2, unsigned char *s3) {
    int argc = cob_get_global_ptr()->cob_call_params;
    char fn[5];
    trim(fn, fnarea, 4);
    if (!strcmp(fn, "CHKP")) {
        chkp();
        if (mask) memcpy(mask + 10, "  ", 2);
        return 0;
    }
    pcb_t *p = pcb_of(mask);
    if (!p) {
        if (mask) memcpy(mask + 10, "AH", 2);
        return 0;
    }
    unsigned char *raw[3] = {s1, s2, s3};
    ssa_t ssa[3];
    int nssa = argc > 3 ? argc - 3 : 0;
    if (nssa > 3) nssa = 3;
    for (int i = 0; i < nssa; i++) parse_ssa(&ssa[i], raw[i]);
    result_t r;
    dispatch(p, fn, ssa, nssa, io, 0, 0, &r);
    fill_mask(p, &r);
    return 0;
}
