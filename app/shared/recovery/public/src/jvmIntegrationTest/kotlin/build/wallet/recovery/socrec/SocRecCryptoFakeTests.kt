package build.wallet.recovery.socrec

import build.wallet.bitcoin.AppPrivateKeyDaoFake
import build.wallet.bitkey.hardware.AppGlobalAuthKeyHwSignature
import build.wallet.bitkey.hardware.HwAuthPublicKey
import build.wallet.bitkey.socrec.PakeCode
import build.wallet.encrypt.MessageSignerImpl
import build.wallet.encrypt.SignatureVerifierImpl
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.getOrThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.core.toByteArray
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

class SocRecCryptoFakeTests : FunSpec({
  val appPrivateKeyDao = AppPrivateKeyDaoFake()
  val cryptoFake = SocRecCryptoFake(
    messageSigner = MessageSignerImpl(),
    signatureVerifier = SignatureVerifierImpl(),
    appPrivateKeyDao = appPrivateKeyDao
  )

  beforeTest {
    appPrivateKeyDao.reset()
  }

  test("encrypt and decrypt private key material") {
    // Endorsement
    val hwEndorsementKeyPair = cryptoFake.generateAppAuthKeypair()
    val appEndorsementKeyPair = cryptoFake.generateAppAuthKeypair()
    val hwSignature =
      cryptoFake
        .sign(
          privateKey = hwEndorsementKeyPair.privateKey.key,
          message = appEndorsementKeyPair.publicKey.pubKey.value.encodeUtf8()
        )
        .hex()
        .let(::AppGlobalAuthKeyHwSignature)

    val invalidHwEndorsementKeyPair = cryptoFake.generateAppAuthKeypair()
    val invalidAppEndorsementKeyPair = cryptoFake.generateAppAuthKeypair()

    // Enrollment
    val trustedContactIdentityKey = cryptoFake.generateDelegatedDecryptionKey().getOrThrow()

    // Key authentication
    val enrollmentCode = PakeCode("F00DBAR".toByteArray().toByteString())
    val invalidEnrollmentCode = PakeCode("F00DBAN".toByteArray().toByteString())
    val protectedCustomerEnrollmentPakeKey =
      cryptoFake.generateProtectedCustomerEnrollmentPakeKey(enrollmentCode).getOrThrow()
    val encryptTrustedContactIdentityKeyOutput =
      cryptoFake.encryptDelegatedDecryptionKey(
        enrollmentCode,
        protectedCustomerEnrollmentPakeKey,
        trustedContactIdentityKey
      ).getOrThrow()
    val decryptedTrustedContactIdentityKey =
      cryptoFake.decryptDelegatedDecryptionKey(
        enrollmentCode,
        protectedCustomerEnrollmentPakeKey,
        encryptTrustedContactIdentityKeyOutput
      ).getOrThrow()
    decryptedTrustedContactIdentityKey.publicKey.shouldBe(trustedContactIdentityKey.publicKey)
    // Invalid password
    shouldThrow<SocRecCryptoError.KeyConfirmationFailed> {
      cryptoFake.decryptDelegatedDecryptionKey(
        invalidEnrollmentCode,
        protectedCustomerEnrollmentPakeKey,
        encryptTrustedContactIdentityKeyOutput
      ).getOrThrow()
    }
    val invalidEncryptTrustedContactIdentityKeyOutput =
      cryptoFake.encryptDelegatedDecryptionKey(
        invalidEnrollmentCode,
        protectedCustomerEnrollmentPakeKey,
        trustedContactIdentityKey
      ).getOrThrow()
    shouldThrow<SocRecCryptoError.KeyConfirmationFailed> {
      cryptoFake.decryptDelegatedDecryptionKey(
        invalidEnrollmentCode,
        protectedCustomerEnrollmentPakeKey,
        invalidEncryptTrustedContactIdentityKeyOutput
      ).getOrThrow()
    }
    shouldThrow<SocRecCryptoError.KeyConfirmationFailed> {
      cryptoFake.decryptDelegatedDecryptionKey(
        enrollmentCode,
        protectedCustomerEnrollmentPakeKey,
        invalidEncryptTrustedContactIdentityKeyOutput
      ).getOrThrow()
    }
    // Key certificate verification
    // Can verify when both keys are valid
    val keyCertificate =
      cryptoFake.generateKeyCertificate(
        delegatedDecryptionKey = decryptedTrustedContactIdentityKey,
        hwAuthKey = HwAuthPublicKey(hwEndorsementKeyPair.publicKey.pubKey),
        appGlobalAuthKey = appEndorsementKeyPair.publicKey,
        appGlobalAuthKeyHwSignature = hwSignature
      ).getOrThrow()
    // Can verify with app auth key only
    cryptoFake.verifyKeyCertificate(
      keyCertificate = keyCertificate,
      hwAuthKey = null,
      appGlobalAuthKey = appEndorsementKeyPair.publicKey
    ).getOrThrow().publicKey.shouldBe(trustedContactIdentityKey.publicKey)
    // Can verify with hw auth key only
    cryptoFake.verifyKeyCertificate(
      keyCertificate = keyCertificate,
      hwAuthKey = HwAuthPublicKey(hwEndorsementKeyPair.publicKey.pubKey),
      appGlobalAuthKey = null
    ).getOrThrow().publicKey.shouldBe(trustedContactIdentityKey.publicKey)
    // Can verify if at least app auth key is valid
    cryptoFake.verifyKeyCertificate(
      keyCertificate = keyCertificate,
      hwAuthKey = HwAuthPublicKey(invalidHwEndorsementKeyPair.publicKey.pubKey),
      appGlobalAuthKey = appEndorsementKeyPair.publicKey
    ).getOrThrow().publicKey.shouldBe(trustedContactIdentityKey.publicKey)
    // Can verify if at least hw auth key is valid
    cryptoFake.verifyKeyCertificate(
      keyCertificate = keyCertificate,
      hwAuthKey = HwAuthPublicKey(hwEndorsementKeyPair.publicKey.pubKey),
      appGlobalAuthKey = invalidAppEndorsementKeyPair.publicKey
    ).getOrThrow().publicKey.shouldBe(trustedContactIdentityKey.publicKey)
    // Both auth keys are not provided
    cryptoFake.verifyKeyCertificate(
      keyCertificate = keyCertificate,
      hwAuthKey = null,
      appGlobalAuthKey = null
    ).shouldBe(Err(SocRecCryptoError.AuthKeysNotPresent))
    // Invalid trusted keys
    shouldThrow<SocRecCryptoError.KeyCertificateVerificationFailed> {
      cryptoFake.verifyKeyCertificate(
        keyCertificate,
        HwAuthPublicKey(invalidHwEndorsementKeyPair.publicKey.pubKey),
        invalidAppEndorsementKeyPair.publicKey
      ).getOrThrow()
    }
    // Invalid key certificate
    val modifiedCertificate =
      keyCertificate.copy(
        appGlobalAuthPublicKey = invalidAppEndorsementKeyPair.publicKey
      )
    shouldThrow<SocRecCryptoError.KeyCertificateVerificationFailed> {
      cryptoFake.verifyKeyCertificate(
        modifiedCertificate,
        HwAuthPublicKey(hwEndorsementKeyPair.publicKey.pubKey),
        invalidAppEndorsementKeyPair.publicKey
      ).getOrThrow()
    }

    // Protected Customer creates encrypted backup
    val privateKeyMaterial =
      "wsh(sortedmulti(2," +
        "[9d3902ae/84'/1'/0']" +
        "tpubDCU8xtEiG4DZ8J5qNsGrCNWzm4WzPBPM2nKTAiZqZfA6m2GMcva1n" +
        "GRLsiLKpLktmuJrdWg9XKxpcSd9uafyPSLfACwToyvk43XQVs8SH6P/*," +
        "[ff2d9449/84'/1'/0']" + "" +
        "tpubDGm8VUmd9iJKkfFhJCVTVfJx5ezF4iiwr5MrpjfaWNtot46fq2L5v" +
        "skeHLrccYKhBFfQ1BReoxwPRHaoUVAouFTTWyzqLVv3or8EBVHzFp5/*," +
        "[56a09b24/84'/1'/0']" +
        "tpubDDe5r54a9Ajy7dF8w16WCWegTJgGXceZNyBw2vkRczvwm1ZcRgiUE" +
        "8RUX7uHgExeNtbhrKVsQN4Eb24sWRrwoLDUmdxSeM4a3kgQrJr5m7P/*" +
        "))"
    val encryptedPrivateKeyMaterialOutput =
      cryptoFake.encryptPrivateKeyMaterial(privateKeyMaterial.toByteArray().toByteString())
        .getOrThrow()
    val privateKeyEncryptionKey = encryptedPrivateKeyMaterialOutput.privateKeyEncryptionKey
    val sealedPrivateKeyMaterial = encryptedPrivateKeyMaterialOutput.sealedPrivateKeyMaterial
    val sealedPrivateKeyEncryptionKey =
      cryptoFake.encryptPrivateKeyEncryptionKey(
        decryptedTrustedContactIdentityKey,
        privateKeyEncryptionKey
      ).getOrThrow()

    // Trusted Contact assists Protected Customer with recovery
    val recoveryCode = PakeCode("12345678901".toByteArray().toByteString())
    val invalidRecoveryCode = PakeCode("12345678991".toByteArray().toByteString())
    val protectedCustomerRecoveryPakeKey =
      cryptoFake.generateProtectedCustomerRecoveryPakeKey(recoveryCode).getOrThrow()
    val encryptedPrivateKeyEncryptionKeyOutput =
      cryptoFake.decryptPrivateKeyEncryptionKey(
        recoveryCode,
        protectedCustomerRecoveryPakeKey,
        trustedContactIdentityKey,
        sealedPrivateKeyEncryptionKey
      ).getOrThrow()

    // Decryption by Protected Customer
    // TODO: add more invalid code tests, ala enrollment code
    shouldThrow<SocRecCryptoError.KeyConfirmationFailed> {
      cryptoFake.decryptPrivateKeyMaterial(
        invalidRecoveryCode,
        protectedCustomerRecoveryPakeKey,
        encryptedPrivateKeyEncryptionKeyOutput,
        sealedPrivateKeyMaterial
      ).getOrThrow()
    }
    cryptoFake.decryptPrivateKeyMaterial(
      recoveryCode,
      protectedCustomerRecoveryPakeKey,
      encryptedPrivateKeyEncryptionKeyOutput,
      sealedPrivateKeyMaterial
    ).getOrThrow().utf8().shouldBe(privateKeyMaterial)
  }
})
