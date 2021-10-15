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

import ca.phon.app.log.*;
import ca.phon.app.session.editor.SessionMediaModel;
import ca.phon.formatter.FormatterFactory;
import ca.phon.project.Project;
import ca.phon.session.Session;
import ca.phon.ui.decorations.*;
import ca.phon.ui.fonts.FontPreferences;
import ca.phon.ui.jbreadcrumb.BreadcrumbButton;
import ca.phon.ui.nativedialogs.*;
import ca.phon.ui.text.*;
import ca.phon.ui.toast.*;
import ca.phon.ui.wizard.BreadcrumbWizardFrame;
import ca.phon.ui.wizard.WizardStep;
import org.jdesktop.swingx.*;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class DiarizationWizard extends BreadcrumbWizardFrame {

    private final DiarizationTimelineTier diarizationTier;

    private WizardStep diarizationSelectionStep;

    private JRadioButton liumDiarizationButton;
    private JCheckBox doCEClusteringBox;
    private FormatterTextField<Integer> maxSpeakersField;

    private JRadioButton googleSpeechToTextButton;

    private WizardStep reportStep;

    private JXBusyLabel busyLabel;

    private BufferPanel bufferPanel;

    private AtomicReference<DiarizationWorker> workerRef = new AtomicReference<>();

    private BreadcrumbButton btnStop;

    public final static String TITLE = "Diarization";

    public DiarizationWizard(DiarizationTimelineTier diarizationTier) {
        super(TITLE);
        this.diarizationTier = diarizationTier;

        init();
    }

    private void init() {
        diarizationSelectionStep = createDiarizationSelectionStep();
        diarizationSelectionStep.setPrevStep(-1);
        diarizationSelectionStep.setNextStep(1);
        addWizardStep(diarizationSelectionStep);

        reportStep = createReportStep();
        reportStep.setNextStep(-1);
        reportStep.setPrevStep(0);
        addWizardStep(reportStep);

        btnStop = new BreadcrumbButton();
        btnStop.setFont(FontPreferences.getTitleFont().deriveFont(Font.BOLD));
        btnStop.setText("Stop");
        btnStop.setBackground(Color.red);
        btnStop.setForeground(Color.white);
        btnStop.addActionListener( (e) -> close() );
    }

    @Override
    protected void cancel() {
        if(workerRef.get() != null) {
            final MessageDialogProperties props = new MessageDialogProperties();
            props.setParentWindow(this);
            props.setTitle("Close");
            props.setHeader("Stop execution");
            props.setMessage("Stop execution and close?");
            props.setOptions(new String[] { "Cancel", "Stop", "Stop and Close"});
            props.setDefaultOption("Cancel");
            props.setRunAsync(true);
            props.setListener( (e) -> {
                final int retVal = e.getDialogResult();

                if(retVal == 0) return;
                workerRef.get().cancel();

                if(retVal == 2) {
                    SwingUtilities.invokeLater( () -> super.cancel() );
                }
            });

            NativeDialogs.showMessageDialog(props);
        } else {
            super.cancel();
        }
    }

    @Override
    public void close() {
        if(workerRef.get() != null) {
            cancel();
        } else {
            super.close();
        }
    }

    @Override
    protected void updateBreadcrumbButtons() {
        JButton endBtn = nextButton;

        // remove all buttons from breadcrumb
        breadCrumbViewer.remove(nextButton);
        if(btnStop != null)
            breadCrumbViewer.remove(btnStop);

        if(breadCrumbViewer.getBreadcrumb().getCurrentState() == reportStep) {
            if(workerRef.get() != null) {
                btnStop.setText("Stop");
                btnStop.setBackground(Color.red);
                btnStop.setForeground(Color.white);

                breadCrumbViewer.add(btnStop);
                setBounds(btnStop);
                endBtn = btnStop;
            } else {
                btnStop.setText("Close window");
                btnStop.setBackground(btnNext.getBackground());
                btnStop.setForeground(Color.black);

                breadCrumbViewer.add(btnStop);
                setBounds(btnStop);
                endBtn = btnStop;
            }
        } else {
            breadCrumbViewer.add(nextButton);
            setBounds(nextButton);
            endBtn = nextButton;
        }

        if(numberOfSteps() == 0 || getCurrentStepIndex() < 0
                || getCurrentStep().getNextStep() < 0) {
            nextButton.setVisible(false);
        } else {
            nextButton.setVisible(true);
        }

        if(getCurrentStep() != reportStep)
            getRootPane().setDefaultButton(endBtn);
        else
            getRootPane().setDefaultButton(null);

        breadCrumbViewer.revalidate();
        breadCrumbViewer.scrollRectToVisible(endBtn.getBounds());
    }

    private WizardStep newStepWithHeader(String title, String desc) {
        WizardStep retVal = new WizardStep();
        retVal.setTitle(title);

        DialogHeader header = new DialogHeader(title, desc);
        retVal.setLayout(new BorderLayout());
        retVal.add(header, BorderLayout.NORTH);

        retVal.setTitle(title);
        return retVal;
    }

    private WizardStep createDiarizationSelectionStep() {
        final String title = "Diarization Method";
        final String desc = "Select diarization method";

        WizardStep retVal = newStepWithHeader(title, desc);

//        TitledPanel btnPanel = new TitledPanel(desc);
//        btnPanel.getContentContainer().setLayout(new VerticalLayout());
        ButtonGroup btnGrp = new ButtonGroup();

        JPanel liumOptionsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        liumDiarizationButton = new JRadioButton("LIUM Diarization (embedded)");
        liumDiarizationButton.setToolTipText("Diarize session audio using the open source LIUM diarization tool");
        liumDiarizationButton.setSelected(true);
        btnGrp.add(liumDiarizationButton);

        doCEClusteringBox = new JCheckBox("Do CE clustering");
        doCEClusteringBox.setSelected(true);
        doCEClusteringBox.setToolTipText("Perform a final clustering step using the CE method");

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        liumOptionsPanel.add(new JLabel("Clustering options:"), gbc);
        ++gbc.gridy;
        gbc.insets = new Insets(0, 20, 0, 0);
        liumOptionsPanel.add(doCEClusteringBox, gbc);

        maxSpeakersField = new FormatterTextField<Integer>(FormatterFactory.createFormatter(Integer.class));
        maxSpeakersField.setPrompt("Enter max speakers, 0 = auto");
        maxSpeakersField.setValue(0);

        ++gbc.gridy;
        gbc.insets = new Insets(0, 0, 0, 0);
        liumOptionsPanel.add(new JLabel("Max speakers:"), gbc);
        ++gbc.gridy;
        gbc.insets = new Insets(0, 20, 0, 0);
        liumOptionsPanel.add(maxSpeakersField, gbc);

        liumDiarizationButton.addPropertyChangeListener("selected", (e) -> {
            doCEClusteringBox.setEnabled(liumDiarizationButton.isSelected());
            maxSpeakersField.setEnabled(liumDiarizationButton.isSelected());
        });

        googleSpeechToTextButton = new JRadioButton("Google Speech to Text (Cloud Services)");
        googleSpeechToTextButton.setToolTipText("Diarize session audio using Google Cloud Services (requires account)");
        googleSpeechToTextButton.setSelected(false);
        googleSpeechToTextButton.setEnabled(false);
        btnGrp.add(googleSpeechToTextButton);

        JPanel googleOptionsPanel = new JPanel();

        TitledPanel liumPanel = new TitledPanel("", liumOptionsPanel);
        liumPanel.setLeftDecoration(liumDiarizationButton);

        TitledPanel googlePanel = new TitledPanel("", googleOptionsPanel);
        googlePanel.setLeftDecoration(googleSpeechToTextButton);

        JPanel contentPanel = new JPanel(new VerticalLayout(0));
        contentPanel.add(liumPanel);
        contentPanel.add(googlePanel);

//        btnPanel.getContentContainer().add(liumDiarizationButton);
//        btnPanel.getContentContainer().add(liumOptionsPanel);
//
//        btnPanel.getContentContainer().add(googleSpeechToTextButton);

        retVal.add(contentPanel, BorderLayout.CENTER);

        return retVal;
    }

    private WizardStep createReportStep() {
        final String title = "Diarization";
        final String desc = "Diarization output";

        WizardStep retVal = newStepWithHeader(title, desc);

        bufferPanel = new BufferPanel("Diarization results");
        bufferPanel.getLogBuffer().setEditable(false);
        busyLabel = new JXBusyLabel(new Dimension(16, 16));

        TitledPanel titledPanel = new TitledPanel("Log", bufferPanel);
        titledPanel.setLeftDecoration(busyLabel);

        retVal.add(titledPanel, BorderLayout.CENTER);

        return retVal;
    }

    private void startDiarization(DiarizationTool tool, File mediaFile) {
        tool.addListener(diarizationListener);
        try {
            DiarizationFutureResult result = tool.diarize(mediaFile);

            DiarizationWorker worker = new DiarizationWorker(result);
            workerRef.set(worker);
            worker.execute();
        } catch (IOException e) {
            Toolkit.getDefaultToolkit().beep();
            LogUtil.severe(e);
        }
    }

    private void startLIUMDiarization() {
        SessionMediaModel mediaModel = diarizationTier.getParentView().getEditor().getMediaModel();
        if(!mediaModel.isSessionAudioAvailable()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }

        busyLabel.setBusy(true);

        LIUMDiarizationTool tool = new LIUMDiarizationTool();
        tool.setDoCEClustering(doCEClusteringBox.isSelected());
        if(maxSpeakersField.getValue() > 0) {
            tool.setForceSpeakerMax(true);
            tool.setMaxSpeakerCount(maxSpeakersField.getValue());
        } else {
            tool.setForceSpeakerMax(false);
        }
        startDiarization(tool, mediaModel.getSessionAudioFile());
    }

    @Override
    public void next() {
        if(getWizardStep(getCurrentStepIndex()) == diarizationSelectionStep) {
            if(liumDiarizationButton.isSelected()) {
                if(!maxSpeakersField.validateText()) {
                    Toolkit.getDefaultToolkit().beep();
                    return;
                }
            }
        }
        super.next();
    }

    @Override
    public void gotoStep(int stepIndex) {
        if(getWizardStep(stepIndex) == reportStep) {
            startLIUMDiarization();
        }
        super.gotoStep(stepIndex);
    }

    private final DiarizationListener diarizationListener = (DiarizationEvent e) -> {
        SwingUtilities.invokeLater(() -> {
            bufferPanel.getLogBuffer().append(e.toString() + "\n");
            bufferPanel.getLogBuffer().setCaretPosition(bufferPanel.getLogBuffer().getDocument().getLength());
            if(e.getType() == DiarizationEvent.DiarizationEventType.DiarizationError) {
                bufferPanel.getLogBuffer().setForeground(Color.red);
            }
        });
    };

    private class DiarizationWorker extends SwingWorker<Session, Session> {

        private DiarizationFutureResult diarizationResult;

        public DiarizationWorker(DiarizationFutureResult diarizationResult) {
            this.diarizationResult = diarizationResult;
        }

        public void cancel() {
            this.diarizationResult.cancel();
        }

        @Override
        protected Session doInBackground() throws Exception {
            Session s = diarizationResult.getFutureSession().get();
            s.setCorpus(diarizationTier.getParentView().getEditor().getSession().getCorpus());
            publish(s);

            Project p = diarizationTier.getParentView().getEditor().getProject();
            DiarizationResultsManager resultsManager = new DiarizationResultsManager(p, s);
            diarizationListener.diarizationEvent(new DiarizationEvent("Saving diarization results to file " + resultsManager.diarizationResultsFile(false)));

            try {
                resultsManager.saveDiarizationResults(s);
                diarizationListener.diarizationEvent(new DiarizationEvent("Close window to view and modify diarization results"));
            } catch (IOException e) {
                Toolkit.getDefaultToolkit().beep();
                LogUtil.severe(e);
                SwingUtilities.invokeLater(() -> {
                    bufferPanel.getLogBuffer().append("Error saving results " + e.getMessage() + "\n");
                    bufferPanel.getLogBuffer().setCaretPosition(bufferPanel.getLogBuffer().getDocument().getLength());
                    bufferPanel.getLogBuffer().setForeground(Color.red);
                });
            }

            return s;
        }

        @Override
        protected void done() {
            busyLabel.setBusy(false);
            workerRef.set(null);

            updateBreadcrumbButtons();

            try {
                Session s = get();
                diarizationTier.setSession(s);
            } catch(InterruptedException | ExecutionException e) {
                Toolkit.getDefaultToolkit().beep();
                LogUtil.severe(e);
            }
        }

    }

}
