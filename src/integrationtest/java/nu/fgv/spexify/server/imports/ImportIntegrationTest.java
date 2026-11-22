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

package nu.fgv.spexify.server.imports;

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import API (spec 5.6): albums of a kind by name, tracks with audio prepared as uploads are (WAV to FLAC) and
 * linked to Spexipedia's numbers, unpublished, repeatable by source key, and only for IMPORT.
 */
class ImportIntegrationTest extends AbstractIntegrationTest {

    private static final String MIGRATION = "fgv-content-migration";
    private static final String BATCH = """
            mutation($input: ImportBatchInput!, $dryRun: Boolean!) { importBatch(input: $input, dryRun: $dryRun) {
                created updated unchanged kept failed items { kind sourceKey id outcome message warnings }
            } }""";

    @TempDir
    private Path folder;

    @BeforeEach
    void spexipedia() {
        spexipediaUp();
    }

    private Map<String, String> upload(final Path file) throws Exception {
        final byte[] bytes = Files.readAllBytes(file);
        final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        http().put().uri("/api/import/files/" + sha).header("Authorization", serviceToken(MIGRATION)).body(bytes).retrieve().toBodilessEntity();
        return Map.of("sha256", sha, "name", file.getFileName().toString());
    }

    private GraphQlTester.Response batch(final Map<String, Object> input, final boolean dryRun) {
        return graphQlWithToken(serviceToken(MIGRATION)).document(BATCH).variable("input", input).variable("dryRun", dryRun).execute();
    }

    private Path wav() throws Exception {
        final Path file = folder.resolve("1 Ouvertyr.wav");

        ffmpeg.ffmpeg(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=2", "-c:a", "pcm_s16le", file.toString()));
        return file;
    }

    @Test
    void onlyImportReachesTheImportApi() {
        expectError(graphQl(ADMIN).document(BATCH).variable("input", Map.of()).variable("dryRun", false).execute(), "FORBIDDEN");
    }

    @Test
    void albumsAndTracksArriveUnpublishedAsFlacLinkedToNumbers() throws Exception {
        final Map<String, Object> input = Map.of(
                "albums", List.of(Map.of("sourceKey", "test:album", "spexIds", List.of(SPEX_ID), "kind", "internskiva", "year", 2024)),
                "tracks", List.of(Map.of("sourceKey", "test:album/1", "albumSourceKey", "test:album", "trackNumber", 1,
                        "title", "Ouvertyr", "numberId", NUMBER_ID, "melody", "Dancing Queen (ABBA)", "file", upload(wav()))));

        batch(input, true).path("importBatch.created").entity(Integer.class).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM album").query(Integer.class).single()).isZero();

        final String album = batch(input, false).path("importBatch.created").entity(Integer.class).isEqualTo(2)
                .path("importBatch.items[0].id").entity(String.class).get();

        graphQl(EDITOR).document("query($id: ID!) { album(id: $id) { published kind { nameSv } tracks { title format melody creditsFromNumber numberId } } }")
                .variable("id", album).execute()
                .path("album.published").entity(Boolean.class).isEqualTo(false)
                .path("album.kind.nameSv").entity(String.class).isEqualTo("Studio")
                .path("album.tracks[0].format").entity(String.class).isEqualTo("FLAC")
                // A track of a number takes the melody and credits from the number in Spexipedia, not from the import
                .path("album.tracks[0].creditsFromNumber").entity(Boolean.class).isEqualTo(true)
                .path("album.tracks[0].numberId").entity(String.class).isEqualTo(NUMBER_ID);

        // The same file again changes nothing, though the stored file is a conversion of it
        batch(input, false).path("importBatch.unchanged").entity(Integer.class).isEqualTo(2);
        graphQlWithToken(serviceToken(MIGRATION)).document("mutation { importForget(sourcePrefix: \"test:\") }").execute()
                .path("importForget").entity(Integer.class).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM album").query(Integer.class).single()).isZero();
    }

    @Test
    void aRecordsMessageIsInTheLanguageAskedFor() {
        graphQlWithToken(serviceToken(MIGRATION)).document("""
                        mutation($input: ImportBatchInput!) { importBatch(input: $input, dryRun: true) {
                            items { outcome sv: message(locale: "sv") en: message(locale: "en") }
                        } }""")
                .variable("input", Map.of("albums", List.of(Map.of("sourceKey", " ", "spexIds", List.of(SPEX_ID), "kind", "internskiva", "year", 2024)))).execute()
                .path("importBatch.items[0].outcome").entity(String.class).isEqualTo("FAILED")
                .path("importBatch.items[0].sv").entity(String.class).isEqualTo("Fältet sourceKey är tomt, för långt eller ogiltigt.")
                .path("importBatch.items[0].en").entity(String.class).isEqualTo("The field sourceKey is empty, too long or invalid.");
    }
}
