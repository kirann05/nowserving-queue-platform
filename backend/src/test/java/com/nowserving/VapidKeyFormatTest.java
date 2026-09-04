package com.nowserving;

import com.interaso.webpush.VapidKeys;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the VAPID key ENCODING, because getting it wrong cost a real debugging
 * session and the error message points at the wrong thing.
 *
 * The symptom was:
 *   InvalidKeySpecException: Unable to decode key
 *   Caused by: EOFException: not enough content   (X509Key.decode)
 *
 * which reads like a corrupt key but actually means "you handed an X.509
 * decoder some bytes that were never X.509". The library offers two loaders
 * and they are not interchangeable:
 *
 *   create(...)                -> X.509 SPKI + PKCS#8, base64.
 *                                 Public key starts "MFkwEw…"
 *   fromUncompressedBytes(...) -> RAW base64url: 65-byte uncompressed EC
 *                                 point (87 chars, starts "B") + 32-byte
 *                                 scalar (43 chars)
 *
 * We standardise on RAW because that is what `npx web-push
 * generate-vapid-keys` produces and what every web-push implementation in
 * every language uses — one key pair, portable across the whole ecosystem.
 *
 * A plain unit test: no Spring, no Docker, milliseconds. The keys below are
 * THROWAWAY fixtures generated purely for this test — they protect nothing
 * and are safe to commit. Real keys live in an env var (see
 * docs/PUSH_NOTIFICATIONS_SETUP.md).
 */
class VapidKeyFormatTest {

    private static final String RAW_PUBLIC =
            "BL4ZdIqHNjchUI3NOk-S7afIRSZk_B8bVVzxTWgsHOtmBABtVZL8vDak6MncHytBkpgWcuDCI7sQuknTAgzjwkc";
    private static final String RAW_PRIVATE = "f0OSoHMQejOTOuKACjulYlPGIrNjlpUz12s9ZIJK59Y";

    @Test
    void rawBase64UrlKeys_loadSuccessfully() {
        VapidKeys keys = VapidKeys.fromUncompressedBytes(RAW_PUBLIC, RAW_PRIVATE);

        assertThat(keys.getPublicKey()).isNotNull();
        assertThat(keys.getPrivateKey()).isNotNull();
    }

    @Test
    void theBrowsersApplicationServerKey_isByteIdenticalToTheConfiguredPublicKey() {
        // This is the property the frontend depends on. WebPushChannel serves
        // getApplicationServerKey() from /public/push/vapid-key, and the
        // browser passes it to pushManager.subscribe(). If it ever drifted
        // from the configured key, subscriptions would be created against one
        // key and signed with another — and every push would be rejected.
        VapidKeys keys = VapidKeys.fromUncompressedBytes(RAW_PUBLIC, RAW_PRIVATE);

        String appServerKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(keys.getApplicationServerKey());

        assertThat(appServerKey).isEqualTo(RAW_PUBLIC);
    }

    @Test
    void theRawFormatHasTheShapeWeDocument() {
        // 65 raw bytes -> 87 base64url chars, first byte 0x04 ("uncompressed
        // point") -> the string always starts with 'B'. Worth asserting so the
        // setup doc and the code can't drift apart.
        assertThat(RAW_PUBLIC).hasSize(87).startsWith("B");
        assertThat(RAW_PRIVATE).hasSize(43); // 32-byte scalar
    }

    @Test
    void feedingItX509Keys_failsLoudly_ratherThanSilentlyMisbehaving() {
        // The exact mistake that broke startup: keys in the OTHER encoding.
        // Better that this throws than that it half-works.
        VapidKeys x509Pair = VapidKeys.generate();

        assertThatThrownBy(() -> VapidKeys.fromUncompressedBytes(
                x509Pair.getX509PublicKey(), x509Pair.getPkcs8PrivateKey()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void andTheReverseMistakeAlsoFails_whichIsTheBugWeHit() {
        // create() given RAW keys is precisely what produced
        // "EOFException: not enough content" on startup.
        assertThatThrownBy(() -> VapidKeys.create(RAW_PUBLIC, RAW_PRIVATE))
                .isInstanceOf(Exception.class);
    }
}
