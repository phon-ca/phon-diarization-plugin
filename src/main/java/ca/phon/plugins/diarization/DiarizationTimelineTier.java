package ca.phon.plugins.diarization;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.*;

import javax.swing.*;
import javax.swing.undo.*;

import ca.phon.app.session.editor.view.timeline.*;
import ca.phon.session.io.*;
import ca.phon.util.Tuple;

import ca.phon.app.log.LogUtil;
import ca.phon.app.session.editor.undo.AddParticipantEdit;
import ca.phon.app.session.editor.undo.AddRecordEdit;
import ca.phon.app.session.editor.view.timeline.RecordGrid.GhostMarker;
import ca.phon.media.TimeUIModel;
import ca.phon.media.TimeUIModel.Marker;
import ca.phon.plugin.PhonPlugin;
import ca.phon.session.MediaSegment;
import ca.phon.session.Participant;
import ca.phon.session.Record;
import ca.phon.session.Session;
import ca.phon.session.SessionFactory;
import ca.phon.session.SystemTierType;
import ca.phon.ui.action.PhonActionEvent;
import ca.phon.ui.action.PhonUIAction;
import ca.phon.ui.menu.MenuBuilder;

@PhonPlugin(name="phon-diarization-plugin", author="Greg Hedlund", minPhonVersion="3.1.0")
public class DiarizationTimelineTier extends TimelineTier {

	private JToolBar toolbar;

	private JButton diarizeButton;
	
	private RecordGrid recordGrid;
	private TimeUIModel.Interval currentRecordInterval = null;

	public DiarizationTimelineTier(TimelineView parent) {
		super(parent);
		
		init();
	}
	
	private void init() {
		SessionFactory factory = SessionFactory.newFactory();
		recordGrid = new RecordGrid(getTimeModel(), factory.createSession());
		recordGrid.setTiers(Collections.singletonList(SystemTierType.Segment.getName()));
		recordGrid.addRecordGridMouseListener(mouseListener);

		recordGrid.addParticipantMenuHandler((Participant p, MenuBuilder builder) -> {
			builder.removeAll();
			JMenu assignMenu = builder.addMenu(".", "Assign records to participant");
			setupParticipantAssigmentMenu(p, new MenuBuilder(assignMenu));
		});
		
		toolbar = getParentView().getToolbar();
		
		final PhonUIAction diarizeAct = new PhonUIAction(this, "showDiarizationMenu");
		diarizeAct.putValue(PhonUIAction.NAME, "Diarization");
		diarizeAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Run LIUM speaker diarization tool");
		diarizeButton = new JButton(diarizeAct);
		
		toolbar.addSeparator();
		toolbar.add(diarizeButton);
		
		setLayout(new BorderLayout());
		add(new TimelineTitledSeparator(getTimeModel(), "Diarization Results", null, SwingConstants.LEFT, Color.black, 1), BorderLayout.NORTH);
		add(recordGrid, BorderLayout.CENTER);

		setVisible(false);
	}

	private void setupRecordGridActions() {
		final InputMap inputMap = recordGrid.getInputMap();
		final ActionMap actionMap = recordGrid.getActionMap();

		final String selectAllKey = "select_all";
		final PhonUIAction selectAllAct = new PhonUIAction(this, "onSelectAll");
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), selectAllKey);
		actionMap.put(selectAllKey, selectAllAct);

		final String escapeKey = "escape";
		final PhonUIAction escapeAction = new PhonUIAction(this, "onEscape", false);
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), escapeKey);
		actionMap.put(escapeKey, escapeAction);
