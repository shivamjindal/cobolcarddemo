/*
 * Db2 stand-in for the embedded SQL of the transaction-type extension.
 *
 * sqlprep.py turns each EXEC SQL into CALL "SQLEXEC" USING SQLCA, "<PROGRAM>:<n>", host
 * variables. Statement n of a program is line n of $CARDDEMO_SQL_DIR/<PROGRAM>.sql. The SQL
 * runs for real on Postgres ($CARDDEMO_DB2_CONNINFO, a SQL_ASCII database with C collation so
 * strings compare byte by byte), with the Db2 behavior the programs depend on:
 *   - a failing statement backs out alone (a savepoint per statement), not the unit of work;
 *   - a searched UPDATE or DELETE that touches no row is SQLCODE +100;
 *   - SQLSTATEs map to the Db2 SQLCODEs the programs test (-803, -530, -532, -501, ...).
 * The unit of work ends at SYNCPOINT, at task or step end (commit), or at an abend (rollback).
 * Postgres text cannot hold X'00', so a LOW-VALUES byte in an input host variable is sent as
 * X'01'; it orders the same against every key the programs compare it with.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libcob.h>
#include <libpq-fe.h>

#pragma pack(push, 1)
struct sqlca {
    char sqlcaid[8];
    int32_t sqlcabc, sqlcode;
    unsigned char sqlerrml[2];
    char sqlerrmc[70];
    char sqlerrp[8];
    int32_t sqlerrd[6];
    char sqlwarn[11];
    char sqlstate[5];
};
#pragma pack(pop)

#define MAXSTMT 128
#define MAXPARAM 16

typedef struct {
    char verb[12], cursor[32], params[MAXPARAM][3];
    int nparams;
    char *sql;
} stmt;

typedef struct {
    char program[9];
    stmt s[MAXSTMT];
    int n;
} stmt_table;

static stmt_table tables[8];
static int ntables;
static PGconn *conn;
static int in_tx;

void sql_end(int commit);

static void sql_exit(void) { sql_end(1); }

static stmt_table *load_table(const char *program) {
    for (int i = 0; i < ntables; i++)
        if (!strcmp(tables[i].program, program)) return &tables[i];
    stmt_table *t = &tables[ntables++];
    snprintf(t->program, sizeof t->program, "%s", program);
    char path[600], line[8192];
    snprintf(path, sizeof path, "%s/%s.sql", getenv("CARDDEMO_SQL_DIR") ? getenv("CARDDEMO_SQL_DIR") : ".", program);
    FILE *in = fopen(path, "r");
    if (!in) {
        fprintf(stderr, "SQLSTUB no statement table %s\n", path);
        return t;
    }
    while (fgets(line, sizeof line, in) && t->n < MAXSTMT) {
        line[strcspn(line, "\n")] = 0;
        char *f[5];
        char *p = line;
        for (int i = 0; i < 5; i++) {
            f[i] = p;
            p = i < 4 ? strchr(p, '\t') : NULL;
            if (i < 4) {
                if (!p) break;
                *p++ = 0;
            }
        }
        stmt *s = &t->s[t->n++];
        snprintf(s->verb, sizeof s->verb, "%s", f[1]);
        snprintf(s->cursor, sizeof s->cursor, "%s", f[2]);
        for (char *tok = strtok(f[3], ","); tok && strcmp(tok, "-"); tok = strtok(NULL, ","))
            snprintf(s->params[s->nparams++], 3, "%s", tok);
        s->sql = strdup(f[4]);
    }
    fclose(in);
    return t;
}

static void set_sqlca(struct sqlca *ca, int code, const char *state, const char *msg, int rows) {
    memcpy(ca->sqlcaid, "SQLCA   ", 8);
    ca->sqlcabc = (int32_t)sizeof *ca;
    ca->sqlcode = code;
    memset(ca->sqlerrmc, ' ', sizeof ca->sqlerrmc);
    int ml = msg ? (int)strlen(msg) : 0;
    if (ml > 70) ml = 70;
    if (ml) memcpy(ca->sqlerrmc, msg, ml);
    ca->sqlerrml[0] = (unsigned char)(ml >> 8);
    ca->sqlerrml[1] = (unsigned char)ml;
    memcpy(ca->sqlerrp, "DSN     ", 8);
    memset(ca->sqlerrd, 0, sizeof ca->sqlerrd);
    ca->sqlerrd[2] = rows;
    memset(ca->sqlwarn, ' ', sizeof ca->sqlwarn);
    memcpy(ca->sqlstate, state, 5);
}

static int db2_sqlcode(const char *state, const char *verb) {
    static const struct { const char *state; int code; } map[] = {
        {"23505", -803}, {"23502", -407}, {"22001", -404}, {"42P03", -502}, {"34000", -501},
        {"40P01", -911}, {"55P03", -911}, {"21000", -811}, {"22P02", -420},
    };
    if (!strcmp(state, "23503")) return strcmp(verb, "DELETE") ? -530 : -532;
    for (size_t i = 0; i < sizeof map / sizeof map[0]; i++)
        if (!strcmp(map[i].state, state)) return map[i].code;
    return -9999;
}

static PGresult *run(const char *sql, int n, const char *const *values) {
    return n ? PQexecParams(conn, sql, n, NULL, values, NULL, NULL, 0) : PQexec(conn, sql);
}

static int simple(const char *sql) {
    PGresult *r = PQexec(conn, sql);
    int ok = PQresultStatus(r) == PGRES_COMMAND_OK;
    if (!ok) fprintf(stderr, "SQLSTUB %s: %s", sql, PQerrorMessage(conn));
    PQclear(r);
    return ok;
}

static int connect_db(void) {
    if (conn) return 1;
    const char *info = getenv("CARDDEMO_DB2_CONNINFO");
    conn = PQconnectdb(info ? info : "");
    if (PQstatus(conn) != CONNECTION_OK) {
        fprintf(stderr, "SQLSTUB connect: %s", PQerrorMessage(conn));
        PQfinish(conn);
        conn = NULL;
        return 0;
    }
    atexit(sql_exit);
    return 1;
}

void sql_end(int commit) {
    if (conn && in_tx) simple(commit ? "COMMIT" : "ROLLBACK");
    in_tx = 0;
}

static int is_numeric_param(int n) {
    int t = cob_get_param_type(n);
    return t == COB_TYPE_NUMERIC_DISPLAY || t == COB_TYPE_NUMERIC_BINARY ||
           t == COB_TYPE_NUMERIC_PACKED || t == COB_TYPE_NUMERIC_COMP5;
}

/* Input host variable k (COBOL parameter n) as a C string. */
static char *input_value(const char *kind, unsigned char *p, int n) {
    char *v;
    if (is_numeric_param(n)) {
        v = malloc(32);
        snprintf(v, 32, "%lld", (long long)cob_get_s64_param(n));
        return v;
    }
    int len = cob_get_param_size(n);
    if (kind[1] == 'V') {
        len = (p[0] << 8) | p[1];
        p += 2;
    }
    v = malloc(len + 1);
    for (int i = 0; i < len; i++) v[i] = p[i] ? (char)p[i] : '\x01';
    v[len] = 0;
    return v;
}

