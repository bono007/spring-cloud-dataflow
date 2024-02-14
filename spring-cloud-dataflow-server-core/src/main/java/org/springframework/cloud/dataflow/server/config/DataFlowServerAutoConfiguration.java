/*
 * Copyright 2016 the original author or authors.
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

package org.springframework.cloud.dataflow.server.config;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * Auto-configuration for dataflow server.
 *
 * @author Janne Valkealahti
 */
@AutoConfiguration
@AutoConfigureOrder(value = Ordered.HIGHEST_PRECEDENCE)
@AutoConfigureBefore({JacksonAutoConfiguration.class})
@ConditionalOnBean(EnableDataFlowServerConfiguration.Marker.class)
@Import(DataFlowServerConfiguration.class)
@ConditionalOnProperty(prefix = "dataflow.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataFlowServerAutoConfiguration {

	@PostConstruct
	void init() {
		System.out.println("***** DONT TELL MOM");
	}
}
