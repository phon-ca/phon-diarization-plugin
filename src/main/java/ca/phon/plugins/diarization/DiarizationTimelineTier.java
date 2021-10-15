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
import javax.swing.event.MouseInputAdapter;
import javax.swing.undo.*;

import ca.phon.app.session.editor.SegmentPlayback;
import ca.phon.app.session.editor.actions.PlaySegmentAction;
import ca.phon.app.session.editor.undo.*;
import ca.phon.app.session.editor.view.media_player.MediaPlayerEditorView;
import ca.phon.app.session.editor.view.timeline.*;
import ca.phon.session.io.*;
import ca.phon.ui.*;
import ca.phon.ui.nativedialogs.*;
import ca.phon.util.Tuple;

import ca.phon.app.log.LogUtil;
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
import ca.phon.util.icons.*;

@PhonPlugin(name="phon-diarization-plugin", author="Greg Hedlund", minPhonVersion="3.1.0")
public class DiarizationTimelineTier extends TimelineTier {

	private final static String ICON = "actions/segmentation";

	private final static String SEPARATOR_LABEL = "Diarization Results";

	private JToolBar toolbar;

	private JButton diarizeButton;

	private TimelineTitledSeparator separator;
	
	private RecordGrid recordGrid;
	private TimeUIModel.Interval currentRecordInterval = null;

	private boolean hasUnsavedChanges = false;

	public DiarizationTimelineTier(TimelineView parent) {
		super(parent);

		init();
		setupRecordGridActions();

		parent.addMenuHandler( (builder, includeAccel) -> {
			JMenu diaMenu = builder.addMenu(".", "Diarization");
			setupDiarizationMenu(new MenuBuilder(diaMenu), includeAccel);
		});

		recordGrid.addMouseListener(parent.getContextMenuListener());
	}
	
