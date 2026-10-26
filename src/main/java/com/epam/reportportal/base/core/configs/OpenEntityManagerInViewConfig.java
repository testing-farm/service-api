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

package com.epam.reportportal.base.core.configs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the open-in-view interceptor that Spring Boot registers by default, but skips the
 * suggest endpoints.
 *
 * <p>The Hibernate session holds its JDBC connection until it closes
 * ({@code DELAYED_ACQUISITION_AND_HOLD}, set by {@code HibernateJpaVendorAdapter}). With open in
 * view the session lives for the whole request, so a suggest request would keep a connection while
 * it waits for the analyzer, even outside a transaction. The suggest endpoints return DTOs built
 * inside their own transactions and need no open session.
 *
 * <p>Set {@code spring.jpa.open-in-view=false} to turn open in view off everywhere.
 */
@Configuration
@ConditionalOnProperty(name = "spring.jpa.open-in-view", havingValue = "true", matchIfMissing = true)
public class OpenEntityManagerInViewConfig implements WebMvcConfigurer {

  static final String[] EXCLUDED_PATHS = {"/v1/*/item/suggest/**"};

  @Bean
  public OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor() {
    return new OpenEntityManagerInViewInterceptor();
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addWebRequestInterceptor(openEntityManagerInViewInterceptor())
        .excludePathPatterns(EXCLUDED_PATHS);
  }
}
