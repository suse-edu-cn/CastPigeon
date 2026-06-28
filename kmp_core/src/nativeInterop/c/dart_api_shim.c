#include "dart_api_shim.h"
#include "dart_api_dl.h"

intptr_t castpigeon_dart_initialize_api_dl(void* data) {
    if (data == 0) {
        return -1;
    }
    return Dart_InitializeApiDL(data);
}

int castpigeon_dart_post_integer(int64_t port, int64_t message) {
    if (Dart_PostInteger_DL == 0) {
        return 0;
    }
    return Dart_PostInteger_DL(port, message) ? 1 : 0;
}

#include "dart_api_dl.c"
