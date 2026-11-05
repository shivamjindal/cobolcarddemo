/*
 * Batch step launcher: jobrun PROGRAM [PARM]. Calls the program module with the
 * JCL PARM in z/OS layout (halfword length, then text) and exits with RETURN-CODE.
 */
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <libcob.h>

int main(int argc, char **argv) {
    static unsigned char parm[2 + 100];
    if (argc < 2) {
        fprintf(stderr, "usage: jobrun PROGRAM [PARM]\n");
        return 2;
    }
    const char *text = argc > 2 ? argv[2] : "";
    size_t n = strlen(text) > 100 ? 100 : strlen(text);
    parm[0] = (unsigned char)(n >> 8);
    parm[1] = (unsigned char)n;
    memcpy(parm + 2, text, n);
    cob_init(0, NULL);
    void *args[1] = {parm};
    int rc = cob_call(argv[1], 1, args);
    cob_stop_run(rc);
    return rc;
}
