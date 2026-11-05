#include <stdio.h>
#include <string.h>
#include "region.h"

char region_dir[512] = ".";

void region_init(const char *dir) {
    snprintf(region_dir, sizeof region_dir, "%s", dir);
}

void uow_commit(void) {
    dli_commit();
    mq_commit();
    afr_commit();
}

void uow_rollback(void) {
    dli_rollback();
    mq_rollback();
    afr_rollback();
}

int32_t be32(const void *p) {
    const unsigned char *b = p;
    return (int32_t)((uint32_t)b[0] << 24 | (uint32_t)b[1] << 16 | (uint32_t)b[2] << 8 | b[3]);
}

void put_be32(void *p, int32_t v) {
    unsigned char *b = p;
    b[0] = (unsigned char)((uint32_t)v >> 24);
    b[1] = (unsigned char)((uint32_t)v >> 16);
    b[2] = (unsigned char)((uint32_t)v >> 8);
    b[3] = (unsigned char)v;
}

int16_t be16(const void *p) {
    const unsigned char *b = p;
    return (int16_t)(b[0] << 8 | b[1]);
}

void put_be16(void *p, int16_t v) {
    unsigned char *b = p;
    b[0] = (unsigned char)((uint16_t)v >> 8);
    b[1] = (unsigned char)v;
}

void trim(char *dst, const void *src, int n) {
    memcpy(dst, src, n);
    dst[n] = 0;
    for (int i = n - 1; i >= 0 && (dst[i] == ' ' || dst[i] == 0); i--) dst[i] = 0;
}

void to_hex(char *dst, const unsigned char *p, int n) {
    for (int i = 0; i < n; i++) sprintf(dst + 2 * i, "%02x", p[i]);
    dst[2 * n] = 0;
}

int from_hex(const char *s, unsigned char *out, int max) {
    int n = 0;
    while (n < max && s[0] && s[1] && s[0] != '\n' && s[0] != ' ') {
        unsigned v;
        if (sscanf(s, "%2x", &v) != 1) break;
        out[n++] = (unsigned char)v;
        s += 2;
    }
    return n;
}
