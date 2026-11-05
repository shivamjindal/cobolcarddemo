/*
 * WebSphere MQ stand-in: MQOPEN, MQGET, MQPUT, MQPUT1, MQCLOSE over queue files in
 * <run>/mq/<queue name>. One message per line:
 *   origin msgid correlid replytoq data      (origin H = put by the harness, P = by a program;
 *                                           the other fields are hex)
 * Any non-blank queue name exists. Like MQ on z/OS, a GET or PUT is under syncpoint
 * unless it says NO-SYNCPOINT: a syncpoint GET hides the message until commit removes it
 * (rollback brings it back) and a syncpoint PUT is invisible until commit. Integers in
 * the MQ structures and parameters are BINARY, big-endian under cobc.
 *
 * MQGET with WAIT returns 2033 at once when the queue is empty: the harness has no
 * other producer that could put a message during the wait.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include "region.h"

enum { CC_OK = 0, CC_WARNING = 1, CC_FAILED = 2 };
enum { RC_HOBJ_ERROR = 2019, RC_NO_MSG = 2033, RC_TRUNCATED = 2080, RC_UNKNOWN_OBJECT = 2085 };
enum { GMO_SYNCPOINT = 2, GMO_NO_SYNCPOINT = 4, PMO_SYNCPOINT = 2, PMO_NO_SYNCPOINT = 4 };

/* MQMD v1 field offsets */
enum { MD_MSGID = 48, MD_CORRELID = 72, MD_REPLYTOQ = 100, MD_FORMAT = 32, MD_MSGTYPE = 12,
       MD_PUTDATE = 304, MD_PUTTIME = 312 };
#define OD_NAME 12
#define GMO_OPTIONS 8
#define PMO_OPTIONS 8

enum { AVAILABLE, LOCKED, PENDING };

typedef struct {
    int state;
    char origin;
    unsigned char msgid[24], correlid[24], replyto[48];
    unsigned char *data;
    int len;
} msg_t;

typedef struct {
    char name[49];
    msg_t *m;
    int n;
} queue_t;

static queue_t queues[16];
static int nqueues;
static char handles[32][49];
static long next_id;

static void queue_path(char *path, int size, const char *name) {
    snprintf(path, size, "%s/mq/%s", region_dir, name);
}

static queue_t *queue(const char *name) {
    for (int i = 0; i < nqueues; i++)
        if (!strcmp(queues[i].name, name)) return &queues[i];
    queue_t *q = &queues[nqueues++];
    memset(q, 0, sizeof *q);
    snprintf(q->name, sizeof q->name, "%s", name);
    char path[700];
    queue_path(path, sizeof path, name);
    FILE *in = fopen(path, "r");
    if (!in) return q;
    size_t cap = 1 << 16;
    char *line = malloc(cap);
    while (fgets(line, (int)cap, in)) {
        char origin, f[4][40000];
        if (sscanf(line, " %c %39999s %39999s %39999s %39999s", &origin, f[0], f[1], f[2], f[3]) < 4) continue;
        q->m = realloc(q->m, sizeof(msg_t) * (q->n + 1));
        msg_t *m = &q->m[q->n++];
        memset(m, 0, sizeof *m);
        m->origin = origin;
        from_hex(f[0], m->msgid, 24);
        from_hex(f[1], m->correlid, 24);
        memset(m->replyto, ' ', 48);
        from_hex(f[2], m->replyto, 48);
        m->data = malloc(strlen(f[3]) / 2 + 1);
        m->len = strcmp(f[3], "-") ? from_hex(f[3], m->data, (int)strlen(f[3]) / 2) : 0;
    }
    free(line);
    fclose(in);
    return q;
}

static void save(queue_t *q) {
    char path[700];
    snprintf(path, sizeof path, "%s/mq", region_dir);
    mkdir(path, 0777);
    queue_path(path, sizeof path, q->name);
    FILE *out = fopen(path, "w");
    for (int i = 0; i < q->n; i++) {
        msg_t *m = &q->m[i];
        if (m->state == PENDING) continue;
        char a[49], b[49], c[97];
        char *d = malloc(2 * m->len + 2);
        to_hex(a, m->msgid, 24);
        to_hex(b, m->correlid, 24);
        to_hex(c, m->replyto, 48);
        if (m->len) to_hex(d, m->data, m->len);
        else strcpy(d, "-");
        fprintf(out, "%c %s %s %s %s\n", m->origin, a, b, c, d);
        free(d);
    }
    fclose(out);
}

static void drop(queue_t *q, int i) {
    free(q->m[i].data);
    memmove(&q->m[i], &q->m[i + 1], sizeof(msg_t) * (q->n - i - 1));
    q->n--;
}

void mq_commit(void) {
    for (int k = 0; k < nqueues; k++) {
        queue_t *q = &queues[k];
        int changed = 0;
        for (int i = q->n - 1; i >= 0; i--) {
            if (q->m[i].state == LOCKED) { drop(q, i); changed = 1; }
            else if (q->m[i].state == PENDING) { q->m[i].state = AVAILABLE; changed = 1; }
        }
        if (changed) save(q);
    }
}

void mq_rollback(void) {
    for (int k = 0; k < nqueues; k++) {
        queue_t *q = &queues[k];
        for (int i = q->n - 1; i >= 0; i--) {
            if (q->m[i].state == LOCKED) q->m[i].state = AVAILABLE;
            else if (q->m[i].state == PENDING) drop(q, i);
        }
    }
}

static int is_none(const unsigned char *p, int n) {
    for (int i = 0; i < n; i++)
        if (p[i]) return 0;
    return 1;
}

static void new_msgid(unsigned char *id) {
    char buf[25];
    snprintf(buf, sizeof buf, "CARDDEMO%016ld", ++next_id);
    memcpy(id, buf, 24);
}

