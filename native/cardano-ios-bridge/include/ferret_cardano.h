#ifndef FERRET_CARDANO_H
#define FERRET_CARDANO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct { const uint8_t *ptr; size_t len; } FerretBytes;
typedef struct { uint8_t *ptr; size_t len; } FerretBuffer;
typedef struct FerretBuild FerretBuild;

void ferret_buffer_free(FerretBuffer buffer);
void ferret_build_free(FerretBuild *build);
int32_t ferret_derive(FerretBytes entropy, uint32_t network_id, FerretBuffer *result, FerretBuffer *error);
int32_t ferret_build_begin(FerretBytes request_json, FerretBuild **result, FerretBuffer *error);
int32_t ferret_build_next(FerretBuild *build, FerretBytes evaluation_json, FerretBytes evaluation_entropy, FerretBuffer *result, FerretBuffer *error);
int32_t ferret_authorize(FerretBytes cbor, FerretBytes request_json, FerretBuffer *result, FerretBuffer *error);
int32_t ferret_sign(FerretBytes cbor, FerretBytes entropy, FerretBytes request_json, FerretBuffer *result, FerretBuffer *error);
int32_t ferret_inspect(FerretBytes cbor, FerretBuffer *result, FerretBuffer *error);
int32_t ferret_transaction_id(FerretBytes cbor, FerretBuffer *result, FerretBuffer *error);
int32_t ferret_minimum_ada(FerretBytes cbor, FerretBytes protocol_json, uint32_t output_index, FerretBuffer *result, FerretBuffer *error);
int32_t ferret_decode_datum(FerretBytes cbor_hex, FerretBytes assets_json, FerretBuffer *result, FerretBuffer *error);
int32_t ferret_protocol_key(FerretBytes entropy, uint32_t network_id, FerretBuffer *result, FerretBuffer *error);
int32_t ferret_protocol_sign(FerretBytes entropy, uint32_t network_id, FerretBytes message, FerretBuffer *result, FerretBuffer *error);
int32_t ferret_protocol_verify(FerretBytes key, FerretBytes message, FerretBytes signature, FerretBuffer *result, FerretBuffer *error);

#ifdef __cplusplus
}
#endif
#endif
