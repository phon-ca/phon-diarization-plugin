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
import ca.phon.ui.nativedialogs.FileFilter;
import ca.phon.ui.text.*;
import ca.phon.ui.wizard.BreadcrumbWizardFrame;
import ca.phon.ui.wizard.WizardStep;
import ca.phon.util.PrefHelper;
import org.jdesktop.swingx.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Diarization wizard
 *
 */
public class DiarizationWizard extends BreadcrumbWizardFrame {

    private final DiarizationTimelineTier diarizationTier;

    private WizardStep diarizationSelectionStep;

    private JRadioButton liumDiarizationButton;
    private JCheckBox doCEClusteringBox;
    private FormatterTextField<Integer> liumMaxSpeakersField;

    private final static String LAST_GOOGLE_PROJECT_ID = "gcst.projectId";
    private final static String LAST_GOOGLE_CREDENTIALS_FILE = "gcst.credentialsFile";
    private final static String LAST_GOOGLE_STORAGE_LOCATION = "gcst.storageLocation";
    private final static String DEFAULT_GOOGLE_STORAGE_LOCATION = "US";
    private final static String LAST_GOOGLE_LANGUAGE_MODEL = "gcst.languageModel";
    private final static String DEFAULT_GOOGLE_LANGUAGE_MODEL = "English (United States)";
    private final static String LAST_GOOGLE_FORMAT_MODEL = "gcst.formatModel";
    private final static String DEFAULT_GOOGLE_FORMAT_MODEL = "video";

    private JRadioButton googleSpeechToTextButton;
    private PromptedTextField projectIdField;
    private FileSelectionField credentialsFileField;
    private JComboBox<String> bucketStorageLocationBox;
    private JComboBox<String> languageModelSelectionBox;
    private JComboBox<String> formatModelSelectionBox;
    private JLabel formatModelDescriptionLabel;
    private FormatterTextField<Integer> googleMaxSpeakersField;

    private WizardStep reportStep;

    private JXBusyLabel busyLabel;

    private BufferPanel bufferPanel;

    private AtomicReference<DiarizationWorker> workerRef = new AtomicReference<>();
    private AtomicReference<Session> resultsRef = new AtomicReference<>();

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
            if(resultsRef.get() != null) {
                diarizationTier.setSession(resultsRef.get());
            }
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

        final JLabel ceClusteringLbl = new JLabel("Clustering options:");
        final JLabel liumMaxSpeakersLbl = new JLabel("Max speakers:");

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        liumOptionsPanel.add(ceClusteringLbl, gbc);
        ++gbc.gridy;
        gbc.insets = new Insets(0, 20, 0, 0);
        liumOptionsPanel.add(doCEClusteringBox, gbc);

        liumMaxSpeakersField = new FormatterTextField<Integer>(FormatterFactory.createFormatter(Integer.class));
        liumMaxSpeakersField.setPrompt("Enter max speakers, 0 = auto");
        liumMaxSpeakersField.setValue(0);

        ++gbc.gridy;
        gbc.insets = new Insets(0, 0, 0, 0);
        liumOptionsPanel.add(liumMaxSpeakersLbl, gbc);
        ++gbc.gridy;
        gbc.insets = new Insets(0, 20, 0, 0);
        liumOptionsPanel.add(liumMaxSpeakersField, gbc);

        ++gbc.gridy;
        gbc.weighty = 1.0f;
        liumOptionsPanel.add(Box.createVerticalGlue(), gbc);
        gbc.weighty = 0.0f;

        liumDiarizationButton.addChangeListener((e) -> {
            ceClusteringLbl.setEnabled(liumDiarizationButton.isSelected());
            doCEClusteringBox.setEnabled(liumDiarizationButton.isSelected());

            liumMaxSpeakersLbl.setEnabled(liumDiarizationButton.isSelected());
            liumMaxSpeakersField.setEnabled(liumDiarizationButton.isSelected());
        });

