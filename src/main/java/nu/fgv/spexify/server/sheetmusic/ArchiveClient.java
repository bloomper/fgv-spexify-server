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


package nu.fgv.spexify.server.sheetmusic;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.config.ArchiveProperties;
import nu.fgv.spexify.server.config.SpexipediaClientConfig;
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

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

/**
 * Spexarkivet's API, as the {@code SPEXIFY} role on {@code fgv-archive} allows: the arrangements of numbers with their
 * open versions and files, and those files. Without a configured address, or when the archive is unavailable, there is
 * no sheet music, and Spexify works otherwise as usual.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class ArchiveClient {

    /**
     * Score files are small; a larger one is not passed on.
     */
    static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration FILE_TIMEOUT = Duration.ofSeconds(60);
    private static final String SHEET_MUSIC = """
            query SheetMusic($numberIds: [ID!]!) {
                spexifySheetMusic(numberIds: $numberIds) {
                    arrangementId numberId
                    versions {
                        id label arranger recommended keyTonic keyMode tempo
                        files { id title name extension contentType size checksum pages roleSv roleEn instrumentsSv instrumentsEn desk }
                    }
                }
            }""";

    private final ArchiveProperties properties;
    private final @Nullable HttpSyncGraphQlClient graphQlClient;
    private final @Nullable RestClient fileClient;

    public ArchiveClient(final ArchiveProperties properties, final OAuth2AuthorizedClientManager serviceAuthorizedClientManager) {
        this.properties = properties;
        if (StringUtils.hasText(properties.graphqlUrl())) {
            this.graphQlClient = HttpSyncGraphQlClient.builder(client(serviceAuthorizedClientManager, TIMEOUT)).url(properties.graphqlUrl()).build();
            this.fileClient = client(serviceAuthorizedClientManager, FILE_TIMEOUT);
        } else {
            this.graphQlClient = null;
            this.fileClient = null;
        }
    }

    private static RestClient client(final OAuth2AuthorizedClientManager manager, final Duration readTimeout) {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults()
                        .withConnectTimeout(TIMEOUT)
                        .withReadTimeout(readTimeout)))
                .requestInterceptor(new OAuth2ClientHttpRequestInterceptor(manager))
                .defaultRequest(request -> request.attributes(clientRegistrationId(SpexipediaClientConfig.REGISTRATION_ID)))
                .build();
    }

    public boolean enabled() {
        return graphQlClient != null;
    }

    /**
     * The arrangements of the numbers with open versions; empty when the archive is unavailable.
     */
    public List<Arrangement> sheetMusic(final Collection<UUID> numberIds) {
        if (graphQlClient == null || numberIds.isEmpty()) {
            return List.of();
        }
        try {
            final ClientGraphQlResponse response = graphQlClient.document(SHEET_MUSIC)
                    .variable("numberIds", numberIds.stream().map(String::valueOf).toList())
                    .executeSync();

            if (!response.getErrors().isEmpty() || !response.isValid()) {
                log.debug("Spexarkivet answered with errors: {}", response.getErrors());
                throw new ArchiveUnavailableException("Spexarkivet answered with errors");
            }
            return response.field("spexifySheetMusic").toEntityList(Arrangement.class).stream().filter(Objects::nonNull).toList();
        } catch (final ArchiveUnavailableException e) {
            throw e;
        } catch (final RuntimeException e) {
            log.debug("Spexarkivet is unavailable: {}", e.getMessage());
            throw new ArchiveUnavailableException(e.getMessage());
        }
    }

    /**
     * A file of an open version.
     *
     * @throws ArchiveUnavailableException when the archive does not hand it out
     */
    public byte[] file(final UUID id) {
        if (fileClient == null) {
            throw new ArchiveUnavailableException("Spexarkivet is not configured");
        }
        try {
            return fileClient.get().uri(properties.fileUrl(id)).exchange((_, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new ArchiveUnavailableException("Spexarkivet answered " + response.getStatusCode().value() + " for file " + id);
                }
                try (InputStream body = response.getBody()) {
                    final byte[] content = body.readNBytes((int) MAX_FILE_BYTES + 1);

                    if (content.length > MAX_FILE_BYTES) {
                        throw new ArchiveUnavailableException("File " + id + " is too large");
                    }
                    return content;
                }
            });
        } catch (final ArchiveUnavailableException e) {
            throw e;
        } catch (final RuntimeException e) {
            throw new ArchiveUnavailableException(e.getMessage());
        }
    }

    /**
     * The arrangement's page in the archive's web, or null without one.
     */
    public @Nullable String arrangementUrl(final UUID arrangementId) {
        final String web = properties.webUrl();

        return StringUtils.hasText(web) ? web.replaceAll("/+$", "") + "/arrangements/" + arrangementId : null;
    }

    public record Arrangement(String arrangementId, String numberId, List<Version> versions) {
    }

    public record Version(String id, String label, @Nullable String arranger, boolean recommended,
                          @Nullable String keyTonic,
                          @Nullable String keyMode, @Nullable String tempo, List<File> files) {
    }

    public record File(String id, String title, String name, String extension, String contentType, double size,
                       String checksum,
                       @Nullable Integer pages, String roleSv, String roleEn, List<String> instrumentsSv,
                       List<String> instrumentsEn, @Nullable String desk) {
    }

    /**
     * The archive did not answer, or not as expected.
     */
    public static class ArchiveUnavailableException extends RuntimeException {

        public ArchiveUnavailableException(final @Nullable String message) {
            super(message);
        }
    }
}
