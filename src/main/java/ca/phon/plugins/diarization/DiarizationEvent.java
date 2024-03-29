/*
 * Copyright (C) 2021-present Gregory Hedlund
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *    http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.phon.plugins.diarization;

import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoField;

public class DiarizationEvent {

	public static enum DiarizationEventType {
		DiarizationStarted,
		DiarizationMessage,
		DiarizationCompleted,
		DiarizationError
	};

	private DiarizationEventType type;

	private String message;

	private long timestamp;

	public DiarizationEvent(String message) {
		this(DiarizationEventType.DiarizationMessage, message, System.currentTimeMillis());
	}

	public DiarizationEvent(DiarizationEventType type, String message) {
		this(type, message, System.currentTimeMillis());
	}

	public DiarizationEvent(DiarizationEventType type, String message, long timestamp) {
		this.type = type;
		this.message = message;
		this.timestamp = timestamp;
	}

	public DiarizationEventType getType() {
		return type;
	}

	public void setType(DiarizationEventType type) {
		this.type = type;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	@Override
	public String toString() {
		LocalDateTime localDateTime =
				Instant.ofEpochMilli(getTimestamp()).atZone(ZoneId.systemDefault()).toLocalDateTime();
		DateTimeFormatterBuilder formatterBuilder = new DateTimeFormatterBuilder();
		formatterBuilder.appendLiteral('[');
		formatterBuilder.appendValue(ChronoField.HOUR_OF_DAY, 2);
		formatterBuilder.appendLiteral(':');
		formatterBuilder.appendValue(ChronoField.MINUTE_OF_HOUR, 2);
		formatterBuilder.appendLiteral('.');
		formatterBuilder.appendValue(ChronoField.MILLI_OF_SECOND, 3);
		formatterBuilder.appendLiteral(']');

		String retVal = formatterBuilder.toFormatter().format(localDateTime);
		switch (getType()) {
			case DiarizationStarted:
				retVal += " Diarization started";
				break;

			case DiarizationCompleted:
				retVal += " Diarization completed";
				break;

			default:
				break;
		}
		if(getMessage() != null && getMessage().length() > 0)
			retVal += " " + getMessage();
		return retVal;
	}

}
