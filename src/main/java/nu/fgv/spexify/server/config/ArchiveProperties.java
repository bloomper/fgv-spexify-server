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


package nu.fgv.spexify.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spexarkivet, FGV's archive of manuscripts and sheet music, which Spexify asks for the sheet music of its tracks'
 * numbers with the {@code SPEXIFY} role on {@code fgv-archive}.
 *
 * @param graphqlUrl the archive's GraphQL API, e.g. {@code http://archive-api:8080/api/graphql}; empty for none, which
 *                   leaves out sheet music
 * @param webUrl     the archive's web, for links to its pages; empty for none
 * @author Anders Jacobsson
 * @since 1.0
 */
@ConfigurationProperties(prefix = "spexify.archive")
public record ArchiveProperties(String graphqlUrl, String webUrl) {

    /**
     * Where the archive serves a file, beside its GraphQL API: {@code .../api/graphql} becomes {@code .../api/files/{id}}.
     */
    public String fileUrl(final java.util.UUID id) {
        final String base = graphqlUrl.endsWith("/graphql") ? graphqlUrl.substring(0, graphqlUrl.length() - "/graphql".length()) : graphqlUrl;

        return base + "/files/" + id;
    }
}
