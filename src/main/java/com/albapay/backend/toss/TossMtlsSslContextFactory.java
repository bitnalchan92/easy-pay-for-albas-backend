package com.albapay.backend.toss;

import com.albapay.backend.config.AlbapayProperties;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the client-certificate SSLContext used to call Toss's mTLS-protected OAuth endpoints.
 * Mirrors the Node `https.Agent({ cert, key, ca })` setup from the original api/toss/_utils.ts,
 * using only the base64 env vars (TOSS_MTLS_CERT/KEY/CA) already wired in application.yml —
 * no local file-path fallback, since this backend's config doesn't expose one.
 */
final class TossMtlsSslContextFactory {

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);

    private TossMtlsSslContextFactory() {}

    static SSLContext build(AlbapayProperties.Toss toss) {
        try {
            String certPem = decodeToPem(toss.getMtlsCert(), "TOSS_MTLS_CERT");
            String keyPem = decodeToPem(toss.getMtlsKey(), "TOSS_MTLS_KEY");

            X509Certificate certificate = parseCertificate(certPem);
            PrivateKey privateKey = parsePrivateKey(keyPem);

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            char[] noPassword = new char[0];
            keyStore.setKeyEntry("toss-client", privateKey, noPassword, new Certificate[]{certificate});

            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, noPassword);

            TrustManagerFactory trustManagerFactory = buildTrustManagerFactory(toss.getMtlsCa());

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    keyManagerFactory.getKeyManagers(),
                    trustManagerFactory.getTrustManagers(),
                    new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("Toss mTLS SSLContext 초기화에 실패했습니다.", e);
        }
    }

    private static TrustManagerFactory buildTrustManagerFactory(String caB64) throws Exception {
        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (caB64 == null || caB64.isBlank()) {
            // no custom CA configured -> fall back to the JVM default trust store,
            // matching Node's behavior when `ca` is left undefined.
            trustManagerFactory.init((KeyStore) null);
            return trustManagerFactory;
        }

        String caPem = decodeToPem(caB64, "TOSS_MTLS_CA");
        X509Certificate caCert = parseCertificate(caPem);
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("toss-ca", caCert);
        trustManagerFactory.init(trustStore);
        return trustManagerFactory;
    }

    private static String decodeToPem(String base64Value, String envName) {
        if (base64Value == null || base64Value.isBlank()) {
            throw new IllegalStateException(envName + " 환경변수가 필요합니다.");
        }
        return new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8);
    }

    private static X509Certificate parseCertificate(String pem) throws Exception {
        Matcher matcher = PEM_BLOCK.matcher(pem);
        if (!matcher.find() || !matcher.group(1).contains("CERTIFICATE")) {
            throw new IllegalStateException("PEM 인증서 블록을 찾을 수 없습니다.");
        }
        byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(der));
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        Matcher matcher = PEM_BLOCK.matcher(pem);
        if (!matcher.find()) {
            throw new IllegalStateException("PEM 개인키 블록을 찾을 수 없습니다.");
        }
        String header = matcher.group(1);
        if (!header.equals("PRIVATE KEY")) {
            // ponytail: PKCS1 ("RSA PRIVATE KEY") not supported without a PEM/ASN.1 library.
            // Upgrade path: convert once with `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.p8`
            // and store the PKCS8 result in TOSS_MTLS_KEY, or add Bouncy Castle if runtime conversion is required.
            throw new IllegalStateException(
                    "지원하지 않는 개인키 형식입니다 (PKCS8 'PRIVATE KEY'만 지원). "
                            + "openssl pkcs8 -topk8 -nocrypt 로 변환 후 TOSS_MTLS_KEY에 설정하세요.");
        }
        byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (Exception rsaFailure) {
            return KeyFactory.getInstance("EC").generatePrivate(keySpec);
        }
    }
}
