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

package nu.fgv.spexify.server.spexflix;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.config.SpexifyProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

/**
 * Spexflix's GraphQL API, as the {@code SPEXIFY} role on {@code fgv-spexflix} allows: which published videos have
 * chapters linked to given tracks. Without a configured URL, or when Spexflix does not answer, there are no links.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SpexflixClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String LINKS = """
            query ChapterLinks($trackIds: [ID!]!) {
                chapterLinks(trackIds: $trackIds) { trackId videoId videoTitle chapterTitle startSeconds }
            }""";

    private final @Nullable HttpSyncGraphQlClient graphQlClient;

    public SpexflixClient(final SpexifyProperties properties, final OAuth2AuthorizedClientManager spexregisterAuthorizedClientManager) {
        final String url = properties.spexflix().graphqlUrl();

        if (StringUtils.hasText(url)) {
            final RestClient restClient = RestClient.builder()
                    .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults()
                            .withConnectTimeout(TIMEOUT)
                            .withReadTimeout(TIMEOUT)))
                    .requestInterceptor(new OAuth2ClientHttpRequestInterceptor(spexregisterAuthorizedClientManager))
                    .defaultRequest(request -> request.attributes(clientRegistrationId(nu.fgv.spexify.server.config.SpexregisterClientConfig.REGISTRATION_ID)))
                    .build();

            this.graphQlClient = HttpSyncGraphQlClient.builder(restClient).url(url).build();
        } else {
            this.graphQlClient = null;
        }
    }

    public List<ChapterLink> links(final Collection<Long> trackIds) {
        if (graphQlClient == null || trackIds.isEmpty()) {
            return List.of();
        }
        try {
            final ClientGraphQlResponse response = graphQlClient.document(LINKS)
                    .variable("trackIds", trackIds.stream().map(String::valueOf).toList())
                    .executeSync();

            if (!response.getErrors().isEmpty() || !response.isValid()) {
                log.debug("Spexflix answered with errors: {}", response.getErrors());
                return List.of();
            }

            final List<ChapterLink> links = response.field("chapterLinks").toEntityList(ChapterLink.class);

            return links.stream().filter(Objects::nonNull).toList();
        } catch (final RuntimeException e) {
            log.debug("Spexflix is unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * A chapter in a published video linked to a track.
     */
    public record ChapterLink(String trackId, String videoId, String videoTitle, String chapterTitle, int startSeconds) {
    }
}
