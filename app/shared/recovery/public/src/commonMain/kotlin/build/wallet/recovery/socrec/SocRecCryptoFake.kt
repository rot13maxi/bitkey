package build.wallet.recovery.socrec

import build.wallet.bitcoin.AppPrivateKeyDao
import build.wallet.bitkey.app.AppGlobalAuthKeypair
import build.wallet.bitkey.app.AppGlobalAuthPrivateKey
import build.wallet.bitkey.app.AppGlobalAuthPublicKey
import build.wallet.bitkey.hardware.AppGlobalAuthKeyHwSignature
import build.wallet.bitkey.hardware.HwAuthPublicKey
import build.wallet.bitkey.keys.app.AppKey
import build.wallet.bitkey.keys.app.AppKeyImpl
import build.wallet.bitkey.socrec.DelegatedDecryptionKey
import build.wallet.bitkey.socrec.PakeCode
import build.wallet.bitkey.socrec.PrivateKeyEncryptionKey
import build.wallet.bitkey.socrec.ProtectedCustomerEnrollmentPakeKey
import build.wallet.bitkey.socrec.ProtectedCustomerIdentityKey
import build.wallet.bitkey.socrec.ProtectedCustomerRecoveryPakeKey
import build.wallet.bitkey.socrec.SocRecKey
import build.wallet.bitkey.socrec.TcIdentityKeyAppSignature
import build.wallet.bitkey.socrec.TrustedContactEnrollmentPakeKey
import build.wallet.bitkey.socrec.TrustedContactKeyCertificate
import build.wallet.bitkey.socrec.TrustedContactRecoveryPakeKey
import build.wallet.crypto.CurveType
import build.wallet.crypto.PrivateKey
import build.wallet.crypto.PublicKey
import build.wallet.encrypt.MessageSigner
import build.wallet.encrypt.Secp256k1PrivateKey
import build.wallet.encrypt.Secp256k1PublicKey
import build.wallet.encrypt.SignatureVerifier
import build.wallet.encrypt.XCiphertext
import build.wallet.encrypt.XNonce
import build.wallet.encrypt.XSealedData
import build.wallet.encrypt.toXSealedData
import build.wallet.encrypt.verifyEcdsaResult
import build.wallet.recovery.socrec.SocRecCryptoError.ErrorGettingPrivateKey
import build.wallet.recovery.socrec.SocRecCryptoError.PublicKeyMissing
import build.wallet.recovery.socrec.SocRecCryptoError.UnsupportedXCiphertextVersion
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.binding
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.toErrorIfNull
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.toBigInteger
import com.ionspin.kotlin.bignum.modular.ModularBigInteger
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.random.Random
import com.github.michaelbull.result.coroutines.binding.binding as suspendBinding

/**
 * This implementation uses simplified crypto operations to simulate the real
 * crypto operations. The crypto operations are not secure and should not be
 * used in production. IT IS EXTREMELY DANGEROUS AND RECKLESS TO USE THIS IN
 * PRODUCTION!
 *
 * The default randomness generator has a fixed seed, ensuring that key
 * generation is deterministic in tests.
 *
 * @param messageSigner used to create a key certificate. Note that if this is `null`,
 *  then [generateKeyCertificate] and [sign] will crash.
 * @param signatureVerifier used to verify a key certificate. Note that if this is `null`,
 *  then [verifyKeyCertificate] and [verifySig] will crash.
 */
