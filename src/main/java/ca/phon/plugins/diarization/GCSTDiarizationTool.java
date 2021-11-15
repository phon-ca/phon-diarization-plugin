package ca.phon.plugins.diarization;

import ca.phon.app.log.LogUtil;
import ca.phon.audio.*;
import ca.phon.orthography.Orthography;
import ca.phon.session.*;
import ca.phon.session.Record;
import ca.phon.util.PrefHelper;
import ca.phon.worker.PhonWorker;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.paging.Page;
import com.google.api.services.storage.StorageScopes;
import com.google.auth.oauth2.*;
import com.google.cloud.speech.v1.*;
import com.google.cloud.storage.*;
import com.google.protobuf.*;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.internal.storage.file.GC;

import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.text.ParseException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Diarization using Google Cloud Speech to Text services.
 *
 */
public class GCSTDiarizationTool extends DiarizationTool {

	private final static float UPLOAD_SAMPLE_RATE = 16000.0f;
	private final static int UPLOAD_CHANNEL_COUNT = 1;

	/**
	 * The following supported langs come from the table displayed
	 * at https://cloud.google.com/speech-to-text/docs/languages
	 * with diarization selected as the filter
	 */
	public final static String[] SUPPORTED_LANGUAGE_NAMES =
			{ "Chinese, Mandarin", "English (India)", "English (Singapore)",
				"English (United Kingdom)", "English (United States)", "French (France)",
				"German (Germany)", "Italian (Italy)", "Japanese (Japan)", "Portuguese (Brazil)",
				"Russian (Russia)", "Spanish (Spain)" };

	/**
	 * Language tags for SUPPORTED_LANGUAGE_NAMES
	 */
	public final static String[] SUPPORTED_LANGUAGE_TAGS =
			{ "zh", "en-IN", "en-SG", "en-GB", "en-US", "fr-FR", "de-DE", "it-IT", "ja-JP", "pt-BR", "ru-RU", "es-ES" };

	/**
	 * Speech-to-Text models: https://cloud.google.com/speech-to-text/docs/basics#select-model
	 */
	public final static String[] SPEECH_TO_TEXT_MODELS = {
			"default",
			"video",
			"phone_call",
			"command_and_search"
	};

	public final static String[] SPEECH_TO_TEXT_MODEL_DESCRIPTIONS = {
			"""
            Use this model if your audio does not fit any of the other models.
            For example, you can use this for long-form audio recordings that feature a single speaker only.
            The default model will produce transcription results for any type of audio, including audio such as
            video clips that has a separate model specifically tailored to it. However, recognizing video clip audio
            using the default model would like yield lower-quality results than using the video model. Ideally, the
            audio is high-fidelity, recorded at 16,000Hz or greater sampling rate.
            """,
			"""
            Use this model for transcribing audio from video clips or other sources (such as podcasts) that have
            multiple speakers. This model is also often the best choice for audio that was recorded with a high-quality
            microphone or that has lots of background noise. For best results, provide audio recorded at 16,000Hz or
            greater sampling rate.
            """,
			"""
            Use this model for transcribing audio from a phone call. Typically, phone audio is recorded at 8,000Hz
            sampling rate.
            """,
			"""
            Use this model for transcribing shorter audio clips. Some examples include voice commands or voice search.
            """
	};

	public final static String[] STORAGE_LOCATIONS = {"US", "EU", "ASIA"};

	private final static String BUCKET_NAME_PROP = "diarization.bucketName";

	/** Language model, should be one of SUPPORTED_LANGUAGE_TAGS */
	private String languageModel = "en-US";

	/** Speech to Text model used */
	private String gcstModel = "default";

	/** Google Cloud services project id */
	private String projectId;

	/** Max speakers */
	private int maxSpeakers;

	/** Credentials JSON file */
	private String credentialsFile;

	private GoogleCredentials credentials;
	private AccessToken accessToken;

	public String getCredentialsFile() {
		return credentialsFile;
	}

	public void setCredentialsFile(String credentialsFile) {
		this.credentialsFile = credentialsFile;
	}

	public String getLanguageModel() {
		return languageModel;
	}

	public void setLanguageModel(String languageModel) {
		this.languageModel = languageModel;
	}

	public String getGcstModel() {
		return gcstModel;
	}

	public void setGcstModel(String gcstModel) {
		this.gcstModel = gcstModel;
	}

	public String getProjectId() {
		return projectId;
	}

