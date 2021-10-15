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

import ca.phon.project.Project;
import ca.phon.session.Session;
import ca.phon.session.io.*;

import java.io.*;

public class DiarizationResultsManager {

	/**
	 * Location of stored diarization files in project __res folder.
	 */
	public final static String DIARIZATION_FOLDER = "diarization";

	private final Project project;

	private final Session session;

	public DiarizationResultsManager(Project project, Session session) {
		this.project = project;
		this.session = session;
	}

	public Project getProject() { return this.project; }

	public Session getSession() { return this.session; }

	/**
	 * Return location of diarization results file
	 *
	 * @param createFolder create diarization results folder for project if it does not exist
	 * @return
	 */
	public File diarizationResultsFile(boolean createFolder) {
		File resFolder = new File(project.getLocation(), "__res");
		File diarizationFolder = new File(resFolder, DIARIZATION_FOLDER);

		if(createFolder && !diarizationFolder.exists()) {
			diarizationFolder.mkdirs();
		}

		File retVal = new File(diarizationFolder, session.getCorpus() + "_" + session.getName() + ".xml");
		return retVal;
	}

	/**
	 * Does the session have an existing results file?
	 *
	 * @return true if results files exists
	 */
	public boolean hasDiarizationsResults() {
		File diarizationResultsFile = diarizationResultsFile(false);
		return diarizationResultsFile.exists();
	}

	/**
	 * Save diarization results
	 *
	 * @param s
	 * @throws IOException
	 */
	public void saveDiarizationResults(Session s) throws IOException {
		SessionOutputFactory outputFactory = new SessionOutputFactory();
		SessionWriter writer = outputFactory.createWriter();
		writer.writeSession(s, new FileOutputStream(diarizationResultsFile(true)));
	}

}
