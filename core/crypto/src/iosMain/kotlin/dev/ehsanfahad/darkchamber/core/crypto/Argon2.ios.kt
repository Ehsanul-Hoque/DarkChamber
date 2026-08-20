package dev.ehsanfahad.darkchamber.core.crypto

import argon2.argon2id_hash_raw
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned

/**
 * iOS Argon2id via the vendored phc-winner-argon2 reference library (cinterop `argon2`), shared by
 * iosArm64 + iosSimulatorArm64. phc's raw API is Argon2 1.3 (version 0x13), matching the BouncyCastle
 * actual and the published KAT. This is the second of the only two files allowed to import an Argon2
 * primitive (hard rule #2).
 *
 * Inputs are validated in the engine, so a non-zero return here is a precondition/programming error,
 * not attacker-controlled — surfaced as [IllegalStateException].
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun platformArgon2id(
    password: ByteArray,
    salt: ByteArray,
    memoryKib: Int,
    iterations: Int,
    parallelism: Int,
    outLenBytes: Int,
): ByteArray {
    val out = ByteArray(outLenBytes)
    val rc = password.usePinned { pw ->
        salt.usePinned { s ->
            out.usePinned { o ->
                argon2id_hash_raw(
                    iterations.convert(),
                    memoryKib.convert(),
                    parallelism.convert(),
                    pw.addressOf(0),
                    password.size.convert(),
                    s.addressOf(0),
                    salt.size.convert(),
                    o.addressOf(0),
                    outLenBytes.convert(),
                )
            }
        }
    }
    check(rc == 0) { "argon2id_hash_raw failed with code $rc" }
    return out
}