	public void setProjectId(String projectId) {
		this.projectId = projectId;
	}

	public int getMaxSpeakers() {
		return maxSpeakers;
	}

	public void setMaxSpeakers(int maxSpeakers) {
		this.maxSpeakers = maxSpeakers;
	}

	private void loadCredentials() throws IOException {
		credentials = GoogleCredentials.fromStream(new FileInputStream(new File(credentialsFile)))
						.createScoped(Collections.singleton(StorageScopes.CLOUD_PLATFORM));
		credentials.refreshIfExpired();
		if(this.accessToken == null)
			accessToken = credentials.getAccessToken();
		else
			accessToken = credentials.refreshAccessToken();
	}

	private String bucketName() {
		String bucketName = PrefHelper.get(BUCKET_NAME_PROP, null);
		if(bucketName == null) {
			bucketName = UUID.randomUUID().toString();
			PrefHelper.getUserPreferences().put(BUCKET_NAME_PROP, bucketName);
		}
		return bucketName;
	}

	private boolean hasBucket(String projectId, String bucketId) {
		Storage storage = StorageOptions.newBuilder()
				.setCredentials(credentials)
				.setProjectId(projectId).build().getService();
		Page<Bucket> bucketList = storage.list();

		for(Bucket bucket:bucketList.iterateAll()) {
			if(bucket.getName().equals(bucketId)) return true;
		}
		return false;
	}

	private void createStorageBucket(String projectId, String bucketId) {
		Storage storage = StorageOptions.newBuilder()
				.setCredentials(credentials)
				.setProjectId(projectId).build().getService();

		StorageClass storageClass = StorageClass.STANDARD;
		String location = "US";

		Bucket bucket = storage.create(BucketInfo.newBuilder(bucketId)
				.setStorageClass(storageClass).setLocation(location).build());
	}

	private boolean audioFileExistsInBucket(String projectId, String bucketName, String objectName) {
		Storage storage = StorageOptions.newBuilder()
				.setCredentials(credentials)
				.setProjectId(projectId).build().getService();

		Page<Blob> blobs = storage.list(bucketName);
		for(Blob blob:blobs.iterateAll()) {
			if(blob.getName().equals(objectName)) {
				return true;
			}
		}
		return false;
	}

	private void uploadAudioFile(String projectId, String bucketName, String objectName, File file) throws IOException {
		Storage storage = StorageOptions.newBuilder()
				.setCredentials(credentials)
				.setProjectId(projectId).build().getService();
		BlobId blobId = BlobId.of(bucketName, objectName);
		BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
		storage.create(blobInfo, Files.readAllBytes(file.toPath()));
	}

	private File checkAudioFileEncoding(AudioFile audioFile) throws IOException, AudioIOException {
		File retVal = audioFile.getFile();
		if(audioFile.getSampleRate() != UPLOAD_SAMPLE_RATE || audioFile.getNumberOfChannels() != UPLOAD_CHANNEL_COUNT) {
			Sampled sampled = new AudioFileSampled(audioFile);

			if(audioFile.getSampleRate() != UPLOAD_SAMPLE_RATE) {
				fireDiarizationEvent("Resampling audio @ 16KHz");
				final Resampler resampler = new Resampler();
				sampled = resampler.resample(sampled, UPLOAD_SAMPLE_RATE);
			}
			if(audioFile.getNumberOfChannels() != UPLOAD_CHANNEL_COUNT) {
				fireDiarizationEvent("Converting audio to mono");
				sampled = new MonoSampled(sampled);
			}

			// save samples to temporary file
			final File tempFile = File.createTempFile("phon", "diarization.wav");
			AudioIO.writeSamples(sampled, 0, sampled.getNumberOfSamples(), audioFile.getAudioFileEncoding(),
					new FileOutputStream(tempFile));
			tempFile.deleteOnExit();
			retVal = tempFile;
		}
		return retVal;
	}

