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

import ca.phon.app.session.editor.view.timeline.DefaultRecordGridUI;
import ca.phon.session.*;
import ca.phon.session.Record;
import ca.phon.ui.action.PhonUIAction;
import ca.phon.util.icons.*;
import com.github.davidmoten.rtree.geometry.Geometries;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DiarizationRecordGridUI extends DefaultRecordGridUI {

	DiarizationTimelineTier tier;

	public DiarizationRecordGridUI(DiarizationTimelineTier tier) {
		super();
		this.tier = tier;
	}

	@Override
	protected void paintSegmentLabelAndActions(Graphics2D g2, int recordIndex, Record r, Rectangle2D segmentRect) {
		Icon recordIcon = null;
		Color recordLblColor = (getRecordGrid().getSelectionModel().isSelectedIndex(recordIndex) ? Color.black : Color.lightGray);

		// don't paint overlap warning if record is at 0 and has zero-length segment
		boolean checkForOverlap = true;
		final MediaSegment mediaSeg = r.getSegment().getGroup(0);
		if(mediaSeg.getStartValue() == 0.0f
				&& mediaSeg.getEndValue() - mediaSeg.getStartValue() == 0.0f) {
			checkForOverlap = false;
		}

		String warnings = null;
		if(checkForOverlap) {
			// check to see if record overlaps other records for speaker
			var overlapEntries = recordTree.search(Geometries.rectangle(segmentRect.getX(), segmentRect.getY(),
					segmentRect.getMaxX(), segmentRect.getMaxY()));
			java.util.List<Integer> overlappingRecordsList = new ArrayList<Integer>();
			List<Integer> potentialOverlaps = new ArrayList<Integer>();
			overlapEntries.map( entry -> entry.value() ).filter( v -> v != recordIndex).forEach(potentialOverlaps::add);

			for(int rIdx:potentialOverlaps) {

				Record r2 = getRecordGrid().getSession().getRecord(rIdx);
				MediaSegment seg2 = r2.getSegment().getGroup(0);

				boolean isZeroAtZero = (seg2.getStartValue() == 0.0f) && (seg2.getEndValue() - seg2.getStartValue() == 0.0f);
				boolean isContiguous = (mediaSeg.getStartValue() == seg2.getEndValue() || seg2.getStartValue() == mediaSeg.getEndValue());

				if(isZeroAtZero || isContiguous) continue;

				overlappingRecordsList.add(rIdx);
			}

			if(overlappingRecordsList.size() > 0) {
				warnings = "Overlapping segments ("
						+ overlappingRecordsList.stream().map(rIdx-> String.format("#%d", rIdx+1)).collect(Collectors.joining(","))
						+ ")";
				recordIcon = IconManager.getInstance().getIcon("emblems/flag-red", IconSize.XSMALL);
			}
		}

		// check to see if record is outside of media bounds
		float recordEndTime = getRecordGrid().timeAtX(segmentRect.getMaxX());
		if(getRecordGrid().getTimeModel().getMediaEndTime() > 0.0f && recordEndTime > getRecordGrid().getTimeModel().getMediaEndTime()) {
			warnings = (warnings != null ? warnings + "\n" : "" ) + "Segment out of bounds";
			recordIcon = IconManager.getInstance().getIcon("emblems/flag-red", IconSize.XSMALL);
		}

		if(recordIndex >= 0) {
			Rectangle2D lblRect = paintRecordNumberLabel(g2, recordIndex, recordIcon, recordLblColor, segmentRect);
			recordTree = recordTree.add(recordIndex, Geometries.rectangle((float) lblRect.getX(), (float) lblRect.getY(),
					(float) lblRect.getMaxX(), (float) (lblRect.getMaxY() - 0.1f)));

			if (warnings != null) {
				// add warning to UI
				messageTree = messageTree.add(warnings, Geometries.rectangle(lblRect.getX(), lblRect.getY(),
						lblRect.getMaxX(), lblRect.getMaxY()));
			}

			if (getRecordGrid().getSelectionModel().isSelectedIndex(recordIndex)) {
				// paint add record and delete record actions
				ImageIcon acceptIcon = IconManager.getInstance().getIcon("actions/list-add", IconSize.XSMALL);
				Rectangle2D acceptRect = new Rectangle2D.Double(lblRect.getMaxX() + 2, lblRect.getY(),
						acceptIcon.getIconWidth(), acceptIcon.getIconHeight());
				g2.drawImage(acceptIcon.getImage(), (int) acceptRect.getX(), (int) acceptRect.getY(), getRecordGrid());

				final PhonUIAction acceptAct = new PhonUIAction(tier, "onAddSelectedRecords", recordIndex);
				var acceptRect2 = Geometries.rectangle(acceptRect.getX(), acceptRect.getY(), acceptRect.getMaxX(), acceptRect.getMaxY());
				actionsTree = actionsTree.add(acceptAct, acceptRect2);
				messageTree = messageTree.add("Add record to session", acceptRect2);

				ImageIcon cancelIcon = IconManager.getInstance().getIcon("actions/list-remove", IconSize.XSMALL);
				int cancelRectX = (int)(acceptRect.getMaxX() + 2);
				Rectangle2D cancelRect = new Rectangle2D.Double(cancelRectX, acceptRect.getY(),
						cancelIcon.getIconWidth(), cancelIcon.getIconHeight());
				g2.drawImage(cancelIcon.getImage(), (int) cancelRect.getX(), (int) cancelRect.getY(), getRecordGrid());

				final PhonUIAction delAct = new PhonUIAction(tier, "onDeleteRecords", recordIndex);
				var cancelRect2 = Geometries.rectangle(cancelRect.getX(), cancelRect.getY(), cancelRect.getMaxX(), cancelRect.getMaxY());
				actionsTree = actionsTree.add(delAct, cancelRect2);
				messageTree = messageTree.add("Delete record", cancelRect2);
			}
		}
	}

}
