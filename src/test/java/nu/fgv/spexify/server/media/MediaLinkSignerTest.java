/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.spexify.server.media;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaLinkSignerTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private static MediaLinkSigner signer(final String key, final Instant now) {
        return new MediaLinkSigner("https://api.example.org/", key, Duration.ofHours(6), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aLinkHoldsItsExpiryAndSignatureAndIsValidUntilItExpires() {
        final MediaLinkSigner.Link link = signer("key", NOW).sign("tracks/abc/audio");
        final long expires = NOW.plus(Duration.ofHours(6)).getEpochSecond();

        assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(6)));
        assertThat(link.url()).matches("https://api\\.example\\.org/media/" + expires + "/[A-Za-z0-9_-]{43}/tracks/abc/audio");

        final String signature = link.url().split("/")[5];

        assertThat(signer("key", NOW).verify(expires, signature, "tracks/abc/audio")).isEqualTo(MediaLinkSigner.Verdict.VALID);
        assertThat(signer("key", NOW.plus(Duration.ofHours(7))).verify(expires, signature, "tracks/abc/audio"))
                .isEqualTo(MediaLinkSigner.Verdict.EXPIRED);
        assertThat(signer("key", NOW).verify(expires, signature, "tracks/abd/audio")).isEqualTo(MediaLinkSigner.Verdict.INVALID);
        assertThat(signer("key", NOW).verify(expires + 1, signature, "tracks/abc/audio")).isEqualTo(MediaLinkSigner.Verdict.INVALID);
        assertThat(signer("other", NOW).verify(expires, signature, "tracks/abc/audio")).isEqualTo(MediaLinkSigner.Verdict.INVALID);
    }

    @Test
    void thereIsNoFallbackKey() {
        assertThatThrownBy(() -> signer(" ", NOW)).isInstanceOf(IllegalStateException.class).hasMessageContaining("API_MEDIA_SIGNING_KEY");
        assertThatThrownBy(() -> signer(null, NOW)).isInstanceOf(IllegalStateException.class);
    }
}
