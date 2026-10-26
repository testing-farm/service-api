/*
 * Copyright 2026 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.ta.reportportal.exception;

import com.epam.reportportal.rules.exception.ErrorType;
import com.epam.ta.reportportal.exception.rest.DefaultErrorResolver;
import com.epam.ta.reportportal.exception.rest.RestError;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AnalyzerBusyExceptionTest {

  @Test
  void mapsToServiceUnavailable() {
    RestError error = new DefaultErrorResolver(ExceptionMappings.DEFAULT_MAPPING).resolveError(
        new AnalyzerBusyException("Too many concurrent suggest requests, try again later."));

    Assertions.assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.httpStatus());
    Assertions.assertEquals(ErrorType.UNABLE_INTERACT_WITH_INTEGRATION,
        error.errorRs().getErrorType());
    Assertions.assertTrue(error.errorRs().getMessage().contains("Too many concurrent suggest requests"));
  }
}
