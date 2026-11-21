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
    @Autowired
    private java.time.Clock clock;

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
    void spexipediaReadsTheDiscographyWithTracksWithoutAudioAndSignedCovers() {
        linkToTheNumber();
        graphQl(EDITOR).document("""
                        mutation($id: ID!) { albumUpdate(id: $id, input: {spexIds: ["%s"], catalogueNumber: "CEP 019",
                            mediumId: "00000000-0000-7000-8000-00000000b002", released: "1987-04", conductor: "Dirigenten"}) {
                            catalogueNumber medium { nameEn } released conductor } }""".formatted(SPEX_ID))
                .variable("id", albumId)
                .execute()
                .path("albumUpdate.catalogueNumber").entity(String.class).isEqualTo("CEP 019")
                .path("albumUpdate.medium.nameEn").entity(String.class).isEqualTo("EP");

        final String withoutAudio = graphQl(EDITOR).document("""
                        mutation($album: ID!) { trackAdd(albumId: $album, input: {discNumber: 1, trackNumber: 2, title: "Finalen",
                            side: "B", lyricsSynced: false}) { id hasAudio listed } }""")
                .variable("album", albumId)
                .execute()
                .path("trackAdd.hasAudio").entity(Boolean.class).isEqualTo(false)
                .path("trackAdd.id").entity(String.class).get();

        graphQl(EDITOR).document("mutation($id: ID!) { trackPublish(id: $id, published: true) { id } }").variable("id", withoutAudio).execute();
        graphQlWithToken(serviceToken(SPEXIPEDIA_SERVICE)).document("""
                        query($spex: [ID!]!) { spexipediaDiscography(spexIds: $spex) {
                            id catalogueNumber mediumNameSv released conductor spexId coverUrl
                            tracks { id side title numberId hasAudio } } }""")
                .variable("spex", List.of(SPEX_ID))
                .execute()
                .path("spexipediaDiscography[0].id").entity(String.class).isEqualTo(albumId)
                .path("spexipediaDiscography[0].catalogueNumber").entity(String.class).isEqualTo("CEP 019")
                .path("spexipediaDiscography[0].mediumNameSv").entity(String.class).isEqualTo("EP")
                .path("spexipediaDiscography[0].spexId").entity(String.class).isEqualTo(SPEX_ID)
                .path("spexipediaDiscography[0].tracks[0].numberId").entity(String.class).isEqualTo(NUMBER_ID)
                .path("spexipediaDiscography[0].tracks[0].hasAudio").entity(Boolean.class).isEqualTo(true)
                .path("spexipediaDiscography[0].tracks[1].id").entity(String.class).isEqualTo(withoutAudio)
                .path("spexipediaDiscography[0].tracks[1].side").entity(String.class).isEqualTo("B")
                .path("spexipediaDiscography[0].tracks[1].hasAudio").entity(Boolean.class).isEqualTo(false);

        // A track without audio is not in the links for listening
        graphQlWithToken(serviceToken(SPEXIPEDIA_SERVICE)).document(LINKS)
                .variables(Map.of("spex", List.of(SPEX_ID), "numbers", List.of(NUMBER_ID)))
                .execute()
                .path("spexipediaLinks.tracks").entityList(Object.class).hasSize(1);
        expectError(graphQl(EDITOR).document("query($spex: [ID!]!) { spexipediaDiscography(spexIds: $spex) { id } }")
                .variable("spex", List.of(SPEX_ID)).execute(), "FORBIDDEN");
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
        final UUID kind = UUID.fromString("00000000-0000-7000-8000-0000000000c7");

        sync.applyAll(List.of(
                new FeedChange("1", "SPEX_CATEGORY", category, "UPSERT", null, new FeedChange.SpexCategory(category, "Chalmersspexet!"), null, null),
                new FeedChange("2", "SPEX", spex, "UPSERT", null, null, new FeedChange.Spex(spex, "2025", "Kleopatra", category, null, false), null),
                new FeedChange("3", "NUMBER", number, "UPSERT", null, null, null, new FeedChange.Number(number, spex, "Uvertyr", kind))));

        graphQl(EDITOR).document("query($id: ID!) { album(id: $id) { spex { title year categoryName } tracks { numberTitle } } }")
                .variable("id", albumId).execute()
                .path("album.spex[0].title").entity(String.class).isEqualTo("Kleopatra")
                .path("album.spex[0].year").entity(Integer.class).isEqualTo(2025)
                .path("album.spex[0].categoryName").entity(String.class).isEqualTo("Chalmersspexet!")
                .path("album.tracks[0].numberTitle").entity(String.class).isEqualTo("Uvertyr");
        // The number's kind follows the feed too
        assertThat(jdbcClient.sql("SELECT number_kind_id FROM track WHERE number_id = :n").param("n", number.toString())
                .query(String.class).single()).isEqualTo(kind.toString());

        sync.applyAll(List.of(
                new FeedChange("4", "SPEX", spex, "MERGE", otherSpex, null, null, null),
                new FeedChange("5", "NUMBER", number, "MERGE", otherNumber, null, null, null)));

        assertThat(jdbcClient.sql("SELECT spex_id FROM album_spex").query(String.class).single()).isEqualTo(otherSpex.toString());
        assertThat(jdbcClient.sql("SELECT number_id FROM track").query(String.class).single()).isEqualTo(otherNumber.toString());

        // An album about both spex names the surviving one once, and the positions close up
        final UUID third = UUID.fromString("00000000-0000-7000-8000-000000000006");
        final UUID duplicate = UUID.fromString("00000000-0000-7000-8000-000000000007");

        jdbcClient.sql("""
                        INSERT INTO album_spex (album_id, position, spex_id, title)
                        SELECT album_id, position + 1, :duplicate, 'Dubblett' FROM album_spex WHERE spex_id = :other""")
                .param("duplicate", duplicate.toString()).param("other", otherSpex.toString()).update();
        jdbcClient.sql("""
                        INSERT INTO album_spex (album_id, position, spex_id, title)
                        SELECT album_id, position + 2, :third, 'Tredje' FROM album_spex WHERE spex_id = :other""")
                .param("third", third.toString()).param("other", otherSpex.toString()).update();
        sync.applyAll(List.of(new FeedChange("9", "SPEX", duplicate, "MERGE", otherSpex, null, null, null)));
        assertThat(jdbcClient.sql("SELECT spex_id FROM album_spex ORDER BY position").query(String.class).list())
                .containsExactly(otherSpex.toString(), third.toString());
        assertThat(jdbcClient.sql("SELECT MAX(position) - MIN(position) FROM album_spex").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void tracksHaveTheKindsOfTheirNumbersAndEachKindItsTracks() {
        SPEXIPEDIA.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/api/graphql")
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("NumberById"))
                .atPriority(1)
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("""
                        {"data": {"number": {"id": "00000000-0000-7000-8000-0000000000b1", "act": 1, "position": 1, "title": "Ouvertyr",
                                             "spex": {"id": "00000000-0000-7000-8000-000000000001"},
                                             "kind": {"id": "00000000-0000-7000-8000-0000000000c1"}}}}""")));
        SPEXIPEDIA.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/api/graphql")
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("NumberKinds"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("""
                        {"data": {"kinds": [
                            {"id": "00000000-0000-7000-8000-0000000000c1", "nameSv": "Ouvertyr", "nameEn": "Overture", "position": 0, "retired": false},
                            {"id": "00000000-0000-7000-8000-0000000000c2", "nameSv": "Final", "nameEn": "Finale", "position": 1, "retired": false}]}}""")));
        linkToTheNumber();

        graphQl(LISTENER).document("{ numberKinds { id nameSv nameEn trackCount tracks(limit: 5) { id } } }")
                .execute()
                .path("numberKinds").entityList(Object.class).hasSize(1)
                .path("numberKinds[0].nameEn").entity(String.class).isEqualTo("Overture")
                .path("numberKinds[0].trackCount").entity(Integer.class).isEqualTo(1)
                .path("numberKinds[0].tracks[0].id").entity(String.class).isEqualTo(trackId);
        graphQl(LISTENER).document("query($id: ID!) { album(id: $id) { tracks { numberKind { id nameSv } } } }")
                .variable("id", albumId).execute()
                .path("album.tracks[0].numberKind.nameSv").entity(String.class).isEqualTo("Ouvertyr");

        // A kind whose tracks are no longer listed has no shelf
        graphQl(EDITOR).document("mutation($id: ID!) { albumPublish(id: $id, published: false) { id } }").variable("id", albumId).execute();
        graphQl(LISTENER).document("{ numberKinds { id } }").execute()
                .path("numberKinds").entityList(Object.class).hasSize(0);
    }

    @Test
    void thisDayHasTheAlbumsOfSpexThatPremieredOnTodaysDate() {
        final java.time.LocalDate today = java.time.LocalDate.now(clock);

        SPEXIPEDIA.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/api/graphql")
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("AllSpex"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("""
                        {"data": {"spexAll": [{"id": "00000000-0000-7000-8000-000000000001", "year": "2024", "title": "Cleopatra",
                            "revival": false, "retired": false, "parent": null, "poster": null, "category": null,
                            "page": {"premiere": "%s"}}]}}""".formatted(today.minusYears(2)))));

        graphQl(LISTENER).document("{ thisDayAlbums { id title } }").execute()
                .path("thisDayAlbums[0].id").entity(String.class).isEqualTo(albumId);

        // Unpublished albums are not shown
        graphQl(EDITOR).document("mutation($id: ID!) { albumPublish(id: $id, published: false) { id } }").variable("id", albumId).execute();
        graphQl(LISTENER).document("{ thisDayAlbums { id } }").execute()
                .path("thisDayAlbums").entityList(Object.class).hasSize(0);
    }

    @Test
    void aChangeThatKeepsFailingIsSkippedAndTheFeedGoesOn() {
        final String before = jdbcClient.sql("SELECT cursor_value FROM spexipedia_sync WHERE id = 1").query(String.class).optional().orElse(null);
        final UUID category = UUID.fromString("00000000-0000-7000-8000-0000000000e1");
        // A change without its record cannot be applied, however often it is tried
        final FeedChange broken = new FeedChange("9001", "SPEX", UUID.fromString("00000000-0000-7000-8000-0000000000e2"), "UPSERT", null, null, null, null);
        final FeedChange fine = new FeedChange("9002", "SPEX_CATEGORY", category, "UPSERT", null,
                new FeedChange.SpexCategory(category, "Efter den trasiga"), null, null);
        final FeedChange.Page page = new FeedChange.Page(List.of(broken, fine), "9002", false);

        try {
            for (int attempt = 1; attempt < SpexipediaSync.MAX_ATTEMPTS; attempt++) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> sync.applyPage(page, "9002")).isInstanceOf(RuntimeException.class);
                assertThat(jdbcClient.sql("SELECT COUNT(*) FROM spexipedia_category WHERE id = :id").param("id", category.toString())
                        .query(Integer.class).single()).isZero();
            }
            sync.applyPage(page, "9002");

            assertThat(jdbcClient.sql("SELECT cursor_value FROM spexipedia_sync WHERE id = 1").query(String.class).single()).isEqualTo("9002");
            assertThat(jdbcClient.sql("SELECT name FROM spexipedia_category WHERE id = :id").param("id", category.toString())
                    .query(String.class).single()).isEqualTo("Efter den trasiga");
            graphQl(ADMIN).document("{ spexipediaSync { skipped { sequence kind change id error } } }").execute()
                    .path("spexipediaSync.skipped[0].sequence").entity(String.class).isEqualTo("9001")
                    .path("spexipediaSync.skipped[0].kind").entity(String.class).isEqualTo("SPEX")
                    .path("spexipediaSync.skipped[0].change").entity(String.class).isEqualTo("UPSERT");
            expectError(graphQl(EDITOR).document("{ spexipediaSync { lastError } }").execute(), "FORBIDDEN");
        } finally {
            jdbcClient.sql("DELETE FROM spexipedia_sync_skipped").update();
            jdbcClient.sql("DELETE FROM spexipedia_category WHERE id = :id").param("id", category.toString()).update();
            jdbcClient.sql("UPDATE spexipedia_sync SET cursor_value = :cursor, failing_sequence = NULL, failing_attempts = 0 WHERE id = 1")
                    .param("cursor", before).update();
        }
    }
}
