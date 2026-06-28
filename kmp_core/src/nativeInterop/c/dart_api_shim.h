#ifndef CASTPIGEON_DART_API_SHIM_H
#define CASTPIGEON_DART_API_SHIM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

intptr_t castpigeon_dart_initialize_api_dl(void* data);
int castpigeon_dart_post_integer(int64_t port, int64_t message);

#ifdef __cplusplus
}
#endif

#endif
