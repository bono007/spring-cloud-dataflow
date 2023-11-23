/*
 * Copyright 2023 the original author or authors.
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
package org.springframework.cloud.dataflow.server.converter;

import java.util.Date;

import org.springframework.core.convert.converter.Converter;

/**
 * {@link Converter} implementation from {@link Date} to {@link String}.
 * To be discarded when moving to Boot 3.x and the converters from org.springframework.batch.core.converter used instead.
 * This converter formats dates according to the
 * {@link java.time.format.DateTimeFormatter#ISO_INSTANT} format.
 * To be discarded when moving to Boot 3.x and the converters from org.springframework.batch.core.converter used instead.
 * @author Mahmoud Ben Hassine
 * @author Corneil du Plessis
 * @since 2.11.2
 */
@Deprecated
public class DateToStringConverter extends AbstractDateTimeConverter implements Converter<Date, String> {

	@Override
	public String convert(Date source) {
		return super.instantFormatter.format(source.toInstant());
	}

}