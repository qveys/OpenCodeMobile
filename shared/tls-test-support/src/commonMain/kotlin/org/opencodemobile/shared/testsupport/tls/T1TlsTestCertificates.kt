package org.opencodemobile.shared.testsupport.tls

/**
 * Throwaway, test-only self-signed certificate material for the T1
 * real-handshake validation (OPE-94).
 *
 * These are **not** credentials and protect nothing: they were generated with
 * OpenSSL solely so the T1 tests can run a local TLS peer that presents a
 * self-signed certificate, exactly like a self-hosted OpenCode server.
 *
 * Generated with:
 * ```
 * openssl ecparam -name prime256v1 -genkey -noout -out key.pem
 * openssl req -new -x509 -key key.pem -out cert.pem -days 3650 \
 *   -subj "/CN=opencode-t1-test-<a|b>" -sha256
 * openssl x509 -in cert.pem -pubkey -noout | openssl pkey -pubin -outform der \
 *   | openssl dgst -sha256
 * ```
 * The [FINGERPRINT_*] digests below are the independently computed OpenSSL
 * SPKI SHA-256 values; `ServerFingerprintFixtureTest` and the device tests
 * assert the app's shared DER walk produces the same value.
 *
 * Certificate A is the "genuine" server; certificate B is the replacement used
 * to prove the fail-closed path (pinned A, presented B).
 */
public object T1TlsTestCertificates {

    /** Fixed ports the iOS simulator test connects to; CI starts the host servers on them. */
    public const val SIMULATOR_PORT_A: Int = 9943
    public const val SIMULATOR_PORT_B: Int = 9944

    public const val FINGERPRINT_A_HEX: String =
        "dfc8a1abd35caf18da0d4e8c4e004d62bcfb761cff1ce49ebd90839ba016f0f3"

    public const val FINGERPRINT_A_COLON: String =
        "df:c8:a1:ab:d3:5c:af:18:da:0d:4e:8c:4e:00:4d:62:" +
            "bc:fb:76:1c:ff:1c:e4:9e:bd:90:83:9b:a0:16:f0:f3"

    public const val FINGERPRINT_B_HEX: String =
        "d934a19ab6609bd2cb22bc923bd49ede2b897fd8490ab31c8167eff79d602678"

    public const val FINGERPRINT_B_COLON: String =
        "d9:34:a1:9a:b6:60:9b:d2:cb:22:bc:92:3b:d4:9e:de:" +
            "2b:89:7f:d8:49:0a:b3:1c:81:67:ef:f7:9d:60:26:78"

    public const val CERT_A_PEM: String = """-----BEGIN CERTIFICATE-----
MIIBkDCCATWgAwIBAgIUOAaEKYALOJ/eN9YG92DMDP8sPx8wCgYIKoZIzj0EAwIw
HTEbMBkGA1UEAwwSb3BlbmNvZGUtdDEtdGVzdC1hMB4XDTI2MDkyNjA0MjgyOVoX
DTM2MDkyMzA0MjgyOVowHTEbMBkGA1UEAwwSb3BlbmNvZGUtdDEtdGVzdC1hMFkw
EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEuy9fIEpwTksNAdBRvuGYamYltDsh3AN5
cJqv7sBc/Iai7vToxlWniCtqGfFZWXR0vH8bt6Rf73gMXQulwHFBGqNTMFEwHQYD
VR0OBBYEFEM3kz7IVMAHuQd9+JL5/d2Npn0FMB8GA1UdIwQYMBaAFEM3kz7IVMAH
uQd9+JL5/d2Npn0FMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSQAwRgIh
APlygP0C9PEZSQ0MqlLs/BFCCqngGIR8o8XhVssU5K2fAiEAu04hqR9X4n123cNY
og0c/GIG7j1OuuDs+czjevJGynw=
-----END CERTIFICATE-----"""

    public const val KEY_A_PKCS8_PEM: String = """-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgP7WIdaGm0F5AUJCb
WRvQ4oN7jO2km6DeWUgD8wj014GhRANCAAS7L18gSnBOSw0B0FG+4ZhqZiW0OyHc
A3lwmq/uwFz8hqLu9OjGVaeIK2oZ8VlZdHS8fxu3pF/veAxdC6XAcUEa
-----END PRIVATE KEY-----"""

    public const val CERT_B_PEM: String = """-----BEGIN CERTIFICATE-----
MIIBjzCCATWgAwIBAgIUT+58ha7m6u5hjJEFYW7yMHMPqZAwCgYIKoZIzj0EAwIw
HTEbMBkGA1UEAwwSb3BlbmNvZGUtdDEtdGVzdC1iMB4XDTI2MDkyNjA0MjgyOVoX
DTM2MDkyMzA0MjgyOVowHTEbMBkGA1UEAwwSb3BlbmNvZGUtdDEtdGVzdC1iMFkw
EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEWKaD5kOBunaknfhEl36BSUXPabHgk+LH
IKC3oizu2yTLQsM68SAibWEouIeQNBoawkWABge//m27/FIKhyhenqNTMFEwHQYD
VR0OBBYEFGre6Wfg2mWekyVHJCULBFUgeDeoMB8GA1UdIwQYMBaAFGre6Wfg2mWe
kyVHJCULBFUgeDeoMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIh
AKWnMiXx+QqIZG2KMKUnfYq2u8u57T9uob6DnStwtjoTAiBzZwdcFAmcvqBcH54v
m5TsgwgjSiHCpBCmoTZTWzq/Qw==
-----END CERTIFICATE-----"""

    public const val KEY_B_PKCS8_PEM: String = """-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg178JyeJcxM7IFCmD
KPfLJV3enqhA0QG0aePTK585hXmhRANCAARYpoPmQ4G6dqSd+ESXfoFJRc9pseCT
4scgoLeiLO7bJMtCwzrxICJtYSi4h5A0GhrCRYAGB7/+bbv8UgqHKF6e
-----END PRIVATE KEY-----"""

    /** The bearer token the device tests attach; CI asserts it never leaves a verified connection. */
    public const val TEST_AUTHORIZATION_VALUE: String = "Bearer t1-device-validation-secret"
}