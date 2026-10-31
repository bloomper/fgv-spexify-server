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

package nu.fgv.spexify.server.apppassword;

import nu.fgv.spexify.server.config.SpexifyProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordCryptoTest {

    private static PasswordCrypto crypto(final String key) {
        return new PasswordCrypto(new SpexifyProperties(null, null, null, null, null,
                new SpexifyProperties.Crypto(key), null, null, null));
    }

    @Test
    void encryptsSoThatOnlyTheSameKeyDecrypts() {
        final PasswordCrypto crypto = crypto(Base64.getEncoder().encodeToString(new byte[32]));
        final byte[] ciphertext = crypto.encrypt("abcd-efgh");

        assertThat(crypto.decrypt(ciphertext)).isEqualTo("abcd-efgh");
        assertThat(crypto.encrypt("abcd-efgh")).isNotEqualTo(ciphertext);
        assertThatThrownBy(() -> crypto(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})).decrypt(ciphertext))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void makesPasswordsThatAreEasyToType() {
        final String password = crypto("").newPassword();

        assertThat(password).matches("([a-zA-Z2-9]{4}-){5}[a-zA-Z2-9]{4}").doesNotContain("l", "I", "O", "0", "1");
    }

    @Test
    void computesTheSubsonicTokenAsMd5OfPasswordAndSalt() {
        // The example in the Subsonic API documentation
        assertThat(PasswordCrypto.md5Hex("sesame" + "c19b2d")).isEqualTo("26719a1196d2a940705a59634eb18eab");
    }

    @Test
    void makesApiKeysWithAPrefix() {
        assertThat(crypto("").newApiKey()).startsWith("spx_").hasSize(44);
    }
}
