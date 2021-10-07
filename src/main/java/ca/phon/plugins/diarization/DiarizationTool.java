package ca.phon.plugins.diarization;

import java.io.*;
import java.util.*;

public abstract class DiarizationTool {

	private final List<DiarizationListener> listeners = new ArrayList<>();

	public void addListener(DiarizationListener listener) {
		if(!listeners.contains(listener))
			listeners.add(listener);
	}

	public boolean removeListener(DiarizationListener listener) {
		return listeners.remove(listener);
	}

	public void fireDiarizationEvent(DiarizationEvent.DiarizationEventType type, String message) {
		fireDiarizationEvent(new DiarizationEvent(type, message));
	}

	public void fireDiarizationEvent(String message) {
		fireDiarizationEvent(new DiarizationEvent(message));
	}

	public void fireDiarizationEvent(DiarizationEvent evt) {
		listeners.forEach(listener -> listener.diarizationEvent(evt));
	}

	/**
	 * Execute diarization on given audio file with options.
	 * Write data to output stream.
	 *
	 * @param audioFile
	 *
	 * @throws IOException
	 */
	public abstract  DiarizationResult diarize(File audioFile)
			throws IOException;

}
