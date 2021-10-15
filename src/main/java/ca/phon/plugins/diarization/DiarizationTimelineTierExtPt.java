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

import ca.phon.app.session.editor.view.timeline.TimelineTier;
import ca.phon.app.session.editor.view.timeline.TimelineView;
import ca.phon.plugin.IPluginExtensionFactory;
import ca.phon.plugin.IPluginExtensionPoint;
import ca.phon.plugin.PhonPlugin;

@PhonPlugin(name="phon-diarization-plugin", author="Greg Hedlund", minPhonVersion="3.1.0")
public class DiarizationTimelineTierExtPt implements IPluginExtensionPoint<TimelineTier> {

	@Override
	public Class<?> getExtensionType() {
		return TimelineTier.class;
	}

	@Override
	public IPluginExtensionFactory<TimelineTier> getFactory() {
		return factory;
	}
	
	private IPluginExtensionFactory<TimelineTier> factory = new IPluginExtensionFactory<TimelineTier>() {
		
		@Override
		public TimelineTier createObject(Object... args) {
			if(args.length != 1
					|| !(args[0] instanceof TimelineView))
				throw new IllegalArgumentException("TimlineView not given");
			TimelineView parentView = (TimelineView)args[0];
			return new DiarizationTimelineTier(parentView);
		}
		
	};

}
