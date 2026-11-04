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

package nu.fgv.spexify.server.util.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A signed media link that has run out; the web asks for a new one.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public class LinkExpiredException extends ResponseStatusException {

    public static final String MESSAGE_CODE = "error.link.expired";

    public LinkExpiredException() {
        super(HttpStatus.GONE, MESSAGE_CODE, null, MESSAGE_CODE, null);
    }
}
