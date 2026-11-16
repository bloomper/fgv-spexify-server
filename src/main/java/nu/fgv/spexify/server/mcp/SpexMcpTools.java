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

package nu.fgv.spexify.server.mcp;

import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumSpexDto;
import nu.fgv.spexify.server.catalog.CatalogService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The spex that have music, over MCP: the spex of the published albums, from Spexify's copy of them, so it works while
 * Spexipedia is unavailable.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SpexMcpTools {

    private final CatalogService catalogService;
    private final McpToolSupport support;

    public SpexMcpTools(final CatalogService catalogService, final McpToolSupport support) {
        this.catalogService = catalogService;
        this.support = support;
    }

    private static boolean matches(final AlbumSpexDto spex, final @Nullable String query) {
        if (query == null || query.isBlank()) {
            return true;
        }

        final String wanted = query.strip().toLowerCase(Locale.ROOT);

        return (spex.title() != null && spex.title().toLowerCase(Locale.ROOT).contains(wanted))
                || (spex.year() != null && String.valueOf(spex.year()).equals(wanted))
                || (spex.categoryName() != null && spex.categoryName().toLowerCase(Locale.ROOT).contains(wanted));
    }

    @McpTool(name = "list_spex",
            description = "List the spex that have music in Spexify: those its published albums are about, newest first, with how many albums each has. A revival is its own spex, with the id of its original. Give a spex's id to list_albums for its albums.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolSupport.ListResult<SpexWithMusic> listSpex(
            @McpToolParam(required = false, description = "Only spex whose title or category contains this, or of this year") final @Nullable String query) {
        return support.call(() -> {
            final Map<UUID, AlbumSpexDto> spex = new LinkedHashMap<>();
            final Map<UUID, Integer> albums = new LinkedHashMap<>();

            for (final AlbumDto album : catalogService.albums(null)) {
                for (final AlbumSpexDto ref : album.spex()) {
                    if (matches(ref, query)) {
                        spex.putIfAbsent(ref.id(), ref);
                        albums.merge(ref.id(), 1, Integer::sum);
                    }
                }
            }
            return new McpToolSupport.ListResult<>(spex.values().stream()
                    .sorted(Comparator.comparing(AlbumSpexDto::year, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(ref -> Objects.requireNonNullElse(ref.title(), "")))
                    .map(ref -> new SpexWithMusic(CatalogMcpTools.Spex.of(ref), albums.get(ref.id())))
                    .toList());
        });
    }

    /**
     * A spex and how many published albums are about it.
     */
    public record SpexWithMusic(CatalogMcpTools.Spex spex, int albumCount) {
    }
}