//
//		final String deleteRecordKey = "delete_record";
//		final DeleteRecordsAction deleteRecordAction = new DeleteRecordsAction(getParentView());
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), deleteRecordKey);
//		actionMap.put(deleteRecordKey, deleteRecordAction);
//
//		final String playSegmentKey = "play_segment";
//		final PlaySegmentAction playSegmentAction = new PlaySegmentAction(getParentView().getEditor());
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), playSegmentKey);
//		actionMap.put(playSegmentKey, playSegmentAction);
//
//		final String moveRight = "move_segments_right";
//		final PhonUIAction moveRightAct = new PhonUIAction(this, "onMoveSegmentsRight", 5);
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, KeyEvent.CTRL_DOWN_MASK), moveRight);
//		actionMap.put(moveRight, moveRightAct);
//
//		final String moveRightSlow = "move_segments_right_slow";
//		final PhonUIAction moveRightSlowAct = new PhonUIAction(this, "onMoveSegmentsRight", 1);
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), moveRightSlow);
//		actionMap.put(moveRightSlow, moveRightSlowAct);
//
//		final String moveLeft = "move_segments_left";
//		final PhonUIAction moveLeftAct = new PhonUIAction(this, "onMoveSegmentsLeft", 5);
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, KeyEvent.CTRL_DOWN_MASK), moveLeft);
//		actionMap.put(moveLeft, moveLeftAct);
//
//		final String moveLeftSlow = "move_segments_left_slow";
//		final PhonUIAction moveLeftSlowAct = new PhonUIAction(this, "onMoveSegmentsLeft", 1);
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), moveLeftSlow);
//		actionMap.put(moveLeftSlow, moveLeftSlowAct);
//
//		final String move = "move_segments";
//		final PhonUIAction moveAct = new PhonUIAction(this, "onMoveSegments");
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, KeyEvent.CTRL_DOWN_MASK), move);
//		actionMap.put(move, moveAct);
//
//		final String growSegments = "grow_segments";
//		final PhonUIAction growSegmentsAct = new PhonUIAction(this, "onGrowSegments", 3);
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK ), growSegments);
//		actionMap.put(growSegments, growSegmentsAct);
//
//		final String growSegmentsSlow = "grow_segments_slow";
//		final PhonUIAction growSegmentsSlowAct = new PhonUIAction(this, "onGrowSegments", 1);
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), growSegmentsSlow);
//		actionMap.put(growSegmentsSlow, growSegmentsSlowAct);
//
//		final String shrinkSegments = "shrink_segments";
//		final PhonUIAction shrinkSegmentsAct = new PhonUIAction(this, "onShrinkSegments", 3);
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK ), shrinkSegments);
//		actionMap.put(shrinkSegments, shrinkSegmentsAct);
//
//		final String shrinkSegmentsSlow = "shrink_segments_slow";
//		final PhonUIAction shrinkSegmentsSlowAct = new PhonUIAction(this, "onShrinkSegments", 1);
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK |KeyEvent.SHIFT_DOWN_MASK), shrinkSegmentsSlow);
//		actionMap.put(shrinkSegmentsSlow, shrinkSegmentsSlowAct);
//
//		final PhonUIAction copyRecordsAct = new PhonUIAction(this, "copy");
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "copy");
//		actionMap.put("copy", copyRecordsAct);
//
//		final PhonUIAction pasteRecordsAct = new PhonUIAction( this, "paste" );
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "paste");
//		actionMap.put("paste", pasteRecordsAct);
//
//		final PhonUIAction cutRecordsAct = new PhonUIAction(this, "cut");
//		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "cut");
//		actionMap.put("cut", cutRecordsAct);
//
//		for (int i = 0; i < 10; i++) {
//			final PhonUIAction chSpeakerAct = new PhonUIAction(this, "onChangeSpeakerByIndex", i);
//			KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_0 + i,
//					Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
//			chSpeakerAct.putValue(PhonUIAction.ACCELERATOR_KEY, ks);
//			String id = "change_speaker_" + i;
//
//			actionMap.put(id, chSpeakerAct);
//			inputMap.put(ks, id);
//		}
//
//		// split record
//		final String splitRecordId = "split_record";
//		final SplitRecordAction splitRecordAct = new SplitRecordAction(getParentView());
//		final KeyStroke splitRecordKs = KeyStroke.getKeyStroke(KeyEvent.VK_S, 0);
//		inputMap.put(splitRecordKs, splitRecordId);
//		actionMap.put(splitRecordId, splitRecordAct);
//
//		final String acceptSplitId = "accept_split_record";
//		final PhonUIAction acceptSplitRecordAct = new PhonUIAction(this, "onEndSplitRecord", true);
//		final KeyStroke acceptSplitRecordKs = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
//		inputMap.put(acceptSplitRecordKs, acceptSplitId);
//		actionMap.put(acceptSplitId, acceptSplitRecordAct);
//
//		// modify record split
//		final String splitAtGroupId = "split_record_at_group_";
//		for (int i = 0; i < 10; i++) {
//			final PhonUIAction splitRecordAtGrpAct = new PhonUIAction(this, "onSplitRecordOnGroup", i);
//			final KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_0 + i, 0);
//			inputMap.put(ks, splitAtGroupId + i);
//			actionMap.put(splitAtGroupId + i, splitRecordAtGrpAct);
//		}
//
//		recordGrid.setInputMap(WHEN_FOCUSED, inputMap);
//		recordGrid.setActionMap(actionMap);
	}
	
	private void setupRecord(Record r) {
		if (currentRecordInterval != null)
			getTimeModel().removeInterval(currentRecordInterval);

		MediaSegment segment = r.getSegment().getGroup(0);
		var segStartTime = segment.getStartValue() / 1000.0f;
		var segEndTime = segment.getEndValue() / 1000.0f;

		// check for 'GhostMarker's which, if present, will
		// become the start/end marker for the record interval
		Optional<RecordGrid.GhostMarker> ghostMarker = recordGrid.getTimeModel().getMarkers().parallelStream()
				.filter((m) -> m instanceof GhostMarker).map((m) -> GhostMarker.class.cast(m)).findAny();
		if (ghostMarker.isPresent() && recordGrid.getUI().getCurrentlyDraggedMarker() == ghostMarker.get()) {
			Marker startMarker = ghostMarker.get().isStart() ? ghostMarker.get() : new Marker(segStartTime);
			Marker endMarker = ghostMarker.get().isStart() ? new Marker(segEndTime) : ghostMarker.get();
			currentRecordInterval = getTimeModel().addInterval(startMarker, endMarker);
			currentRecordInterval.setRepaintEntireInterval(true);
			currentRecordInterval.addPropertyChangeListener(new RecordIntervalListener());
			recordGrid.getUI().beginDrag(currentRecordInterval, ghostMarker.get());
		} else {
			currentRecordInterval = getTimeModel().addInterval(segStartTime, segEndTime);
			currentRecordInterval.setRepaintEntireInterval(true);
			currentRecordInterval.addPropertyChangeListener(new RecordIntervalListener());
		}
		currentRecordInterval.getStartMarker()
				.setColor(UIManager.getColor(recordGrid.hasFocus() ? TimelineViewColors.FOCUSED_INTERVAL_MARKER_COLOR
						: TimelineViewColors.INTERVAL_MARKER_COLOR));
		currentRecordInterval.getEndMarker()
				.setColor(UIManager.getColor(recordGrid.hasFocus() ? TimelineViewColors.FOCUSED_INTERVAL_MARKER_COLOR
						: TimelineViewColors.INTERVAL_MARKER_COLOR));
		currentRecordInterval
				.setColor(UIManager.getColor(recordGrid.hasFocus() ? TimelineViewColors.FOCUSED_INTERVAL_BACKGROUND
						: TimelineViewColors.INTERVAL_BACKGROUND));

		recordGrid.setCurrentRecord(r);
		getParentView().getRecordTier().setupRecord(null);
		
		mouseListener.waitForRecordChange = false;
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

		if(session.getRecordCount() > 0) {
			setVisible(true);
			getParentView().getRecordTier().setVisible(false);
			getParentView().getWaveformTier().clearSelection();
		} else {
			setVisible(false);
			getParentView().getRecordTier().setVisible(true);
		}
	}

	public RecordGrid getRecordGrid() {
		return this.recordGrid;
	}

	public boolean isShowingDiarizationResults() {
		return this.isVisible() && this.recordGrid.getSession().getParticipantCount() > 0;
	}

	public void clearDiarizationResults() {
		setSession(SessionFactory.newFactory().createSession());
	}

	public ListSelectionModel getSelectionModel() {
		return recordGrid.getSelectionModel();
	}

	/**
	 * Is the speaker visible?
	 *
	 * @param speaker
	 * @return
	 */
	public boolean isSpeakerVisible(Participant speaker) {
		return recordGrid.getSpeakers().contains(speaker);
	}

	public void setSpeakerVisible(Participant speaker, boolean visible) {
		List<Participant> currentSpeakers = new ArrayList<>(recordGrid.getSpeakers());
		List<Participant> allSpeakers = new ArrayList<>();
		for(Participant p:recordGrid.getSession().getParticipants()) allSpeakers.add(p);

		List<Participant> newSpeakerList = new ArrayList<>();
		for(Participant sessionSpeaker:allSpeakers) {
			if(sessionSpeaker == speaker) {
				if(visible)
					newSpeakerList.add(sessionSpeaker);
			} else {
				if(currentSpeakers.contains(sessionSpeaker))
					newSpeakerList.add(sessionSpeaker);
			}
		}

		recordGrid.setSpeakers(newSpeakerList);
	}

	public void toggleSpeaker(PhonActionEvent pae) {
		Participant speaker = (Participant) pae.getData();
		setSpeakerVisible(speaker, !isSpeakerVisible(speaker));
	}

	public List<Participant> getSpeakerList() {
		List<Participant> retVal = new ArrayList<>();

		Session session = getParentView().getEditor().getSession();
		for (var speaker : session.getParticipants()) {
			if (isSpeakerVisible(speaker)) {
				retVal.add(speaker);
			}
		}

		return retVal;
	}

	public void onSelectAll(PhonActionEvent pae) {
		List<Integer> visibleRecords = new ArrayList<>();
		List<Participant> visibleSpeakers = getSpeakerList();

		for(int i = 0; i < getParentView().getEditor().getSession().getRecordCount(); i++) {
			if(visibleSpeakers.contains(getParentView().getEditor().getSession().getRecord(i).getSpeaker())) {
				visibleRecords.add(i);
			}
		}
		if(visibleRecords.size() == 0) return;

		if(getSelectionModel().getSelectedItemsCount() == visibleRecords.size()) {
			getSelectionModel().setSelectionInterval(getParentView().getEditor().getCurrentRecordIndex(),
					getParentView().getEditor().getCurrentRecordIndex());
		} else {
			for(int visibleRecord:visibleRecords) {
				getSelectionModel().addSelectionInterval(visibleRecord, visibleRecord);
			}
		}
		recordGrid.repaint(recordGrid.getVisibleRect());
	}

	public void showDiarizationMenu(PhonActionEvent pae) {
		JPopupMenu menu = new JPopupMenu();
		MenuBuilder builder = new MenuBuilder(menu);

		setupDiarizationMenu(builder);

		menu.show(diarizeButton, 0, diarizeButton.getHeight());
	}

	private void setupDiarizationMenu(MenuBuilder builder) {
		if(isShowingDiarizationResults()) {
			final PhonUIAction addDiarizationResultsAct = new PhonUIAction(this, "onAddAllResults");
			addDiarizationResultsAct.putValue(PhonUIAction.NAME, "Add diarization results to session");
			addDiarizationResultsAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Add all diarization results to session and close results");
			builder.addItem(".", addDiarizationResultsAct);

			final PhonUIAction closeDiarizationResultsAct = new PhonUIAction(this, "onClearResults");
			closeDiarizationResultsAct.putValue(PhonUIAction.NAME, "Close diarization results");
			closeDiarizationResultsAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Close diarization results with modifying session");
			builder.addItem(".", closeDiarizationResultsAct);
		} else {
			DiarizationResultsManager resultsManager = new DiarizationResultsManager(getParentView().getEditor().getProject(),
					getParentView().getEditor().getSession());
			File prevResultsFile = resultsManager.diarizationResultsFile(false);
			if (prevResultsFile.exists()) {
				PhonUIAction loadPrevResultsAct = new PhonUIAction(this, "onLoadPreviousResults", prevResultsFile);
				loadPrevResultsAct.putValue(PhonUIAction.NAME, "Load previous results");
				loadPrevResultsAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Load result from previous diarization execution");

				builder.addItem(".", loadPrevResultsAct);
				builder.addSeparator(".", "load_previous");
			}

			if (getParentView().getEditor().getMediaModel().isSessionAudioAvailable()) {
				PhonUIAction diarizationWizardAct = new PhonUIAction(this, "onDiarizationWizard");
				diarizationWizardAct.putValue(PhonUIAction.NAME, "Diarization wizard...");
				diarizationWizardAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Start diarization wizard");
				builder.addItem(".", diarizationWizardAct);
			} else {
				JMenuItem noAudioItem = new JMenuItem("<html><em>Session audio file not available</em></html>");
				noAudioItem.setEnabled(false);
				builder.addItem(".", noAudioItem);
			}
		}
	}

	private void setupParticipantAssigmentMenu(Participant diarizationParticipant, MenuBuilder builder) {
		if(getParentView().getEditor().getSession().getParticipantCount() > 0) {
			builder.addItem(".", "<html><em>Session Participants</em></html>").setEnabled(false);
			for (Participant sessionParticipant : getParentView().getEditor().getSession().getParticipants()) {

				final PhonUIAction assignToSpeakerAct = new PhonUIAction(this, "onAssignToSpeaker",
						new Tuple<>(diarizationParticipant, sessionParticipant));
				assignToSpeakerAct.putValue(PhonUIAction.NAME, sessionParticipant.toString());
				assignToSpeakerAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Assign all record to " + sessionParticipant);
				builder.addItem(".", assignToSpeakerAct);
			}
			builder.addSeparator(".", "session_participants");
		}
		builder.addItem(".", "<html><em>Diarization Participants</em></html>").setEnabled(false);
		for(Participant dp:getRecordGrid().getSession().getParticipants()) {
			int sidx = getParentView().getEditor().getSession().getParticipantIndex(dp);
			if(sidx < 0) {
				final PhonUIAction assignToSpeakerAct = new PhonUIAction(this, "onAssignToSpeaker",
						new Tuple<>(diarizationParticipant, dp));
				assignToSpeakerAct.putValue(PhonUIAction.NAME, dp.toString());
				assignToSpeakerAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Assign all record to " + dp);
				builder.addItem(".", assignToSpeakerAct);
			}
		}
	}

	/**
	 * Load previous results file
	 *
	 * @param pae with data of type File
	 *
	 * @throws IllegalArgumentException if pae.getData() is null or not a File
	 */
	public void onLoadPreviousResults(PhonActionEvent pae) {
		if(pae.getData() == null || !(pae.getData() instanceof File))
			throw new IllegalArgumentException();
		File f = (File)pae.getData();

		SessionReader reader = (new SessionInputFactory()).createReaderForFile(f);
		try {
			if (reader != null) {
				Session s = reader.readSession(new FileInputStream(f));
				setSession(s);
			}
		} catch (IOException e) {
			Toolkit.getDefaultToolkit().beep();
			LogUtil.severe(e);
		}
	}

	public void onAddAllResults(PhonActionEvent pae) {
		if(!hasDiarizationResults()) return;
		
		getParentView().getEditor().getUndoSupport().beginUpdate();
		
		for(Participant p:recordGrid.getSession().getParticipants()) {
			int idx = getParentView().getEditor().getSession().getParticipantIndex(p);
			if(idx < 0) {
				final AddParticipantEdit addSpeakerEdit = new AddParticipantEdit(getParentView().getEditor(), p);
				getParentView().getEditor().getUndoSupport().postEdit(addSpeakerEdit);
			}
		}
		
		int editIdx = 0;
		for(Record r:recordGrid.getSession().getRecords()) {
			final AddRecordEdit addEdit = new AddRecordEdit(getParentView().getEditor(), r);
			addEdit.setFireEvent(editIdx++ == 0);
			getParentView().getEditor().getUndoSupport().postEdit(addEdit);
		}
		
		getParentView().getEditor().getUndoSupport().endUpdate();
		clearDiarizationResults();
	}
	
	public void onClearResults(PhonActionEvent pae) {
		// update diarization results on disk
		DiarizationResultsManager manager = new DiarizationResultsManager(getParentView().getEditor().getProject(),
				getParentView().getEditor().getSession());
		try {
			manager.saveDiarizationResults(getRecordGrid().getSession());
		} catch (IOException e) {
			Toolkit.getDefaultToolkit().beep();
			LogUtil.severe(e);
		}
		clearDiarizationResults();
	}

	public void onAssignToSpeaker(PhonActionEvent pae) {
		Tuple<Participant, Participant> tuple = (Tuple<Participant, Participant>)pae.getData();
		Participant diarizationParticipant = tuple.getObj1();
		Participant sessionParticipant = tuple.getObj2();

		// remove diarization participant
		int idx = getRecordGrid().getSession().getParticipantIndex(diarizationParticipant);
		getRecordGrid().getSession().removeParticipant(diarizationParticipant);

		int spkIdx = getRecordGrid().getSession().getParticipantIndex(sessionParticipant);
		if(spkIdx < 0) {
			// replace diarization participant with session participant
			getRecordGrid().getSession().addParticipant(idx, sessionParticipant);
		}

		// reassign records
		getRecordGrid().getSession().getRecords().forEach( (r) -> {
			if(r.getSpeaker() == diarizationParticipant) r.setSpeaker(sessionParticipant); });

		// update record grid participant list
		var speakerList =
				StreamSupport.stream(getRecordGrid().getSession().getParticipants().spliterator(), false).collect(Collectors.toList());
		getRecordGrid().setSpeakers(speakerList);
	}

	public void onDiarizationWizard(PhonActionEvent pae) {
		DiarizationWizard wizard = new DiarizationWizard(this);
		wizard.pack();
		wizard.setSize(800,  600);
		wizard.setLocationRelativeTo(this);
		wizard.setVisible(true);
	}
	
	@Override
	public void setupContextMenu(MenuBuilder builder, boolean includeAccel) {
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
			if(getParentView().getEditor().getMediaModel().isSessionAudioAvailable())
				setupDiarizationMenu(builder);
		}
	}

	@Override
	public boolean isResizeable() {
		return false;
	}
	
	private class RecordIntervalListener implements PropertyChangeListener {

		@Override
		public void propertyChange(PropertyChangeEvent evt) {
			Record r = recordGrid.getCurrentRecord();
			if(r == null) return;
			
			MediaSegment segment = r.getSegment().getGroup(0);
			final SessionFactory factory = SessionFactory.newFactory();

			if(evt.getPropertyName().equals("valueAdjusting")) {
				if(recordGrid.isFocusable()) {
					recordGrid.requestFocusInWindow();
				}
				// exit split mode if active
				recordGrid.setSplitModeAccept(false);
				recordGrid.setSplitMode(false);
				
				if((boolean)evt.getNewValue()) {
					getParentView().getEditor().getUndoSupport().beginUpdate();
				} else {
					getParentView().getEditor().getUndoSupport().endUpdate();
				}
			} else if(evt.getPropertyName().endsWith("time")) {
				MediaSegment newSegment = factory.createMediaSegment();
				newSegment.setStartValue(segment.getStartValue());
				newSegment.setEndValue(segment.getEndValue());
				
				if(evt.getPropertyName().startsWith("startMarker")) {
					newSegment.setStartValue((float)evt.getNewValue() * 1000.0f);
				} else if(evt.getPropertyName().startsWith("endMarker")) {
					newSegment.setEndValue((float)evt.getNewValue() * 1000.0f);
				}

				UndoableDiarizationRecordIntervalChange edit = new UndoableDiarizationRecordIntervalChange(recordGrid.getCurrentRecord(), newSegment);
				getParentView().getEditor().getUndoSupport().postEdit(edit);
				
				recordGrid.repaint(recordGrid.getVisibleRect());
			}
		}

	}
	
	private final RecordMouseListener mouseListener = new RecordMouseListener();
	
	private class RecordMouseListener extends RecordGridMouseAdapter {

		private int currentDraggedRecord = -1;

		// offset (in sec) from left of interval where we are starting the drag
		private float mouseDragOffset = 0.0f;
		
		volatile boolean waitForRecordChange = false;

		@Override
		public void recordClicked(int recordIndex, MouseEvent me) {
		}

		@Override
		public void recordPressed(int recordIndex, MouseEvent me) {
			Record r = recordGrid.getSession().getRecord(recordIndex);
			MediaSegment seg = r.getSegment().getGroup(0);
			
			setupRecord(r);
			
			mouseDragOffset = getTimeModel().timeAtX(me.getX()) - seg.getStartValue() / 1000.0f;
		}

		@Override
		public void recordReleased(int recordIndex, MouseEvent me) {
			if (currentDraggedRecord >= 0) {
				currentDraggedRecord = -1;
				if (currentRecordInterval != null)
					currentRecordInterval.setValueAdjusting(false);
			}
		}

		@Override
		public void recordDragged(int recordIndex, MouseEvent me) {
//			if (getParentView().getEditor().getCurrentRecordIndex() != recordIndex) {
//				getParentView().getEditor().setCurrentRecordIndex(recordIndex);
//				waitForRecordChange = true;
//				return;
//			} else if(waitForRecordChange) {
//				return;
//			} else {
				// shouldn't happen
				if (currentRecordInterval == null)
					return;

				if (currentDraggedRecord != recordIndex) {
					// don't adjust an already changing interval
					if (currentRecordInterval.isValueAdjusting())
						return;
					currentDraggedRecord = recordIndex;
					currentRecordInterval.setValueAdjusting(true);
				}

				// scroll to mouse position if outside of visible rect
				Rectangle visibleRect = recordGrid.getVisibleRect();
				if (me.getX() < visibleRect.getX()) {
					visibleRect.translate(-10, 0);
					getParentView().scrollRectToVisible(visibleRect);
				} else if (me.getX() > visibleRect.getMaxX()) {
					visibleRect.translate(10, 0);
					getParentView().scrollRectToVisible(visibleRect);
				}

				Participant mouseOverSpeaker = recordGrid.getUI().getSpeakerAtPoint(me.getPoint());
				if (mouseOverSpeaker != null
						&& mouseOverSpeaker != recordGrid.getCurrentRecord().getSpeaker()) {
					UndoableDiarizationRecordSpeakerChange speakerChange = new UndoableDiarizationRecordSpeakerChange(recordGrid.getCurrentRecord(), mouseOverSpeaker);
					getParentView().getEditor().getUndoSupport().postEdit(speakerChange);
				}

				float startTime = currentRecordInterval.getStartMarker().getTime();
				float endTime = currentRecordInterval.getEndMarker().getTime();
				float intervalDuration = endTime - startTime;

				float oldOffsetTime = currentRecordInterval.getStartMarker().getTime() + mouseDragOffset;
				float newOffsetTime = recordGrid.timeAtX(me.getX());
				int direction = (oldOffsetTime < newOffsetTime ? 1 : -1);

				float newStartTime = 0.0f;
				float newEndTime = 0.0f;

				if (direction < 0) {
					newStartTime = Math.max(newOffsetTime - mouseDragOffset, getTimeModel().getStartTime());
					newEndTime = newStartTime + intervalDuration;
				} else {
					newEndTime = Math.min(newOffsetTime + (intervalDuration - mouseDragOffset),
							getTimeModel().getEndTime());
					newStartTime = newEndTime - intervalDuration;
				}

				currentRecordInterval.getStartMarker().setTime(newStartTime);
				currentRecordInterval.getEndMarker().setTime(newEndTime);
			}
//		}

	};

	@Override
	public void onClose() {
		onClearResults(new PhonActionEvent(new ActionEvent(this, -1, "close")));
	}

	/**
	 * Undo for record interval changes
	 */
	private class UndoableDiarizationRecordIntervalChange extends AbstractUndoableEdit {

		private Record record;

		private MediaSegment oldSegment;

		private MediaSegment newSegment;

		public UndoableDiarizationRecordIntervalChange(Record r, MediaSegment seg) {
			super();
			record = r;
			newSegment = seg;
			oldSegment = r.getSegment().getGroup(0);
			r.getSegment().setGroup(0, seg);
		}

		@Override
		public void undo() throws CannotUndoException {
			if(oldSegment == null) throw new CannotUndoException();
			record.getSegment().setGroup(0, oldSegment);

			repaint();
		}

		@Override
		public void redo() throws CannotRedoException {
			if(newSegment == null) throw new CannotUndoException();
			record.getSegment().setGroup(0, newSegment);

			repaint();
		}

		@Override
		public String getPresentationName() {
			return "move diarization record";
		}

	}

	private class UndoableDiarizationRecordSpeakerChange extends AbstractUndoableEdit {

		private Record record;

		private Participant oldSpeaker;

		private Participant newSpeaker;

		public UndoableDiarizationRecordSpeakerChange(Record r, Participant speaker) {
			super();
			record = r;
			newSpeaker = speaker;
			oldSpeaker = r.getSpeaker();
			r.setSpeaker(speaker);
		}

		@Override
		public void undo() throws CannotUndoException {
			if(oldSpeaker == null) throw new CannotUndoException();
			record.setSpeaker(oldSpeaker);

			repaint();
		}

		@Override
		public void redo() throws CannotRedoException {
			if(newSpeaker == null) throw new CannotUndoException();
			record.setSpeaker(newSpeaker);

			repaint();
		}

		@Override
		public String getPresentationName() {
			return "change speaker of diarization record";
		}

	}

}
