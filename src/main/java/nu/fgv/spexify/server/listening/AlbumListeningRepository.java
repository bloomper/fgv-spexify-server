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
package nu.fgv.spexify.server.listening;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link AlbumListening}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface AlbumListeningRepository extends JpaRepository<AlbumListening, Long> {

    Optional<AlbumListening> findByUserIdAndAlbumId(Long userId, Long albumId);

    List<AlbumListening> findByUserId(Long userId);

    /**
     * Everyone's ratings and likes of each album anyone did something with, as rows of the album's uuid, the sum of the
     * ratings, the number of ratings and the number of likes.
     */
    @Query("""
            SELECT a.uuid, SUM(l.rating), COUNT(l.rating), SUM(CASE WHEN l.likedAt IS NOT NULL THEN 1 ELSE 0 END)
            FROM AlbumListening l, Album a WHERE a.id = l.albumId GROUP BY a.uuid""")
    List<Object[]> totals();

    /**
     * The same as {@link #totals()} for some albums.
     */
    @Query("""
            SELECT a.uuid, SUM(l.rating), COUNT(l.rating), SUM(CASE WHEN l.likedAt IS NOT NULL THEN 1 ELSE 0 END)
            FROM AlbumListening l, Album a WHERE a.id = l.albumId AND a.uuid IN :uuids GROUP BY a.uuid""")
    List<Object[]> totals(@Param("uuids") Collection<UUID> uuids);

    /**
     * The uuids of the albums a user likes.
     */
    @Query("SELECT a.uuid FROM AlbumListening l, Album a WHERE a.id = l.albumId AND l.userId = :userId AND l.likedAt IS NOT NULL")
    List<UUID> likedAlbums(@Param("userId") Long userId);
}
