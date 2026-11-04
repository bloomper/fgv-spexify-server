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
 * Repository for {@link TrackListening}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface TrackListeningRepository extends JpaRepository<TrackListening, Long> {

    Optional<TrackListening> findByUserIdAndTrackId(Long userId, Long trackId);

    List<TrackListening> findByUserId(Long userId);

    /**
     * Everyone's ratings, likes and plays of each track anyone did something with, as rows of the track's uuid, the
     * sum of the ratings, the number of ratings, the number of likes and the number of plays.
     */
    @Query("""
            SELECT t.uuid, SUM(l.rating), COUNT(l.rating), SUM(CASE WHEN l.likedAt IS NOT NULL THEN 1 ELSE 0 END), SUM(l.playCount)
            FROM TrackListening l, Track t WHERE t.id = l.trackId GROUP BY t.uuid""")
    List<Object[]> totals();

    /**
     * The same as {@link #totals()} for some tracks.
     */
    @Query("""
            SELECT t.uuid, SUM(l.rating), COUNT(l.rating), SUM(CASE WHEN l.likedAt IS NOT NULL THEN 1 ELSE 0 END), SUM(l.playCount)
            FROM TrackListening l, Track t WHERE t.id = l.trackId AND t.uuid IN :uuids GROUP BY t.uuid""")
    List<Object[]> totals(@Param("uuids") Collection<UUID> uuids);

    /**
     * Everyone's plays of each album's tracks, as rows of the album's uuid and the number of plays.
     */
    @Query("""
            SELECT t.album.uuid, SUM(l.playCount) FROM TrackListening l, Track t WHERE t.id = l.trackId GROUP BY t.album.uuid""")
    List<Object[]> albumPlays();
}
