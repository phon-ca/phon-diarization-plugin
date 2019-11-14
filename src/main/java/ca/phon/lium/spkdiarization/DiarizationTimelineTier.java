package ca.phon.lium.spkdiarization;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.swing.JButton;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import org.jdesktop.swingx.JXBusyLabel;

import ca.phon.app.log.LogUtil;
import ca.phon.app.session.SessionMediaModel;
import ca.phon.app.session.editor.undo.AddParticipantEdit;
import ca.phon.app.session.editor.undo.AddRecordEdit;
import ca.phon.app.session.editor.view.timeline.RecordGrid;
import ca.phon.app.session.editor.view.timeline.TimelineTier;
import ca.phon.app.session.editor.view.timeline.TimelineView;
import ca.phon.lium.spkdiarization.LIUMDiarizationTool.DiarizationResult;
import ca.phon.plugin.PhonPlugin;
import ca.phon.session.Participant;
import ca.phon.session.Record;
import ca.phon.session.Session;
import ca.phon.session.SessionFactory;
import ca.phon.session.SystemTierType;
import ca.phon.ui.action.PhonActionEvent;
import ca.phon.ui.action.PhonUIAction;
import ca.phon.ui.menu.MenuBuilder;
import ca.phon.worker.PhonWorker;

@PhonPlugin(name="phon-diarization-plugin", author="Greg Hedlund", minPhonVersion="3.1.0")
public class DiarizationTimelineTier extends TimelineTier {

	private JToolBar toolbar;
	
	private JXBusyLabel busyLabel;
	private JButton diarizeButton;
	
	private JButton addAllButton;
	private JButton clearResultsButton;
	
	private RecordGrid recordGrid;
	
	public DiarizationTimelineTier(TimelineView parent) {
		super(parent);
		
		init();
	}
	
	private void init() {
		SessionFactory factory = SessionFactory.newFactory();
		recordGrid = new RecordGrid(getTimeModel(), factory.createSession());
		recordGrid.setTiers(Collections.singletonList(SystemTierType.Segment.getName()));
		
		toolbar = getParentView().getToolbar();
		
		busyLabel = new JXBusyLabel(new Dimension(16, 16));
		busyLabel.setBusy(false);
		
		final PhonUIAction diarizeAct = new PhonUIAction(this, "onDiarize");
		diarizeAct.putValue(PhonUIAction.NAME, "Diarization");
		diarizeAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Run LIUM speaker diarization tool");
		diarizeButton = new JButton(diarizeAct);
		
		final PhonUIAction addAllAct = new PhonUIAction(this, "onAddAllResults");
		addAllAct.putValue(PhonUIAction.NAME, "Add all diarization results");
		addAllAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Add all diarization results");
		addAllButton = new JButton(addAllAct);
		
		final PhonUIAction clearAllAct = new PhonUIAction(this, "onClearResults");
		clearAllAct.putValue(PhonUIAction.NAME, "Clear diarization results");
		clearAllAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Clear diarization results");
		clearResultsButton = new JButton(clearAllAct);
		
		toolbar.addSeparator();
		updateToolbarButtons();
		
		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(recordGrid, BorderLayout.CENTER);
	}
	
	private void updateToolbarButtons() {
		if(hasDiarizationResults()) {
			toolbar.remove(busyLabel);
			toolbar.remove(diarizeButton);
			
			toolbar.add(addAllButton);
			toolbar.add(clearResultsButton);
		} else {
			toolbar.remove(addAllButton);
			toolbar.remove(clearResultsButton);
			
			toolbar.add(busyLabel);
			toolbar.add(diarizeButton);
		}
		
		toolbar.revalidate();
		toolbar.repaint();
		
		diarizeButton.setEnabled(getParentView().getEditor().getMediaModel().isSessionAudioAvailable());
	}
	
	public boolean hasDiarizationResults() {
		return recordGrid.getSession().getRecordCount() > 0;
	}
	
