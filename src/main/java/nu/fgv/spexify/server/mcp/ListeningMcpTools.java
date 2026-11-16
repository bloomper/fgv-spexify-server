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
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumFilter;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.listening.ListeningService;
import nu.fgv.spexify.server.listening.ListeningService.TopBy;
import nu.fgv.spexify.server.listening.ListeningService.Totals;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Everyone's plays, likes and ratings over MCP, as the top lists of the GraphQL API: never who played what.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ListeningMcpTools {

    private final ListeningService listeningService;
    private final CatalogService catalogService;
    private final McpToolSupport support;

    public ListeningMcpTools(final ListeningService listeningService, final CatalogService catalogService, final McpToolSupport support) {
        this.listeningService = listeningService;
        this.catalogService = catalogService;
        this.support = support;
    }

    @McpTool(name = "list_top_tracks",
            description = "The published tracks everyone played most (PLAYED, the default), liked most (LIKED) or rated highest (RATED), optionally of a spex or a year. Tracks nobody played, liked or rated are left out. Counts are everyone's, never per person.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolSupport.ListResult<TopTrack> listTopTracks(
            @McpToolParam(required = false, description = "PLAYED (default), LIKED or RATED") final @Nullable TopBy by,
            @McpToolParam(required = false, description = "A spex's id, from list_spex; its revivals are included for an original") final @Nullable String spexId,
            @McpToolParam(required = false, description = "Tracks of albums of this year") final @Nullable Integer year,
            @McpToolParam(required = false, description = "How many, 1 to 100; 20 by default") final @Nullable Integer limit) {
        return support.call(() -> {
            final AlbumFilter filter = new AlbumFilter(null, year, McpToolSupport.optionalUuid(spexId, "error.spex.notFound"), false);
            final List<TrackDto> tracks = listeningService.topTracks(Objects.requireNonNullElse(by, TopBy.PLAYED), filter,
                    Objects.requireNonNullElse(limit, 20));
            final Map<UUID, Totals> totals = listeningService.trackTotals(tracks.stream().map(TrackDto::id).toList());
            final Map<UUID, @Nullable AlbumDto> albums = new HashMap<>();

            return new McpToolSupport.ListResult<>(tracks.stream()
                    .map(track -> {
                        final Totals entry = totals.getOrDefault(track.id(), Totals.NONE);

                        return new TopTrack(CatalogMcpTools.Track.of(track, albums.computeIfAbsent(track.albumId(), catalogService::album)),
                                entry.playCount(), entry.likeCount(), entry.average(), entry.ratingCount());
                    })
                    .toList());
        });
    }

    /**
     * A track with everyone's plays, likes and average rating (1 to 5).
     */
    public record TopTrack(CatalogMcpTools.Track track, long plays, int likes, @Nullable Double averageRating,
                           int ratingCount) {
    }
}