	private Session processSpeechRecognitionAlternative(SpeechRecognitionAlternative alternative) {
		SessionFactory factory = SessionFactory.newFactory();
		Session retVal = factory.createSession();

		List<WordInfo> wordList = alternative.getWordsList();
		int currentSpeakerTag = -1;
		long currentStart = -1L;
		long currentEnd = 0L;
		Participant currentSpeaker = null;
		Map<Integer, Participant> speakerMap = new HashMap<>();

		StringBuilder builder = new StringBuilder();

		for(WordInfo wordInfo:wordList) {
			long thisWordStart = (long)(wordInfo.getStartTime().getSeconds() * 1000);
			thisWordStart += (long)(wordInfo.getStartTime().getNanos() * 1e-6);
			if(currentSpeakerTag < 0 || wordInfo.getSpeakerTag() != currentSpeakerTag ||
					(currentEnd >= 0L && (currentEnd != thisWordStart))) {
				if(currentStart >= 0) {
					Record r = factory.createRecord(currentSpeaker);
					try {
						Orthography ortho = Orthography.parseOrthography(builder.toString());
						r.getOrthography().setGroup(0, ortho);
					} catch (ParseException e) {
						LogUtil.warning(e);
						fireDiarizationEvent(e.getLocalizedMessage());
					}

					// add record to session
					MediaSegment seg = factory.createMediaSegment();
					seg.setStartValue((float)currentStart);
					seg.setEndValue((float)currentEnd);
					r.getSegment().setGroup(0, seg);

					retVal.addRecord(r);
				}

				Participant speaker = speakerMap.get(wordInfo.getSpeakerTag());
				if(speaker == null) {
					speaker = factory.createParticipant();
					speaker.setId("P" + wordInfo.getSpeakerTag());
					speaker.setRole(ParticipantRole.PARTICIPANT);

					retVal.addParticipant(speaker);
					speakerMap.put(wordInfo.getSpeakerTag(), speaker);
				}
				currentSpeaker = speaker;
				currentSpeakerTag = wordInfo.getSpeakerTag();

				currentStart = -1L;
				builder = new StringBuilder();
			}

			if(currentStart < 0) {
				currentStart = wordInfo.getStartTime().getSeconds() * 1000;
				currentStart += wordInfo.getStartTime().getNanos() * (1e-6);
			}
			currentEnd = wordInfo.getEndTime().getSeconds() * 1000;
			currentEnd += wordInfo.getEndTime().getNanos() * (1e-6);

			if(builder.length() > 0) builder.append(" ");
			builder.append(wordInfo.getWord());
		}

		if(currentStart >= 0) {
			Record r = factory.createRecord(currentSpeaker);
			try {
				Orthography ortho = Orthography.parseOrthography(builder.toString());
				r.getOrthography().setGroup(0, ortho);
			} catch (ParseException e) {
				LogUtil.warning(e);
				fireDiarizationEvent(e.getLocalizedMessage());
			}

			// add record to session
			MediaSegment seg = factory.createMediaSegment();
			seg.setStartValue((float)currentStart);
			seg.setEndValue((float)currentEnd);
			r.getSegment().setGroup(0, seg);

			retVal.addRecord(r);
		}

		return retVal;
	}

	private Session gcstShortFileDiarization(File file) throws IOException {
		Path path = file.toPath();
		byte[] content = Files.readAllBytes(path);

		try (SpeechClient speechClient = SpeechClient.create()) {
			RecognitionAudio recognitionAudio =
					RecognitionAudio.newBuilder().setContent(ByteString.copyFrom(content)).build();

			SpeakerDiarizationConfig speakerDiarizationConfig =
					SpeakerDiarizationConfig.newBuilder()
							.setEnableSpeakerDiarization(true)
							.setMaxSpeakerCount(this.maxSpeakers)
							.build();

			RecognitionConfig config =
					RecognitionConfig.newBuilder()
							.setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
							.setLanguageCode(this.languageModel)
							.setSampleRateHertz((int)UPLOAD_SAMPLE_RATE)
							.setDiarizationConfig(speakerDiarizationConfig)
							.build();

			RecognizeResponse recognizeResponse = speechClient.recognize(config, recognitionAudio);

			// Speaker Tags are only included in the last result object, which has only one alternative.
			SpeechRecognitionAlternative alternative =
					recognizeResponse.getResults(recognizeResponse.getResultsCount() - 1).getAlternatives(0);

			return processSpeechRecognitionAlternative(alternative);
		}
	}

