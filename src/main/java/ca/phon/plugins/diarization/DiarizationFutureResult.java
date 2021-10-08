package ca.phon.plugins.diarization;

import ca.phon.session.Session;

import java.util.concurrent.Future;

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
