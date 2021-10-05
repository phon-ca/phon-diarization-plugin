package ca.phon.lium.spkdiarization;

import ca.phon.ui.decorations.DialogHeader;
import ca.phon.ui.wizard.BreadcrumbWizardFrame;
import ca.phon.ui.wizard.WizardStep;

import javax.swing.*;
import java.awt.*;

public class DiarizationWizard extends BreadcrumbWizardFrame {

    private WizardStep diarizationSelectionStep;

    private JRadioButton liumDiarizationButton;

    private JRadioButton googleSpeechToTextButton;

    public final static String TITLE = "Diarization";

    public DiarizationWizard() {
        super(TITLE);

    }

    private void init() {
        diarizationSelectionStep = createDiarizationSelectionStep();
        diarizationSelectionStep.setPrevStep(-1);
        diarizationSelectionStep.setNextStep(1);
        addWizardStep(diarizationSelectionStep);
    }

    private WizardStep createDiarizationSelectionStep() {
        final String title = "Diarization Selection";
        final String desc = "";

        WizardStep retVal = new WizardStep();
        retVal.setTitle(title);

        DialogHeader header = new DialogHeader(title, desc);
        retVal.setLayout(new BorderLayout());
        retVal.add(header, BorderLayout.NORTH);

    }


}
