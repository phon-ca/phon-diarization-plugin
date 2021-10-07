package ca.phon.plugins.diarization;

@FunctionalInterface
public interface DiarizationListener {

	public void diarizationEvent(DiarizationEvent evt);

}
