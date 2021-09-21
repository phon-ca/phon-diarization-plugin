package ca.phon.lium.spkdiarization;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.swing.*;

import ca.phon.app.session.editor.actions.PlaySegmentAction;
import ca.phon.app.session.editor.view.timeline.actions.SplitRecordAction;
import org.jdesktop.swingx.JXBusyLabel;

import ca.phon.app.log.LogUtil;
import ca.phon.app.session.editor.EditorEvent;
import ca.phon.app.session.editor.EditorEventType;
import ca.phon.app.session.editor.SessionMediaModel;
import ca.phon.app.session.editor.undo.AddParticipantEdit;
import ca.phon.app.session.editor.undo.AddRecordEdit;
import ca.phon.app.session.editor.undo.ChangeSpeakerEdit;
import ca.phon.app.session.editor.undo.TierEdit;
import ca.phon.app.session.editor.view.timeline.RecordGrid;
import ca.phon.app.session.editor.view.timeline.RecordGridMouseAdapter;
import ca.phon.app.session.editor.view.timeline.TimelineRecordTier;
import ca.phon.app.session.editor.view.timeline.TimelineTier;
import ca.phon.app.session.editor.view.timeline.TimelineView;
import ca.phon.app.session.editor.view.timeline.TimelineViewColors;
import ca.phon.app.session.editor.view.timeline.RecordGrid.GhostMarker;
import ca.phon.lium.spkdiarization.LIUMDiarizationTool.DiarizationResult;
import ca.phon.media.TimeUIModel;
import ca.phon.media.TimeUIModel.Interval;
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
import ca.phon.worker.PhonWorker;

@PhonPlugin(name="phon-diarization-plugin", author="Greg Hedlund", minPhonVersion="3.1.0")
public class DiarizationTimelineTier extends TimelineTier {

	private JToolBar toolbar;
	
	private JXBusyLabel busyLabel;
	private JButton diarizeButton;
	
	private JButton addAllButton;
	private JButton clearResultsButton;
	
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
		
		setLayout(new BorderLayout());
		add(recordGrid, BorderLayout.CENTER);
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

	public RecordGrid getRecordGrid() {
		return this.recordGrid;
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
	
	private class RecordIntervalListener implements PropertyChangeListener {

//		private boolean isFirstChange = true;

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
				
//				if((boolean)evt.getNewValue()) {
//					isFirstChange = true;
//					getParentView().getEditor().getUndoSupport().beginUpdate();
//				} else {
//					getParentView().getEditor().getUndoSupport().endUpdate();
//					getParentView().getEditor().getEventManager().queueEvent(new EditorEvent(EditorEventType.TIER_CHANGED_EVT, TimelineRecordTier.class, SystemTierType.Segment.getName()));
//				}
			} else if(evt.getPropertyName().endsWith("time")) {
				MediaSegment newSegment = factory.createMediaSegment();
				newSegment.setStartValue(segment.getStartValue());
				newSegment.setEndValue(segment.getEndValue());
				
				if(evt.getPropertyName().startsWith("startMarker")) {
					newSegment.setStartValue((float)evt.getNewValue() * 1000.0f);
				} else if(evt.getPropertyName().startsWith("endMarker")) {
					newSegment.setEndValue((float)evt.getNewValue() * 1000.0f);
				}
				
				recordGrid.getCurrentRecord().getSegment().setGroup(0, newSegment);
				
//				TierEdit<MediaSegment> segmentEdit = new TierEdit<MediaSegment>(getParentView().getEditor(), r.getSegment(), 0, newSegment);
//				getParentView().getEditor().getUndoSupport().postEdit(segmentEdit);
//				segmentEdit.setFireHardChangeOnUndo(isFirstChange);
//				isFirstChange = false;
				
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
					recordGrid.getCurrentRecord().setSpeaker(mouseOverSpeaker);
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

	@Override
	public void onClose() {
		onClearResults(new PhonActionEvent(new ActionEvent(this, -1, "close")));
	}
	
}