	private Session gcstBucketDiarization(String gsUrl) throws IOException {
		try (SpeechClient speechClient = SpeechClient.create()) {
			SpeakerDiarizationConfig speakerDiarizationConfig =
					SpeakerDiarizationConfig.newBuilder()
							.setEnableSpeakerDiarization(true)
							.setMaxSpeakerCount(this.maxSpeakers)
							.build();

			RecognitionConfig config =
					RecognitionConfig.newBuilder()
							.setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
							.setLanguageCode(this.languageModel)
							.setSampleRateHertz((int) UPLOAD_SAMPLE_RATE)
							.setDiarizationConfig(speakerDiarizationConfig)
							.build();

			RecognitionAudio audio = RecognitionAudio.newBuilder().setUri(gsUrl).build();

			OperationFuture<LongRunningRecognizeResponse, LongRunningRecognizeMetadata> response =
					speechClient.longRunningRecognizeAsync(config, audio);

			while(!response.isDone()) {
				fireDiarizationEvent("Waiting for response...");
				Thread.sleep(10000);
			}

			LongRunningRecognizeResponse longRunningRecognizeResponse = response.get();
			SpeechRecognitionAlternative alternative =
					longRunningRecognizeResponse.getResults(longRunningRecognizeResponse.getResultsCount() - 1)
							.getAlternatives(0);

			return processSpeechRecognitionAlternative(alternative);
		} catch (InterruptedException | ExecutionException e) {
			throw new IOException(e);
		}
	}

	private Session gcstDiarizeFile(File file) throws GoogleJsonResponseException {
		fireDiarizationEvent("Google Cloud Speech to Text Diarization");
		fireDiarizationEvent("---------------------------------------");
		fireDiarizationEvent("Project id: " + projectId);
		fireDiarizationEvent("Service account credentials file: " + credentialsFile);
		fireDiarizationEvent("\n");

		try {
			loadCredentials();
		} catch (IOException e) {
			LogUtil.severe(e);
			Toolkit.getDefaultToolkit().beep();
			fireDiarizationEvent(DiarizationEvent.DiarizationEventType.DiarizationError, e.getLocalizedMessage());
			return null;
		}

		String bucketName = bucketName();
		if(!hasBucket(projectId, bucketName)) {
			fireDiarizationEvent("Creating storage bucket with id " + bucketName);
			createStorageBucket(projectId, bucketName);
		} else {
			fireDiarizationEvent("Using storage bucket with id " + bucketName);
		}

		try (AudioFile audioFile = AudioIO.openAudioFile(file)) {
			final File resampledFile = checkAudioFileEncoding(audioFile);

			// if less than 60s, use short method
			if(audioFile.getLength() < 60.0f) {
				fireDiarizationEvent("Waiting for diarization result....");
				return gcstShortFileDiarization(resampledFile);
			} else {
				if(!audioFileExistsInBucket(projectId, bucketName, file.getName())) {
					try {
						fireDiarizationEvent("Uploading audio file to bucket " + bucketName + " with name " + file.getName());
						uploadAudioFile(projectId, bucketName, file.getName(), resampledFile);
					} catch (IOException e) {
						LogUtil.severe(e);
						Toolkit.getDefaultToolkit().beep();
						fireDiarizationEvent(DiarizationEvent.DiarizationEventType.DiarizationError, e.getLocalizedMessage());
						return null;
					}
				}
				fireDiarizationEvent("Waiting for diarization result....");
				final String gsUrl = "gs://" + bucketName + "/" + file.getName();
				return gcstBucketDiarization(gsUrl);
			}
		} catch (IOException | AudioIOException e) {
			LogUtil.severe(e);
			fireDiarizationEvent(DiarizationEvent.DiarizationEventType.DiarizationError, e.getLocalizedMessage());
			return null;
		}
	}

	@Override
	public DiarizationFutureResult diarize(final File file) throws IOException {
		FutureTask<Session> sessionFutureTask = new FutureTask<Session>( () -> {
			try {
				return gcstDiarizeFile(file);
			} catch (StorageException | GoogleJsonResponseException ex) {
				fireDiarizationEvent(DiarizationEvent.DiarizationEventType.DiarizationError, ex.getMessage());
				return null;
			}
		} );

		PhonWorker worker = PhonWorker.createWorker();
		worker.setName("Phon Diarization");
		worker.setFinishWhenQueueEmpty(true);
		worker.invokeLater(sessionFutureTask);
		worker.start();

		return new GCSTDiarizationResult(sessionFutureTask);
	}

	private class GCSTDiarizationResult implements DiarizationFutureResult {

		FutureTask<Session> futureSession;

		public GCSTDiarizationResult(FutureTask<Session> futureSession) {
			this.futureSession = futureSession;
		}

		@Override
		public Future<Session> getFutureSession() {
			return futureSession;
		}

		@Override
		public void cancel() {
			this.futureSession.cancel(true);
		}

	}

}