static void output_value(const char *kind, unsigned char *p, int n, const char *val, int len) {
    if (is_numeric_param(n)) {
        cob_put_s64_param(n, atoll(val));
        return;
    }
    int size = cob_get_param_size(n);
    if (kind[1] == 'V') {
        int max = size - 2;
        if (len > max) len = max;
        p[0] = (unsigned char)(len >> 8);
        p[1] = (unsigned char)len;
        memcpy(p + 2, val, len);
        return;
    }
    memset(p, ' ', size);
    memcpy(p, val, len < size ? len : size);
}

int SQLEXEC(struct sqlca *ca, char *id, unsigned char *h1, unsigned char *h2, unsigned char *h3,
            unsigned char *h4, unsigned char *h5, unsigned char *h6, unsigned char *h7,
            unsigned char *h8, unsigned char *h9, unsigned char *h10, unsigned char *h11,
            unsigned char *h12, unsigned char *h13, unsigned char *h14, unsigned char *h15,
            unsigned char *h16) {
    unsigned char *hv[MAXPARAM] = {h1, h2, h3, h4, h5, h6, h7, h8, h9, h10, h11, h12, h13, h14, h15, h16};
    char program[9] = "";
    int number = 0;
    sscanf(id, "%8[^:]:%4d", program, &number);
    stmt_table *t = load_table(program);
    if (number < 1 || number > t->n) {
        fprintf(stderr, "SQLSTUB unknown statement %s:%d\n", program, number);
        set_sqlca(ca, -9999, "HY000", "unknown statement", 0);
        return 0;
    }
    stmt *s = &t->s[number - 1];

    if (!connect_db()) {
        set_sqlca(ca, -924, "58031", "connection failed", 0);
        return 0;
    }
    if (!in_tx) {
        simple("BEGIN");
        in_tx = 1;
    }
    if (!strcmp(s->verb, "COMMIT") || !strcmp(s->verb, "ROLLBACK")) {
        sql_end(!strcmp(s->verb, "COMMIT"));
        set_sqlca(ca, 0, "00000", NULL, 0);
        return 0;
    }

    char sql[8400];
    const char *values[MAXPARAM];
    int nin = 0;
    for (int i = 0; i < s->nparams; i++)
        if (s->params[i][0] == 'I') values[nin++] = input_value(s->params[i], hv[i], i + 3);
    if (!strcmp(s->verb, "OPEN")) snprintf(sql, sizeof sql, "DECLARE \"%s\" CURSOR FOR %s", s->cursor, s->sql);
    else if (!strcmp(s->verb, "FETCH")) snprintf(sql, sizeof sql, "FETCH NEXT FROM \"%s\"", s->cursor);
    else if (!strcmp(s->verb, "CLOSE")) snprintf(sql, sizeof sql, "CLOSE \"%s\"", s->cursor);
    else snprintf(sql, sizeof sql, "%s", s->sql);

    simple("SAVEPOINT harness_stmt");
    PGresult *r = run(sql, nin, values);
    ExecStatusType st = PQresultStatus(r);
    if (st != PGRES_COMMAND_OK && st != PGRES_TUPLES_OK) {
        const char *state = PQresultErrorField(r, PG_DIAG_SQLSTATE);
        if (!state) state = "HY000";
        int code = db2_sqlcode(state, s->verb);
        if (code == -9999) fprintf(stderr, "SQLSTUB unmapped %s %s: %s", state, sql, PQerrorMessage(conn));
        set_sqlca(ca, code, state, PQresultErrorField(r, PG_DIAG_MESSAGE_PRIMARY), 0);
        PQclear(r);
        simple("ROLLBACK TO SAVEPOINT harness_stmt");
    } else {
        int rows = st == PGRES_TUPLES_OK ? PQntuples(r) : atoi(PQcmdTuples(r));
        int code = 0;
        const char *state = "00000";
        if ((st == PGRES_TUPLES_OK && rows == 0) ||
            ((!strcmp(s->verb, "UPDATE") || !strcmp(s->verb, "DELETE")) && rows == 0)) {
            code = 100;
            state = "02000";
        } else if (!strcmp(s->verb, "SELECT") && rows > 1) {
            code = -811;
            state = "21000";
        }
        if (st == PGRES_TUPLES_OK && rows > 0) {
            int col = 0;
            for (int i = 0; i < s->nparams; i++)
                if (s->params[i][0] == 'O' && col < PQnfields(r)) {
                    output_value(s->params[i], hv[i], i + 3, PQgetvalue(r, 0, col), PQgetlength(r, 0, col));
                    col++;
                }
        }
        set_sqlca(ca, code, state, NULL, rows);
        PQclear(r);
        simple("RELEASE SAVEPOINT harness_stmt");
    }
    for (int i = 0; i < nin; i++) free((void *)values[i]);
    return 0;
}

