package com.suseoaa.castpigeon.shared.crypto

actual class Crypto actual constructor() {
    actual fun generateKeyPair() {}
    actual fun getPublicKeyBytes(): ByteArray = ByteArray(0)
    actual fun computeSharedSecret(peerPublicKey: ByteArray) {}
    actual fun encryptAesGcm(plainText: ByteArray): ByteArray = plainText
    actual fun decryptAesGcm(cipherText: ByteArray): ByteArray = cipherText
}
