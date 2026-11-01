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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subsonic apps through the gateway to a real Navidrome: app passwords, refused operations, revoked access, and the
 * published catalogue as Navidrome sees it after a scan.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SubsonicGatewayIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    private Path folder;

    @Test
    void anAppPasswordLogsIn() throws Exception {
        final Credentials credentials = appPassword(LISTENER);

        assertThat(subsonic("ping", credentials.username(), credentials.password(), Map.of()).path("status").asString()).isEqualTo("ok");
        assertThat(subsonic("ping", credentials.username(), "wrong-password", Map.of()).path("error").path("code").asInt()).isEqualTo(40);
    }

    @Test
    void operationsThatChangeTheLibraryAreRefused() throws Exception {
        final Credentials credentials = appPassword(ADMIN);
        final JsonNode response = subsonic("startScan", credentials.username(), credentials.password(), Map.of());

        assertThat(response.path("status").asString()).isEqualTo("failed");
        assertThat(response.path("error").path("code").asInt()).isEqualTo(50);
    }

    @Test
    void revokedAccessInKeycloakStopsTheApps() throws Exception {
        final Credentials credentials = appPassword(REVOKED);

        assertThat(subsonic("ping", credentials.username(), credentials.password(), Map.of()).path("status").asString()).isEqualTo("ok");
        revokeClientRole(REVOKED, "USER");
        assertThat(subsonic("ping", credentials.username(), credentials.password(), Map.of()).path("status").asString()).isEqualTo("failed");
    }

    @Test
    void publishedTracksReachNavidrome() throws Exception {
        final String albumId = graphQl(EDITOR)
                .document("mutation { albumCreate(input: {spexId: 1, kind: SHOW}) { id } }")
                .execute()
                .path("albumCreate.id").entity(String.class).get();
        final String uploadId = upload(EDITOR, flac(folder, 2));
        final String trackId = graphQl(EDITOR)
                .document("mutation($album: ID!, $upload: String!) { trackCreate(albumId: $album, uploadId: $upload) { id } }")
                .variables(Map.of("album", albumId, "upload", uploadId))
                .execute()
                .path("trackCreate.id").entity(String.class).get();

        graphQl(EDITOR).document("mutation($id: ID!) { albumPublish(id: $id, published: true) { id } }").variable("id", albumId).execute();

        // Navidrome scans, then Spexify matches its song ids to tracks
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
        String navidromeId = null;

        while (navidromeId == null && Instant.now().isBefore(deadline)) {
            final Map<?, ?> track = graphQl(LISTENER)
                    .document("query($id: ID!) { album(id: $id) { tracks { navidromeId } } }")
                    .variable("id", albumId)
                    .execute()
                    .path("album.tracks[0]").entity(Map.class).get();

            navidromeId = (String) track.get("navidromeId");
            if (navidromeId == null) {
                Thread.sleep(1_000);
            }
        }
        assertThat(navidromeId).as("Navidrome's id of the published track").isNotNull();

        final Credentials credentials = appPassword(LISTENER);
        final JsonNode songs = subsonic("search3", credentials.username(), credentials.password(), Map.of("query", "Ouvertyr"))
                .path("searchResult3").path("song");

        assertThat(songs.get(0).path("id").asString()).isEqualTo(navidromeId);
        graphQl(LISTENER)
                .document("query($ids: [String!]!) { tracksByNavidromeIds(ids: $ids) { id } }")
                .variable("ids", java.util.List.of(navidromeId))
                .execute()
                .path("tracksByNavidromeIds[0].id").entity(String.class).isEqualTo(trackId);
    }

    private Credentials appPassword(final String user) {
        final var response = graphQl(user)
                .document("mutation { appPasswordCreate(name: \"Telefon\") { username password } }")
                .execute();

        return new Credentials(response.path("appPasswordCreate.username").entity(String.class).get(),
                response.path("appPasswordCreate.password").entity(String.class).get());
    }

    private JsonNode subsonic(final String endpoint, final String username, final String password, final Map<String, String> parameters) throws Exception {
        final StringBuilder query = new StringBuilder("u={u}&p={p}&v=1.16.1&c=test&f=json");
        final Map<String, String> variables = new java.util.HashMap<>(Map.of("u", username, "p", password));

        parameters.forEach((key, value) -> {
            query.append('&').append(key).append("={").append(key).append('}');
            variables.put(key, value);
        });

        final String body = http().get()
                .uri("/rest/" + endpoint + ".view?" + query, variables)
                .retrieve()
                .body(String.class);

        return JSON.readTree(body).path("subsonic-response");
    }

    private record Credentials(String username, String password) {
    }
}