        // labels
        final JLabel projectIdLbl = new JLabel("Project id:");
        projectIdLbl.setEnabled(false);
        final JLabel credLbl = new JLabel("Service Account Credentials File (.json):");
        credLbl.setEnabled(false);
        final JLabel bucketLbl = new JLabel("Google Cloud Storage Bucket Location (if file length > 60s):");
        bucketLbl.setEnabled(false);
        final JLabel languageLbl = new JLabel("Language model:");
        languageLbl.setEnabled(false);
        final JLabel formatLbl = new JLabel("Audio format model:");
        formatLbl.setEnabled(false);
        final JLabel maxSpeakersLbl = new JLabel("Max speakers:");
        maxSpeakersLbl.setEnabled(false);

        googleSpeechToTextButton = new JRadioButton("Google Speech to Text (Cloud Services)");
        googleSpeechToTextButton.setToolTipText("Diarize session audio using Google Cloud Services (requires account)");
        googleSpeechToTextButton.setSelected(false);
        googleSpeechToTextButton.addChangeListener((e) -> {
            projectIdLbl.setEnabled(googleSpeechToTextButton.isSelected());
            projectIdField.setEnabled(googleSpeechToTextButton.isSelected());

            credLbl.setEnabled(googleSpeechToTextButton.isSelected());
            credentialsFileField.setEnabled(googleSpeechToTextButton.isSelected());

            bucketLbl.setEnabled(googleSpeechToTextButton.isSelected());
            bucketStorageLocationBox.setEnabled(googleSpeechToTextButton.isSelected());

            languageLbl.setEnabled(googleSpeechToTextButton.isSelected());
            languageModelSelectionBox.setEnabled(googleSpeechToTextButton.isSelected());

            formatLbl.setEnabled(googleSpeechToTextButton.isSelected());
            formatModelSelectionBox.setEnabled(googleSpeechToTextButton.isSelected());
            formatModelDescriptionLabel.setEnabled(googleSpeechToTextButton.isSelected());

            maxSpeakersLbl.setEnabled(googleSpeechToTextButton.isSelected());
            googleMaxSpeakersField.setEnabled(googleSpeechToTextButton.isSelected());
        });
        btnGrp.add(googleSpeechToTextButton);

        JPanel googleOptionsPanel = new JPanel(new GridBagLayout());
        projectIdField = new PromptedTextField("Enter project id");
        String lastProjectId = PrefHelper.get(LAST_GOOGLE_PROJECT_ID, "");
        projectIdField.setText(lastProjectId);
        projectIdField.setEnabled(false);

        credentialsFileField = new FileSelectionField();
        String prevCredentialsPath = PrefHelper.get(LAST_GOOGLE_CREDENTIALS_FILE, null);
        credentialsFileField.setFileFilter(new FileFilter("Google Service Account Credentials", "json"));
        if(prevCredentialsPath != null) {
            credentialsFileField.setFile(new File(prevCredentialsPath));
        }
        credentialsFileField.setEnabled(false);

        bucketStorageLocationBox = new JComboBox<>(GCSTDiarizationTool.STORAGE_LOCATIONS);
        bucketStorageLocationBox.setSelectedItem(PrefHelper.get(LAST_GOOGLE_STORAGE_LOCATION, DEFAULT_GOOGLE_STORAGE_LOCATION));
        bucketStorageLocationBox.setEnabled(false);

        languageModelSelectionBox = new JComboBox<>(GCSTDiarizationTool.SUPPORTED_LANGUAGE_NAMES);
        languageModelSelectionBox.setSelectedItem(PrefHelper.get(LAST_GOOGLE_LANGUAGE_MODEL, DEFAULT_GOOGLE_LANGUAGE_MODEL));
        languageModelSelectionBox.setEnabled(false);

