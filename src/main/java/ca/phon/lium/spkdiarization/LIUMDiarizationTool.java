package ca.phon.lium.spkdiarization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import ca.phon.session.Session;
import ca.phon.session.io.SessionInputFactory;
import ca.phon.ui.nativedialogs.OSInfo;

public class LIUMDiarizationTool {
	
	private final static String MAIN_CLASS = "fr.lium.spkDiarization.system.Diarization";
	
	private final static String XSLT_FILE = "epac2session.xslt";

	public static final String DO_DECLUSTERING = "--doDEClustering";
	
	public Future<Session> diarize(File audioFile, String[] args) throws IOException {
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
				"--fInputMask", audioFile.getAbsolutePath(),
				"--sOutputMask", tmpFile.getAbsolutePath(),
				"--sOutputFormat", "seg.xml"
		};
		fullCmd.addAll(Arrays.asList(cmd));
		fullCmd.addAll(Arrays.asList(args));
		fullCmd.add(audioFile.getName());
		
		ProcessBuilder pb = new ProcessBuilder(fullCmd);
		pb.redirectError(new File("/Users/ghedlund/LIUM/err.log"));
		
		var p = pb.start();
		return p.onExit().thenApply( process -> {
			if(p.exitValue() == 0) {
				try {
					return transformResults(tmpFile);
				} catch (IOException e) {
					return null;
				}
			} else {
				return null;
			}
		});
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
	
}
