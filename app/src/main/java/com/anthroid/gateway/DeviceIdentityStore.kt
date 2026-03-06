package com.anthroid.gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

data class DeviceIdentity(
  val deviceId: String,
  val publicKeyRawBase64: String,
  val privateKeyPkcs8Base64: String,
  val createdAtMs: Long,
)

class DeviceIdentityStore(context: Context) {
  private val identityFile = File(context.filesDir, "gateway/identity/device.json")
  @Volatile private var cachedIdentity: DeviceIdentity? = null

  @Synchronized
  fun loadOrCreate(): DeviceIdentity {
    cachedIdentity?.let { return it }
    val existing = load()
    if (existing != null) {
      val derived = deriveDeviceId(existing.publicKeyRawBase64)
      if (derived != null && derived != existing.deviceId) {
        val updated = existing.copy(deviceId = derived)
        save(updated)
        cachedIdentity = updated
        return updated
      }
      cachedIdentity = existing
      return existing
    }
    val fresh = generate()
    save(fresh)
    cachedIdentity = fresh
    return fresh
  }

  fun signPayload(payload: String, identity: DeviceIdentity): String? {
    return try {
      val privateKeyBytes = Base64.decode(identity.privateKeyPkcs8Base64, Base64.DEFAULT)
      val pkInfo = org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(privateKeyBytes)
      val parsed = pkInfo.parsePrivateKey()
      val rawPrivate = org.bouncycastle.asn1.DEROctetString.getInstance(parsed).octets
      val privateKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(rawPrivate, 0)
      val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
      signer.init(true, privateKey)
      val payloadBytes = payload.toByteArray(Charsets.UTF_8)
      signer.update(payloadBytes, 0, payloadBytes.size)
      base64UrlEncode(signer.generateSignature())
    } catch (e: Throwable) {
      Log.e(TAG, "signPayload FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
      null
    }
  }

  fun publicKeyBase64Url(identity: DeviceIdentity): String? {
    return try {
      val raw = Base64.decode(identity.publicKeyRawBase64, Base64.DEFAULT)
      base64UrlEncode(raw)
    } catch (_: Throwable) {
      null
    }
  }

  private fun load(): DeviceIdentity? {
    return try {
      if (!identityFile.exists()) return null
      val obj = JSONObject(identityFile.readText(Charsets.UTF_8))
      val deviceId = obj.optString("deviceId", "")
      val publicKey = obj.optString("publicKeyRawBase64", "")
      val privateKey = obj.optString("privateKeyPkcs8Base64", "")
      val createdAt = obj.optLong("createdAtMs", 0)
      if (deviceId.isBlank() || publicKey.isBlank() || privateKey.isBlank()) null
      else DeviceIdentity(deviceId, publicKey, privateKey, createdAt)
    } catch (_: Throwable) {
      null
    }
  }

  private fun save(identity: DeviceIdentity) {
    try {
      identityFile.parentFile?.mkdirs()
      val obj = JSONObject().apply {
        put("deviceId", identity.deviceId)
        put("publicKeyRawBase64", identity.publicKeyRawBase64)
        put("privateKeyPkcs8Base64", identity.privateKeyPkcs8Base64)
        put("createdAtMs", identity.createdAtMs)
      }
      identityFile.writeText(obj.toString(2), Charsets.UTF_8)
    } catch (_: Throwable) {
      // best-effort only
    }
  }

  private fun generate(): DeviceIdentity {
    val kpGen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
    kpGen.init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(java.security.SecureRandom()))
    val kp = kpGen.generateKeyPair()
    val pubKey = kp.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
    val privKey = kp.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
    val rawPublic = pubKey.encoded  // 32 bytes
    val deviceId = sha256Hex(rawPublic)
    val privKeyInfo = org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(privKey)
    val pkcs8Bytes = privKeyInfo.encoded
    return DeviceIdentity(
      deviceId = deviceId,
      publicKeyRawBase64 = Base64.encodeToString(rawPublic, Base64.NO_WRAP),
      privateKeyPkcs8Base64 = Base64.encodeToString(pkcs8Bytes, Base64.NO_WRAP),
      createdAtMs = System.currentTimeMillis(),
    )
  }

  private fun deriveDeviceId(publicKeyRawBase64: String): String? {
    return try {
      val raw = Base64.decode(publicKeyRawBase64, Base64.DEFAULT)
      sha256Hex(raw)
    } catch (_: Throwable) {
      null
    }
  }

  private fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(data)
    val out = CharArray(digest.size * 2)
    var i = 0
    for (byte in digest) {
      val v = byte.toInt() and 0xff
      out[i++] = HEX[v ushr 4]
      out[i++] = HEX[v and 0x0f]
    }
    return String(out)
  }

  private fun base64UrlEncode(data: ByteArray): String {
    return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  }

  companion object {
    private const val TAG = "DeviceIdentity"
    private val HEX = "0123456789abcdef".toCharArray()
  }
}
