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

import com.github.benmanes.caffeine.cache.Caffeine;
import nu.fgv.spexify.server.spex.SpexregisterClient;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;

/**
 * Caches for what other services answer: spex posters from Spexregister and video links from Spexflix.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SPEX_POSTERS = "spexPosters";
    public static final String VIDEO_LINKS = "videoLinks";
    public static final String ALL_SPEX = "allSpex";
    private static final long POSTER_CACHE_BYTES = 64L * 1024 * 1024;

    @Bean
    public CacheManager cacheManager() {
        final CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        cacheManager.registerCustomCache(SPEX_POSTERS, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumWeight(POSTER_CACHE_BYTES)
                .weigher((_, value) -> value instanceof Optional<?> poster && poster.orElse(null) instanceof SpexregisterClient.Poster image
                        ? image.content().length + 64
                        : 64)
                .build());
        // Editors search the spex as they type; Spexregister is asked once every few minutes, not on every key
        cacheManager.registerCustomCache(ALL_SPEX, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(1)
                .build());
        cacheManager.registerCustomCache(VIDEO_LINKS, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(5_000)
                .build());
        return cacheManager;
    }
}
