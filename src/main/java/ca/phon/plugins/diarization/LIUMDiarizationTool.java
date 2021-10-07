package ca.phon.plugins.diarization;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import ca.phon.app.log.LogUtil;
import ca.phon.plugins.diarization.*;
import ca.phon.session.io.*;
import ca.phon.worker.PhonWorker;

import ca.phon.session.Session;
import ca.phon.ui.nativedialogs.OSInfo;

/**
 * Diarization using the LIUM speaker diarization tool available from
 * https://projets-lium.univ-lemans.fr/spkdiarization/.
 *
 */
public final class LIUMDiarizationTool extends DiarizationTool {
	
	private final static String MAIN_CLASS = "ca.phon.plugins.diarization.SpkDiarization";
	
	private final static String XSLT_FILE = "epac2session.xslt";

	public static final String THRESHOLDS = "--thresholds";

	public static final String DO_CECLUSTERING = "--doCEClustering";

	private boolean doCEClustering = true;

	/** The h max. */
	private double lMin = 2, lMax = 2, hMin = 3, hMax = 3;

	/** The d max. */
	private double dMin = 250, dMax = 250;

	/** The c min. */
	private double cMin = 1.2;

	/** The c max. */
	private double cMax = 1.2;

	public void setDoCEClustering(boolean doCEClustering) {
		this.doCEClustering = doCEClustering;
	}

	public boolean isDoCEClustering() {
		return this.doCEClustering;
	}

	public double getlMin() {
		return lMin;
	}

	public void setlMin(double lMin) {
		this.lMin = lMin;
	}

	public double getlMax() {
		return lMax;
	}

	public void setlMax(double lMax) {
		this.lMax = lMax;
	}

	public double gethMin() {
		return hMin;
	}

	public void sethMin(double hMin) {
		this.hMin = hMin;
	}

	public double gethMax() {
		return hMax;
	}

	public void sethMax(double hMax) {
		this.hMax = hMax;
	}

	public double getdMin() {
		return dMin;
	}

	public void setdMin(double dMin) {
		this.dMin = dMin;
	}

	public double getdMax() {
		return dMax;
	}

	public void setdMax(double dMax) {
		this.dMax = dMax;
	}

	public double getcMin() {
		return cMin;
	}

	public void setcMin(double cMin) {
		this.cMin = cMin;
	}

	public double getcMax() {
		return cMax;
	}

	public void setcMax(double cMax) {
		this.cMax = cMax;
	}

	public DiarizationResult diarize(File audioFile) throws IOException {
		final String javaHome = System.getProperty("java.home");
		final String javaBin = javaHome + File.separator + "bin" + File.separator + "java" + 
				(OSInfo.isWindows() ? ".exe" : "");
		final String cp = System.getProperty("java.class.path");
		
		final File tmpFile = File.createTempFile("phon-diarization", audioFile.getName());
		
		List<String> fullCmd = new ArrayList<String>();
		String[] cmd = {
				javaBin,
				"-cp", cp,
				MAIN_CLASS,
				DO_CECLUSTERING,
				THRESHOLDS, String.format("%f:%f,%f:%f,%f:%f,%f:%f", getlMin(), getlMax(), gethMin(), gethMax(), getdMin(), getdMax(), getcMin(), getcMax()),
				"--fInputMask", audioFile.getAbsolutePath(),
				"--sOutputMask", tmpFile.getAbsolutePath(),
				"--sOutputFormat", "seg.xml"
		};
		fullCmd.addAll(Arrays.asList(cmd));
		fullCmd.add(audioFile.getName());
		
		ProcessBuilder pb = new ProcessBuilder(fullCmd);

		fireDiarizationEvent(DiarizationEvent.DiarizationEventType.DiarizationStarted, "");
		Process p = pb.start();
		Runnable readErrData = () -> {
			// read everything from stderr
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
				String line = null;
				while ((line = reader.readLine()) != null) {
					// remove time from beginning of string
					fireDiarizationEvent(line.substring(10));
				}
			} catch (IOException e) {
				fireDiarizationEvent(DiarizationEvent.DiarizationEventType.DiarizationError, e.getLocalizedMessage());
				LogUtil.severe(e);
			}
		};
		PhonWorker.getInstance().invokeLater(readErrData);

		Future<Session> futureSession = p.onExit().thenApply( process -> {
			int exitValue = p.exitValue();
			if(exitValue == 0) {
				try {
					Session retVal = transformResults(tmpFile);
					fireDiarizationEvent(DiarizationEvent.DiarizationEventType.DiarizationCompleted, retVal.getRecordCount() + " segments detected");
					return retVal;
				} catch (IOException e) {
					fireDiarizationEvent(DiarizationEvent.DiarizationEventType.DiarizationError, e.getLocalizedMessage());
					return null;
				}
			} else {
				fireDiarizationEvent(DiarizationEvent.DiarizationEventType.DiarizationError, "Process exited with value " + exitValue);
				return null;
			}
		});
		return new LIUMDiarizationResult(p, futureSession);
	}

	private Session transformResults(File resultFile) throws IOException {
		try {
			SessionInputFactory inputFactory = new SessionInputFactory();
			StreamSource stylesource = new StreamSource(getClass().getResourceAsStream(XSLT_FILE)); 
            Transformer transformer = TransformerFactory.newInstance().newTransformer(stylesource);

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            transformer.transform(new StreamSource(resultFile), new StreamResult(bout));
            
            return inputFactory.createReader("phonbank", "1.2").readSession(new ByteArrayInputStream(bout.toByteArray()));
		} catch (TransformerFactoryConfigurationError | TransformerException e) {
			throw new IOException(e);
		}
	}
	
	public static class LIUMDiarizationResult implements DiarizationResult {
		
		private Process process;
		
		private Future<Session> futureSession;
		
		public LIUMDiarizationResult(Process process, Future<Session> futureSession) {
			this.process = process;
			this.futureSession = futureSession;
		}
		
		public Process getProcess() {
			return this.process;
		}

		@Override
		public Future<Session> getFutureSession() {
			return futureSession;
		}

		@Override
		public void cancel() {
			getProcess().destroyForcibly();
		}

	}
	
}
