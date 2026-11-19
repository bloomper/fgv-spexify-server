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


package nu.fgv.spexify.server.scoresync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.util.AbstractAuditable;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Where in a score each moment of a track's recording is: a map made by hand or found automatically. The score is a
 * MusicXML file in Spexarkivet.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "score_sync")
@Getter
@Setter
public class ScoreSync extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "track_id", nullable = false)
    private Track track;

    /**
     * The score's file in Spexarkivet.
     */
    @Column(name = "file_id", nullable = false, length = 36)
    private UUID fileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false)
    private Method method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    /**
     * JSON: [[seconds, measure, beat], ...] in the order of the recording.
     */
    @Column(name = "points")
    private @Nullable String points;

    /**
     * JSON: [{after, to, nth}, ...], how the recording walks through the written score; manual maps only.
     */
    @Column(name = "jumps")
    private @Nullable String jumps;

    @Column(name = "quality")
    private @Nullable Double quality;

    @Column(name = "error")
    private @Nullable String error;

    /**
     * The score file's checksum when the map was made, so that a replaced score shows as such.
     */
    @Column(name = "score_checksum")
    private @Nullable String scoreChecksum;

    /**
     * The audio file's checksum when the map was made.
     */
    @Column(name = "audio_checksum")
    private @Nullable String audioChecksum;

    public enum Method {
        MANUAL,
        AUTOMATIC
    }

    public enum Status {
        PENDING,
        RUNNING,
        DONE,
        FAILED
    }
}
