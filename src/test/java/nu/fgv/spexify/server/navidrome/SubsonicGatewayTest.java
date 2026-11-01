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

package nu.fgv.spexify.server.navidrome;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubsonicGatewayTest {

    @Test
    void readsTheEndpointWithAndWithoutView() {
        assertThat(SubsonicGateway.endpoint("/rest/getAlbum.view")).isEqualTo("getAlbum");
        assertThat(SubsonicGateway.endpoint("/rest/stream")).isEqualTo("stream");
        assertThat(SubsonicGateway.endpoint("/rest/../admin")).isEmpty();
        assertThat(SubsonicGateway.endpoint("/other")).isEmpty();
    }

    @Test
    void decodesHexEncodedPasswords() {
        assertThat(SubsonicGateway.decodePassword("enc:736563726574")).isEqualTo("secret");
        assertThat(SubsonicGateway.decodePassword("plain")).isEqualTo("plain");
        assertThat(SubsonicGateway.decodePassword(null)).isNull();
    }

    @Test
    void refusesOperationsThatChangeTheLibraryOrUsers() {
        assertThat(SubsonicGateway.REFUSED).contains("startscan", "createuser", "changepassword", "jukeboxcontrol", "createshare");
        assertThat(SubsonicGateway.REFUSED).doesNotContain("stream", "getalbum", "star", "createplaylist", "scrobble");
    }

    @Test
    void matchesSongsByPathOrMusicBrainzId() {
        final String uuid = "0b6c1e1e-2c58-3d3a-9a51-5c3b1a2f9e10";

        assertThat(LibraryScanner.trackUuid(new NavidromeClient.Song("1", "a", "album-uuid/" + uuid + ".flac", ""))).isEqualTo(uuid);
        assertThat(LibraryScanner.trackUuid(new NavidromeClient.Song("1", "a", "Artist/Album/01 - Title.flac", uuid))).isEqualTo(uuid);
        assertThat(LibraryScanner.trackUuid(new NavidromeClient.Song("1", "a", "Artist/Album/01 - Title.flac", ""))).isNull();
    }
}
