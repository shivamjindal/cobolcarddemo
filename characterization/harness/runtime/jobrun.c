/*
 * Batch step launcher: jobrun PROGRAM [PARM]. Calls the program module with the
 * JCL PARM in z/OS layout (halfword length, then text) and exits with RETURN-CODE.
 *
 * With DLI_PSB set the step is an IMS DL/I or BMP region (DFSRRC00): the program is
 * called with one PCB mask per PCB of that PSB instead of the PARM. The region commits
 * the unit of work when the program returns, whatever its RETURN-CODE; a user abend
 * (CEE3ABD) ends the process before that, so its changes are lost.
 */
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libcob.h>
#include "region.h"

#define MAXPCB 16

int main(int argc, char **argv) {
    static unsigned char parm[2 + 100];
    static unsigned char masks[MAXPCB][512];
    if (argc < 2) {
        fprintf(stderr, "usage: jobrun PROGRAM [PARM]\n");
        return 2;
    }
    region_init(".");
    cob_init(0, NULL);
    const char *psb = getenv("DLI_PSB");
    int rc;
    if (psb && *psb) {
        unsigned char *ptrs[MAXPCB];
        void *args[MAXPCB];
        for (int i = 0; i < MAXPCB; i++) ptrs[i] = args[i] = masks[i];
        int n = dli_region(psb, ptrs, MAXPCB);
        if (n < 0) {
            fprintf(stderr, "jobrun: PSB %s not found\n", psb);
            return 2;
        }
        rc = cob_call(argv[1], n, args);
    } else {
        const char *text = argc > 2 ? argv[2] : "";
        size_t n = strlen(text) > 100 ? 100 : strlen(text);
        parm[0] = (unsigned char)(n >> 8);
        parm[1] = (unsigned char)n;
        memcpy(parm + 2, text, n);
        void *args[1] = {parm};
        rc = cob_call(argv[1], 1, args);
    }
    uow_commit();
    cob_stop_run(rc);
    return rc;
}
