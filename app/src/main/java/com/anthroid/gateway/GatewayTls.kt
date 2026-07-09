package com.anthroid.gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import com.anthroid.R
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for the gateway OkHttpClient.
 *
 * Security model — fixes the prior global-trust blast radius:
 *  - The kelvin-tc gateway serves a self-signed cert (res/raw/kelvin_gateway.pem,
 *    CN + SAN IP:124.221.209.247). It is trusted ONLY by this client's TrustManager,
 *    NOT via network_security_config <base-config>. Other app channels (Termux package
 *    mirror, GitHub model download) keep system-only trust, so a leaked kelvin server
 *    key can no longer MITM them.
 *  - An SPKI pin (sha256 of the cert's SubjectPublicKeyInfo) is set for the cert's SAN
 *    host. Trusting the kelvin cert as a root still accepts attacker-signed leaves that
 *    chain to it; the pin rejects any leaf whose public key differs — i.e. it defeats a
 *    leaked server PRIVATE key on the gateway channel itself.
 *  - Both the trusted cert and the pin derive from the SAME bundled PEM, so they cannot
 *    drift. If the server cert is regenerated without updating the PEM, both checks fail
 *    closed (a clear TLS error, not a silent hole).
 *
 * Cleartext ws://:80 (tls=false) needs none of this and keeps working even if TLS setup
 * fails — sslSocketFactory/certificatePinner only affect wss:// handshakes.
 *
 * Future real-CA domain switch: a domain host carries no pin here (only the kelvin SAN
 * host is pinned) and its cert is not the kelvin cert, so it would be rejected by this
 * TrustManager. Switching to a real CA means dropping this custom TrustManager + pin in
 * favor of the platform default — a deliberate, small change.
 */
internal object GatewayTls {
  private const val TAG = "AnthroidGatewayTls"

  /**
   * Adds a gateway-scoped TLS trust manager + SPKI pin to [builder]. Returns the builder
   * unchanged (and logs) if the pinned cert can't be loaded — cleartext ws:// still
   * works; wss:// then fails with a cert error rather than crashing client construction.
   */
  fun configure(builder: OkHttpClient.Builder, context: Context): OkHttpClient.Builder {
    val cert = try {
      loadCert(context)
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to load kelvin_gateway.pem; wss:// disabled (cleartext ws:// still works): ${e.message}")
      return builder
    }

    val trustManager = trustManagerFor(cert)
    val sslContext = SSLContext.getInstance("TLS").apply {
      init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
    builder.sslSocketFactory(sslContext.socketFactory, trustManager)

    val pinHost = sanIpHost(cert)
    val spki = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
    val pin = "sha256/" + Base64.encodeToString(spki, Base64.NO_WRAP)
    builder.certificatePinner(CertificatePinner.Builder().add(pinHost, pin).build())

    Log.i(TAG, "Gateway TLS configured: kelvin-only trust + SPKI pin for $pinHost")
    return builder
  }

  private fun loadCert(context: Context): X509Certificate {
    val pem = context.resources.openRawResource(R.raw.kelvin_gateway).use { it.readBytes() }
    return CertificateFactory.getInstance("X.509")
      .generateCertificate(ByteArrayInputStream(pem)) as X509Certificate
  }

  /** TrustManager that trusts ONLY the kelvin gateway cert (as a trusted root). */
  private fun trustManagerFor(cert: X509Certificate): X509TrustManager {
    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null, null)
      setCertificateEntry("kelvin-gateway", cert)
    }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(ks)
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
  }

  /** The connect host OkHttp will pin/verify — the IP from the cert's SAN. */
  private fun sanIpHost(cert: X509Certificate): String {
    val sans = cert.subjectAlternativeNames
      ?: throw IllegalStateException("kelvin_gateway.pem has no SAN; IP-host verification would fail")
    for (san in sans) {
      val type = san.getOrNull(0) as? Int ?: continue
      if (type == 7) { // GeneralName type 7 = iPAddress
        // X509Certificate.getSubjectAlternativeNames() renders an IP entry's value either as
        // a pre-formatted String ("124.221.209.247", e.g. the RI/JDK) or as a raw byte[]
        // (some providers). Accept both — never crash on the form the running VM picks.
        return when (val ip = san.getOrNull(1)) {
          is String -> ip
          is ByteArray -> InetAddress.getByAddress(ip).hostAddress
            ?: throw IllegalStateException("could not render SAN IP bytes to a host string")
          else -> continue
        }
      }
    }
    throw IllegalStateException("kelvin_gateway.pem SAN has no IP entry")
  }
}
