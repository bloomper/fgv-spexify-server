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
 * Repository for {@link Track}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface TrackRepository extends JpaRepository<Track, Long> {

    Optional<Track> findByUuid(UUID uuid);

    List<Track> findByUuidIn(Collection<UUID> uuids);

    List<Track> findByTrashedAtIsNotNullOrderByTrashedAtDesc();

    List<Track> findByLocation(Location location);

    long countByLocation(Location location);

    List<Track> findByLoudnessLufsIsNullAndTrashedAtIsNullAndFileExtensionIsNotNull();

    @Query("""
            SELECT t FROM Track t JOIN FETCH t.album a
            WHERE a.published = TRUE AND a.trashedAt IS NULL AND t.published = TRUE AND t.trashedAt IS NULL AND t.fileExtension IS NOT NULL
            ORDER BY a.year DESC, a.title ASC, t.discNumber ASC, t.trackNumber ASC""")
    List<Track> findListed();

    List<Track> findByNavidromeIdIn(Collection<String> navidromeIds);

    @Query("""
            SELECT t FROM Track t JOIN t.album a
            WHERE t.trashedAt IS NULL AND a.trashedAt IS NULL AND (:editor = TRUE OR (a.published = TRUE AND t.published = TRUE AND t.fileExtension IS NOT NULL))
              AND (LOWER(t.title) LIKE :q OR LOWER(t.performers) LIKE :q OR LOWER(t.melody) LIKE :q OR LOWER(t.lyrics) LIKE :q)
            ORDER BY a.year DESC, a.title ASC, t.discNumber ASC, t.trackNumber ASC""")
    List<Track> search(@Param("q") String pattern, @Param("editor") boolean editor, org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT t FROM Track t JOIN t.album a
            WHERE a.published = TRUE AND a.trashedAt IS NULL AND t.published = TRUE AND t.trashedAt IS NULL AND t.fileExtension IS NOT NULL
              AND EXISTS (SELECT 1 FROM Album x JOIN x.spex s WHERE x = a AND (s.id IN :spexIds OR s.parentId IN :spexIds))
            ORDER BY a.year ASC, a.title ASC, t.discNumber ASC, t.trackNumber ASC""")
    List<Track> findListedBySpex(@Param("spexIds") Collection<UUID> spexIds);

    @Query("""
            SELECT t FROM Track t JOIN FETCH t.album a
            WHERE a.published = TRUE AND a.trashedAt IS NULL AND t.published = TRUE AND t.trashedAt IS NULL AND t.fileExtension IS NOT NULL
              AND t.numberId IN :numberIds
            ORDER BY a.year, a.title, t.discNumber, t.trackNumber""")
    List<Track> findListedByNumber(@Param("numberIds") Collection<UUID> numberIds);

    @Query("""
            SELECT t FROM Track t JOIN FETCH t.album a
            WHERE a.published = TRUE AND a.trashedAt IS NULL AND t.published = TRUE AND t.trashedAt IS NULL AND t.fileExtension IS NOT NULL
              AND t.numberKindId = :kindId
            ORDER BY a.year DESC, a.title ASC, t.discNumber ASC, t.trackNumber ASC""")
    List<Track> findListedByNumberKind(@Param("kindId") UUID kindId, org.springframework.data.domain.Pageable pageable);

    /**
     * How many listed tracks there are of each number kind, as pairs of the kind's id and the count.
     */
    @Query("""
            SELECT t.numberKindId, COUNT(t) FROM Track t JOIN t.album a
            WHERE a.published = TRUE AND a.trashedAt IS NULL AND t.published = TRUE AND t.trashedAt IS NULL AND t.fileExtension IS NOT NULL
              AND t.numberKindId IS NOT NULL
            GROUP BY t.numberKindId""")
    List<Object[]> countListedByNumberKind();

    List<Track> findByNumberIdIsNotNull();

    /**
     * Every number that a track, trashed or not, is a recording of.
     */
    @Query("SELECT DISTINCT t.numberId FROM Track t WHERE t.numberId IS NOT NULL")
    List<UUID> findLinkedNumberIds();
}
