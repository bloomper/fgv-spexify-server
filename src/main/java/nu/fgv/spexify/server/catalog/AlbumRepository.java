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
import java.util.UUID;

/**
 * Repository for {@link Album}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface AlbumRepository extends JpaRepository<Album, Long> {

    Optional<Album> findByUuid(UUID uuid);

    List<Album> findByUuidIn(Collection<UUID> uuids);

    List<Album> findByTrashedAtIsNullOrderByYearDescTitleAsc();

    List<Album> findByPublishedTrueAndTrashedAtIsNullOrderByYearDescTitleAsc();

    List<Album> findByTrashedAtIsNotNullOrderByTrashedAtDesc();

    List<Album> findByPublishedTrueAndTrashedAtIsNullOrderByPublishedAtDesc(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT DISTINCT a.spex.id FROM Album a WHERE a.spex.id IS NOT NULL")
    List<UUID> findSpexIds();

    List<Album> findBySpexIdIn(Collection<UUID> spexIds);

    @Query("""
            SELECT DISTINCT a FROM Album a LEFT JOIN a.tracks t
            WHERE a.trashedAt IS NULL AND (:editor = TRUE OR a.published = TRUE)
              AND (LOWER(a.title) LIKE :q OR LOWER(a.spex.title) LIKE :q OR LOWER(a.description) LIKE :q
                   OR (t.trashedAt IS NULL AND (:editor = TRUE OR t.published = TRUE)
                       AND (LOWER(t.title) LIKE :q OR LOWER(t.performers) LIKE :q OR LOWER(t.melody) LIKE :q
                            OR LOWER(t.lyrics) LIKE :q)))
            ORDER BY a.year DESC, a.title ASC""")
    List<Album> search(@Param("q") String pattern, @Param("editor") boolean editor);
}
