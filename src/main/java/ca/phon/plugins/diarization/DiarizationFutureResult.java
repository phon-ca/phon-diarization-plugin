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

import ca.phon.session.Session;

import java.util.concurrent.Future;

/**
 * A cancel-able diarization result {@link Future}
 */
public interface DiarizationFutureResult {

	/**
	 * Get session future object
	 *
	 * @return
	 */
	public Future<Session> getFutureSession();

	/**
	 * Cancel execution (if possible)
	 */
	public void cancel();

}
