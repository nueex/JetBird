/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
#include <jni.h>
#include "psmtp/lan_spake.h"

static int load(JNIEnv *env, jbyteArray array, uint8_t *bytes, jsize length) {
    if (!array || (*env)->GetArrayLength(env, array) != length) return 0;
    (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *)bytes);
    return !(*env)->ExceptionCheck(env);
}

static jbyteArray encode(JNIEnv *env, const uint8_t *bytes, jsize length) {
    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)bytes);
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_com_nueex_psmtp_PairingCrypto_share(JNIEnv *env, jobject self, jint code, jbyteArray random) {
    uint8_t scalar[32], point[65];
    (void)self;
    int ok = load(env, random, scalar, sizeof(scalar)) && psmtp_spake_share(code, scalar, 1, point);
    psmtp_spake_clear(scalar, sizeof(scalar));
    return ok ? encode(env, point, sizeof(point)) : NULL;
}

JNIEXPORT jbyteArray JNICALL Java_com_nueex_psmtp_PairingCrypto_proofs(JNIEnv *env, jobject self, jint code,
    jbyteArray random, jbyteArray phone, jbyteArray console, jbyteArray fingerprint) {
    uint8_t scalar[32], a[65], b[65], pin[16], proofs[64];
    (void)self;
    int ok = load(env, random, scalar, sizeof(scalar)) && load(env, phone, a, sizeof(a)) &&
        load(env, console, b, sizeof(b)) && load(env, fingerprint, pin, sizeof(pin)) &&
        psmtp_spake_confirm(code, scalar, 1, a, b, pin, proofs);
    jbyteArray result = ok ? encode(env, proofs, sizeof(proofs)) : NULL;
    psmtp_spake_clear(scalar, sizeof(scalar));
    psmtp_spake_clear(proofs, sizeof(proofs));
    return result;
}