        formatModelDescriptionLabel = new JLabel();
        formatModelSelectionBox = new JComboBox<>(GCSTDiarizationTool.SPEECH_TO_TEXT_MODELS);
        formatModelSelectionBox.setSelectedItem(PrefHelper.get(LAST_GOOGLE_FORMAT_MODEL, DEFAULT_GOOGLE_FORMAT_MODEL));
        formatModelSelectionBox.addActionListener( (e) -> {
            formatModelDescriptionLabel.setText(
                    "<html><p>" + GCSTDiarizationTool.SPEECH_TO_TEXT_MODEL_DESCRIPTIONS[formatModelSelectionBox.getSelectedIndex()] + "</p></html>");
        });
        formatModelDescriptionLabel.setText("<html><p>" + GCSTDiarizationTool.SPEECH_TO_TEXT_MODEL_DESCRIPTIONS[formatModelSelectionBox.getSelectedIndex()] + "</p></html>");
        formatModelSelectionBox.setEnabled(false);
        formatModelDescriptionLabel.setEnabled(false);

        googleMaxSpeakersField = new FormatterTextField<Integer>(FormatterFactory.createFormatter(Integer.class));
        googleMaxSpeakersField.setPrompt("Enter max speakers, 0 = auto");
        googleMaxSpeakersField.setValue(0);
        googleMaxSpeakersField.setEnabled(false);

        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.weighty = 0.0;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        gbc.insets = new Insets(0, 0, 0, 0);
        googleOptionsPanel.add(projectIdLbl, gbc);
        ++gbc.gridy;
        gbc.insets = new Insets(0, 20, 0, 0);
        googleOptionsPanel.add(projectIdField, gbc);

        ++gbc.gridy;
        gbc.insets = new Insets(0, 0, 0, 0);
        googleOptionsPanel.add(credLbl, gbc);
        ++gbc.gridy;
        gbc.insets = new Insets(0, 20, 0, 0);
        googleOptionsPanel.add(credentialsFileField, gbc);

        ++gbc.gridy;
        gbc.insets = new Insets(0, 0, 0, 0);
        googleOptionsPanel.add(bucketLbl, gbc);
        ++gbc.gridy;
        gbc.insets = new Insets(0, 20, 0, 0);
        googleOptionsPanel.add(bucketStorageLocationBox, gbc);

        ++gbc.gridy;
        gbc.insets = new Insets(0, 0, 0, 0);
        googleOptionsPanel.add(languageLbl, gbc);
        ++gbc.gridy;
        gbc.insets = new Insets(0, 20, 0, 0);
        googleOptionsPanel.add(languageModelSelectionBox, gbc);

        ++gbc.gridy;
        gbc.insets = new Insets(0, 0, 0, 0);
        googleOptionsPanel.add(formatLbl, gbc);
        ++gbc.gridy;
        gbc.insets = new Insets(0, 20, 0, 0);
        googleOptionsPanel.add(formatModelSelectionBox, gbc);
        ++gbc.gridy;
        googleOptionsPanel.add(formatModelDescriptionLabel, gbc);

        ++gbc.gridy;
        gbc.insets = new Insets(0, 0, 0, 0);
        googleOptionsPanel.add(maxSpeakersLbl, gbc);
        ++gbc.gridy;
        gbc.insets = new Insets(0, 20, 0, 0);
        googleOptionsPanel.add(googleMaxSpeakersField, gbc);

        ++gbc.gridy;
        gbc.weighty = 1.0f;
        googleOptionsPanel.add(Box.createVerticalGlue(), gbc);

        TitledPanel liumPanel = new TitledPanel("", liumOptionsPanel);
        liumPanel.setLeftDecoration(liumDiarizationButton);

        TitledPanel googlePanel = new TitledPanel("", googleOptionsPanel);
        googlePanel.setLeftDecoration(googleSpeechToTextButton);

        JPanel contentPanel = new JPanel(new GridBagLayout());
        gbc = new GridBagConstraints();
        gbc.weighty = 0.5f;
        gbc.weightx = 1.0f;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.BOTH;

