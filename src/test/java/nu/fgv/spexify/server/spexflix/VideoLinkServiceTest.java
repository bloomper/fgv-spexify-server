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

import nu.fgv.spexify.server.TestCallers;
import nu.fgv.spexify.server.config.CacheConfig;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static nu.fgv.spexify.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Links from tracks to Spexflix's chapters, for users of both.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class VideoLinkServiceTest {

    @Mock
    private SpexflixClient client;
    @Mock
    private CallerService callerService;
    private VideoLinkService service;

    @BeforeEach
    void setUp() {
        service = new VideoLinkService(client, callerService, new ConcurrentMapCacheManager(CacheConfig.VIDEO_LINKS));
    }

    @Test
    void usersWithoutSpexflixGetNoLinksAndSpexflixIsNotAsked() {
        when(callerService.currentIfAny()).thenReturn(Optional.of(TestCallers.listener()));

        assertThat(service.links(List.of(uuid(1), uuid(2)))).containsOnlyKeys(uuid(1), uuid(2)).allSatisfy((id, links) -> assertThat(links).isEmpty());
        verify(client, never()).links(any());
    }

    @Test
    void linksAreGroupedByTrackAndCached() {
        final Caller both = new Caller("s-both", "bodil", "Bodil", Set.of(Role.USER), true);

        when(callerService.currentIfAny()).thenReturn(Optional.of(both));
        when(client.links(List.of(uuid(1), uuid(2)))).thenReturn(List.of(
                new SpexflixClient.ChapterLink(uuid(1).toString(), "7", "Cleopatra", "Ouvertyr", 0),
                new SpexflixClient.ChapterLink(uuid(1).toString(), "8", "Cleopatra – trailer", "Ouvertyr", 12)));

        final var links = service.links(List.of(uuid(1), uuid(2)));

        assertThat(links.get(uuid(1))).extracting(VideoLinkService.VideoLinkDto::videoId).containsExactly("7", "8");
        assertThat(links.get(uuid(2))).isEmpty();
        assertThat(service.links(List.of(uuid(1), uuid(2))).get(uuid(1))).hasSize(2);
        verify(client, times(1)).links(any());
    }
}
