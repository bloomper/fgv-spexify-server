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

import nu.fgv.spexify.server.config.SpexifyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs links to audio and downloads, which the API then serves without a token:
 *
 * <pre>
 * {publicUrl}/media/{expires}/{signature}/{scope}
 * signature = base64url(HMAC-SHA256(key, "{expires}/{scope}")), without padding
 * </pre>
 *
 * <p>The scope is the rest of the path, e.g. {@code tracks/{id}/audio}, so one link opens one file and nothing else,
 * until it expires. They let the web's player and downloads go straight to the API's public address, outside the CDN
 * in front of the web, without the browser ever holding a token.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class MediaLinkSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final String publicUrl;
    private final SecretKeySpec key;
    private final Duration lifetime;
    private final Clock clock;

    MediaLinkSigner(final String publicUrl, final String signingKey, final Duration lifetime, final Clock clock) {
        if (signingKey == null || signingKey.isBlank()) {
            // Never fall back to a known key: anyone could sign links to every track with it
            throw new IllegalStateException("API_MEDIA_SIGNING_KEY is not set");
        }
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
        this.key = new SecretKeySpec(signingKey.strip().getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.lifetime = lifetime == null ? Duration.ofHours(6) : lifetime;
        this.clock = clock;
    }

    @Autowired
    public MediaLinkSigner(final SpexifyProperties properties) {
        this(properties.media().publicUrl(), properties.media().signingKey(), properties.media().linkLifetime(), Clock.systemUTC());
    }

    /**
     * A link to the scope, e.g. {@code tracks/{id}/audio}, valid for the configured lifetime.
     */
    public Link sign(final String scope) {
        final Instant expires = clock.instant().plus(lifetime);
        final long epoch = expires.getEpochSecond();

        return new Link("%s/media/%d/%s/%s".formatted(publicUrl, epoch, signature(epoch, scope), scope), expires);
    }

    /**
     * Whether the signature is the scope's and the link has not expired.
     */
    public Verdict verify(final long expires, final String signature, final String scope) {
        final byte[] expected = signature(expires, scope).getBytes(StandardCharsets.US_ASCII);

        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
            return Verdict.INVALID;
        }
        return clock.instant().getEpochSecond() > expires ? Verdict.EXPIRED : Verdict.VALID;
    }

    private String signature(final long expires, final String scope) {
        try {
            final Mac mac = Mac.getInstance(ALGORITHM);

            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal((expires + "/" + scope).getBytes(StandardCharsets.UTF_8)));
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public enum Verdict {
        VALID, INVALID, EXPIRED
    }

    /**
     * A signed link and when it stops working.
     */
    public record Link(String url, Instant expiresAt) {
    }
}
