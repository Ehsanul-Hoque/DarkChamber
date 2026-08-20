package dev.ehsanfahad.darkchamber.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Android/JVM Argon2id via BouncyCastle — a pure-JVM implementation, so its KATs run in
 * `testAndroidHostTest` rather than only on a device. Version pinned to 0x13 to match the iOS
 * (phc) actual and the published KAT. This file is one of the only two allowed to import an Argon2
 * primitive (hard rule #2); the engine that calls it imports only cryptography-kotlin.
 */
internal actual fun platformArgon2id(
    password: ByteArray,
    salt: ByteArray,
    memoryKib: Int,
    iterations: Int,
    parallelism: Int,
    outLenBytes: Int,
): ByteArray {
    val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13) // 0x13
        .withMemoryAsKB(memoryKib)
        .withIterations(iterations)
        .withParallelism(parallelism)
        .withSalt(salt)
        .build()
    val generator = Argon2BytesGenerator().apply { init(params) }
    val out = ByteArray(outLenBytes)
    generator.generateBytes(password, out)
    return out
}
