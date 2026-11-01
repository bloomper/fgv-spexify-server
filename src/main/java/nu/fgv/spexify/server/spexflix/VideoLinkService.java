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

import nu.fgv.spexify.server.config.CacheConfig;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Spexflix videos linked to tracks, cached for a few minutes, shown only to users who also have access to Spexflix.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class VideoLinkService {

    private final SpexflixClient client;
    private final CallerService callerService;
    private final Cache cache;

    public VideoLinkService(final SpexflixClient client, final CallerService callerService, final CacheManager cacheManager) {
        this.client = client;
        this.callerService = callerService;
        this.cache = Objects.requireNonNull(cacheManager.getCache(CacheConfig.VIDEO_LINKS));
    }

    public Map<UUID, List<VideoLinkDto>> links(final Collection<UUID> trackIds) {
        final Caller caller = callerService.currentIfAny().orElse(null);
        final Map<UUID, List<VideoLinkDto>> result = new HashMap<>();

        if (caller == null || !caller.spexflixUser()) {
            trackIds.forEach(id -> result.put(id, List.of()));
            return result;
        }

        final List<UUID> missing = new ArrayList<>();

        for (final UUID id : trackIds) {
            final Cache.ValueWrapper cached = cache.get(id);

            if (cached != null && cached.get() instanceof List<?> list) {
                result.put(id, list.stream().map(VideoLinkDto.class::cast).toList());
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            final Map<UUID, List<VideoLinkDto>> fetched = new HashMap<>();

            missing.forEach(id -> fetched.put(id, new ArrayList<>()));
            for (final SpexflixClient.ChapterLink link : client.links(missing)) {
                final List<VideoLinkDto> list = fetched.get(UUID.fromString(link.trackId()));

                if (list != null) {
                    list.add(new VideoLinkDto(link.videoId(), link.videoTitle(), link.chapterTitle(), link.startSeconds()));
                }
            }
            fetched.forEach((id, links) -> {
                cache.put(id, List.copyOf(links));
                result.put(id, List.copyOf(links));
            });
        }
        return result;
    }

    public record VideoLinkDto(String videoId, String videoTitle, String chapterTitle, int startSeconds) {
    }
}
