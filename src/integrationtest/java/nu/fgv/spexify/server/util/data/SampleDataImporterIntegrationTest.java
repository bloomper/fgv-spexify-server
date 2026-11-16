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

package nu.fgv.spexify.server.util.data;

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample data is built on Spexipedia's spex and fgv-infra-deploy's sample users, which it records by their fixed
 * ids without asking Keycloak.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SampleDataImporterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SampleDataImporter importer;

    private static void spexipediaWithSpex() {
        SPEXIPEDIA.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing("AllSpex"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spexAll": [
                            {"id": "00000000-0000-7000-8000-000000000001", "year": "1975", "title": "Nobel", "revival": false, "retired": false, "parent": null, "poster": null, "category": {"id": "00000000-0000-7000-8000-000000000001", "name": "Chalmersspexet"}},
                            {"id": "00000000-0000-7000-8000-000000000002", "year": "2024", "title": "Cleopatra", "revival": false, "retired": false, "parent": null, "poster": null, "category": {"id": "00000000-0000-7000-8000-000000000001", "name": "Chalmersspexet"}}
                        ]}}""")));
        SPEXIPEDIA.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing("SpexNumbers"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spex": {"numbers": [{"id": "00000000-0000-7000-8000-0000000000b1", "act": 1, "position": 1, "title": "Ouvertyr"}]}}}""")));
    }

    @Test
    @Order(2)
    void buildsAlbumsOnSpexipediasSpexAndRecordsTheSampleUsers() throws Exception {
        spexipediaWithSpex();

        importer.importSampleData();

        // fgv-infra-deploy's sample users, recorded by their fixed ids without asking Keycloak
        assertThat(jdbcClient.sql("SELECT subject FROM app_user ORDER BY subject").query(String.class).list()).containsExactly(
                "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
                "00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000005");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app_user WHERE username = ?").param(SampleDataImporter.NO_ACCESS).query(Long.class).single())
                .isEqualTo(1);

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM album").query(Long.class).single()).isEqualTo(20);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM album a JOIN album_kind k ON k.id = a.kind_id WHERE k.name_en = 'Compilation' AND NOT EXISTS (SELECT 1 FROM album_spex s WHERE s.album_id = a.id)").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM (SELECT album_id FROM album_spex GROUP BY album_id HAVING COUNT(*) > 1) multiple").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM album WHERE published = FALSE").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM album WHERE trashed_at IS NOT NULL").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(DISTINCT spex_id) FROM album_spex").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM app_password p JOIN app_user u ON u.id = p.user_id WHERE u.username = ?""")
                .param(SampleDataImporter.USER).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM audit_entry").query(Long.class).single()).isEqualTo(8);
        try (var files = Files.walk(MEDIA.resolve("music"))) {
            assertThat(files.filter(file -> file.toString().endsWith(".flac") || file.toString().endsWith(".mp3"))).isNotEmpty();
        }

        // The ids stay the same on every run, so Spexflix's links to tracks hold
        final String firstAlbum = jdbcClient.sql("SELECT uuid FROM album ORDER BY id LIMIT 1").query(String.class).single();

        importer.importSampleData();
        assertThat(jdbcClient.sql("SELECT uuid FROM album ORDER BY id LIMIT 1").query(String.class).single()).isEqualTo(firstAlbum);
        assertThat(jdbcClient.sql("SELECT MIN(id) FROM album").query(Long.class).single()).isEqualTo(1);
    }
}
