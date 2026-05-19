package com.edwares;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;

/**
 * A highly scalable, reusable text editor component designed to handle massive files 
 * (50GB+) with a strict 4GB memory footprint using asynchronous chunk loading.
 */
public class AdvancedTextEditorPanel extends JPanel {
    private static final int SCROLL_RESOLUTION = 10000;

    private final JTextArea textArea;
    private final LineNumberPanel lineNumberPanel;
    private final JScrollPane scrollPane;
    
    private final JLabel lblStatus;
    private final JLabel lblLoadingStatus;
    private final JLabel lblCursorInfo;
    private final JScrollBar globalScrollBar;
    
    private final LargeFileManager fileManager;
    private File activeFile = null;

    private boolean isSyncingScroll = false;
    private boolean isLoadingChunk = false;
    private boolean isDirty = false;
    private boolean isNavigating = false; 

    private int loadedChunkIndex = 0;
    private int pendingTargetChunk = -1;
    private double pendingLocalPercent = -1;
    private boolean pendingPreviewRequest = false;
    
    private boolean isCurrentlyPreview = false;
    private double lastRequestedLocalPercent = -1;
    private final Timer settleTimer; 

    private String currentTitle = "Untitled";

    private JDialog searchDialog;
    private JTextField txtSearch;
    private JTextField txtReplace;