static int object_name(char *name, const unsigned char *od) {
    trim(name, od + OD_NAME, 48);
    return name[0] != 0;
}

static void put(const char *qname, unsigned char *md, const unsigned char *pmo, int len,
                const unsigned char *buf) {
    queue_t *q = queue(qname);
    q->m = realloc(q->m, sizeof(msg_t) * (q->n + 1));
    msg_t *m = &q->m[q->n++];
    memset(m, 0, sizeof *m);
    m->origin = 'P';
    if (is_none(md + MD_MSGID, 24)) new_msgid(md + MD_MSGID);
    memcpy(m->msgid, md + MD_MSGID, 24);
    memcpy(m->correlid, md + MD_CORRELID, 24);
    memcpy(m->replyto, md + MD_REPLYTOQ, 48);
    m->len = len > 0 ? len : 0;
    m->data = malloc(m->len + 1);
    memcpy(m->data, buf, m->len);
    int opts = be32(pmo + PMO_OPTIONS);
    m->state = (opts & PMO_NO_SYNCPOINT) ? AVAILABLE : PENDING;
    if (m->state == AVAILABLE) save(q);
}

int MQOPEN(void *hconn, unsigned char *od, unsigned char *options, unsigned char *hobj,
           unsigned char *cc, unsigned char *rc) {
    char name[49];
    (void)hconn; (void)options;
    if (!object_name(name, od)) {
        put_be32(cc, CC_FAILED);
        put_be32(rc, RC_UNKNOWN_OBJECT);
        return 0;
    }
    int h = 1;
    while (h < 32 && handles[h][0]) h++;
    snprintf(handles[h], sizeof handles[h], "%s", name);
    put_be32(hobj, h);
    put_be32(cc, CC_OK);
    put_be32(rc, 0);
    return 0;
}

int MQCLOSE(void *hconn, unsigned char *hobj, void *options, unsigned char *cc, unsigned char *rc) {
    int h = be32(hobj);
    (void)hconn; (void)options;
    if (h < 1 || h >= 32 || !handles[h][0]) {
        put_be32(cc, CC_FAILED);
        put_be32(rc, RC_HOBJ_ERROR);
        return 0;
    }
    handles[h][0] = 0;
    put_be32(hobj, 0);
    put_be32(cc, CC_OK);
    put_be32(rc, 0);
    return 0;
}

int MQGET(void *hconn, unsigned char *hobj, unsigned char *md, unsigned char *gmo,
          unsigned char *buflen, unsigned char *buf, unsigned char *datalen,
          unsigned char *cc, unsigned char *rc) {
    int h = be32(hobj);
    (void)hconn;
    if (h < 1 || h >= 32 || !handles[h][0]) {
        put_be32(cc, CC_FAILED);
        put_be32(rc, RC_HOBJ_ERROR);
        return 0;
    }
    queue_t *q = queue(handles[h]);
    int want_id = !is_none(md + MD_MSGID, 24), want_corr = !is_none(md + MD_CORRELID, 24);
    int i;
    for (i = 0; i < q->n; i++) {
        msg_t *m = &q->m[i];
        if (m->state != AVAILABLE) continue;
        if (want_id && memcmp(m->msgid, md + MD_MSGID, 24)) continue;
        if (want_corr && memcmp(m->correlid, md + MD_CORRELID, 24)) continue;
        break;
    }
    if (i == q->n) {
        put_be32(cc, CC_FAILED);
        put_be32(rc, RC_NO_MSG);
        return 0;
    }
    msg_t *m = &q->m[i];
    int max = be32(buflen);
    put_be32(datalen, m->len);
    memcpy(md + MD_MSGID, m->msgid, 24);
    memcpy(md + MD_CORRELID, m->correlid, 24);
    memcpy(md + MD_REPLYTOQ, m->replyto, 48);
    memcpy(md + MD_FORMAT, "MQSTR   ", 8);
    put_be32(md + MD_MSGTYPE, is_none(m->replyto, 1) || m->replyto[0] == ' ' ? 8 : 1);
    if (m->len > max) {
        memcpy(buf, m->data, max);
        put_be32(cc, CC_WARNING);
        put_be32(rc, RC_TRUNCATED);
        return 0;
    }
    memcpy(buf, m->data, m->len);
    if (be32(gmo + GMO_OPTIONS) & GMO_NO_SYNCPOINT) {
        drop(q, i);
        save(q);
    } else {
        m->state = LOCKED;
    }
    put_be32(cc, CC_OK);
    put_be32(rc, 0);
    return 0;
}

int MQPUT(void *hconn, unsigned char *hobj, unsigned char *md, unsigned char *pmo,
          unsigned char *buflen, unsigned char *buf, unsigned char *cc, unsigned char *rc) {
    int h = be32(hobj);
    (void)hconn;
    if (h < 1 || h >= 32 || !handles[h][0]) {
        put_be32(cc, CC_FAILED);
        put_be32(rc, RC_HOBJ_ERROR);
        return 0;
    }
    put(handles[h], md, pmo, be32(buflen), buf);
    put_be32(cc, CC_OK);
    put_be32(rc, 0);
    return 0;
}

int MQPUT1(void *hconn, unsigned char *od, unsigned char *md, unsigned char *pmo,
           unsigned char *buflen, unsigned char *buf, unsigned char *cc, unsigned char *rc) {
    char name[49];
    (void)hconn;
    if (!object_name(name, od)) {
        put_be32(cc, CC_FAILED);
        put_be32(rc, RC_UNKNOWN_OBJECT);
        return 0;
    }
    put(name, md, pmo, be32(buflen), buf);
    put_be32(cc, CC_OK);
    put_be32(rc, 0);
    return 0;
}