        contentPanel.add(liumPanel, gbc);
        ++gbc.gridy;
        contentPanel.add(googlePanel, gbc);

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
            tool.fireDiarizationEvent(DiarizationEvent.DiarizationEventType.DiarizationError, e.getLocalizedMessage());
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
        if(liumMaxSpeakersField.getValue() > 0) {
            tool.setForceSpeakerMax(true);
            tool.setMaxSpeakerCount(liumMaxSpeakersField.getValue());
        } else {
            tool.setForceSpeakerMax(false);
        }
        startDiarization(tool, mediaModel.getSessionAudioFile());
    }

    /**
     * Diarize using Google Cloud Speech to Text services.
     *
     */
    private void startGCSTDiarization() {
        SessionMediaModel mediaModel = diarizationTier.getParentView().getEditor().getMediaModel();
        if(!mediaModel.isSessionAudioAvailable()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }

        final String projectId = projectIdField.getText();
        PrefHelper.getUserPreferences().put(LAST_GOOGLE_PROJECT_ID,projectId);

        final String credFile = credentialsFileField.getSelectedFile().getAbsolutePath();
        PrefHelper.getUserPreferences().put(LAST_GOOGLE_CREDENTIALS_FILE, credFile);

        final String storageLocation = bucketStorageLocationBox.getSelectedItem().toString();
        PrefHelper.getUserPreferences().put(LAST_GOOGLE_STORAGE_LOCATION, storageLocation);

        final String langModel = languageModelSelectionBox.getSelectedItem().toString();
        PrefHelper.getUserPreferences().put(LAST_GOOGLE_LANGUAGE_MODEL, langModel);
        final int langIdx = Arrays.asList(GCSTDiarizationTool.SUPPORTED_LANGUAGE_NAMES).indexOf(langModel);
        final String langModelTag = langIdx >= 0 ? GCSTDiarizationTool.SUPPORTED_LANGUAGE_TAGS[langIdx] : "en-US";

        final String formatModel = formatModelSelectionBox.getSelectedItem().toString();
        PrefHelper.getUserPreferences().put(LAST_GOOGLE_FORMAT_MODEL, formatModel);

        busyLabel.setBusy(true);

        GCSTDiarizationTool tool = new GCSTDiarizationTool();
        tool.setStorageLocation(storageLocation);
        tool.setGcstModel(formatModel);
        tool.setLanguageModel(langModelTag);
        tool.setProjectId(projectId);
        tool.setCredentialsFile(credFile);
        if(googleMaxSpeakersField.getValue() > 0) {
            tool.setMaxSpeakers(googleMaxSpeakersField.getValue());
        }
        startDiarization(tool, mediaModel.getSessionAudioFile());
    }

    @Override
    public void next() {
        if(getWizardStep(getCurrentStepIndex()) == diarizationSelectionStep) {
            if(liumDiarizationButton.isSelected()) {
                if(!liumMaxSpeakersField.validateText()) {
                    Toolkit.getDefaultToolkit().beep();
                    return;
                }
            } else if(googleSpeechToTextButton.isSelected()) {
                // TODO check google params
            }
        }
        super.next();
    }

    @Override
    public void gotoStep(int stepIndex) {
        if(getWizardStep(stepIndex) == reportStep) {
            if(liumDiarizationButton.isSelected())
                startLIUMDiarization();
            else
                startGCSTDiarization();
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
            DiarizationResultsManager resultsManager = new DiarizationResultsManager(p, diarizationTier.getParentView().getEditor().getSession());
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
                resultsRef.set(s);
            } catch(InterruptedException | ExecutionException e) {
                bufferPanel.getLogBuffer().append("Unable to complete diarization, execution interrupted or cancelled\n");
                bufferPanel.getLogBuffer().setForeground(Color.red);
                Toolkit.getDefaultToolkit().beep();
                LogUtil.severe(e);
            }
        }

    }

}