	public void setSession(Session session) {
		recordGrid.setSession(session);
		
		recordGrid.clearSpeakers();
		session.getParticipants().forEach(recordGrid::addSpeaker);
		
		getParentView().getTierPanel().revalidate();
		recordGrid.repaint();
	}
	
	public void onDiarize(PhonActionEvent pae) {
		SessionMediaModel mediaModel = getParentView().getEditor().getMediaModel();
		if(!mediaModel.isSessionAudioAvailable()) {
			Toolkit.getDefaultToolkit().beep();
			return;
		}
		
		busyLabel.setBusy(true);
		
		LIUMDiarizationTool tool = new LIUMDiarizationTool();
		try {
			DiarizationResult result = tool.diarize(mediaModel.getSessionAudioFile(), new String[0]);
			
			DiarizationWorker worker = new DiarizationWorker(result.getFutureSession());
			worker.execute();
		} catch (IOException e) {
			Toolkit.getDefaultToolkit().beep();
			LogUtil.severe(e);
		}		
	}

	public void onAddAllResults(PhonActionEvent pae) {
		if(!hasDiarizationResults()) return;
		
		getParentView().getEditor().getUndoSupport().beginUpdate();
		
		for(Participant p:recordGrid.getSession().getParticipants()) {
			final AddParticipantEdit addSpeakerEdit = new AddParticipantEdit(getParentView().getEditor(), p);
			getParentView().getEditor().getUndoSupport().postEdit(addSpeakerEdit);
		}
		
		int editIdx = 0;
		for(Record r:recordGrid.getSession().getRecords()) {
			final AddRecordEdit addEdit = new AddRecordEdit(getParentView().getEditor(), r);
			addEdit.setFireEvent(editIdx++ == 0);
			getParentView().getEditor().getUndoSupport().postEdit(addEdit);
		}
		
		getParentView().getEditor().getUndoSupport().endUpdate();
		onClearResults(pae);
	}
	
	public void onClearResults(PhonActionEvent pae) {
		SessionFactory factory = SessionFactory.newFactory();
		setSession(factory.createSession());
		updateToolbarButtons();
	}
	
	@Override
	public void setupContextMenu(MouseEvent me, MenuBuilder builder) {
		builder.addSeparator(".", "diarization");
		if(hasDiarizationResults()) {
			final PhonUIAction addAllAct = new PhonUIAction(this, "onAddAllResults");
			addAllAct.putValue(PhonUIAction.NAME, "Add all diarization results");
			addAllAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Add all diarization results");			
			
			final PhonUIAction clearAllAct = new PhonUIAction(this, "onClearResults");
			clearAllAct.putValue(PhonUIAction.NAME, "Clear diarization results");
			clearAllAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Clear diarization results");

			builder.addItem(".", addAllAct);
			builder.addItem(".", clearAllAct);
		} else {
			final PhonUIAction diarizeAct = new PhonUIAction(this, "onDiarize");
			diarizeAct.putValue(PhonUIAction.NAME, "Diarization");
			diarizeAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Run LIUM speaker diarization tool");
			
			builder.addItem(".", diarizeAct).setEnabled(getParentView().getEditor().getMediaModel().isSessionAudioAvailable());
		}
	}

	@Override
	public boolean isResizeable() {
		return false;
	}
	
	private class DiarizationWorker extends SwingWorker<Session, Session> {

		private Future<Session> futureSession;
		
		public DiarizationWorker(Future<Session> futureSession) {
			this.futureSession = futureSession;
		}
		
		@Override
		protected Session doInBackground() throws Exception {
			Session s = futureSession.get();
			publish(s);
			return s;
		}
		
		@Override
		protected void process(List<Session> chunks) {
			if(chunks.size() > 0) {
				Session s = chunks.get(0);
				setSession(s);
			}
		}

		@Override
		protected void done() {
			busyLabel.setBusy(false);
			updateToolbarButtons();
		}
		
	}
	
}
