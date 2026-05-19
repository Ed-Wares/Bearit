package com.edwares;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;

public class TextEditorFrame extends JFrame {
    private static final int SCROLL_RESOLUTION = 10000;

    private final JTextArea textArea;
    private final JFileChooser fileChooser;
    private final LineNumberPanel lineNumberPanel;
    private final JScrollPane scrollPane;
    
    private final JLabel lblStatus;
    private final JLabel lblLoadingStatus;
    private final JLabel lblCursorInfo;
    
    private final JScrollBar globalScrollBar;
    private boolean isSyncingScroll = false;
    private boolean isLoadingChunk = false;

    private final LargeFileManager fileManager;
    private boolean isDirty = false;
    private boolean isNavigating = false; 

    private int loadedChunkIndex = 0;
    private int pendingTargetChunk = -1;
    private double pendingLocalPercent = -1;
    private boolean pendingPreviewRequest = false;
    
    private boolean isCurrentlyPreview = false;
    private double lastRequestedLocalPercent = -1;
    private final Timer settleTimer; 

    public TextEditorFrame() {
        this.fileManager = new LargeFileManager();
        
        setTitle("Bearit Text Editor - Untitled");
        setSize(950, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        settleTimer = new Timer(350, e -> {
            if (isCurrentlyPreview && !isLoadingChunk && !isNavigating) {
                triggerAsyncLoad(loadedChunkIndex, 0, lastRequestedLocalPercent, false);
            }
        });
        settleTimer.setRepeats(false);

        JPanel mainPanel = new JPanel(new BorderLayout());

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

        mainPanel.add(scrollPane, BorderLayout.CENTER);

        globalScrollBar = new JScrollBar(JScrollBar.VERTICAL, 0, 1, 0, SCROLL_RESOLUTION);
        globalScrollBar.addAdjustmentListener(e -> {
            if (!isSyncingScroll && !isNavigating) {
                syncGlobalToLocalScroll();
            }
        });
        mainPanel.add(globalScrollBar, BorderLayout.EAST);

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
        mainPanel.add(statusBar, BorderLayout.SOUTH);

        add(mainPanel);
        fileChooser = new JFileChooser();
        setupMenuBar();
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

        // --- UPDATED JUMP LOGIC: Handles local chunk repositioning ---
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.CTRL_DOWN_MASK), "jumpStart");
        am.put("jumpStart", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (loadedChunkIndex == 0 && !isCurrentlyPreview) {
                    // Already in the first chunk, just snap to the top locally
                    SwingUtilities.invokeLater(() -> {
                        textArea.setCaretPosition(0); 
                        scrollPane.getVerticalScrollBar().setValue(0);
                        lineNumberPanel.setCurrentLine(0);
                        syncLocalToGlobalScroll();
                    });
                } else {
                    triggerAsyncLoad(0, 1, -1, false);
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK), "jumpEnd");
        am.put("jumpEnd", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                int targetChunk = Math.max(0, fileManager.getTotalChunks() - 1);
                if (loadedChunkIndex == targetChunk && !isCurrentlyPreview) {
                    // Already in the last chunk, just snap to the bottom locally
                    SwingUtilities.invokeLater(() -> {
                        textArea.setCaretPosition(textArea.getDocument().getLength());
                        JScrollBar vbar = scrollPane.getVerticalScrollBar();
                        vbar.setValue(vbar.getMaximum());
                        lineNumberPanel.setCurrentLine(textArea.getLineCount() - 1);
                        syncLocalToGlobalScroll();
                    });
                } else {
                    triggerAsyncLoad(targetChunk, -1, -1, false);
                }
            }
        });
    }

    private void triggerAsyncLoad(int targetChunk, int direction, double localPercentForScroll, boolean requestPreview) {
        if (isNavigating) return;
        settleTimer.stop(); 

        int total = Math.max(1, fileManager.getTotalChunks());
        if (targetChunk >= total) targetChunk = total - 1;
        if (targetChunk < 0) targetChunk = 0;

        // The abort guard that was ignoring your shortcut when inside the chunk
        if (targetChunk == loadedChunkIndex && !isCurrentlyPreview && !requestPreview) return; 

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
                        applyStateUpdates(state, direction, localPercentForScroll);
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
                triggerAsyncLoad(targetChunk, 0, localPercent, true); 
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

    public void loadInitialFile(File file) {
        fileManager.setFile(file);
        isDirty = false;
        loadedChunkIndex = 0;
        pendingTargetChunk = -1;
        try {
            applyStateUpdates(fileManager.loadCurrentChunk(false), 1, -1);
        } catch (IOException ex) {
            showError("Failed to open command line file argument: " + ex.getMessage());
        }
    }

    private void applyStateUpdates(LargeFileManager.ChunkState state, int direction, double localPercentForScroll) {
        isNavigating = true; 
        loadedChunkIndex = state.chunkIndex();
        isCurrentlyPreview = state.isPreview();
        
        textArea.setEditable(!isCurrentlyPreview);
        textArea.setText(state.content());
        lineNumberPanel.setStartLine(state.startLine());
        setTitle("Bearit Text Editor - " + state.fileName() + (isCurrentlyPreview ? " [PREVIEW]" : ""));
        
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
                    triggerAsyncLoad(nextTarget, 0, nextPercent, nextPreview);
                } else {
                    pendingTargetChunk = -1;
                    syncLocalToGlobalScroll(); 
                }
            } else {
                syncLocalToGlobalScroll();
            }
        });
    }

    private void triggerAutoNavigate(int direction) {
        if (isLoadingChunk || isNavigating) return;
        triggerAsyncLoad(loadedChunkIndex + direction, direction, -1, false);
    }

    private void performNew() {
        fileManager.setNewFile();
        isDirty = false;
        isNavigating = true;
        loadedChunkIndex = 0;
        pendingTargetChunk = -1;
        textArea.setText("");
        isNavigating = false;
        lblStatus.setText("New file creation mode.");
        lineNumberPanel.setStartLine(1);
        setTitle("Bearit Text Editor - Untitled");
        globalScrollBar.setValue(0);
        globalScrollBar.setMaximum(SCROLL_RESOLUTION + globalScrollBar.getVisibleAmount());
        updateCursorStatus();
    }

    private void performOpen() {
        int option = fileChooser.showOpenDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            loadInitialFile(fileChooser.getSelectedFile());
        }
    }

    private void performSave() {
        if (!fileManager.hasFile()) {
            int option = fileChooser.showSaveDialog(this);
            if (option == JFileChooser.APPROVE_OPTION) {
                fileManager.setCurrentFile(fileChooser.getSelectedFile());
            } else {
                return;
            }
        }

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
                    applyStateUpdates(get(), 0, -1);
                } catch (Exception ex) {
                    showError("Streaming save operation failure: " + ex.getMessage());
                } finally {
                    lblLoadingStatus.setText("");
                    isNavigating = false;
                }
            }
        }.execute();
    }

    private void setupMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        JMenuItem newItem = new JMenuItem("New");
        JMenuItem openItem = new JMenuItem("Open...");
        JMenuItem saveItem = new JMenuItem("Save Current File");
        JMenuItem exitItem = new JMenuItem("Exit");

        newItem.addActionListener(e -> performNew());
        openItem.addActionListener(e -> performOpen());
        saveItem.addActionListener(e -> performSave());
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);
        setJMenuBar(menuBar);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "System IO Exception Error", JOptionPane.ERROR_MESSAGE);
    }

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