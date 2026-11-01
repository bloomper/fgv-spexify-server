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

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading spex and posters from Spexregister, as its {@code MEDIA} role allows.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SpexregisterClientTest {

    private final WireMockServer spexregister = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
    private SpexregisterClient client;

    @BeforeEach
    void setUp() {
        spexregister.start();
        client = new SpexregisterClient(HttpSyncGraphQlClient.create(RestClient.create(spexregister.baseUrl() + "/api/graphql")),
                RestClient.create(spexregister.baseUrl()));
    }

    @AfterEach
    void tearDown() {
        spexregister.stop();
    }

    private void graphql(final String operation, final String body) {
        spexregister.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing(operation))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void readsOriginalsAndRevivalsWithTheirCategoriesAndYears() {
        spexregister.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing("parent:NULL"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spexPaged": {"edges": [{"node": {"id": "1", "year": "1975", "title": "Nobel", "revival": false,
                            "parent": null, "category": {"id": "3", "name": "Chalmersspexet"}}}, null]}}}""")));
        spexregister.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing("parent!NULL"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spexPaged": {"edges": [{"node": {"id": "2", "year": "okänt", "title": "Nobel", "revival": true,
                            "parent": {"id": "1"}, "category": null}}]}}}""")));

        assertThat(client.allSpex()).containsExactly(
                new SpexDto(1L, "Nobel", 1975, false, null, 3L, "Chalmersspexet"),
                new SpexDto(2L, "Nobel", null, true, 1L, null, null));
    }

    @Test
    void aSpexThatDoesNotExistIsEmptyButOtherErrorsFail() {
        graphql("SpexById", """
                {"data": {"spex": null}, "errors": [{"message": "Not found", "extensions": {"classification": "NOT_FOUND"}}]}""");
        assertThat(client.spexById(9L)).isEmpty();

        spexregister.resetAll();
        graphql("SpexById", """
                {"data": null, "errors": [{"message": "Forbidden", "extensions": {"classification": "FORBIDDEN"}}]}""");
        assertThatThrownBy(() -> client.spexById(9L)).isInstanceOf(SpexregisterClient.SpexregisterCallException.class);
    }

    @Test
    void anUnreachableSpexregisterFails() {
        spexregister.stubFor(post(urlEqualTo("/api/graphql")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.allSpex()).isInstanceOf(SpexregisterClient.SpexregisterCallException.class);
    }

    @Test
    void aPosterIsReadWithItsTypeAndAMissingOneIsEmpty() {
        spexregister.stubFor(get(urlEqualTo("/api/spex/1/poster"))
                .willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(new byte[]{1, 2})));
        spexregister.stubFor(get(urlEqualTo("/api/spex/2/poster")).willReturn(aResponse().withStatus(404)));

        assertThat(client.poster(1L)).hasValueSatisfying(poster -> {
            assertThat(poster.content()).containsExactly(1, 2);
            assertThat(poster.contentType()).isEqualTo("image/png");
        });
        assertThat(client.poster(2L)).isEmpty();
    }
}
