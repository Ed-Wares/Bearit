package com.edwares;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final JLabel lblFontInfo;
    private final JLabel lblIndexingStatus; // Background tracking label
    private final JProgressBar chunkLoadProgressBar; 
    private final JScrollBar globalScrollBar;
    
    private final LargeFileManager fileManager;
    private File activeFile = null;

    // Tracks the active load task so we can kill it if the user scrolls past it
    private SwingWorker<LargeFileManager.ChunkState, Void> activeChunkWorker = null;

    // --- Global Undo Architecture ---
    private final GlobalUndoManager globalUndoManager = new GlobalUndoManager();
    
    // LRU Cache: Keeps up to 40 Documents (1GB max) in RAM to preserve their Undo history safely
    private final Map<Integer, Document> documentCache = new LinkedHashMap<Integer, Document>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Document> eldest) {
            if (size() > 40) {
                // If we exceed 40 edited chunks, we clear global history to prevent a 4GB OOM crash
                globalUndoManager.discardAllEdits();
                return true; 
            }
            return false;
        }
    };

    private boolean isSyncingScroll = false;
    private boolean isLoadingChunk = false;
    private boolean isDirty = false;
    private boolean hasUnsavedChanges = false; // Tracks global file edits
    private boolean isNavigating = false; 
    
    // Tracks intelligent focus state to survive chained background loads
    private boolean wasEditorFocused = false; 

    private int loadedChunkIndex = 0;
    private int pendingTargetChunk = -1;
    private double pendingLocalPercent = -1;
    private boolean pendingPreviewRequest = false;
    
    private boolean isCurrentlyPreview = false;
    private double lastRequestedLocalPercent = -1;
    private final Timer settleTimer; 
    private long currentChunkStartOffset = 0;

    private String currentTitle = "Untitled";

    private JDialog searchDialog;
    private JTextField txtSearch;
    private JTextField txtReplace;

    private final DocumentListener editorDocumentListener = new DocumentListener() {
        public void insertUpdate(DocumentEvent e) { registerEdit(); }
        public void removeUpdate(DocumentEvent e) { registerEdit(); }
        public void changedUpdate(DocumentEvent e) { registerEdit(); }
        
        private void registerEdit() {
            if (!isNavigating && !isCurrentlyPreview) {
                isDirty = true;
                setUnsavedChanges(true); // Flag the entire file as unsaved
            }
            lineNumberPanel.adjustMetricSizing();
            if (!isNavigating) syncLocalToGlobalScroll();
        }
    };

    public AdvancedTextEditorPanel() {
        setLayout(new BorderLayout());
        this.fileManager = new LargeFileManager();
        
        globalUndoManager.setLimit(2500);

        settleTimer = new Timer(350, e -> {
            if (isCurrentlyPreview && !isLoadingChunk && !isNavigating) {
                triggerAsyncLoad(loadedChunkIndex, 0, lastRequestedLocalPercent, false, null);
            }
        });
        settleTimer.setRepeats(false);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        
        JPanel leftStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        lblStatus = new JLabel("No file active.");
        lblLoadingStatus = new JLabel("");
        lblLoadingStatus.setFont(lblLoadingStatus.getFont().deriveFont(Font.BOLD));
        lblLoadingStatus.setForeground(new Color(220, 100, 0)); 
        
        chunkLoadProgressBar = new JProgressBar();
        chunkLoadProgressBar.setIndeterminate(true);
        chunkLoadProgressBar.setPreferredSize(new Dimension(100, 14));
        chunkLoadProgressBar.setVisible(false);
        
        leftStatusPanel.add(lblStatus);
        leftStatusPanel.add(lblLoadingStatus);
        leftStatusPanel.add(chunkLoadProgressBar);
        
        JPanel rightStatusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        lblIndexingStatus = new JLabel(""); 
        lblIndexingStatus.setForeground(new Color(120, 120, 120)); // Subtle grey
        lblFontInfo = new JLabel("Font: 14pt"); 
        lblCursorInfo = new JLabel("Line: 1 | Pos: 0");
        
        rightStatusPanel.add(lblIndexingStatus);
        rightStatusPanel.add(lblFontInfo);
        rightStatusPanel.add(lblCursorInfo);
        
        statusBar.add(leftStatusPanel, BorderLayout.WEST);
        statusBar.add(rightStatusPanel, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

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
        
        // Intelligent Focus Listener ---
        textArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                wasEditorFocused = true;
            }
            @Override
            public void focusLost(FocusEvent e) {
                // Only mark focus as lost if the user explicitly clicked away.
                // If isEnabled() is false, the focus loss was artificially caused by the chunk loader locking the UI.
                if (textArea.isEnabled()) {
                    wasEditorFocused = false;
                }
                textArea.getCaret().setSelectionVisible(true);
            }
        });

        lineNumberPanel = new LineNumberPanel(this, textArea);

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
        
        setWordWrap(BearitProperties.getInstance().isWordWrap());
        
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!isSyncingScroll && !isNavigating && !isLoadingChunk) {
                syncLocalToGlobalScroll();
            }
        });

        scrollPane.addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                if (e.getWheelRotation() < 0) {
                    adjustFontSize(2); 
                } else {
                    adjustFontSize(-2); 
                }
            } else if (!isLoadingChunk && !isNavigating) {
                JScrollBar vBar = scrollPane.getVerticalScrollBar();
                if (e.getWheelRotation() > 0 && vBar.getValue() + vBar.getVisibleAmount() >= vBar.getMaximum()) {
                    triggerAutoNavigate(1);
                } else if (e.getWheelRotation() < 0 && vBar.getValue() <= 0) {
                    triggerAutoNavigate(-1);
                }
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

    }
    
    public void adjustFontSize(int delta) {
        Font current = textArea.getFont();
        int newSize = Math.max(6, Math.min(72, current.getSize() + delta)); 
        setFont(current.deriveFont((float) newSize));
        BearitProperties.getInstance().setFontSize(newSize);
    }

    public void setFont(Font font) {
        if (textArea != null) {
            textArea.setFont(font);
            if (lineNumberPanel != null) {
                lineNumberPanel.setFont(lineNumberPanel.getFont().deriveFont(font.getSize2D()));
                lineNumberPanel.adjustMetricSizing();
            }
            if (lblFontInfo != null) {
                lblFontInfo.setText("Font: " + font.getSize() + "pt");
            }
        }
    }

    public void setWordWrap(boolean wrap) {
        textArea.setLineWrap(wrap);
        textArea.setWrapStyleWord(wrap);
        scrollPane.setHorizontalScrollBarPolicy(wrap ? JScrollPane.HORIZONTAL_SCROLLBAR_NEVER : JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        lineNumberPanel.revalidate();
        lineNumberPanel.repaint();
    }

    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges;
    }

    private void setUnsavedChanges(boolean b) {
        if (this.hasUnsavedChanges != b) {
            boolean old = this.hasUnsavedChanges;
            this.hasUnsavedChanges = b;
            firePropertyChange("unsavedChanges", old, b);
        }
    }

    /**
     * Helper to safely append the hidden boundary newline before committing
     * edits to the File Manager, ensuring chunks don't accidentally merge.
     */    
    private String getCommitText() {
        String text = textArea.getText();
        if (loadedChunkIndex < fileManager.getTotalChunks() - 1) {
            return text + "\n";
        }
        return text;
    }

    private void updateCursorStatus() {
        if (isCurrentlyPreview) return;
        try {
            int dot = textArea.getCaret().getDot();
            int mark = textArea.getCaret().getMark();
            int localLine = textArea.getLineOfOffset(dot);
            long absoluteLine = lineNumberPanel.getStartLine() + localLine;
            int col = dot - textArea.getLineStartOffset(localLine);

            long absDot = currentChunkStartOffset + dot;
            long absMark = currentChunkStartOffset + mark;

            if (dot == mark) {
                lblCursorInfo.setText(String.format("Line: %d | Col: %d | Pos: %d", absoluteLine, col, absDot));
            } else {
                long selStart = Math.min(absDot, absMark);
                long selEnd = Math.max(absDot, absMark);
                long width = selEnd - selStart; 
                lblCursorInfo.setText(String.format("Line: %d | Col: %d | Sel: %d - %d (width: %d)", absoluteLine, col, selStart, selEnd, width));
            }
        } catch (Exception e) {}
    }

    public void showSearchDialog() {
        if (searchDialog == null) {
            Window parentWindow = SwingUtilities.getWindowAncestor(this);
            searchDialog = new JDialog(parentWindow, "🔍 Search & Replace");
            searchDialog.setModal(false);
            searchDialog.setAlwaysOnTop(true);
            searchDialog.setResizable(false); 
            searchDialog.setLayout(new BorderLayout());
            
            JPanel inputPanel = new JPanel(new GridBagLayout());
            inputPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 10, 15));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(5, 5, 5, 5);
            
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
            inputPanel.add(new JLabel("Find:"), gbc);
            
            gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
            txtSearch = new JTextField(25);
            inputPanel.add(txtSearch, gbc);
            
            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
            inputPanel.add(new JLabel("Replace:"), gbc);
            
            gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0;
            txtReplace = new JTextField(25);
            inputPanel.add(txtReplace, gbc);
            
            JPanel pnlBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton btnFindPrev = new JButton("⬆ Previous");
            JButton btnFindNext = new JButton("⬇ Next");
            JButton btnCount = new JButton("Count Matches");
            JButton btnReplace = new JButton("Replace");
            JButton btnReplaceAll = new JButton("Replace All");
            
            txtSearch.addActionListener(e -> performFindNext(txtSearch.getText())); // Pressing Enter in the search field triggers "Find Next"
            txtReplace.addActionListener(e -> performReplace(txtSearch.getText(), txtReplace.getText())); // Pressing Enter in the replace field triggers "Replace"
            
            btnFindPrev.addActionListener(e -> performFindPrevious(txtSearch.getText()));
            btnFindNext.addActionListener(e -> performFindNext(txtSearch.getText()));
            btnReplace.addActionListener(e -> performReplace(txtSearch.getText(), txtReplace.getText()));
            btnCount.addActionListener(e -> performCountMatches(txtSearch.getText()));
            btnReplaceAll.addActionListener(e -> performReplaceAll(txtSearch.getText(), txtReplace.getText()));
            
            pnlBtns.add(btnFindPrev);
            pnlBtns.add(btnFindNext);
            pnlBtns.add(btnCount);
            pnlBtns.add(btnReplace);
            pnlBtns.add(btnReplaceAll);
            
            searchDialog.add(inputPanel, BorderLayout.CENTER);
            searchDialog.add(pnlBtns, BorderLayout.SOUTH);
            searchDialog.pack();
            searchDialog.setLocationRelativeTo(this);
        }
        searchDialog.setVisible(true);
        txtSearch.requestFocus();
        if (!txtSearch.getText().isEmpty()) {
            txtSearch.selectAll();
        }
    }

    public void showGotoLineDialog() {
        String input = JOptionPane.showInputDialog(this, "Enter Destination Line Number:", "Go To Line", JOptionPane.QUESTION_MESSAGE);
        if (input != null && !input.trim().isEmpty()) {
            try {
                long targetLine = Long.parseLong(input.trim());
                lblLoadingStatus.setText("Searching for line position...");
                
                String commitText = getCommitText();
                boolean wasDirty = isDirty;
                isDirty = false;
                
                new SwingWorker<Integer, Void>() {
                    @Override
                    protected Integer doInBackground() throws Exception {
                        if (wasDirty && !isCurrentlyPreview) {
                            fileManager.commitCurrentChunk(commitText);
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
        if (textArea.getSelectedText() != null && textArea.getSelectedText().equalsIgnoreCase(target)) {
            caret = textArea.getSelectionEnd();
        }

        String text = textArea.getText();
        int idx = text.indexOf(target, caret);
        
        if (idx != -1) {
            textArea.setCaretPosition(idx);
            textArea.moveCaretPosition(idx + target.length());
            return;
        }
        
        lblLoadingStatus.setText("Scanning file for next match...");
        
        String commitText = getCommitText();
        boolean wasDirty = isDirty;
        isDirty = false;
        
        new SwingWorker<Integer, Void>() {
            int foundIdx = -1;
            @Override
            protected Integer doInBackground() throws Exception {
                if (wasDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(commitText);
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
                        });
                    } else {
                        int response = JOptionPane.showConfirmDialog(getDialogParent(), 
                            "Reached end of file. Start again from the top?", 
                            "Search Wrap Around", 
                            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                            
                        if (response == JOptionPane.YES_OPTION) {
                            triggerAsyncLoad(0, 1, -1, false, () -> {
                                textArea.setCaretPosition(0);
                                performFindNext(target);
                            });
                        }
                    }
                } catch (Exception e) {}
            }
        }.execute();
    }

    private void performFindPrevious(String target) {
        if (target == null || target.isEmpty()) return;

        int caret = textArea.getSelectionStart();
        String text = textArea.getText().substring(0, caret);
        int idx = text.lastIndexOf(target);
        
        if (idx != -1) {
            textArea.setCaretPosition(idx);
            textArea.moveCaretPosition(idx + target.length());
            return;
        }
        
        lblLoadingStatus.setText("Scanning file for previous match...");
        
        String commitText = getCommitText();
        boolean wasDirty = isDirty;
        isDirty = false;
        
        new SwingWorker<Integer, Void>() {
            int foundIdx = -1;
            @Override
            protected Integer doInBackground() throws Exception {
                if (wasDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(commitText);
                }
                for (int i = loadedChunkIndex - 1; i >= 0; i--) {
                    String content = fileManager.getChunkContent(i);
                    int match = content.lastIndexOf(target);
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
                        });
                    } else {
                        int response = JOptionPane.showConfirmDialog(getDialogParent(), 
                            "Reached beginning of file. Search again from the bottom?", 
                            "Search Wrap Around", 
                            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                            
                        if (response == JOptionPane.YES_OPTION) {
                            int lastChunk = Math.max(0, fileManager.getTotalChunks() - 1);
                            triggerAsyncLoad(lastChunk, -1, -1, false, () -> {
                                textArea.setCaretPosition(textArea.getDocument().getLength());
                                performFindPrevious(target);
                            });
                        }
                    }
                } catch (Exception e) {}
            }
        }.execute();
    }

    private void performReplace(String target, String replacement) {
        if (target == null || target.isEmpty()) return;
        String selected = textArea.getSelectedText();
        if (selected != null && selected.equalsIgnoreCase(target) && !isCurrentlyPreview) {
            textArea.replaceSelection(replacement);
        }
        performFindNext(target);
    }

    private void performCountMatches(String target) {
        if (target == null || target.isEmpty()) return;
        lblLoadingStatus.setText("Running full file match count...");
        
        String commitText = getCommitText();
        boolean wasDirty = isDirty;
        isDirty = false;
        
        new SwingWorker<Long, Void>() {
            @Override
            protected Long doInBackground() throws Exception {
                if (wasDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(commitText);
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
        
        String commitText = getCommitText();
        boolean wasDirty = isDirty;
        isDirty = false;
        
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (wasDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(commitText);
                }
                fileManager.replaceAllGlobal(target, replacement);
                return null;
            }
            @Override
            protected void done() {
                documentCache.clear(); 
                globalUndoManager.discardAllEdits();
                setUnsavedChanges(true); // Flag global modification
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

    public void undo() { 
        if (!globalUndoManager.canUndo()) return;
        int targetChunk = globalUndoManager.getUndoChunk();
        if (targetChunk != -1 && targetChunk != loadedChunkIndex) {
            triggerAsyncLoad(targetChunk, 0, -1, false, () -> {
                globalUndoManager.undo();
                textArea.requestFocus();
            });
        } else {
            globalUndoManager.undo();
            textArea.requestFocus();
        }
    }

    public void redo() { 
        if (!globalUndoManager.canRedo()) return;
        int targetChunk = globalUndoManager.getRedoChunk();
        if (targetChunk != -1 && targetChunk != loadedChunkIndex) {
            triggerAsyncLoad(targetChunk, 0, -1, false, () -> {
                globalUndoManager.redo();
                textArea.requestFocus();
            });
        } else {
            globalUndoManager.redo();
            textArea.requestFocus();
        }
    }

    public void createNewDocument() {
        fileManager.setNewFile();
        activeFile = null;
        isDirty = false;
        setUnsavedChanges(false); 
        isNavigating = true;
        loadedChunkIndex = 0;
        pendingTargetChunk = -1;
        
        lblIndexingStatus.setText(""); // Clear status for new files
        
        documentCache.clear();
        globalUndoManager.discardAllEdits();
        Document newDoc = new PlainDocument();
        newDoc.addDocumentListener(editorDocumentListener);
        newDoc.addUndoableEditListener(e -> {
            if (!isNavigating && !isCurrentlyPreview) {
                globalUndoManager.addEdit(new ChunkAwareEdit(loadedChunkIndex, e.getEdit()));
            }
        });
        textArea.setDocument(newDoc);
        documentCache.put(0, newDoc);

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
        
        lblIndexingStatus.setText("⚙ Indexing lines: 0%");
        
        // Callback automatically fires as background indexer progresses
        fileManager.buildIndexCacheAsync((indexedChunk) -> {
            int total = Math.max(1, fileManager.getTotalChunks());
            int pct = (int) (((indexedChunk + 1) * 100.0) / total);
            
            if (indexedChunk < total - 1) {
                lblIndexingStatus.setText("⚙ Indexing lines: " + pct + "%");
            } else {
                lblIndexingStatus.setText(""); // Complete, hide label
            }
            
            // If the chunk we are looking at was just processed, correct the line numbers instantly!
            if (indexedChunk == loadedChunkIndex) {
                long exactLine = fileManager.getExactLineOffset(loadedChunkIndex);
                if (exactLine != -1 && exactLine != lineNumberPanel.getStartLine()) {
                    lineNumberPanel.setStartLine(exactLine);
                    updateCursorStatus();
                    lineNumberPanel.repaint(); // Force clean redraw
                }
            }
        }); 

        isDirty = false;
        setUnsavedChanges(false);
        loadedChunkIndex = 0;
        pendingTargetChunk = -1;
        
        documentCache.clear();
        globalUndoManager.discardAllEdits();
        
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

    public boolean hasActiveFile() { return fileManager.hasFile(); }
    public File getActiveFile() { return activeFile; }
    public String getCurrentTitle() { return currentTitle; }

    private void executeSaveRoutine() {
        lblLoadingStatus.setText("Saving file...");
        isNavigating = true; 
        
        String saveText = isCurrentlyPreview ? "" : getCommitText();
        
        new SwingWorker<LargeFileManager.ChunkState, Void>() {
            @Override
            protected LargeFileManager.ChunkState doInBackground() throws Exception {
                return fileManager.saveAll(saveText);
            }
            @Override
            protected void done() {
                try {
                    isDirty = false;
                    setUnsavedChanges(false);
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

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "showSearch");
        am.put("showSearch", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { showSearchDialog(); }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK), "showGotoLine");
        am.put("showGotoLine", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { showGotoLineDialog(); }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        am.put("undo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { undo(); }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        am.put("redo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { redo(); }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), "zoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, InputEvent.CTRL_DOWN_MASK), "zoomInNumPad");
        am.put("zoomIn", new AbstractAction() { public void actionPerformed(ActionEvent e) { adjustFontSize(2); }});
        am.put("zoomInNumPad", new AbstractAction() { public void actionPerformed(ActionEvent e) { adjustFontSize(2); }});

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "zoomOut");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, InputEvent.CTRL_DOWN_MASK), "zoomOutNumPad");
        am.put("zoomOut", new AbstractAction() { public void actionPerformed(ActionEvent e) { adjustFontSize(-2); }});
        am.put("zoomOutNumPad", new AbstractAction() { public void actionPerformed(ActionEvent e) { adjustFontSize(-2); }});

        // --- Wrap-Around Keyboard Navigation Routing ---
        bindWrapAroundNavigation(im, am, KeyEvent.VK_DOWN, 1);
        bindWrapAroundNavigation(im, am, KeyEvent.VK_PAGE_DOWN, 1);
        bindWrapAroundNavigation(im, am, KeyEvent.VK_UP, -1);
        bindWrapAroundNavigation(im, am, KeyEvent.VK_PAGE_UP, -1);
    }

    private void bindWrapAroundNavigation(InputMap im, ActionMap am, int keyCode, int direction) {
        KeyStroke keyStroke = KeyStroke.getKeyStroke(keyCode, 0);
        Object origActionKey = im.get(keyStroke);
        Action originalAction = am.get(origActionKey);

        String customKey = "customWrapNav_" + keyCode;
        im.put(keyStroke, customKey);
        am.put(customKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isLoadingChunk || isNavigating) return;

                int caretPos = textArea.getCaretPosition();
                int currentLine = 0;
                int totalLines = 0;

                try {
                    currentLine = textArea.getLineOfOffset(caretPos);
                    totalLines = textArea.getLineCount();
                } catch (Exception ex) {
                    if (direction > 0 && caretPos == textArea.getDocument().getLength() && loadedChunkIndex < fileManager.getTotalChunks() - 1) {
                        triggerAsyncLoad(loadedChunkIndex + 1, direction, -1, false, null);
                    } else if (direction < 0 && caretPos == 0 && loadedChunkIndex > 0) {
                        triggerAsyncLoad(loadedChunkIndex - 1, direction, -1, false, null);
                    } else if (originalAction != null) {
                        originalAction.actionPerformed(e);
                    }
                    return;
                }

                // Trigger chunk load if on the boundary lines, otherwise rely on native cursor movements
                if (direction > 0 && currentLine == totalLines - 1 && loadedChunkIndex < fileManager.getTotalChunks() - 1) {
                    triggerAsyncLoad(loadedChunkIndex + 1, direction, -1, false, null);
                } else if (direction < 0 && currentLine == 0 && loadedChunkIndex > 0) {
                    triggerAsyncLoad(loadedChunkIndex - 1, direction, -1, false, null);
                } else if (originalAction != null) {
                    originalAction.actionPerformed(e);
                }
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

        // Immediately abort any pending disk reads if the user keeps scrolling ---
        if (activeChunkWorker != null && !activeChunkWorker.isDone()) {
            activeChunkWorker.cancel(true);
        }

        wasEditorFocused = textArea.isFocusOwner();

        isLoadingChunk = true;
        textArea.setEnabled(false); 
        chunkLoadProgressBar.setVisible(true); 
        lastRequestedLocalPercent = localPercentForScroll;
        
        if (requestPreview) {
            lblLoadingStatus.setText("Previewing chunk " + (targetChunk + 1) + "...");
        } else {
            lblLoadingStatus.setText("Loading full chunk " + (targetChunk + 1) + "...");
        }
        lblStatus.setText(String.format("Chunk %d of %d", targetChunk + 1, total));

        String currentText = getCommitText();
        boolean wasDirty = isDirty;
        isDirty = false;

        final int finalTarget = targetChunk;
        
        // Assign to the tracking variable
        activeChunkWorker = new SwingWorker<LargeFileManager.ChunkState, Void>() {
            @Override
            protected LargeFileManager.ChunkState doInBackground() throws Exception {
                if (wasDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(currentText);
                }
                // If cancelled before we even start the heavy IO, abort.
                if (isCancelled()) throw new java.util.concurrent.CancellationException();
                return fileManager.navigateToIndex(finalTarget, requestPreview);
            }

            @Override
            protected void done() {
                try {
                    // If the user scrolled past this chunk, quietly exit without updating the UI
                    if (isCancelled()) return; 
                    
                    LargeFileManager.ChunkState state = get();
                    if (state != null) {
                        applyStateUpdates(state, direction, localPercentForScroll, postLoadAction);
                    } else {
                        unlockUI();
                    }
                } catch (java.util.concurrent.CancellationException ce) {
                    // Expected behavior when scrolling fast, do nothing.
                } catch (Exception e) {
                    if (!isCancelled()) {
                        showError("Chunk load failure: " + e.getMessage());
                        lblLoadingStatus.setText("");
                        unlockUI();
                    }
                }
            }
            
            private void unlockUI() {
                isLoadingChunk = false;
                textArea.setEnabled(true);
                chunkLoadProgressBar.setVisible(false);
                if (wasEditorFocused && !isCurrentlyPreview) textArea.requestFocusInWindow();
            }
        };
        
        activeChunkWorker.execute();
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
        currentChunkStartOffset = state.startOffset();
        
        Document doc = documentCache.get(loadedChunkIndex);
        
        if (doc != null && !isCurrentlyPreview) {
            textArea.setDocument(doc);
        } else {
            Document newDoc = new PlainDocument();
            try {
                String content = state.content();
                // Strip the trailing newline used strictly for chunk file boundaries
                if (state.hasNext()) {
                    if (content.endsWith("\r\n")) {
                        content = content.substring(0, content.length() - 2);
                    } else if (content.endsWith("\n")) {
                        content = content.substring(0, content.length() - 1);
                    }
                }
                newDoc.insertString(0, content, null);
            } catch (Exception ex) {}
            
            newDoc.addDocumentListener(editorDocumentListener);
            newDoc.addUndoableEditListener(e -> {
                if (!isNavigating && !isCurrentlyPreview) {
                    globalUndoManager.addEdit(new ChunkAwareEdit(loadedChunkIndex, e.getEdit()));
                }
            });
            
            textArea.setDocument(newDoc);
            
            if (!isCurrentlyPreview) {
                documentCache.put(loadedChunkIndex, newDoc);
            }
        }
        
        textArea.setEnabled(true);
        textArea.setEditable(!isCurrentlyPreview);
        chunkLoadProgressBar.setVisible(false);

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
                
                if (wasEditorFocused && !isCurrentlyPreview) {
                    textArea.requestFocusInWindow();
                }
                
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

    // --- Embedded Global Undo Handlers ---

    private static class ChunkAwareEdit implements UndoableEdit {
        final int chunkIndex;
        final UndoableEdit inner;

        public ChunkAwareEdit(int chunkIndex, UndoableEdit inner) {
            this.chunkIndex = chunkIndex;
            this.inner = inner;
        }

        @Override public void undo() throws CannotUndoException { inner.undo(); }
        @Override public boolean canUndo() { return inner.canUndo(); }
        @Override public void redo() throws CannotRedoException { inner.redo(); }
        @Override public boolean canRedo() { return inner.canRedo(); }
        @Override public void die() { inner.die(); }
        
        @Override public boolean addEdit(UndoableEdit anEdit) {
            if (anEdit instanceof ChunkAwareEdit) {
                ChunkAwareEdit other = (ChunkAwareEdit) anEdit;
                if (this.chunkIndex == other.chunkIndex) {
                    return inner.addEdit(other.inner);
                }
            }
            return false;
        }
        
        @Override public boolean replaceEdit(UndoableEdit anEdit) {
            if (anEdit instanceof ChunkAwareEdit) {
                ChunkAwareEdit other = (ChunkAwareEdit) anEdit;
                if (this.chunkIndex == other.chunkIndex) {
                    return inner.replaceEdit(other.inner);
                }
            }
            return false;
        }
        
        @Override public boolean isSignificant() { return inner.isSignificant(); }
        @Override public String getPresentationName() { return inner.getPresentationName(); }
        @Override public String getUndoPresentationName() { return inner.getUndoPresentationName(); }
        @Override public String getRedoPresentationName() { return inner.getRedoPresentationName(); }
    }

    /*  Instead of isolating history to a single chunk and losing it when you scroll away, we will keep up to 40 chunks (1GB of RAM) active in 
    an LRU (Least Recently Used) cache. The GlobalUndoManager will intercept every keystroke, tag it with its chunk index, and store it in a single unified timeline. */
    private static class GlobalUndoManager extends UndoManager {
        public int getUndoChunk() {
            UndoableEdit edit = editToBeUndone();
            if (edit instanceof ChunkAwareEdit) {
                return ((ChunkAwareEdit)edit).chunkIndex;
            }
            return -1;
        }
        
        public int getRedoChunk() {
            UndoableEdit edit = editToBeRedone();
            if (edit instanceof ChunkAwareEdit) {
                return ((ChunkAwareEdit)edit).chunkIndex;
            }
            return -1;
        }
    }

    private static class LineNumberPanel extends JPanel {
        private final JTextArea textArea;
        private final AdvancedTextEditorPanel parent; // Access parent to check indexing
        private long startLine = 1;
        private int currentLocalLine = 0;

        public LineNumberPanel(AdvancedTextEditorPanel parent, JTextArea textArea) {
            this.parent = parent;
            this.textArea = textArea;
            setBackground(new Color(245, 245, 245));
            setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.LIGHT_GRAY));
            
            // Force repaint when indexing status changes ---
            parent.lblIndexingStatus.addPropertyChangeListener("text", evt -> repaint());

            textArea.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    revalidate();
                    repaint();
                }
            });
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
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
            int totalLines = textArea.getLineCount();
            int maximumDigits = String.valueOf(startLine + totalLines).length() + 2; // +2 for "~ "
            int functionalWidth = fm.stringWidth("0") * Math.max(maximumDigits, 4) + 12;
            
            return new Dimension(functionalWidth, textArea.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            FontMetrics fm = g.getFontMetrics(textArea.getFont());
            Rectangle viewportClip = getVisibleRect(); 

            // Check if indexing is currently active via the status label
            boolean isIndexing = parent.lblIndexingStatus.getText().contains("⚙");

            try {
                int startOffset = textArea.viewToModel2D(new Point(0, viewportClip.y));
                int endOffset = textArea.viewToModel2D(new Point(0, viewportClip.y + viewportClip.height + fm.getHeight()));
                
                int startLineIdx = textArea.getLineOfOffset(startOffset);
                int endLineIdx = textArea.getLineOfOffset(endOffset);

                for (int i = startLineIdx; i <= endLineIdx; i++) {
                    int offset = textArea.getLineStartOffset(i);
                    Rectangle r = textArea.modelToView2D(offset).getBounds();
                    
                    if (r.y + r.height < viewportClip.y) continue;
                    if (r.y > viewportClip.y + viewportClip.height) break;
                    
                    // --- Add ~ symbol if indexing is still in progress ---
                    String stringLabel = (isIndexing ? "~" : "") + String.valueOf(startLine + i);
                    int alignedX = getWidth() - fm.stringWidth(stringLabel) - 6;
                    
                    if (i == currentLocalLine) {
                        g.setFont(textArea.getFont().deriveFont(Font.BOLD));
                        g.setColor(new Color(40, 40, 40));
                    } else {
                        g.setFont(textArea.getFont());
                        g.setColor(new Color(110, 110, 110));
                    }
                    
                    g.drawString(stringLabel, alignedX, r.y + fm.getAscent());
                }
            } catch (Exception e) {}
        }
    }
}