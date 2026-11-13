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

package nu.fgv.spexify.server.spex;

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spexify and Spexipedia: tracks linked to Spexipedia's numbers, what Spexipedia's service account reads with
 * {@code SPEXIPEDIA}, its notifications and the change feed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SpexipediaIntegrationTest extends AbstractIntegrationTest {

    private static final String SPEXIPEDIA_SERVICE = "fgv-spexipedia-service";
    private static final String LINKS = """
            query($spex: [ID!]!, $numbers: [ID!]!) { spexipediaLinks(spexIds: $spex, numberIds: $numbers) {
                albums { id title spexId } tracks { id numberId albumId }
            } }""";

    @TempDir
    private Path folder;
    @Autowired
    private SpexipediaSync sync;

    private String albumId;
    private String trackId;

    @BeforeEach
    void publishATrack() throws Exception {
        albumId = graphQl(EDITOR)
                .document("mutation { albumCreate(input: {spexIds: [\"00000000-0000-7000-8000-000000000001\"]}) { id } }")
                .execute()
                .path("albumCreate.id").entity(String.class).get();
        trackId = graphQl(EDITOR)
                .document("mutation($album: ID!, $upload: String!) { trackCreate(albumId: $album, uploadId: $upload) { id } }")
                .variables(Map.of("album", albumId, "upload", upload(EDITOR, flac(folder, 1))))
                .execute()
                .path("trackCreate.id").entity(String.class).get();
        graphQl(EDITOR).document("mutation($id: ID!) { albumPublish(id: $id, published: true) { id } }").variable("id", albumId).execute();
    }

    private void linkToTheNumber() {
        graphQl(EDITOR).document("mutation($id: ID!, $number: ID) { trackSetNumber(id: $id, numberId: $number) { numberId numberTitle spexipediaUrl } }")
                .variables(Map.of("id", trackId, "number", NUMBER_ID))
                .execute()
                .path("trackSetNumber.numberId").entity(String.class).isEqualTo(NUMBER_ID)
                .path("trackSetNumber.numberTitle").entity(String.class).isEqualTo("Ouvertyr")
                .path("trackSetNumber.spexipediaUrl").entity(String.class)
                .isEqualTo("https://spexipedia.test/spex/" + SPEX_ID + "#number-" + NUMBER_ID);
    }

    @Test
    void editorsLinkTracksToNumbersOfTheAlbumsSpexOnly() {
        expectError(graphQl(LISTENER).document("mutation($id: ID!) { trackSetNumber(id: $id, numberId: null) { id } }")
                .variable("id", trackId).execute(), "FORBIDDEN");
        linkToTheNumber();

        SPEXIPEDIA.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/api/graphql")
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("NumberById"))
                .atPriority(1)
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("""
                        {"data": {"number": {"id": "00000000-0000-7000-8000-0000000000b2", "act": 1, "position": 1, "title": "Annat",
                                             "spex": {"id": "00000000-0000-7000-8000-000000000009"}}}}""")));
        expectError(graphQl(EDITOR).document("mutation($id: ID!) { trackSetNumber(id: $id, numberId: \"00000000-0000-7000-8000-0000000000b2\") { id } }")
                .variable("id", trackId).execute(), "BAD_REQUEST");
    }

    @Test
    void spexipediaReadsThePublishedAlbumsOfSpexAndTracksOfNumbers() {
        linkToTheNumber();

        graphQlWithToken(serviceToken(SPEXIPEDIA_SERVICE)).document(LINKS)
                .variables(Map.of("spex", List.of(SPEX_ID), "numbers", List.of(NUMBER_ID)))
                .execute()
                .path("spexipediaLinks.albums[0].id").entity(String.class).isEqualTo(albumId)
                .path("spexipediaLinks.albums[0].spexId").entity(String.class).isEqualTo(SPEX_ID)
                .path("spexipediaLinks.tracks[0].id").entity(String.class).isEqualTo(trackId)
                .path("spexipediaLinks.tracks[0].albumId").entity(String.class).isEqualTo(albumId);

        // Unpublished albums are not linked to
        graphQl(EDITOR).document("mutation($id: ID!) { albumPublish(id: $id, published: false) { id } }").variable("id", albumId).execute();
        graphQlWithToken(serviceToken(SPEXIPEDIA_SERVICE)).document(LINKS)
                .variables(Map.of("spex", List.of(SPEX_ID), "numbers", List.of(NUMBER_ID)))
                .execute()
                .path("spexipediaLinks.albums").entityList(Object.class).hasSize(0)
                .path("spexipediaLinks.tracks").entityList(Object.class).hasSize(0);
    }

    @Test
    void onlySpexipediaReadsItsLinks() {
        expectError(graphQl(EDITOR).document(LINKS).variables(Map.of("spex", List.of(SPEX_ID), "numbers", List.of())).execute(), "FORBIDDEN");
        expectError(graphQlWithToken(spexflixServiceToken()).document(LINKS)
                .variables(Map.of("spex", List.of(SPEX_ID), "numbers", List.of())).execute(), "FORBIDDEN");
    }

    @Test
    void spexipediaNotifiesWithItsServiceAccountOnly() {
        notify(serviceToken(SPEXIPEDIA_SERVICE)).expectStatus().isAccepted();
        notify(token(ADMIN)).expectStatus().isForbidden();
        notify(null).expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec notify(final String token) {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + localPort).build()
                .post().uri("/api/sync/notify")
                .headers(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token);
                    }
                })
                .exchange();
    }

    @Test
    void theFeedKeepsTheCopiesCurrentAndMovesMergedReferences() {
        linkToTheNumber();

        final UUID spex = UUID.fromString(SPEX_ID);
        final UUID category = UUID.fromString("00000000-0000-7000-8000-000000000001");
        final UUID number = UUID.fromString(NUMBER_ID);
        final UUID otherSpex = UUID.fromString("00000000-0000-7000-8000-000000000005");
        final UUID otherNumber = UUID.fromString("00000000-0000-7000-8000-0000000000b5");

        sync.applyAll(List.of(
                new FeedChange("1", "SPEX_CATEGORY", category, "UPSERT", null, new FeedChange.SpexCategory(category, "Chalmersspexet!"), null, null),
                new FeedChange("2", "SPEX", spex, "UPSERT", null, null, new FeedChange.Spex(spex, "2025", "Kleopatra", category, null, false), null),
                new FeedChange("3", "NUMBER", number, "UPSERT", null, null, null, new FeedChange.Number(number, spex, "Uvertyr"))));

        graphQl(EDITOR).document("query($id: ID!) { album(id: $id) { spex { title year categoryName } tracks { numberTitle } } }")
                .variable("id", albumId).execute()
                .path("album.spex[0].title").entity(String.class).isEqualTo("Kleopatra")
                .path("album.spex[0].year").entity(Integer.class).isEqualTo(2025)
                .path("album.spex[0].categoryName").entity(String.class).isEqualTo("Chalmersspexet!")
                .path("album.tracks[0].numberTitle").entity(String.class).isEqualTo("Uvertyr");

        sync.applyAll(List.of(
                new FeedChange("4", "SPEX", spex, "MERGE", otherSpex, null, null, null),
                new FeedChange("5", "NUMBER", number, "MERGE", otherNumber, null, null, null)));

        assertThat(jdbcClient.sql("SELECT spex_id FROM album_spex").query(String.class).single()).isEqualTo(otherSpex.toString());
        assertThat(jdbcClient.sql("SELECT number_id FROM track").query(String.class).single()).isEqualTo(otherNumber.toString());
    }
}