/* DSNTIAC: format the SQLCA as Db2's DSNTIAR does, LRECL-wide lines after a halfword length.
   Only the first message (DSNT404I/DSNT408I) is reproduced, with the Db2 message text of the
   SQLCODEs the stand-in can raise; it is what reaches the 75-byte screen message. */
static const char *db2_text(int code) {
    switch (code) {
    case 100: return "ROW NOT FOUND FOR FETCH, UPDATE OR DELETE, OR THE RESULT OF A QUERY IS AN EMPTY TABLE";
    case -803: return "AN INSERTED OR UPDATED VALUE IS INVALID BECAUSE THE INDEX IN INDEX SPACE "
                      "CONSTRAINS COLUMNS OF THE TABLE SO NO TWO ROWS CAN CONTAIN DUPLICATE VALUES "
                      "IN THOSE COLUMNS";
    case -532: return "THE RELATIONSHIP RESTRICTS THE DELETION OF ROW";
    case -530: return "THE INSERT OR UPDATE VALUE OF FOREIGN KEY IS INVALID";
    case -911: return "THE CURRENT UNIT OF WORK HAS BEEN ROLLED BACK DUE TO DEADLOCK OR TIMEOUT";
    case -501: return "THE CURSOR IDENTIFIED IN A FETCH OR CLOSE STATEMENT IS NOT OPEN";
    case -502: return "THE CURSOR IDENTIFIED IN AN OPEN STATEMENT IS ALREADY OPEN";
    default: return "MESSAGE TEXT NOT REPRODUCED BY THE HARNESS";
    }
}

int DSNTIAC(void *eib, void *commarea, struct sqlca *ca, unsigned char *msg, int16_t *lrecl_be) {
    (void)eib;
    (void)commarea;
    unsigned char *lr = (unsigned char *)lrecl_be;
    int lrecl = (lr[0] << 8) | lr[1];
    int total = (msg[0] << 8) | msg[1];
    char *area = (char *)msg + 2;
    /* DSNTIAR's documented minimums; callers that INITIALIZE the area length hit RC 12. */
    if (lrecl < 72 || lrecl > 240) return 8;
    if (total < 240) return 12;
    int lines = total / lrecl;
    memset(area, ' ', lines * lrecl);
    char text[1024];
    snprintf(text, sizeof text, " %s SQLCODE = %d, %s  %s", ca->sqlcode > 0 ? "DSNT404I" : "DSNT408I",
             ca->sqlcode, ca->sqlcode == 100 ? "NOT FOUND:" : ca->sqlcode > 0 ? "WARNING:" : "ERROR:",
             db2_text(ca->sqlcode));
    /* DSNTIAR breaks between words and indents continuation lines ten columns. */
    const char *p = text;
    for (int line = 0; line < lines && *p; line++) {
        char *out = area + line * lrecl;
        int pos = 0;
        if (line > 0) {
            pos = 10;
            while (*p == ' ') p++;
        }
        int room = lrecl - pos, take = (int)strlen(p);
        if (take > room) {
            take = room;
            while (take > 0 && p[take] != ' ') take--;
            if (take == 0) take = room;
        }
        memcpy(out + pos, p, take);
        p += take;
    }
    return 0;
}
