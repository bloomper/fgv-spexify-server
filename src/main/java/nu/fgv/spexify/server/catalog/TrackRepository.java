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

package nu.fgv.spexify.server.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Track}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface TrackRepository extends JpaRepository<Track, Long> {

    Optional<Track> findByUuid(String uuid);

    List<Track> findByTrashedAtIsNotNullOrderByTrashedAtDesc();

    List<Track> findByLocation(Location location);

    long countByLocation(Location location);

    List<Track> findByNavidromeIdIn(Collection<String> navidromeIds);

    @Query("""
            SELECT t FROM Track t JOIN t.album a
            WHERE t.trashedAt IS NULL AND a.trashedAt IS NULL AND (:editor = TRUE OR (a.published = TRUE AND t.published = TRUE))
              AND (LOWER(t.title) LIKE :q OR LOWER(t.performers) LIKE :q OR LOWER(t.melody) LIKE :q OR LOWER(t.lyrics) LIKE :q)
            ORDER BY a.year DESC, a.title ASC, t.discNumber ASC, t.trackNumber ASC""")
    List<Track> search(@Param("q") String pattern, @Param("editor") boolean editor, org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT t FROM Track t JOIN t.album a
            WHERE a.spex.id = :spexId AND a.published = TRUE AND a.trashedAt IS NULL
              AND t.published = TRUE AND t.trashedAt IS NULL
            ORDER BY a.title ASC, t.discNumber ASC, t.trackNumber ASC""")
    List<Track> findListedBySpex(@Param("spexId") Long spexId);
}