    public AdvancedTextEditorPanel() {
        setLayout(new BorderLayout());
        this.fileManager = new LargeFileManager();

        settleTimer = new Timer(350, e -> {
            if (isCurrentlyPreview && !isLoadingChunk && !isNavigating) {
                triggerAsyncLoad(loadedChunkIndex, 0, lastRequestedLocalPercent, false, null);
            }
        });
        settleTimer.setRepeats(false);

        textArea = new JTextArea() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                try {
                    Rectangle r = modelToView2D(getCaretPosition()).getBounds();
                    g.setColor(new Color(235, 245, 255)); 
                    g.fillRect(0, r.y, getWidth(), r.height);
                } catch (Exception e) {}
                
                setOpaque(false);
                super.paintComponent(g);
                setOpaque(true);
            }
        };
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        textArea.setLineWrap(false);

        lineNumberPanel = new LineNumberPanel(textArea);

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { registerEdit(); }
            public void removeUpdate(DocumentEvent e) { registerEdit(); }
            public void changedUpdate(DocumentEvent e) { registerEdit(); }
            
            private void registerEdit() {
                if (!isNavigating && !isCurrentlyPreview) isDirty = true;
                lineNumberPanel.adjustMetricSizing();
                if (!isNavigating) syncLocalToGlobalScroll();
            }
        });

        textArea.addCaretListener(e -> {
            if (isNavigating) return;
            updateCursorStatus();
            try {
                int line = textArea.getLineOfOffset(textArea.getCaretPosition());
                lineNumberPanel.setCurrentLine(line);
            } catch (Exception ex) {}
            textArea.repaint(); 
        });

        setupKeyboardShortcuts();

        scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scrollPane.setRowHeaderView(lineNumberPanel);
        
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!isSyncingScroll && !isNavigating && !isLoadingChunk) {
                syncLocalToGlobalScroll();
            }
        });

        scrollPane.addMouseWheelListener(e -> {
            if (isLoadingChunk || isNavigating) return;
            JScrollBar vBar = scrollPane.getVerticalScrollBar();
            if (e.getWheelRotation() > 0 && vBar.getValue() + vBar.getVisibleAmount() >= vBar.getMaximum()) {
                triggerAutoNavigate(1);
            } else if (e.getWheelRotation() < 0 && vBar.getValue() <= 0) {
                triggerAutoNavigate(-1);
            }
        });

        add(scrollPane, BorderLayout.CENTER);

        globalScrollBar = new JScrollBar(JScrollBar.VERTICAL, 0, 1, 0, SCROLL_RESOLUTION);
        globalScrollBar.addAdjustmentListener(e -> {
            if (!isSyncingScroll && !isNavigating) {
                syncGlobalToLocalScroll();
            }
        });
        add(globalScrollBar, BorderLayout.EAST);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        
        JPanel leftStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        lblStatus = new JLabel("No file active.");
        lblLoadingStatus = new JLabel("");
        lblLoadingStatus.setFont(lblLoadingStatus.getFont().deriveFont(Font.BOLD));
        lblLoadingStatus.setForeground(new Color(220, 100, 0)); 
        
        leftStatusPanel.add(lblStatus);
        leftStatusPanel.add(lblLoadingStatus);
        
        lblCursorInfo = new JLabel("Line: 1 | Pos: 0");
        
        statusBar.add(leftStatusPanel, BorderLayout.WEST);
        statusBar.add(lblCursorInfo, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);
    }

    public void showSearchDialog() {
        if (searchDialog == null) {
            Window parentWindow = SwingUtilities.getWindowAncestor(this);
            searchDialog = new JDialog(parentWindow, "Search & Replace");
            searchDialog.setModal(false);
            searchDialog.setAlwaysOnTop(true);
            searchDialog.setLayout(new GridLayout(3, 1, 5, 5));
            searchDialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            JPanel pnlSearch = new JPanel(new BorderLayout());
            pnlSearch.add(new JLabel("Find:     "), BorderLayout.WEST);
            txtSearch = new JTextField(25);
            pnlSearch.add(txtSearch, BorderLayout.CENTER);
            
            JPanel pnlReplace = new JPanel(new BorderLayout());
            pnlReplace.add(new JLabel("Replace: "), BorderLayout.WEST);
            txtReplace = new JTextField(25);
            pnlReplace.add(txtReplace, BorderLayout.CENTER);
            
            JPanel pnlBtns = new JPanel(new FlowLayout());
            JButton btnFind = new JButton("Find Next");
            JButton btnCount = new JButton("Count Matches");
            JButton btnReplaceAll = new JButton("Replace All");
            
            btnFind.addActionListener(e -> performFindNext(txtSearch.getText()));
            btnCount.addActionListener(e -> performCountMatches(txtSearch.getText()));
            btnReplaceAll.addActionListener(e -> performReplaceAll(txtSearch.getText(), txtReplace.getText()));
            
            pnlBtns.add(btnFind);
            pnlBtns.add(btnCount);
            pnlBtns.add(btnReplaceAll);
            
            searchDialog.add(pnlSearch);
            searchDialog.add(pnlReplace);
            searchDialog.add(pnlBtns);
            searchDialog.pack();
            searchDialog.setLocationRelativeTo(this);
        }
        searchDialog.setVisible(true);
        txtSearch.requestFocus();
    }

    public void showGotoLineDialog() {
        String input = JOptionPane.showInputDialog(this, "Enter Destination Line Number:", "Go To Line", JOptionPane.QUESTION_MESSAGE);
        if (input != null && !input.trim().isEmpty()) {
            try {
                long targetLine = Long.parseLong(input.trim());
                lblLoadingStatus.setText("Searching for line position...");
                new SwingWorker<Integer, Void>() {
                    @Override
                    protected Integer doInBackground() throws Exception {
                        if (isDirty && !isCurrentlyPreview) {
                            fileManager.commitCurrentChunk(textArea.getText());
                            isDirty = false;
                        }
                        return fileManager.getChunkForLine(targetLine);
                    }

                    @Override
                    protected void done() {
                        try {
                            int targetChunk = get();
                            if (targetChunk == loadedChunkIndex) {
                                jumpToLocalLine(targetLine);
                                lblLoadingStatus.setText("");
                            } else {
                                triggerAsyncLoad(targetChunk, 0, -1, false, () -> jumpToLocalLine(targetLine));
                            }
                        } catch (Exception e) {
                            lblLoadingStatus.setText("");
                        }
                    }
                }.execute();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Please enter a valid numeric line value.", "Invalid Input", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void jumpToLocalLine(long absoluteTargetLine) {
        long startLine = lineNumberPanel.getStartLine();
        int localLine = (int) (absoluteTargetLine - startLine);
        
        if (localLine >= 0 && localLine < textArea.getLineCount()) {
            try {
                int offset = textArea.getLineStartOffset(localLine);
                textArea.setCaretPosition(offset);
                textArea.requestFocus();
            } catch (Exception e) {}
        }
    }

    private Component getDialogParent() {
        return (searchDialog != null && searchDialog.isVisible()) ? searchDialog : this;
    }

    private void performFindNext(String target) {
        if (target == null || target.isEmpty()) return;

        int caret = textArea.getCaretPosition();
        String text = textArea.getText();
        int idx = text.indexOf(target, caret);
        
        if (idx != -1) {
            textArea.setCaretPosition(idx);
            textArea.moveCaretPosition(idx + target.length());
            textArea.requestFocus();
            return;
        }
        
        lblLoadingStatus.setText("Scanning file for next match...");
        new SwingWorker<Integer, Void>() {
            int foundIdx = -1;
            
            @Override
            protected Integer doInBackground() throws Exception {
                if (isDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(textArea.getText());
                    isDirty = false;
                }
                int total = fileManager.getTotalChunks();
                for (int i = loadedChunkIndex + 1; i < total; i++) {
                    String content = fileManager.getChunkContent(i);
                    int match = content.indexOf(target);
                    if (match != -1) {
                        foundIdx = match;
                        return i;
                    }
                }
                return -1;
            }

            @Override
            protected void done() {
                try {
                    int targetChunk = get();
                    lblLoadingStatus.setText("");
                    if (targetChunk != -1) {
                        triggerAsyncLoad(targetChunk, 0, -1, false, () -> {
                            textArea.setCaretPosition(foundIdx);
                            textArea.moveCaretPosition(foundIdx + target.length());
                            textArea.requestFocus();
                        });
                    } else {
                        JOptionPane.showMessageDialog(getDialogParent(), "No more matches found in the file.", "Search Complete", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {}
            }
        }.execute();
    }

    private void performCountMatches(String target) {
        if (target == null || target.isEmpty()) return;
        lblLoadingStatus.setText("Running full file match count...");
        new SwingWorker<Long, Void>() {
            @Override
            protected Long doInBackground() throws Exception {
                if (isDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(textArea.getText());
                    isDirty = false;
                }
                return fileManager.countGlobalMatches(target);
            }
            @Override
            protected void done() {
                try {
                    lblLoadingStatus.setText("");
                    JOptionPane.showMessageDialog(getDialogParent(), "Total occurrences found: " + get(), "Match Count", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {}
            }
        }.execute();
    }

    private void performReplaceAll(String target, String replacement) {
        if (target == null || target.isEmpty()) return;
        
        if (!fileManager.hasFile()) {
            textArea.setText(textArea.getText().replace(target, replacement));
            return;
        }
        
        lblLoadingStatus.setText("Replacing occurrences globally...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (isDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(textArea.getText());
                    isDirty = false;
                }
                fileManager.replaceAllGlobal(target, replacement);
                return null;
            }
            @Override
            protected void done() {
                triggerAsyncLoad(loadedChunkIndex, 0, -1, false, () -> {
                    lblLoadingStatus.setText("");
                    JOptionPane.showMessageDialog(getDialogParent(), "Global replacement complete.", "Replace All", JOptionPane.INFORMATION_MESSAGE);
                });
            }
        }.execute();
    }

    public void cut() { if (!isCurrentlyPreview) textArea.cut(); }
    public void copy() { textArea.copy(); }
    public void paste() { if (!isCurrentlyPreview) textArea.paste(); }

    public void createNewDocument() {
        fileManager.setNewFile();
        activeFile = null;
        isDirty = false;
        isNavigating = true;
        loadedChunkIndex = 0;
        pendingTargetChunk = -1;
        textArea.setText("");
        isNavigating = false;
        lblStatus.setText("New file creation mode.");
        lineNumberPanel.setStartLine(1);
        updateTitle("Untitled");
        globalScrollBar.setValue(0);
        globalScrollBar.setMaximum(SCROLL_RESOLUTION + globalScrollBar.getVisibleAmount());
        updateCursorStatus();
    }

    public void loadFile(File file) {
        this.activeFile = file;
        fileManager.setFile(file);
        isDirty = false;
        loadedChunkIndex = 0;
        pendingTargetChunk = -1;
        try {
            applyStateUpdates(fileManager.loadCurrentChunk(false), 1, -1, null);
        } catch (IOException ex) {
            showError("Failed to open file: " + ex.getMessage());
        }
    }

    public void saveCurrentFile() {
        if (!fileManager.hasFile()) {
            throw new IllegalStateException("No active file is set. Use saveAsFile(File) instead.");
        }
        executeSaveRoutine();
    }

    public void saveAsFile(File file) {
        this.activeFile = file;
        fileManager.setCurrentFile(file);
        executeSaveRoutine();
    }

    public boolean hasActiveFile() {
        return fileManager.hasFile();
    }
    
    public File getActiveFile() {
        return activeFile;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    // ----------------------------

    private void executeSaveRoutine() {
        lblLoadingStatus.setText("Saving file...");
        isNavigating = true; 
        new SwingWorker<LargeFileManager.ChunkState, Void>() {
            @Override
            protected LargeFileManager.ChunkState doInBackground() throws Exception {
                String saveText = isCurrentlyPreview ? "" : textArea.getText();
                return fileManager.saveAll(saveText);
            }
            @Override
            protected void done() {
                try {
                    isDirty = false;
                    pendingTargetChunk = -1;
                    applyStateUpdates(get(), 0, -1, null);
                } catch (Exception ex) {
                    showError("Streaming save operation failure: " + ex.getMessage());
                } finally {
                    lblLoadingStatus.setText("");
                    isNavigating = false;
                }
            }
        }.execute();
    }

    private void updateTitle(String newTitle) {
        String oldTitle = this.currentTitle;
        this.currentTitle = newTitle;
        // Fires a standard Swing property change event so the parent window can update its JFrame title
        firePropertyChange("editorTitle", oldTitle, newTitle);
    }

    private void updateCursorStatus() {
        if (isCurrentlyPreview) return;
        try {
            int dot = textArea.getCaret().getDot();
            int mark = textArea.getCaret().getMark();
            int localLine = textArea.getLineOfOffset(dot);
            long absoluteLine = lineNumberPanel.getStartLine() + localLine;

            if (dot == mark) {
                lblCursorInfo.setText(String.format("Line: %d | Pos: %d", absoluteLine, dot));
            } else {
                int selStart = Math.min(dot, mark);
                int selEnd = Math.max(dot, mark);
                lblCursorInfo.setText(String.format("Line: %d | Sel: %d - %d", absoluteLine, selStart, selEnd));
            }
        } catch (Exception e) {}
    }

    private void setupKeyboardShortcuts() {
        InputMap im = textArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = textArea.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.CTRL_DOWN_MASK), "none");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK), "none");

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.CTRL_DOWN_MASK), "jumpStart");
        am.put("jumpStart", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (loadedChunkIndex == 0 && !isCurrentlyPreview) {
                    SwingUtilities.invokeLater(() -> {
                        textArea.setCaretPosition(0); 
                        scrollPane.getVerticalScrollBar().setValue(0);
                        lineNumberPanel.setCurrentLine(0);
                        syncLocalToGlobalScroll();
                    });
                } else {
                    triggerAsyncLoad(0, 1, -1, false, null);
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK), "jumpEnd");
        am.put("jumpEnd", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                int targetChunk = Math.max(0, fileManager.getTotalChunks() - 1);
                if (loadedChunkIndex == targetChunk && !isCurrentlyPreview) {
                    SwingUtilities.invokeLater(() -> {
                        textArea.setCaretPosition(textArea.getDocument().getLength());
                        JScrollBar vbar = scrollPane.getVerticalScrollBar();
                        vbar.setValue(vbar.getMaximum());
                        lineNumberPanel.setCurrentLine(textArea.getLineCount() - 1);
                        syncLocalToGlobalScroll();
                    });
                } else {
                    triggerAsyncLoad(targetChunk, -1, -1, false, null);
                }
            }
        });

        // Add Ctrl+F (Search) shortcut
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "showSearch");
        am.put("showSearch", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                showSearchDialog();
            }
        });

        // Add Ctrl+G (Go To Line) shortcut
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK), "showGotoLine");
        am.put("showGotoLine", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                showGotoLineDialog();
            }
        });
    }

    private void triggerAsyncLoad(int targetChunk, int direction, double localPercentForScroll, boolean requestPreview, Runnable postLoadAction) {
        if (isNavigating) return;
        settleTimer.stop(); 

        int total = Math.max(1, fileManager.getTotalChunks());
        if (targetChunk >= total) targetChunk = total - 1;
        if (targetChunk < 0) targetChunk = 0;

        if (targetChunk == loadedChunkIndex && !isCurrentlyPreview && !requestPreview) {
            if (postLoadAction != null) postLoadAction.run();
            return; 
        }

        isLoadingChunk = true;
        textArea.setEditable(false); 
        lastRequestedLocalPercent = localPercentForScroll;
        
        if (requestPreview) {
            lblLoadingStatus.setText("Previewing chunk " + (targetChunk + 1) + "...");
        } else {
            lblLoadingStatus.setText("Loading full chunk " + (targetChunk + 1) + "...");
        }
        lblStatus.setText(String.format("Chunk %d of %d", targetChunk + 1, total));

        String currentText = textArea.getText();
        boolean wasDirty = isDirty;
        isDirty = false;

        final int finalTarget = targetChunk;
        new SwingWorker<LargeFileManager.ChunkState, Void>() {
            @Override
            protected LargeFileManager.ChunkState doInBackground() throws Exception {
                if (wasDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(currentText);
                }
                return fileManager.navigateToIndex(finalTarget, requestPreview);
            }

            @Override
            protected void done() {
                try {
                    LargeFileManager.ChunkState state = get();
                    if (state != null) {
                        applyStateUpdates(state, direction, localPercentForScroll, postLoadAction);
                    } else {
                        isLoadingChunk = false;
                        textArea.setEditable(!isCurrentlyPreview);
                    }
                } catch (Exception e) {
                    showError("Chunk load failure: " + e.getMessage());
                    isLoadingChunk = false;
                    lblLoadingStatus.setText("");
                    textArea.setEditable(!isCurrentlyPreview);
                }
            }
        }.execute();
    }

    private void syncLocalToGlobalScroll() {
        if (isNavigating || isLoadingChunk) return;
        isSyncingScroll = true;
        try {
            int totalChunks = fileManager.getTotalChunks();
            int maxScrollRange = Math.max(1, totalChunks) * SCROLL_RESOLUTION;
            globalScrollBar.setMaximum(maxScrollRange + globalScrollBar.getVisibleAmount());

            JScrollBar localBar = scrollPane.getVerticalScrollBar();
            double localMax = localBar.getMaximum() - localBar.getVisibleAmount();
            double localPercent = localMax == 0 ? 0 : localBar.getValue() / localMax;

            int globalValue = (loadedChunkIndex * SCROLL_RESOLUTION) + (int)(localPercent * SCROLL_RESOLUTION);
            globalScrollBar.setValue(globalValue);
        } catch (Exception ex) {
        } finally {
            isSyncingScroll = false;
        }
    }

    private void syncGlobalToLocalScroll() {
        if (isNavigating) return; 
        isSyncingScroll = true;
        try {
            int globalValue = globalScrollBar.getValue();
            int targetChunk = globalValue / SCROLL_RESOLUTION;
            double localPercent = (globalValue % SCROLL_RESOLUTION) / (double) SCROLL_RESOLUTION;
            
            int total = Math.max(1, fileManager.getTotalChunks());
            if (targetChunk >= total) {
                targetChunk = total - 1;
                localPercent = 1.0;
            }

            lblStatus.setText(String.format("Chunk %d of %d", targetChunk + 1, total));

            if (isLoadingChunk) {
                pendingTargetChunk = targetChunk;
                pendingLocalPercent = localPercent;
                pendingPreviewRequest = true; 
            } else if (targetChunk != loadedChunkIndex) {
                isSyncingScroll = false; 
                triggerAsyncLoad(targetChunk, 0, localPercent, true, null); 
            } else {
                JScrollBar localBar = scrollPane.getVerticalScrollBar();
                int targetLocalValue = (int)(localPercent * (localBar.getMaximum() - localBar.getVisibleAmount()));
                localBar.setValue(targetLocalValue);
                
                lastRequestedLocalPercent = localPercent;
                if (isCurrentlyPreview) settleTimer.restart();
            }
        } catch (Exception ex) {
        } finally {
            isSyncingScroll = false;
        }
    }

    private void applyStateUpdates(LargeFileManager.ChunkState state, int direction, double localPercentForScroll, Runnable postLoadAction) {
        isNavigating = true; 
        loadedChunkIndex = state.chunkIndex();
        isCurrentlyPreview = state.isPreview();
        
        textArea.setEditable(!isCurrentlyPreview);
        textArea.setText(state.content());
        lineNumberPanel.setStartLine(state.startLine());
        
        updateTitle(state.fileName() + (isCurrentlyPreview ? " [PREVIEW]" : ""));
        
        int maxScrollRange = state.totalChunks() * SCROLL_RESOLUTION;
        globalScrollBar.setMaximum(maxScrollRange + globalScrollBar.getVisibleAmount());
        
        if (pendingTargetChunk == -1) {
            lblStatus.setText(state.statusText());
        }
        
        SwingUtilities.invokeLater(() -> {
            if (localPercentForScroll >= 0) {
                JScrollBar localBar = scrollPane.getVerticalScrollBar();
                int targetLocalValue = (int)(localPercentForScroll * (localBar.getMaximum() - localBar.getVisibleAmount()));
                localBar.setValue(targetLocalValue);
            } else if (direction > 0) {
                textArea.setCaretPosition(0); 
                scrollPane.getVerticalScrollBar().setValue(0);
                lineNumberPanel.setCurrentLine(0);
            } else if (direction < 0) {
                textArea.setCaretPosition(textArea.getDocument().getLength());
                JScrollBar vbar = scrollPane.getVerticalScrollBar();
                vbar.setValue(vbar.getMaximum());
                lineNumberPanel.setCurrentLine(textArea.getLineCount() - 1);
            }
            
            updateCursorStatus();
            
            if (isCurrentlyPreview) {
                lblLoadingStatus.setText("Preview Active");
                settleTimer.restart(); 
            } else {
                lblLoadingStatus.setText("");
                settleTimer.stop();
            }

            isNavigating = false;
            isLoadingChunk = false;
            
            if (pendingTargetChunk != -1) {
                if (pendingTargetChunk != loadedChunkIndex) {
                    int nextTarget = pendingTargetChunk;
                    double nextPercent = pendingLocalPercent;
                    boolean nextPreview = pendingPreviewRequest;
                    
                    pendingTargetChunk = -1; 
                    triggerAsyncLoad(nextTarget, 0, nextPercent, nextPreview, postLoadAction);
                } else {
                    pendingTargetChunk = -1;
                    syncLocalToGlobalScroll();
                    if (postLoadAction != null) postLoadAction.run();
                }
            } else {
                syncLocalToGlobalScroll();
                if (postLoadAction != null) postLoadAction.run();
            }
        });
    }

    private void triggerAutoNavigate(int direction) {
        if (isLoadingChunk || isNavigating) return;
        triggerAsyncLoad(loadedChunkIndex + direction, direction, -1, false, null);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "System IO Exception Error", JOptionPane.ERROR_MESSAGE);
    }

    // --- Embedded Components ---

    private static class LineNumberPanel extends JPanel {
        private final JTextArea textArea;
        private long startLine = 1;
        private int currentLocalLine = 0;

        public LineNumberPanel(JTextArea textArea) {
            this.textArea = textArea;
            setBackground(new Color(245, 245, 245));
            setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.LIGHT_GRAY));
            adjustMetricSizing();
        }

        public void setStartLine(long startLine) {
            this.startLine = startLine;
            adjustMetricSizing();
        }

        public long getStartLine() { return this.startLine; }

        public void setCurrentLine(int line) {
            if (this.currentLocalLine != line) {
                this.currentLocalLine = line;
                repaint();
            }
        }

        public void adjustMetricSizing() {
            FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
            int totalLines = textArea.getLineCount();
            int maximumDigits = String.valueOf(startLine + totalLines).length();
            int functionalWidth = fm.stringWidth("0") * Math.max(maximumDigits, 3) + 12;
            setPreferredSize(new Dimension(functionalWidth, textArea.getPreferredSize().height));
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            FontMetrics fm = g.getFontMetrics(textArea.getFont());
            int lineHeight = fm.getHeight();
            int localLineCount = textArea.getLineCount();

            Rectangle viewportClip = g.getClipBounds();
            int startingY = viewportClip.y;
            int boundingY = viewportClip.y + viewportClip.height;

            int computedY = fm.getAscent();
            for (int i = 0; i < localLineCount; i++) {
                if (computedY + lineHeight >= startingY && computedY <= boundingY) {
                    String stringLabel = String.valueOf(startLine + i);
                    int alignedX = getWidth() - fm.stringWidth(stringLabel) - 6;
                    
                    if (i == currentLocalLine) {
                        g.setFont(textArea.getFont().deriveFont(Font.BOLD));
                        g.setColor(new Color(40, 40, 40));
                    } else {
                        g.setFont(textArea.getFont());
                        g.setColor(new Color(110, 110, 110));
                    }
                    
                    g.drawString(stringLabel, alignedX, computedY + 2);
                }
                computedY += lineHeight;
            }
        }
    }
}