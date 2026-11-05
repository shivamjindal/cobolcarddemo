/*
 * Shared state of one harness region (a CICS task or a batch job step): the run
 * directory and the unit of work over the recoverable resources, IMS DL/I, MQ and
 * the AUTHFRDS table. Each resource buffers its changes in memory; commit writes
 * them to the run directory, rollback forgets them.
 */
#ifndef REGION_H
#define REGION_H

#include <stdint.h>

extern char region_dir[512];

void region_init(const char *dir);
void uow_commit(void);
void uow_rollback(void);

void dli_commit(void);
void dli_rollback(void);
void dli_unschedule(void);
int dli_region(const char *psb, unsigned char **masks, int max);
void mq_commit(void);
void mq_rollback(void);
void afr_commit(void);
void afr_rollback(void);

int32_t be32(const void *p);
void put_be32(void *p, int32_t v);
int16_t be16(const void *p);
void put_be16(void *p, int16_t v);
void trim(char *dst, const void *src, int n);
void to_hex(char *dst, const unsigned char *p, int n);
int from_hex(const char *s, unsigned char *out, int max);

#endif