	private void init() {
		SessionFactory factory = SessionFactory.newFactory();
		recordGrid = new RecordGrid(getTimeModel(), factory.createSession());
		recordGrid.setUI(new DiarizationRecordGridUI(this));
		recordGrid.setTiers(Collections.singletonList(SystemTierType.Segment.getName()));
		recordGrid.addRecordGridMouseListener(mouseListener);
		getSelectionModel().addListSelectionListener( (e) -> {
			int rIdx = getSelectionModel().getLeadSelectionIndex();
			if(rIdx >= 0 && rIdx < getRecordGrid().getSession().getRecordCount()) {
				Record r = getRecordGrid().getSession().getRecord(rIdx);
				setupRecord(r);
			}
		});

		recordGrid.addParticipantMenuHandler(this::setupParticipantMenu);

		toolbar = getParentView().getToolbar();

		DropDownIcon icn = new DropDownIcon(IconManager.getInstance().getIcon(ICON, IconSize.SMALL), 0, SwingConstants.BOTTOM);

		final PhonUIAction diarizeAct = new PhonUIAction(this, "showDiarizationMenu");
		diarizeAct.putValue(PhonUIAction.NAME, "Diarization");
		diarizeAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Run LIUM speaker diarization tool");
		diarizeButton = new JButton(diarizeAct);
		diarizeButton.setIcon(icn);
		
		toolbar.addSeparator();
		toolbar.add(diarizeButton);
		
		setLayout(new BorderLayout());

		separator = new TimelineTitledSeparator(getTimeModel(), SEPARATOR_LABEL, icn, SwingConstants.LEFT, Color.black, 1);
		separator.addMouseListener(separatorMouseListener);
		separator.addMouseMotionListener(separatorMouseListener);
		addPropertyChangeListener("hasUnsavedChanges", (e) -> {
			separator.setTitle(SEPARATOR_LABEL + (this.hasUnsavedChanges() ? "*" : ""));
			separator.repaint();
		});

		add(separator, BorderLayout.NORTH);
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

		final String deleteRecordKey = "delete_record";
		final PhonUIAction deleteRecordAction = new PhonUIAction(this, "onDeleteRecords");
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), deleteRecordKey);
		actionMap.put(deleteRecordKey, deleteRecordAction);

		final String playSegmentKey = "play_segment";
		final PhonUIAction playSegmentAction = new PhonUIAction(this, "onPlayCurrentRecordSegment");
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), playSegmentKey);
		actionMap.put(playSegmentKey, playSegmentAction);

		final String moveRight = "move_segments_right";
		final PhonUIAction moveRightAct = new PhonUIAction(this, "onMoveSegmentsRight", 5);
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, KeyEvent.CTRL_DOWN_MASK), moveRight);
		actionMap.put(moveRight, moveRightAct);

		final String moveRightSlow = "move_segments_right_slow";
		final PhonUIAction moveRightSlowAct = new PhonUIAction(this, "onMoveSegmentsRight", 1);
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), moveRightSlow);
		actionMap.put(moveRightSlow, moveRightSlowAct);

		final String moveLeft = "move_segments_left";
		final PhonUIAction moveLeftAct = new PhonUIAction(this, "onMoveSegmentsLeft", 5);
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, KeyEvent.CTRL_DOWN_MASK), moveLeft);
		actionMap.put(moveLeft, moveLeftAct);

		final String moveLeftSlow = "move_segments_left_slow";
		final PhonUIAction moveLeftSlowAct = new PhonUIAction(this, "onMoveSegmentsLeft", 1);
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), moveLeftSlow);
		actionMap.put(moveLeftSlow, moveLeftSlowAct);

		final String move = "move_segments";
		final PhonUIAction moveAct = new PhonUIAction(this, "onMoveSegments");
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, KeyEvent.CTRL_DOWN_MASK), move);
		actionMap.put(move, moveAct);

		final String growSegments = "grow_segments";
		final PhonUIAction growSegmentsAct = new PhonUIAction(this, "onGrowSegments", 3);
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK ), growSegments);
		actionMap.put(growSegments, growSegmentsAct);

		final String growSegmentsSlow = "grow_segments_slow";
		final PhonUIAction growSegmentsSlowAct = new PhonUIAction(this, "onGrowSegments", 1);
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), growSegmentsSlow);
		actionMap.put(growSegmentsSlow, growSegmentsSlowAct);

		final String shrinkSegments = "shrink_segments";
		final PhonUIAction shrinkSegmentsAct = new PhonUIAction(this, "onShrinkSegments", 3);
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK ), shrinkSegments);
		actionMap.put(shrinkSegments, shrinkSegmentsAct);

		final String shrinkSegmentsSlow = "shrink_segments_slow";
		final PhonUIAction shrinkSegmentsSlowAct = new PhonUIAction(this, "onShrinkSegments", 1);
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK |KeyEvent.SHIFT_DOWN_MASK), shrinkSegmentsSlow);
		actionMap.put(shrinkSegmentsSlow, shrinkSegmentsSlowAct);

		final String addSelected = "add_selected_records";
		final PhonUIAction addSelectedAct = new PhonUIAction(this, "onAddSelectedRecords");
		inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), addSelected);
		actionMap.put(addSelected, addSelectedAct);

		recordGrid.setInputMap(WHEN_FOCUSED, inputMap);
		recordGrid.setActionMap(actionMap);
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

	}

	public boolean hasDiarizationResults() {
		return recordGrid.getSession().getRecordCount() > 0;
	}
	
	public void setSession(Session session) {
		setHasUnsavedChanges(false);
		recordGrid.setSession(session);

		recordGrid.clearSpeakers();
		session.getParticipants().forEach(recordGrid::addSpeaker);

		getParentView().getTierPanel().revalidate();
		recordGrid.repaint();

		if(session.getRecordCount() > 0) {
			setVisible(true);
			getParentView().getRecordTier().setVisible(false);
			getParentView().getWaveformTier().clearSelection();

			if(getParentView().getRecordTier().currentRecordInterval() != null)
				getParentView().getRecordTier().currentRecordInterval().setVisible(false);
		} else {
			setVisible(false);
			getParentView().getRecordTier().setVisible(true);

			getTimeModel().removeInterval(currentRecordInterval);
			if(getParentView().getRecordTier().currentRecordInterval() != null)
				getParentView().getRecordTier().currentRecordInterval().setVisible(true);
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

	private void fireDiarizationResultEdit(UndoableEdit edit) {
		setHasUnsavedChanges(true);
		getParentView().getEditor().getUndoSupport().postEdit(edit);
	}

	public ListSelectionModel getSelectionModel() {
		return recordGrid.getSelectionModel();
	}

	public boolean hasUnsavedChanges() {
		return this.hasUnsavedChanges;
	}

	public void setHasUnsavedChanges(boolean hasUnsavedChanges) {
		boolean oldVal = this.hasUnsavedChanges;
		this.hasUnsavedChanges = hasUnsavedChanges;
		firePropertyChange("hasUnsavedChanges", oldVal, hasUnsavedChanges);
	}

	/**
	 * Is the speaker visible?
	 *
	 * @param speaker
	 * @return
	 */
	public boolean isSpeakerVisible(Participant speaker) {
		return getRecordGrid().getSpeakers().contains(speaker);
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

		Session session = getRecordGrid().getSession();
		for (var speaker : session.getParticipants()) {
			if (isSpeakerVisible(speaker)) {
				retVal.add(speaker);
			}
		}

		return retVal;
	}

	/**
	 * Remove specified record from results
	 *
	 * @param pae
	 */
	public void onDeleteRecord(PhonActionEvent pae) {
		Integer recordIndex = (Integer) pae.getData();
		if(recordIndex == null || recordIndex < 0 || recordIndex >= getRecordGrid().getSession().getRecordCount()) return;

		UndoableDiarizationRecordDeletion edit = new UndoableDiarizationRecordDeletion(recordIndex);
		fireDiarizationResultEdit(edit);
	}

	public void onDeleteRecords(PhonActionEvent pae) {
		List<Integer> recordList = new ArrayList<>();
		for(int rIdx:getSelectionModel().getSelectedIndices()) recordList.add(rIdx);
		Collections.sort(recordList);
		Collections.reverse(recordList);

		getParentView().getEditor().getUndoSupport().beginUpdate();
		for(int recordIndex:recordList) {
			UndoableDiarizationRecordDeletion edit = new UndoableDiarizationRecordDeletion(recordIndex);
			fireDiarizationResultEdit(edit);
		}
		getParentView().getEditor().getUndoSupport().endUpdate();
	}

	public void onSelectAll(PhonActionEvent pae) {
		List<Integer> visibleRecords = new ArrayList<>();
		List<Participant> visibleSpeakers = getSpeakerList();

		for(int i = 0; i < getRecordGrid().getSession().getRecordCount(); i++) {
			if(visibleSpeakers.contains(getRecordGrid().getSession().getRecord(i).getSpeaker())) {
				visibleRecords.add(i);
			}
		}
		if(visibleRecords.size() == 0) return;

		if(getSelectionModel().getSelectedItemsCount() == visibleRecords.size()) {
			getSelectionModel().setSelectionInterval(getRecordGrid().getCurrentRecordIndex(),
					getRecordGrid().getCurrentRecordIndex());
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

		setupDiarizationMenu(builder, true);

		menu.show(diarizeButton, 0, diarizeButton.getHeight());
	}

	private void setupDiarizationMenu(MenuBuilder builder, boolean includeAccel) {
		if(isShowingDiarizationResults()) {
			final PhonUIAction addSelectedResultsAct = new PhonUIAction(this, "onAddSelectedRecords");
			addSelectedResultsAct.putValue(PhonUIAction.NAME, "Add selected record" +
					(getSelectionModel().getSelectedItemsCount() > 1 ? "s" : ""));
			addSelectedResultsAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Add selected record(s) to session");
			addSelectedResultsAct.putValue(PhonUIAction.SMALL_ICON, IconManager.getInstance().getIcon("actions/list-add", IconSize.SMALL));
			if(includeAccel)
				addSelectedResultsAct.putValue(PhonUIAction.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
			builder.addItem(".", addSelectedResultsAct).setEnabled(getSelectionModel().getSelectedItemsCount() > 0);

			final PhonUIAction delectSelectedResultsAct = new PhonUIAction(this, "onDeleteRecords");
			delectSelectedResultsAct.putValue(PhonUIAction.NAME, "Delete selected record" +
					(getSelectionModel().getSelectedItemsCount() > 1 ? "s" : ""));
			delectSelectedResultsAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Delete selected record(s) from diarization results");
			delectSelectedResultsAct.putValue(PhonUIAction.SMALL_ICON, IconManager.getInstance().getIcon("actions/list-remove", IconSize.SMALL));
			if(includeAccel)
				delectSelectedResultsAct.putValue(PhonUIAction.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
			builder.addItem(".", delectSelectedResultsAct).setEnabled(getSelectionModel().getSelectedItemsCount() > 0);

			final PhonUIAction addDiarizationResultsAct = new PhonUIAction(this, "onAddAllResults");
			addDiarizationResultsAct.putValue(PhonUIAction.NAME, "Add all records to session");
			addDiarizationResultsAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Add all diarization results to session and close results");
			addDiarizationResultsAct.putValue(PhonUIAction.SMALL_ICON, IconManager.getInstance().getIcon("actions/list-add", IconSize.SMALL));
			builder.addItem(".", addDiarizationResultsAct);

			builder.addSeparator(".", "dia_sep");

			final PhonUIAction updateResultsAct = new PhonUIAction(this, "onUpdateResults");
			updateResultsAct.putValue(PhonUIAction.NAME, "Save changes to diarization results");
			updateResultsAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Update diarization results on disk");
			updateResultsAct.putValue(PhonUIAction.SMALL_ICON, IconManager.getInstance().getIcon("actions/document-save", IconSize.SMALL));
			builder.addItem(".", updateResultsAct).setEnabled(this.hasUnsavedChanges);

			final PhonUIAction closeDiarizationResultsAct = new PhonUIAction(this, "onClearResults");
			closeDiarizationResultsAct.putValue(PhonUIAction.NAME, "Close diarization results");
			closeDiarizationResultsAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Close diarization results with modifying session");
			closeDiarizationResultsAct.putValue(PhonUIAction.SMALL_ICON, IconManager.getInstance().getIcon("actions/button_cancel", IconSize.SMALL));
			builder.addItem(".", closeDiarizationResultsAct);

			builder.addSeparator(".", "speakers");

			for(Participant p:getRecordGrid().getSpeakers()) {
				JMenu speakerMenu = builder.addMenu(".", p.toString());
				speakerMenu.setIcon(IconManager.getInstance().getIcon("apps/system-users", IconSize.SMALL));
				setupParticipantMenu(p, new MenuBuilder(speakerMenu));
			}
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

	private void setupParticipantMenu(Participant diarizationParticipant, MenuBuilder builder) {
		builder.removeAll();

		PhonUIAction addAllRecordsAct = new PhonUIAction(this, "onAddResultsForSpeaker", diarizationParticipant);
		addAllRecordsAct.putValue(PhonUIAction.NAME, "Add all records to session");
		addAllRecordsAct.putValue(PhonUIAction.SHORT_DESCRIPTION, "Add all records for " + diarizationParticipant + " to session");
		addAllRecordsAct.putValue(PhonUIAction.SMALL_ICON, IconManager.getInstance().getIcon("actions/list-add", IconSize.SMALL));
		builder.addItem(".", addAllRecordsAct);

		JMenu assignMenu = builder.addMenu(".", "Assign records to participant");
		setupParticipantAssigmentMenu(diarizationParticipant, new MenuBuilder(assignMenu));
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

	public void onMoveSegmentsRight(PhonActionEvent pae) {
		int amount = Integer.parseInt(pae.getData().toString());
		float secondsPerPixel = getTimeModel().timeAtX(getTimeModel().getTimeInsets().left+1);
		float secondsToAdd = amount * secondsPerPixel;

		getParentView().getEditor().getUndoSupport().beginUpdate();
		for(int recordIdx:getSelectionModel().getSelectedIndices()) {
			Record r = getRecordGrid().getSession().getRecord(recordIdx);

			MediaSegment recordSeg = r.getSegment().getGroup(0);
			MediaSegment seg = SessionFactory.newFactory().createMediaSegment();
			float startValue = recordSeg.getStartValue() + (1000.0f * secondsToAdd);
			float endValue = recordSeg.getEndValue() + (1000.0f * secondsToAdd);
			if(endValue/1000.0f <= getTimeModel().getEndTime()) {
				seg.setStartValue(startValue);
				seg.setEndValue(endValue);

				UndoableDiarizationRecordIntervalChange changeSeg = new UndoableDiarizationRecordIntervalChange(r, seg);
				getParentView().getEditor().getUndoSupport().postEdit(changeSeg);
			}
		}
		getParentView().getEditor().getUndoSupport().endUpdate();
		getRecordGrid().repaint(getRecordGrid().getVisibleRect());
	}

	public void onGrowSegments(PhonActionEvent pae) {
		int amount = Integer.parseInt(pae.getData().toString());
		float secondsPerPixel = getTimeModel().timeAtX(getTimeModel().getTimeInsets().left+1);
		float secondsToAdd = amount * secondsPerPixel;

		getParentView().getEditor().getUndoSupport().beginUpdate();
		for(int recordIdx:getSelectionModel().getSelectedIndices()) {
			Record r = getRecordGrid().getSession().getRecord(recordIdx);

			MediaSegment recordSeg = r.getSegment().getGroup(0);
			MediaSegment seg = SessionFactory.newFactory().createMediaSegment();
			float startValue =  Math.max(0, recordSeg.getStartValue() - (1000.0f * secondsToAdd));
			float endValue = Math.min(getTimeModel().getEndTime() * 1000.0f, recordSeg.getEndValue() + (1000.0f * secondsToAdd));

			seg.setStartValue(startValue);
			seg.setEndValue(endValue);

			UndoableDiarizationRecordIntervalChange changeSeg = new UndoableDiarizationRecordIntervalChange(r, seg);
			getParentView().getEditor().getUndoSupport().postEdit(changeSeg);
		}
		getParentView().getEditor().getUndoSupport().endUpdate();
		getRecordGrid().repaint(getRecordGrid().getVisibleRect());
	}

	public void onShrinkSegments(PhonActionEvent pae) {
		int amount = Integer.parseInt(pae.getData().toString());
		float secondsPerPixel = getTimeModel().timeAtX(getTimeModel().getTimeInsets().left+1);
		float secondsToSubtract = amount * secondsPerPixel;

		getParentView().getEditor().getUndoSupport().beginUpdate();
		for(int recordIdx:getSelectionModel().getSelectedIndices()) {
			Record r = getRecordGrid().getSession().getRecord(recordIdx);

			MediaSegment recordSeg = r.getSegment().getGroup(0);
			MediaSegment seg = SessionFactory.newFactory().createMediaSegment();
			float startValue = recordSeg.getStartValue() + (1000.0f * secondsToSubtract);
			float endValue = recordSeg.getEndValue() - (1000.0f * secondsToSubtract);

			if(startValue <= endValue) {
				seg.setStartValue(startValue);
				seg.setEndValue(endValue);

				UndoableDiarizationRecordIntervalChange changeSeg = new UndoableDiarizationRecordIntervalChange(r, seg);
				getParentView().getEditor().getUndoSupport().postEdit(changeSeg);
			}
		}
		getParentView().getEditor().getUndoSupport().endUpdate();
		getRecordGrid().repaint(getRecordGrid().getVisibleRect());
	}

	public void onMoveSegmentsLeft(PhonActionEvent pae) {
		int amount = Integer.parseInt(pae.getData().toString());
		float secondsPerPixel = getTimeModel().timeAtX(getTimeModel().getTimeInsets().left+1);
		float secondsToAdd = amount * secondsPerPixel;

		getParentView().getEditor().getUndoSupport().beginUpdate();
		for(int recordIdx:getSelectionModel().getSelectedIndices()) {
			Record r = getRecordGrid().getSession().getRecord(recordIdx);

			MediaSegment recordSeg = r.getSegment().getGroup(0);
			MediaSegment seg = SessionFactory.newFactory().createMediaSegment();
			float startValue =  recordSeg.getStartValue() - (1000.0f * secondsToAdd);
			float endValue = recordSeg.getEndValue() - (1000.0f * secondsToAdd);

			if(startValue >= 0) {
				seg.setStartValue(startValue);
				seg.setEndValue(endValue);

				UndoableDiarizationRecordIntervalChange changeSeg = new UndoableDiarizationRecordIntervalChange(r, seg);
				getParentView().getEditor().getUndoSupport().postEdit(changeSeg);
			}
		}
		getParentView().getEditor().getUndoSupport().endUpdate();
		getRecordGrid().repaint(getRecordGrid().getVisibleRect());
	}

	public void onPlayCurrentRecordSegment(PhonActionEvent pae) {
		if(!hasDiarizationResults()) return;

		Record r = getRecordGrid().getCurrentRecord();
		if(r == null) return;

		MediaSegment seg = r.getSegment().getGroup(0);

		PlaySegmentAction playSegmentAction = new PlaySegmentAction(getParentView().getEditor(),
				seg.getStartValue()/1000.0f, seg.getEndValue()/1000.0f);
		playSegmentAction.actionPerformed(pae.getActionEvent());
	}

	public void onEscape(PhonActionEvent pae) {
//		if (getRecordGrid().isSplitModeActive()) {
//			onEndSplitRecord(pae);
//		} else
		if (getParentView().getEditor().getViewModel().isShowing(MediaPlayerEditorView.VIEW_TITLE)) {
			SegmentPlayback segmentPlayback = getParentView().getEditor().getMediaModel().getSegmentPlayback();
			if(segmentPlayback != null && segmentPlayback.isPlaying()) {
				segmentPlayback.stopPlaying();
			}
		}
		if(getSelectionModel().getSelectedItemsCount() > 1) {
			// reset record selection
			getSelectionModel().setSelectionInterval(getRecordGrid().getCurrentRecordIndex(),
					getRecordGrid().getCurrentRecordIndex());
			recordGrid.repaint(recordGrid.getVisibleRect());
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

				// fix participants if session participants are found in diarization results
				Iterator<Participant> diaParticipantItr =  s.getParticipants().iterator();
				while(diaParticipantItr.hasNext()) {
					Participant diaParticipant = diaParticipantItr.next();
					Participant sameSpeaker = null;
					for(Participant sessionParticipant:getParentView().getEditor().getSession().getParticipants()) {
						if(diaParticipant.getId().equals(sessionParticipant.getId())) {
							if( (diaParticipant.getName() == null && sessionParticipant.getName() == null)
								|| (diaParticipant.getName() != null && diaParticipant.getName().equals(sessionParticipant.getName()))) {
								sameSpeaker = sessionParticipant;
								break;
							}
						}
					}
					if(sameSpeaker != null) {
						int speakerIdx = s.getParticipantIndex(diaParticipant);
						diaParticipantItr.remove();
						s.addParticipant(speakerIdx, sameSpeaker);
						for(Record r:s.getRecords()) {
							if(r.getSpeaker() == diaParticipant)
								r.setSpeaker(sameSpeaker);
						}
					}
				}

				setSession(s);
			}
		} catch (IOException e) {
			Toolkit.getDefaultToolkit().beep();
			LogUtil.severe(e);
		}
	}

	/**
	 * Add specified record index to session
	 *
	 * @param pae
	 */
	public void onAddRecord(PhonActionEvent pae) {
		Integer recordIdx = (Integer) pae.getData();
		if(recordIdx == null || recordIdx < 0 || recordIdx >= getRecordGrid().getSession().getRecordCount()) return;

		getParentView().getEditor().getUndoSupport().beginUpdate();;

		Record r = getRecordGrid().getSession().getRecord(recordIdx);
		Participant p = r.getSpeaker();

		int pIdx = getParentView().getEditor().getSession().getParticipantIndex(p);
		if(pIdx < 0) {
			AddParticipantEdit participantEdit = new AddParticipantEdit(getParentView().getEditor(), p);
			getParentView().getEditor().getUndoSupport().postEdit(participantEdit);
		}

		AddRecordEdit recordEdit = new AddRecordEdit(getParentView().getEditor(), r);
		getParentView().getEditor().getUndoSupport().postEdit(recordEdit);

		UndoableDiarizationRecordDeletion delRecordEdit = new UndoableDiarizationRecordDeletion(recordIdx);
		fireDiarizationResultEdit(delRecordEdit);

		getParentView().getEditor().getUndoSupport().endUpdate();
	}

	public void onAddSelectedRecords(PhonActionEvent pae) {
		if(!hasDiarizationResults()) return;

		boolean hasFocus = getRecordGrid().hasFocus();

		List<Integer> selectedRecords =
				Arrays.stream(getSelectionModel().getSelectedIndices()).boxed().collect(Collectors.toList());
		Collections.reverse(selectedRecords);

		getParentView().getEditor().getUndoSupport().beginUpdate();

		for(int rIdx:selectedRecords) {
			PhonActionEvent rpae = new PhonActionEvent(pae.getActionEvent(), rIdx);
			onAddRecord(rpae);
		}

		getSelectionModel().clearSelection();

		getParentView().getEditor().getUndoSupport().endUpdate();

		if(!hasDiarizationResults())
			clearDiarizationResults();

		if(hasFocus) {
			SwingUtilities.invokeLater(getRecordGrid()::requestFocus);
		}
	}

	public void onAddResultsForSpeaker(PhonActionEvent pae) {
		if(pae.getData() == null || !(pae.getData() instanceof Participant)) return;
		if(!hasDiarizationResults()) return;

		getParentView().getEditor().getUndoSupport().beginUpdate();

		Participant diaParticipant = (Participant) pae.getData();
		// add participant to session if necessary
		int idx = getParentView().getEditor().getSession().getParticipantIndex(diaParticipant);
		if(idx < 0) {
			final AddParticipantEdit addSpeakerEdit = new AddParticipantEdit(getParentView().getEditor(), diaParticipant);
			getParentView().getEditor().getUndoSupport().postEdit(addSpeakerEdit);
		}

		List<Integer> speakerRecords = new ArrayList<>();
		int editIdx = 0;
		for(int rIdx = 0; rIdx < getRecordGrid().getSession().getRecordCount(); rIdx++) {
			Record r = getRecordGrid().getSession().getRecord(rIdx);
			if(r.getSpeaker() == diaParticipant) {
				final AddRecordEdit addEdit = new AddRecordEdit(getParentView().getEditor(), r);
				addEdit.setFireEvent(editIdx++ == 0);
				getParentView().getEditor().getUndoSupport().postEdit(addEdit);

				speakerRecords.add(rIdx);
			}
		}
		Collections.reverse(speakerRecords);

		for(int rIdx:speakerRecords) {
			UndoableDiarizationRecordDeletion edit = new UndoableDiarizationRecordDeletion(rIdx);
			fireDiarizationResultEdit(edit);
		}
		UndoableDiarizationParticipantDeletion delSpeaker = new UndoableDiarizationParticipantDeletion(diaParticipant);
		fireDiarizationResultEdit(delSpeaker);

		getParentView().getEditor().getUndoSupport().endUpdate();

		if(!hasDiarizationResults())
			clearDiarizationResults();
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

	public void onUpdateResults(PhonActionEvent pae) {
		// update diarization results on disk
		DiarizationResultsManager manager = new DiarizationResultsManager(getParentView().getEditor().getProject(),
				getParentView().getEditor().getSession());
		try {
			manager.saveDiarizationResults(getRecordGrid().getSession());
		} catch (IOException e) {
			Toolkit.getDefaultToolkit().beep();
			LogUtil.severe(e);
		}

		this.hasUnsavedChanges = false;
		firePropertyChange("hasUnsavedChanges", true, false);
	}
	
	public void onClearResults(PhonActionEvent pae) {
		if(this.hasUnsavedChanges) {
			final MessageDialogProperties props = new MessageDialogProperties();
			props.setParentWindow(CommonModuleFrame.getCurrentFrame());
			props.setRunAsync(false);
			props.setOptions(MessageDialogProperties.yesNoCancelOptions);
			props.setTitle("Save changes");
			props.setHeader("Save changes");
			props.setMessage("Save changes to diarization results on disk?");
			props.setDefaultOption("Yes");

			int selection = NativeDialogs.showMessageDialog(props);
			if(selection == 0) {
				onUpdateResults(pae);
			} else if(selection == 2) {
				return;
			}
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
		Session s = getRecordGrid().getSession();
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
				setupDiarizationMenu(builder, includeAccel);
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
				fireDiarizationResultEdit(edit);
				
				recordGrid.repaint(recordGrid.getVisibleRect());
			}
		}

	}

	/* Dragging */
	private class DragData {
		/*
		 * Participant being dragged or Participant.ALL if
		 * multiple participants are being dragged
		 */
		Participant participant;

		int draggedRecord = -1;
		float mouseDragOffset = -1.0f;

		//Map<Integer, TierEdit<MediaSegment>> tierEdits = new LinkedHashMap<>();
		Map<Integer, MediaSegment> editSegments = new LinkedHashMap<>();
		Map<Integer, MediaSegment> originalSegments = new LinkedHashMap<>();

		boolean isFirstChange = true;
	}
	private final DiarizationTimelineTier.DragData dragData = new DiarizationTimelineTier.DragData();

	private void beginRecordDrag(int recordIndex) {
		Toolkit.getDefaultToolkit().addAWTEventListener(cancelDragListener, AWTEvent.KEY_EVENT_MASK);

		dragData.draggedRecord = recordIndex;
		Set<Participant> participants = new HashSet<>();

		dragData.originalSegments.clear();
		dragData.editSegments.clear();
		for(int selectedRecord:getSelectionModel().getSelectedIndices()) {
			Record r = getRecordGrid().getSession().getRecord(selectedRecord);
			participants.add(r.getSpeaker());

			MediaSegment recordSeg = r.getSegment().getGroup(0);
			MediaSegment origSeg = SessionFactory.newFactory().createMediaSegment();
			origSeg.setStartValue(recordSeg.getStartValue());
			origSeg.setEndValue(recordSeg.getEndValue());
			dragData.originalSegments.put(selectedRecord, origSeg);

			MediaSegment editSegment = SessionFactory.newFactory().createMediaSegment();
			editSegment.setStartValue(origSeg.getStartValue());
			editSegment.setEndValue(origSeg.getEndValue());
			dragData.editSegments.put(selectedRecord, editSegment);
		}
		dragData.participant = (participants.size() == 1 ? participants.iterator().next() : Participant.ALL);
		currentRecordInterval.setValueAdjusting(true);

		dragData.isFirstChange = true;
	}

	private void endRecordDrag() {
		Toolkit.getDefaultToolkit().removeAWTEventListener(cancelDragListener);
		if (currentRecordInterval != null)
			currentRecordInterval.setValueAdjusting(false);
		dragData.draggedRecord = -1;
	}

	private void cancelRecordDrag() {
		for(int recordIndex:getSelectionModel().getSelectedIndices()) {
			Record r = getRecordGrid().getSession().getRecord(recordIndex);
			if(dragData.participant != Participant.ALL)
				r.setSpeaker(dragData.participant);
			MediaSegment origSegment = dragData.originalSegments.get(recordIndex);
			if (currentRecordInterval != null && getRecordGrid().getCurrentRecordIndex() == recordIndex) {
				currentRecordInterval.getStartMarker().setTime(origSegment.getStartValue()/1000.0f);
				currentRecordInterval.getEndMarker().setTime(origSegment.getEndValue()/1000.0f);
			} else {
				MediaSegment recordSeg = r.getSegment().getGroup(0);
				recordSeg.setStartValue(origSegment.getStartValue());
				recordSeg.setEndValue(origSegment.getEndValue());
			}
		}
		dragData.mouseDragOffset = -1.0f;
		endRecordDrag();
	}

	private final AWTEventListener cancelDragListener = new AWTEventListener() {

		@Override
		public void eventDispatched(AWTEvent event) {
			if(event instanceof KeyEvent) {
				KeyEvent ke = (KeyEvent)event;
				if(ke.getID() == KeyEvent.KEY_PRESSED &&
						ke.getKeyChar() == KeyEvent.VK_ESCAPE) {
					cancelRecordDrag();
					((KeyEvent) event).consume();
				}
			}
		}
	};

	private final MouseInputAdapter separatorMouseListener = new MouseInputAdapter() {
		@Override
		public void mousePressed(MouseEvent e) {
			super.mousePressed(e);
			if(separator.getLabelRect().contains(e.getPoint())) {
				JPopupMenu diaMenu = new JPopupMenu();
				setupDiarizationMenu(new MenuBuilder(diaMenu), true);
				diaMenu.show(separator, separator.getLabelRect().x, separator.getHeight());
			}
		}

		@Override
		public void mouseEntered(MouseEvent e) {
			super.mouseEntered(e);
			if(separator.getLabelRect().contains(e.getPoint())) {
				separator.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			}
		}

		@Override
		public void mouseExited(MouseEvent e) {
			super.mouseExited(e);
			separator.setCursor(Cursor.getDefaultCursor());
		}

		@Override
		public void mouseMoved(MouseEvent e) {
			super.mouseMoved(e);
			if(separator.getLabelRect().contains(e.getPoint())) {
				separator.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			}
		}

	};

	private final RecordMouseListener mouseListener = new RecordMouseListener();
	
	private class RecordMouseListener extends RecordGridMouseAdapter {

		@Override
		public void recordClicked(int recordIndex, MouseEvent me) {
		}

		@Override
		public void recordPressed(int recordIndex, MouseEvent me) {
			Record r = recordGrid.getSession().getRecord(recordIndex);
			MediaSegment seg = r.getSegment().getGroup(0);
			
			setupRecord(r);
			
			dragData.mouseDragOffset = getTimeModel().timeAtX(me.getX()) - seg.getStartValue() / 1000.0f;
		}

		@Override
		public void recordReleased(int recordIndex, MouseEvent me) {
			endRecordDrag();
		}

		@Override
		public void recordDragged(int recordIndex, MouseEvent me) {
			if (!getSelectionModel().isSelectedIndex(recordIndex)) {
				if((me.getModifiersEx() & Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) == Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) {
					getSelectionModel().addSelectionInterval(recordIndex, recordIndex);
				} else {
					getSelectionModel().setSelectionInterval(recordIndex, recordIndex);
				}
				return;
			} else {
				// shouldn't happen
				if (currentRecordInterval == null)
					return;
			}

			if (dragData.draggedRecord != recordIndex) {
				// don't adjust an already changing interval
				if (currentRecordInterval.isValueAdjusting())
					return;

				if(dragData.mouseDragOffset < 0) return;

				beginRecordDrag(recordIndex);
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
			if (dragData.participant != Participant.ALL
					&& mouseOverSpeaker != null
					&& mouseOverSpeaker != getRecordGrid().getCurrentRecord().getSpeaker()) {
				for(int rIdx:getSelectionModel().getSelectedIndices()) {
					UndoableDiarizationRecordSpeakerChange speakerChange = new UndoableDiarizationRecordSpeakerChange(getRecordGrid().getSession().getRecord(rIdx), mouseOverSpeaker);
					fireDiarizationResultEdit(speakerChange);
				}
			}

			Record dragRecord = getRecordGrid().getSession().getRecord(dragData.draggedRecord);
			MediaSegment dragSeg = dragRecord.getSegment().getGroup(0);

			float startTime = dragSeg.getStartValue() / 1000.0f;
			float oldOffsetTime = startTime + dragData.mouseDragOffset;
			float newOffsetTime = recordGrid.timeAtX(me.getX());
			int direction = (oldOffsetTime < newOffsetTime ? 1 : -1);
			float delta = (direction < 0 ? oldOffsetTime - newOffsetTime : newOffsetTime - oldOffsetTime);

			for(int rIdx:getSelectionModel().getSelectedIndices()) {
				Record selectedRecord = getRecordGrid().getSession().getRecord(rIdx);
				MediaSegment seg = selectedRecord.getSegment().getGroup(0);

				float st = (rIdx == getRecordGrid().getCurrentRecordIndex() ?
						currentRecordInterval.getStartMarker().getTime() : seg.getStartValue() / 1000.0f);
				float et = (rIdx == getRecordGrid().getCurrentRecordIndex() ?
						currentRecordInterval.getEndMarker().getTime() : seg.getEndValue() / 1000.0f);
				float intervalDuration = et - st;

				float newStartTime = 0.0f;
				float newEndTime = 0.0f;

				if (direction < 0) {
					newStartTime = Math.max(st - delta, getTimeModel().getStartTime());
					newEndTime = newStartTime + intervalDuration;
				} else {
					newEndTime = Math.min(et + delta, getTimeModel().getEndTime());
					newStartTime = newEndTime - intervalDuration;
				}
				if(rIdx == getRecordGrid().getCurrentRecordIndex()) {
					currentRecordInterval.getStartMarker().setTime(newStartTime);
					currentRecordInterval.getEndMarker().setTime(newEndTime);
				} else {
					MediaSegment editSeg = dragData.editSegments.get(rIdx);
					editSeg.setStartValue(newStartTime * 1000.0f);
					editSeg.setEndValue(newEndTime * 1000.0f);

					if(dragData.isFirstChange) {
						UndoableDiarizationRecordIntervalChange edit = new UndoableDiarizationRecordIntervalChange(selectedRecord, editSeg);
						fireDiarizationResultEdit(edit);
					}
				}
			}
			dragData.isFirstChange = false;
//			for(int selectedRecordIdx:recordGrid.getSelectionModel().getSelectedIndices()) {
//				if(recordGrid.getCurrentRecordIndex() == selectedRecordIdx) {
//					float startTime = currentRecordInterval.getStartMarker().getTime();
//					float endTime = currentRecordInterval.getEndMarker().getTime();
//					float intervalDuration = endTime - startTime;
//
//					float oldOffsetTime = currentRecordInterval.getStartMarker().getTime() + mouseDragOffset;
//					float newOffsetTime = recordGrid.timeAtX(me.getX());
//					int direction = (oldOffsetTime < newOffsetTime ? 1 : -1);
//
//					float newStartTime = 0.0f;
//					float newEndTime = 0.0f;
//
//					if (direction < 0) {
//						newStartTime = Math.max(newOffsetTime - mouseDragOffset, getTimeModel().getStartTime());
//						newEndTime = newStartTime + intervalDuration;
//					} else {
//						newEndTime = Math.min(newOffsetTime + (intervalDuration - mouseDragOffset),
//								getTimeModel().getEndTime());
//						newStartTime = newEndTime - intervalDuration;
//					}
//
//					currentRecordInterval.getStartMarker().setTime(newStartTime);
//					currentRecordInterval.getEndMarker().setTime(newEndTime);
//				} else {
//					Record r = recordGrid.getSession().getRecord(selectedRecordIdx);
//					MediaSegment seg = r.getSegment().getGroup(0);
//					float segLength = (seg.getEndValue() - seg.getStartValue()) / 1000.0f;
//
//					float oldOffsetTime = seg.getStartValue() / 1000.0f + mouseDragOffset;
//					float newOffsetTime = recordGrid.timeAtX(me.getX());
//					float delta = Math.abs(newOffsetTime - oldOffsetTime);
//					int direction = (oldOffsetTime < newOffsetTime ? 1 : -1);
//
//					float newStartTime = 0.0f;
//					float newEndTime = 0.0f;
//
//					if(direction < 0) {
//						newStartTime = Math.max(seg.getStartValue() / 1000.0f - delta, getTimeModel().getStartTime());
//						newEndTime = newStartTime + segLength;
//					} else {
//						newEndTime = Math.min(seg.getEndValue() / 1000.0f + delta, getTimeModel().getEndTime());
//						newStartTime = newEndTime - segLength;
//					}
//
//					MediaSegment newSeg = SessionFactory.newFactory().createMediaSegment();
//					newSeg.setStartValue(newStartTime * 1000.0f);
//					newSeg.setEndValue(newEndTime * 1000.0f);
//					UndoableDiarizationRecordIntervalChange edit = new UndoableDiarizationRecordIntervalChange(r, newSeg);
//					fireDiarizationResultEdit(edit);
//				}
//			}
		}

	};

	@Override
	public void onClose() {
		onClearResults(new PhonActionEvent(new ActionEvent(this, -1, "close")));
	}

	private class UndoableDiarizationParticipantDeletion extends AbstractUndoableEdit {

		private int participantIdx;

		private Participant participant;

		public UndoableDiarizationParticipantDeletion(Participant participant) {
			super();
			this.participantIdx = getRecordGrid().getSession().getParticipantIndex(participant);
			this.participant = participant;
			getRecordGrid().getSession().removeParticipant(participant);
			updateSpeakerList();
		}

		@Override
		public void undo() throws CannotUndoException {
			if(participantIdx < 0 || participant == null) throw new CannotUndoException();

			getRecordGrid().getSession().addParticipant(participantIdx, participant);
			updateSpeakerList();
		}

		private void updateSpeakerList() {
			var speakerList =
					StreamSupport.stream(getRecordGrid().getSession().getParticipants().spliterator(), false).collect(Collectors.toList());
			Session s = getRecordGrid().getSession();
			getRecordGrid().setSpeakers(speakerList);
		}

		@Override
		public void redo() throws CannotRedoException {
			if(participant == null) throw new CannotRedoException();

			getRecordGrid().getSession().removeParticipant(participant);

			updateSpeakerList();
		}

		@Override
		public String getPresentationName() {
			return "delete diarization participant";
		}
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

			update();
		}

		@Override
		public void redo() throws CannotRedoException {
			if(newSegment == null) throw new CannotUndoException();
			record.getSegment().setGroup(0, newSegment);

			update();
		}

		private void update() {
			// update if recordGrid.currentRecord = record
			if(this.record == recordGrid.getCurrentRecord()) {
				// update interval
				MediaSegment seg = this.record.getSegment().getGroup(0);
				currentRecordInterval.getStartMarker().setTime(seg.getStartValue()/1000.0f);
				currentRecordInterval.getEndMarker().setTime(seg.getEndValue()/1000.0f);
			}
		}

		@Override
		public String getPresentationName() {
			return "move diarization record";
		}

	}

	private class UndoableDiarizationRecordDeletion extends AbstractUndoableEdit {

		private int recordIndex;

		private Record record;

		public UndoableDiarizationRecordDeletion(int recordIndex) {
			record = getRecordGrid().getSession().getRecord(recordIndex);
			this.recordIndex = recordIndex;
			getRecordGrid().getSession().removeRecord(recordIndex);

			repaint();
		}

		@Override
		public void undo() throws CannotUndoException {
			if(record == null || recordIndex < 0 || recordIndex > getRecordGrid().getSession().getRecordCount())
				throw new CannotUndoException();
			getRecordGrid().getSession().addRecord(recordIndex, record);

			repaint();
		}

		@Override
		public void redo() throws CannotRedoException {
			if(recordIndex < 0 || recordIndex >= getRecordGrid().getSession().getRecordCount()) {
				throw new CannotUndoException();
			}
			getRecordGrid().getSession().removeRecord(recordIndex);

			repaint();
		}

		@Override
		public String getPresentationName() {
			return "delete diarization record";
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

			if(r == getRecordGrid().getCurrentRecord())
				repaint();
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
