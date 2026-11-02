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

package nu.fgv.spexify.server.maintenance;

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
class MaintenanceIntegrationTest extends AbstractIntegrationTest {

    private static final String JOBS = "{ maintenanceJobs { job schedule running lastStartedAt lastFinishedAt lastSucceeded } }";
    private static final String RUN = "mutation($job: MaintenanceJob!) { maintenanceJobRun(job: $job) }";

    @Test
    void adminsSeeAndRunTheJobs() {
        graphQl(ADMIN).document(JOBS).execute()
                .path("maintenanceJobs[*].job").entityList(String.class).containsExactly("SPEX_REFRESH", "UPLOAD_CLEANUP", "LOUDNESS_SCAN")
                .path("maintenanceJobs[0].schedule").entity(String.class).isEqualTo("0 15 3 * * *");
        graphQl(ADMIN).document(RUN).variable("job", "UPLOAD_CLEANUP").execute()
                .path("maintenanceJobRun").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void nobodyElseSeesOrRunsTheJobs() {
        for (final String user : new String[]{EDITOR, LISTENER}) {
            expectError(graphQl(user).document(JOBS).execute(), "FORBIDDEN");
            expectError(graphQl(user).document(RUN).variable("job", "SPEX_REFRESH").execute(), "FORBIDDEN");
        }
    }
}
