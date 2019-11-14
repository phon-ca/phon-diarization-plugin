package ca.phon.lium.spkdiarization;

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
