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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Spexregister's GraphQL API, as its {@code MEDIA} role allows: spex, with their category, and a spex's poster.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SpexregisterClient {

    static final int MAX_SPEX = 1000;
    private static final String SPEX_FIELDS = "id year title revival parent { id } category { id name }";
    private static final String ALL_SPEX = """
            query AllSpex($first: Int!, $filter: String!) {
                spexPaged(first: $first, filter: $filter, sort: ["year"], direction: ASC) {
                    edges { node { %s } }
                }
            }""".formatted(SPEX_FIELDS);
    private static final String SPEX_BY_ID = """
            query SpexById($id: ID!) {
                spex(id: $id) { %s }
            }""".formatted(SPEX_FIELDS);
    private static final String POSTER = "/api/spex/{id}/poster";

    private final HttpSyncGraphQlClient graphQlClient;
    private final RestClient restClient;

    public SpexregisterClient(final HttpSyncGraphQlClient spexregisterGraphQlClient, final RestClient spexregisterRestClient) {
        this.graphQlClient = spexregisterGraphQlClient;
        this.restClient = spexregisterRestClient;
    }

    /**
     * Every spex, originals and revivals, by year.
     */
    public List<SpexDto> allSpex() {
        return call(() -> {
            final List<SpexDto> spex = new ArrayList<>();

            for (final String filter : List.of("parent:NULL", "parent!NULL")) {
                final ClientGraphQlResponse response = graphQlClient.document(ALL_SPEX)
                        .variable("first", MAX_SPEX)
                        .variable("filter", filter)
                        .executeSync();

                requireValid(response);

                final SpexConnection page = response.field("spexPaged").toEntity(SpexConnection.class);

                if (page != null && page.edges() != null) {
                    page.edges().stream().filter(Objects::nonNull).map(SpexEdge::node).filter(Objects::nonNull)
                            .map(SpexNode::toDto).forEach(spex::add);
                }
            }
            return spex;
        });
    }

    public Optional<SpexDto> spexById(final Long id) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(SPEX_BY_ID).variable("id", id).executeSync();

            if (!response.getErrors().isEmpty() || !response.isValid()) {
                if (response.getErrors().stream().allMatch(error -> "NOT_FOUND".equals(String.valueOf(error.getErrorType())))) {
                    return Optional.empty();
                }
                throw new SpexregisterCallException("Response has errors");
            }
            final SpexNode node = response.field("spex").toEntity(SpexNode.class);

            return Optional.ofNullable(node).map(SpexNode::toDto);
        });
    }

    /**
     * A spex's poster, or empty when it has none.
     */
    public Optional<Poster> poster(final Long id) {
        return call(() -> {
            final ResponseEntity<byte[]> response = restClient.get()
                    .uri(POSTER, id)
                    .header("X-API-Version", "1.0")
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (_, _) -> {
                    })
                    .toEntity(byte[].class);
            final MediaType contentType = response.getHeaders().getContentType();

            if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value() || response.getBody() == null || contentType == null) {
                return Optional.empty();
            }
            return Optional.of(new Poster(response.getBody(), contentType.toString()));
        });
    }

    private static void requireValid(final ClientGraphQlResponse response) {
        if (!response.getErrors().isEmpty() || !response.isValid()) {
            throw new SpexregisterCallException("Response has errors: " + response.getErrors());
        }
    }

    private static <T> T call(final Supplier<T> action) {
        try {
            return action.get();
        } catch (final SpexregisterCallException e) {
            throw e;
        } catch (final RuntimeException e) {
            if (e instanceof RestClientException || e.getClass().getName().startsWith("org.springframework.graphql")) {
                throw new SpexregisterCallException(e.getMessage() == null ? e.getClass().getName() : e.getMessage(), e);
            }
            throw e;
        }
    }

    record SpexConnection(@Nullable List<@Nullable SpexEdge> edges) {
    }

    record SpexEdge(@Nullable SpexNode node) {
    }

    record IdRef(String id) {
    }

    record CategoryNode(String id, @Nullable String name) {
    }

    record SpexNode(String id, @Nullable String year, @Nullable String title, @Nullable Boolean revival,
                    @Nullable IdRef parent, @Nullable CategoryNode category) {

        SpexDto toDto() {
            Integer parsedYear = null;

            try {
                parsedYear = year == null ? null : Integer.valueOf(year);
            } catch (final NumberFormatException _) {
                // left out
            }
            return new SpexDto(Long.valueOf(id), title, parsedYear, Boolean.TRUE.equals(revival),
                    parent == null ? null : Long.valueOf(parent.id()),
                    category == null ? null : Long.valueOf(category.id()),
                    category == null ? null : category.name());
        }
    }

    /**
     * A poster image as Spexregister stores it.
     */
    public record Poster(byte[] content, String contentType) {
    }

    /**
     * Spexregister could not be reached or answered with errors.
     */
    public static class SpexregisterCallException extends RuntimeException {

        public SpexregisterCallException(final String message) {
            super(message);
        }

        public SpexregisterCallException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