class SocRecCryptoFake(
  private val messageSigner: MessageSigner? = null,
  private val signatureVerifier: SignatureVerifier? = null,
  private val appPrivateKeyDao: AppPrivateKeyDao? = null,
  private val random: Random = Random(0),
) : SocRecCrypto {
  private val g = Secp256k1.g()
  private val q = ModularBigInteger.creatorForModulo(Secp256k1.Q)

  /** Generates a usable but insecure key pair */
  private fun generateProtectedCustomerIdentityKey():
    Result<ProtectedCustomerIdentityKey, SocRecCryptoError> =
    Ok(ProtectedCustomerIdentityKey(generateAsymmetricKey()))

  /** Generates a usable but insecure key pair */
  override fun generateDelegatedDecryptionKey(): Result<DelegatedDecryptionKey, SocRecCryptoError> =
    Ok(DelegatedDecryptionKey(generateAsymmetricKey()))

  override fun generateProtectedCustomerEnrollmentPakeKey(
    password: PakeCode,
  ): Result<ProtectedCustomerEnrollmentPakeKey, SocRecCryptoError> {
    return Ok(
      ProtectedCustomerEnrollmentPakeKey(
        generatePakeKey(password)
      )
    )
  }

  override fun generateProtectedCustomerRecoveryPakeKey(
    password: PakeCode,
  ): Result<ProtectedCustomerRecoveryPakeKey, SocRecCryptoError> {
    return Ok(
      ProtectedCustomerRecoveryPakeKey(
        generatePakeKey(password)
      )
    )
  }

  private fun generatePakeKey(password: PakeCode): AppKey {
    // x ⭠ ℤ_q
    val privKey = randomBytes()
    val x = q.parseString(privKey.hex(), 16)
    // 'X = xG
    val basePubKey = g * x
    // X = 'X * H(password)
    val pubKey = basePubKey * q.parseString(password.bytes.sha256().hex(), 16)

    return AppKeyImpl(
      CurveType.SECP256K1,
      PublicKey(pubKey.secSerialize().hex()),
      PrivateKey(x.toByteArray().toByteString())
    )
  }

  override fun encryptDelegatedDecryptionKey(
    password: PakeCode,
    protectedCustomerEnrollmentPakeKey: ProtectedCustomerEnrollmentPakeKey,
    delegatedDecryptionKey: DelegatedDecryptionKey,
  ): Result<EncryptDelegatedDecryptionKeyOutput, SocRecCryptoError> {
    val trustedContactIdentityKeyBytes = delegatedDecryptionKey.publicKey.value.decodeHex()
    // Generate PAKE keys
    val secureChannelOutput =
      establishSecureChannel(
        password,
        protectedCustomerEnrollmentPakeKey.publicKey,
        trustedContactIdentityKeyBytes.size
      )
    // Encrypt TC Identity Key
    val trustedContactIdentityKeyCiphertext =
      trustedContactIdentityKeyBytes.xorWith(secureChannelOutput.sharedSecretKey)

    return Ok(
      EncryptDelegatedDecryptionKeyOutput(
        sealedDelegatedDecryptionKey =
          XSealedData(
            XSealedData.Header(algorithm = "SocRecCryptoFake"),
            ciphertext = trustedContactIdentityKeyCiphertext,
            nonce = XNonce(ByteString.EMPTY)
          ).toOpaqueCiphertext(),
        trustedContactEnrollmentPakeKey =
          TrustedContactEnrollmentPakeKey(
            secureChannelOutput.trustedContactPasswordAuthenticatedKey
          ),
        keyConfirmation = secureChannelOutput.keyConfirmation
      )
    )
  }

  override fun decryptDelegatedDecryptionKey(
    password: PakeCode,
    protectedCustomerEnrollmentPakeKey: ProtectedCustomerEnrollmentPakeKey,
    encryptDelegatedDecryptionKeyOutput: EncryptDelegatedDecryptionKeyOutput,
  ): Result<DelegatedDecryptionKey, SocRecCryptoError> =
    binding {
      val trustedContactIdentityKey =
        decryptSecureChannel(
          password,
          (protectedCustomerEnrollmentPakeKey.key as AppKeyImpl).privateKey!!,
          encryptDelegatedDecryptionKeyOutput.trustedContactEnrollmentPakeKey.publicKey,
          encryptDelegatedDecryptionKeyOutput.keyConfirmation,
          encryptDelegatedDecryptionKeyOutput.sealedDelegatedDecryptionKey
        ).bind()

      DelegatedDecryptionKey(
        AppKeyImpl(
          CurveType.SECP256K1,
          PublicKey(trustedContactIdentityKey.hex()),
          null
        )
      )
    }

  // Key certificates to automatically reject. For testing purposes.
  val invalidCertificates = mutableSetOf<TrustedContactKeyCertificate>()

  // Key certificates to automatically accept. For testing purposes.
  val validCertificates = mutableSetOf<TrustedContactKeyCertificate>()

  override fun verifyKeyCertificate(
    keyCertificate: TrustedContactKeyCertificate,
    hwAuthKey: HwAuthPublicKey?,
    appGlobalAuthKey: AppGlobalAuthPublicKey?,
  ): Result<DelegatedDecryptionKey, SocRecCryptoError> {
    if (hwAuthKey == null && appGlobalAuthKey == null) {
      return Err(SocRecCryptoError.AuthKeysNotPresent)
    }

    return binding {
      if (keyCertificate in invalidCertificates) {
        Err(
          SocRecCryptoError.KeyCertificateVerificationFailed(
            IllegalArgumentException("Invalid key certificate")
          )
        ).bind<SocRecCryptoError>()
      }

      val hwEndorsementKey = keyCertificate.hwAuthPublicKey
      val appEndorsementKey = keyCertificate.appGlobalAuthPublicKey
      // Check if the hwEndorsementKey matches the trusted key
      val isHwKeyTrusted = hwEndorsementKey == hwAuthKey
      // Check if the appEndorsementKey matches the trusted key
      val isAppKeyTrusted = appEndorsementKey == appGlobalAuthKey

      // Ensure at least one key matches a trusted key
      if (!isHwKeyTrusted && !isAppKeyTrusted) {
        Err(
          SocRecCryptoError.KeyCertificateVerificationFailed(
            IllegalArgumentException("None of the keys match the trusted keys provided")
          )
        ).bind<SocRecCryptoError>()
      }

      // Ensure at least one key matches a trusted key
      if ((keyCertificate !in validCertificates) && (
          !signatureVerifier!!.verifyEcdsaResult(
            signature = keyCertificate.appAuthGlobalKeyHwSignature.value,
            publicKey = hwEndorsementKey.pubKey,
            message = keyCertificate.appGlobalAuthPublicKey.pubKey.value.encodeUtf8()
          ).mapError { SocRecCryptoError.KeyCertificateVerificationFailed(it) }.bind() ||
            !signatureVerifier.verifyEcdsaResult(
              signature = keyCertificate.trustedContactIdentityKeyAppSignature.value,
              publicKey = appEndorsementKey.pubKey,
              message = keyCertificate.delegatedDecryptionKey.publicKey.value.encodeUtf8()
            ).mapError { SocRecCryptoError.KeyCertificateVerificationFailed(it) }.bind()
        )
      ) {
        Err(
          SocRecCryptoError.KeyCertificateVerificationFailed(
            IllegalArgumentException("Key certificate verification failed")
          )
        ).bind<SocRecCryptoError>()
      }

      keyCertificate.delegatedDecryptionKey
    }
  }

  override suspend fun generateKeyCertificate(
    delegatedDecryptionKey: DelegatedDecryptionKey,
    hwAuthKey: HwAuthPublicKey,
    appGlobalAuthKey: AppGlobalAuthPublicKey,
    appGlobalAuthKeyHwSignature: AppGlobalAuthKeyHwSignature,
  ): Result<TrustedContactKeyCertificate, SocRecCryptoError> =
    suspendBinding {
      val appAuthPrivateKey = appPrivateKeyDao!!
        .getGlobalAuthKey(appGlobalAuthKey)
        .mapError(::ErrorGettingPrivateKey)
        .toErrorIfNull { SocRecCryptoError.PrivateKeyMissing }
        .bind()

      val appSignature =
        sign(
          privateKey = appAuthPrivateKey.key,
          message = delegatedDecryptionKey.publicKey.value.encodeUtf8()
        ).hex().let(::TcIdentityKeyAppSignature)

      TrustedContactKeyCertificate(
        delegatedDecryptionKey = delegatedDecryptionKey,
        hwAuthPublicKey = hwAuthKey,
        appGlobalAuthPublicKey = appGlobalAuthKey,
        appAuthGlobalKeyHwSignature = appGlobalAuthKeyHwSignature,
        trustedContactIdentityKeyAppSignature = appSignature
      )
    }

  override fun <T : SocRecKey> generateAsymmetricKey(
    factory: (AppKey) -> T,
  ): Result<T, SocRecCryptoError> = Ok(factory(generateAsymmetricKey()))

  private fun generateAsymmetricKey(): AppKey {
    val (privKey, pubKey) = generateKeyPair()

    return AppKeyImpl(
      CurveType.SECP256K1,
      PublicKey(pubKey.value),
      PrivateKey(privKey.bytes)
    )
  }

  /**
   * Generates a ciphertext and 256-bit key with an insecure RNG and an insecure
   * encryption algorithm (i.e. naive key expansion and XOR).
   */
  override fun encryptPrivateKeyMaterial(
    privateKeyMaterial: ByteString,
  ): Result<EncryptPrivateKeyMaterialOutput, SocRecCryptoError> {
    val privateKeyEncryptionKey = randomBytes()
    val expandedKey = expandKey(privateKeyEncryptionKey, privateKeyMaterial.size)
    val privateKeyMaterialCiphertext = privateKeyMaterial.xorWith(expandedKey)
    return Ok(
      EncryptPrivateKeyMaterialOutput(
        sealedPrivateKeyMaterial =
          XSealedData(
            XSealedData.Header(algorithm = "SocRecCryptoFake"),
            ciphertext = privateKeyMaterialCiphertext,
            nonce = XNonce(ByteString.EMPTY)
          ).toOpaqueCiphertext(),
        privateKeyEncryptionKey =
          PrivateKeyEncryptionKey(
            SymmetricKeyFake(privateKeyEncryptionKey)
          )
      )
    )
  }

  /**
   * Generates a ciphertext with an insecure Diffie-Hellman derivation and an
   * insecure encryption algorithm (i.e. naive key expansion and XOR).
   */
  override fun encryptPrivateKeyEncryptionKey(
    delegatedDecryptionKey: DelegatedDecryptionKey,
    privateKeyEncryptionKey: PrivateKeyEncryptionKey,
  ): Result<XCiphertext, SocRecCryptoError> =
    binding {
      require(privateKeyEncryptionKey.key is SymmetricKeyFake)
      val protectedCustomerIdentityKey = generateProtectedCustomerIdentityKey().bind()
      val keyMat = (privateKeyEncryptionKey.key as SymmetricKeyFake).raw
      val deserializedPubKey =
        Point.secDeserialize(
          delegatedDecryptionKey.publicKey.value.decodeHex()
        )
      val deserializedPrivKey =
        q.parseString(
          (protectedCustomerIdentityKey.key as AppKeyImpl).privateKey!!.bytes.hex(),
          16
        )
      val sharedSecret = deserializedPubKey * deserializedPrivKey

      val expandedKey =
        expandKey(
          sharedSecret.secSerialize(),
          privateKeyEncryptionKey.length
        )
      val privateKeyEncryptionKeyCiphertext = keyMat.xorWith(expandedKey)

      XSealedData(
        header = XSealedData.Header(algorithm = "SocRecCryptoFake", version = 2),
        ciphertext = privateKeyEncryptionKeyCiphertext,
        nonce = XNonce(ByteString.EMPTY),
        publicKey = protectedCustomerIdentityKey.publicKey
      ).toOpaqueCiphertext()
    }

  override fun decryptPrivateKeyEncryptionKey(
    password: PakeCode,
    protectedCustomerRecoveryPakeKey: ProtectedCustomerRecoveryPakeKey,
    delegatedDecryptionKey: DelegatedDecryptionKey,
    sealedPrivateKeyEncryptionKey: XCiphertext,
  ): Result<DecryptPrivateKeyEncryptionKeyOutput, SocRecCryptoError> {
    val sealedPrivateKeyEncryptionKeyData = sealedPrivateKeyEncryptionKey.toXSealedData()
    if (sealedPrivateKeyEncryptionKeyData.header.version != 2) {
      return Err(UnsupportedXCiphertextVersion)
    }
    // Generate PAKE keys
    val secureChannelOutput =
      establishSecureChannel(
        password,
        protectedCustomerRecoveryPakeKey.publicKey,
        sealedPrivateKeyEncryptionKey.toXSealedData().ciphertext.size
      )
    val protectedCustomerIdentityPubKey = sealedPrivateKeyEncryptionKeyData.publicKey
      ?: return Err(PublicKeyMissing)
    val deserializedIdentityPubKey =
      Point.secDeserialize(
        protectedCustomerIdentityPubKey.value.decodeHex()
      )
    val deserializedIdentityPrivKey =
      q.parseString(
        (delegatedDecryptionKey.key as AppKeyImpl).privateKey!!.bytes.hex(),
        16
      )
    val identitySharedSecret = deserializedIdentityPubKey * deserializedIdentityPrivKey
    val identitySharedSecretKey =
      expandKey(
        // Expand the shared secret to the size of the private key encryption key
        identitySharedSecret.secSerialize(),
        32
      )
    // Decrypt PKEK
    val privateKeyEncryptionKey =
      sealedPrivateKeyEncryptionKey.toXSealedData().ciphertext.xorWith(identitySharedSecretKey)
    // Encrypt PKEK with PAKE shared secret
    val pakeSealedPrivateKeyEncryptionKey =
      privateKeyEncryptionKey.xorWith(
        secureChannelOutput.sharedSecretKey
      )

    return Ok(
      DecryptPrivateKeyEncryptionKeyOutput(
        trustedContactRecoveryPakeKey =
          TrustedContactRecoveryPakeKey(
            secureChannelOutput.trustedContactPasswordAuthenticatedKey
          ),
        keyConfirmation = secureChannelOutput.keyConfirmation,
        sealedPrivateKeyEncryptionKey =
          XSealedData(
            XSealedData.Header(algorithm = "SocRecCryptoFake"),
            ciphertext = pakeSealedPrivateKeyEncryptionKey,
            nonce = XNonce(ByteString.EMPTY)
          ).toOpaqueCiphertext()
      )
    )
  }

  override fun decryptPrivateKeyMaterial(
    password: PakeCode,
    protectedCustomerRecoveryPakeKey: ProtectedCustomerRecoveryPakeKey,
    decryptPrivateKeyEncryptionKeyOutput: DecryptPrivateKeyEncryptionKeyOutput,
    sealedPrivateKeyMaterial: XCiphertext,
  ): Result<ByteString, SocRecCryptoError> =
    // Decrypt PKEK
    binding {
      val privateKeyEncryptionKey =
        decryptSecureChannel(
          password,
          (protectedCustomerRecoveryPakeKey.key as AppKeyImpl).privateKey!!,
          decryptPrivateKeyEncryptionKeyOutput.trustedContactRecoveryPakeKey.publicKey,
          decryptPrivateKeyEncryptionKeyOutput.keyConfirmation,
          decryptPrivateKeyEncryptionKeyOutput.sealedPrivateKeyEncryptionKey
        ).bind()
      // Decrypt PKMat
      val expandedPrivateKeyEncryptionKey =
        expandKey(privateKeyEncryptionKey, sealedPrivateKeyMaterial.toXSealedData().ciphertext.size)

      sealedPrivateKeyMaterial.toXSealedData().ciphertext.xorWith(expandedPrivateKeyEncryptionKey)
    }

  private data class EstablishSecureChannelOutput(
    val trustedContactPasswordAuthenticatedKey: AppKey,
    val keyConfirmation: ByteString,
    val sharedSecretKey: ByteString,
  )

  private fun establishSecureChannel(
    password: PakeCode,
    protectedCustomerPasswordAuthenticatedKey: PublicKey,
    length: Int,
  ): EstablishSecureChannelOutput {
    // Generate TC PAKE Key
    // x ⭠ ℤ_q
    val privKey = randomBytes()
    val x = q.parseString(privKey.hex(), 16)
    // 'X = xG
    val basePubKey = g * x
    // X = X * H(password)
    val (passwordHashInt, invPasswordHashInt) = derivePasswordHashIntegers(password.bytes)
    val pubKey = basePubKey * passwordHashInt
    val trustedContactPasswordAuthenticatedKey =
      AppKeyImpl(
        CurveType.SECP256K1,
        PublicKey(pubKey.secSerialize().hex()),
        PrivateKey(x.toByteArray().toByteString())
      )
    // Tweak PC PAKE Key
    val deserializedProtectedCustomerPasswordAuthenticatedKey =
      Point.secDeserialize(
        protectedCustomerPasswordAuthenticatedKey.value.decodeHex()
      )
    val tweakedProtectedCustomerPasswordAuthenticatedKey =
      deserializedProtectedCustomerPasswordAuthenticatedKey * invPasswordHashInt
    // Derive PAKE shared secret
    val sharedSecret = tweakedProtectedCustomerPasswordAuthenticatedKey * x
    // Generate key confirmation
    val keyConfirmationString = "SocRecKeyConfirmation".encodeUtf8()
    val keyConfirmation = keyConfirmationString.hmacSha256(sharedSecret.secSerialize())
    // Generate expanded shared secret key for the specified length
    val sharedSecretKey =
      expandKey(
        sharedSecret.secSerialize(),
        length
      )

    return EstablishSecureChannelOutput(
      trustedContactPasswordAuthenticatedKey = trustedContactPasswordAuthenticatedKey,
      keyConfirmation = keyConfirmation,
      sharedSecretKey = sharedSecretKey
    )
  }

  private fun decryptSecureChannel(
    password: PakeCode,
    protectedCustomerPasswordAuthenticatedKey: PrivateKey,
    trustedContactPasswordAuthenticatedKey: PublicKey,
    keyConfirmation: ByteString,
    sealedData: XCiphertext,
  ): Result<ByteString, SocRecCryptoError> {
    // Tweak TC PAKE Key
    val deserializedTrustedContactPasswordAuthenticatedKey =
      Point.secDeserialize(
        trustedContactPasswordAuthenticatedKey.value.decodeHex()
      )
    val deserializedProtectedCustomerAuthenticatedPrivKey =
      q.parseString(
        protectedCustomerPasswordAuthenticatedKey.bytes.hex(),
        16
      )
    val (_, invPasswordHashInt) = derivePasswordHashIntegers(password.bytes)
    val tweakedTrustedContactPasswordAuthenticatedKey =
      deserializedTrustedContactPasswordAuthenticatedKey * invPasswordHashInt
    // Generate PAKE shared secret
    val sharedSecret =
      tweakedTrustedContactPasswordAuthenticatedKey *
        deserializedProtectedCustomerAuthenticatedPrivKey
    // Validate key confirmation
    val keyConfirmationString = "SocRecKeyConfirmation".encodeUtf8()
    val calculatedKeyConfirmation = keyConfirmationString.hmacSha256(sharedSecret.secSerialize())
    if (calculatedKeyConfirmation != keyConfirmation) {
      return Err(
        SocRecCryptoError.KeyConfirmationFailed(
          IllegalArgumentException("Key confirmation mismatch")
        )
      )
    }
    // Decrypt sealed data
    val expandedKey =
      expandKey(
        sharedSecret.secSerialize(),
        sealedData.toXSealedData().ciphertext.size
      )
    return Ok(sealedData.toXSealedData().ciphertext.xorWith(expandedKey))
  }

  private object Secp256k1 {
    val P =
      BigInteger.parseString(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",
        16
      )
    val Q =
      BigInteger.parseString(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
        16
      )
    val Gx =
      BigInteger.parseString(
        "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798",
        16
      )
    val Gy =
      BigInteger.parseString(
        "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8",
        16
      )

    val creator = ModularBigInteger.creatorForModulo(P)
    val modGx = creator.fromBigInteger(Gx)
    val modGy = creator.fromBigInteger(Gy)

    fun g() = Point(modGx, modGy)
  }

  private class Point(x: ModularBigInteger? = null, y: ModularBigInteger? = null) {
    val x: ModularBigInteger?
    val y: ModularBigInteger?

    init {
      val p = Secp256k1.P
      require((x == null || x.modulus == p) && (y == null || y.modulus == p)) {
        "Moduli do not match"
      }
      this.x = x
      this.y = y
    }

    companion object {
      fun secDeserialize(hexPublicKey: ByteString): Point {
        val p = Secp256k1.P
        val creator = ModularBigInteger.creatorForModulo(p)
        // Parse the parity byte
        val isEven = hexPublicKey[0].toInt() == 2
        // Parse the x coordinate
        val x = BigInteger.parseString(hexPublicKey.substring(1).hex(), 16)
        val xMod = creator.fromBigInteger(x)
        // y^2 = x^3 + 7
        val ySquared = xMod.pow(3) + 7
        // y = (y^2)^((p + 1) / 4) mod p
        val possibleY = ySquared.pow((p + 1) / 4).toBigInteger()

        val evenParity = possibleY.mod(2.toBigInteger()) == BigInteger.ZERO && isEven
        val oddParity = possibleY.mod(2.toBigInteger()) != BigInteger.ZERO && !isEven
        val parityMatch = evenParity || oddParity
        val y = if (parityMatch) possibleY else p - possibleY
        val yMod = creator.fromBigInteger(y)

        return Point(xMod, yMod)
      }
    }

    fun secSerialize(): ByteString {
      // Point at infinity
      if (this.x == null || this.y == null) return "00".decodeHex()
      // Parity byte
      val isEven = this.y.toBigInteger().mod(2.toBigInteger()) == BigInteger.ZERO
      val prefix = if (isEven) "02" else "03"
      // x coordinate
      val xBytes = this.x.toByteArray()

      return (
        prefix.decodeHex().toByteArray() + xBytes
      ).toByteString()
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other == null || other !is Point) return false

      return x == other.x && y == other.y
    }

    operator fun unaryMinus(): Point {
      if (this.x == null || this.y == null) return this
      if (this.x.isZero() && this.y.isZero()) return this
      val creator = ModularBigInteger.creatorForModulo(Secp256k1.P)
      val negY = creator.fromBigInteger(Secp256k1.P - this.y.toBigInteger())

      return Point(this.x, negY)
    }

    operator fun plus(other: Point): Point {
      // Points at infinity
      if (this.x == null || this.y == null) return other
      if (other.x == null || other.y == null) return this
      // Tangent line is vertical
      if (this == other && this.y.isZero()) {
        return Point()
      }
      // Opposite points
      if (this.x == other.x && this.y != other.y) return Point()

      // Same point
      if (this == other) {
        val s = (this.x * this.x * 3) / (this.y * 2)
        val x = s * s - this.x * 2
        val y = s * (this.x - x) - this.y
        return Point(x, y)
      }

      // Different points
      val s = (other.y - this.y) / (other.x - this.x)
      val x = s * s - this.x - other.x
      val y = s * (this.x - x) - this.y
      return Point(x, y)
    }

    operator fun times(scalar: ModularBigInteger): Point {
      var currentScalar = scalar.toBigInteger()
      var currentPoint = this
      var sumPoint = Point()

      while (currentScalar != BigInteger.ZERO) {
        if (currentScalar and BigInteger.ONE != BigInteger.ZERO) {
          sumPoint += currentPoint
        }
        currentPoint += currentPoint
        currentScalar = currentScalar shr 1
      }

      return sumPoint
    }

    override fun hashCode(): Int {
      var result = x.hashCode()
      result = 31 * result + y.hashCode()
      return result
    }
  }

  private fun ByteString.xorWith(other: ByteString): ByteString {
    require(size == other.size) { "ByteStrings must be of the same length" }

    val result = ByteArray(size)
    for (i in 0 until size) {
      result[i] =
        (
          this[i].toInt() xor other[i].toInt()
        ).toByte()
    }

    return result.toByteString()
  }

  private fun expandKey(
    key: ByteString,
    length: Int,
  ): ByteString {
    val result = ByteArray(length)
    var currentLength = 0

    var index = BigInteger.ZERO
    while (currentLength < length) {
      val combined = (key.toByteArray() + index.toByteArray())
      val hashBytes = combined.toByteString().sha256().toByteArray()

      for (byte in hashBytes) {
        if (currentLength >= length) break
        result[currentLength] = byte
        currentLength++
      }

      index++
    }

    return result.toByteString()
  }

  private fun randomBytes(): ByteString {
    // Generates a random 256-bit key from an RNG that is not cryptographically
    // secure.
    val randomBytes = ByteArray(32)
    random.nextBytes(randomBytes)

    return randomBytes.toByteString()
  }

  private fun derivePasswordHashIntegers(
    password: ByteString,
  ): Pair<ModularBigInteger, ModularBigInteger> {
    val passwordHash = password.sha256().hex()
    val passwordHashInt = q.parseString(passwordHash, 16)
    // Compute modular multiplicative inverse of the password hash using Fermat's Little Theorem
    val invPasswordHashInt = passwordHashInt.pow(Secp256k1.Q - 2)

    return Pair(passwordHashInt, invPasswordHashInt)
  }

  fun sign(
    privateKey: Secp256k1PrivateKey,
    message: ByteString,
  ): ByteString = messageSigner!!.sign(message, privateKey).decodeHex()

  fun generateKeyPair(): Pair<Secp256k1PrivateKey, Secp256k1PublicKey> {
    // x ⭠ ℤ_q
    val privKey = randomBytes()
    val x = q.parseString(privKey.hex(), 16)
    // X = xG
    val pubKey = g * x

    return Pair(
      Secp256k1PrivateKey(x.toByteArray().toByteString()),
      Secp256k1PublicKey(pubKey.secSerialize().hex())
    )
  }

  suspend fun generateAppAuthKeypair(): AppGlobalAuthKeypair {
    val (privKey, pubKey) = generateKeyPair()
    return AppGlobalAuthKeypair(
      publicKey = AppGlobalAuthPublicKey(pubKey),
      privateKey = AppGlobalAuthPrivateKey(privKey)
    ).also {
      appPrivateKeyDao!!.storeAppAuthKeyPair(it)
    }
  }

  fun reset() {
    validCertificates.clear()
    invalidCertificates.clear()
  }
}
