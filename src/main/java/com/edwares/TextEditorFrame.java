package com.edwares;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;

public class TextEditorFrame extends JFrame {
    private static final int SCROLL_RESOLUTION = 10000; // Granularity for the global scroll thumb

    private final JTextArea textArea;
    private final JFileChooser fileChooser;
    private final LineNumberPanel lineNumberPanel;
    private final JScrollPane scrollPane;
    private final JLabel lblStatus;
    
    // Unified Global Scrolling Components
    private final JScrollBar globalScrollBar;
    private boolean isSyncingScroll = false;

    private final LargeFileManager fileManager;
    private boolean isDirty = false;
    private boolean isNavigating = false;

    public TextEditorFrame() {
        this.fileManager = new LargeFileManager();
        
        setTitle("Bearit Text Editor - Untitled");
        setSize(950, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout());

        textArea = new JTextArea();
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        textArea.setLineWrap(false);

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { registerEdit(); }
            public void removeUpdate(DocumentEvent e) { registerEdit(); }
            public void changedUpdate(DocumentEvent e) { registerEdit(); }
            
            private void registerEdit() {
                if (!isNavigating) isDirty = true;
                lineNumberPanel.adjustMetricSizing();
                syncLocalToGlobalScroll();
            }
        });

        setupKeyboardShortcuts();

        scrollPane = new JScrollPane(textArea);
        // Hide the local scrollbar and implement our own global track
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        lineNumberPanel = new LineNumberPanel(textArea);
        scrollPane.setRowHeaderView(lineNumberPanel);
        
        // Sync local scrolling events (mouse wheel, arrow keys) to the global scrollbar
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!isSyncingScroll) syncLocalToGlobalScroll();
        });

        scrollPane.addMouseWheelListener(e -> {
            JScrollBar vBar = scrollPane.getVerticalScrollBar();
            if (e.getWheelRotation() > 0 && vBar.getValue() + vBar.getVisibleAmount() >= vBar.getMaximum()) {
                triggerAutoNavigate(1);
            } else if (e.getWheelRotation() < 0 && vBar.getValue() <= 0) {
                triggerAutoNavigate(-1);
            }
        });

        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // --- Custom Global Scrollbar Setup ---
        globalScrollBar = new JScrollBar(JScrollBar.VERTICAL, 0, 1, 0, SCROLL_RESOLUTION);
        globalScrollBar.addAdjustmentListener(e -> {
            if (!isSyncingScroll) syncGlobalToLocalScroll();
        });
        mainPanel.add(globalScrollBar, BorderLayout.EAST);

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        lblStatus = new JLabel("No file active.");
        statusBar.add(lblStatus);
        mainPanel.add(statusBar, BorderLayout.SOUTH);

        add(mainPanel);
        fileChooser = new JFileChooser();
        setupMenuBar();
    }

    private void setupKeyboardShortcuts() {
        InputMap im = textArea.getInputMap();
        ActionMap am = textArea.getActionMap();

        // CTRL + HOME
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.CTRL_DOWN_MASK), "jumpStart");
        am.put("jumpStart", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                jumpToBoundary(0, true);
            }
        });

        // CTRL + END
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK), "jumpEnd");
        am.put("jumpEnd", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                jumpToBoundary(fileManager.getTotalChunks() - 1, false);
            }
        });
    }

    private void syncLocalToGlobalScroll() {
        isSyncingScroll = true;
        try {
            int currentChunk = fileManager.getTotalChunks() == 0 ? 0 : Math.max(0, fileManager.getTotalChunks() - 1);
            if (currentChunk == 0 && fileManager.getTotalChunks() <= 1) {
                globalScrollBar.setMaximum(SCROLL_RESOLUTION);
                currentChunk = 0;
            } else {
                globalScrollBar.setMaximum(fileManager.getTotalChunks() * SCROLL_RESOLUTION);
                currentChunk = Integer.parseInt(lblStatus.getText().split(" ")[1]) - 1; // Extract index safely from status
            }

            JScrollBar localBar = scrollPane.getVerticalScrollBar();
            double localMax = localBar.getMaximum() - localBar.getVisibleAmount();
            double localPercent = localMax == 0 ? 0 : localBar.getValue() / localMax;

            int globalValue = (currentChunk * SCROLL_RESOLUTION) + (int)(localPercent * SCROLL_RESOLUTION);
            globalScrollBar.setValue(globalValue);
        } catch (Exception ex) {
            // Ignore format exceptions during UI transitioning
        } finally {
            isSyncingScroll = false;
        }
    }

    private void syncGlobalToLocalScroll() {
        isSyncingScroll = true;
        try {
            int globalValue = globalScrollBar.getValue();
            int targetChunk = globalValue / SCROLL_RESOLUTION;
            double localPercent = (globalValue % SCROLL_RESOLUTION) / (double) SCROLL_RESOLUTION;

            int currentChunk = Integer.parseInt(lblStatus.getText().split(" ")[1]) - 1;

            if (targetChunk != currentChunk && targetChunk < fileManager.getTotalChunks()) {
                commitIfDirty();
                LargeFileManager.ChunkState state = fileManager.navigateToIndex(targetChunk);
                applyStateUpdates(state, 0); // Applies update but doesn't set caret
            }

            SwingUtilities.invokeLater(() -> {
                JScrollBar localBar = scrollPane.getVerticalScrollBar();
                int targetLocalValue = (int)(localPercent * (localBar.getMaximum() - localBar.getVisibleAmount()));
                localBar.setValue(targetLocalValue);
                isSyncingScroll = false;
            });
        } catch (Exception ex) {
            isSyncingScroll = false;
        }
    }

    private void jumpToBoundary(int targetIndex, boolean isStart) {
        try {
            commitIfDirty();
            LargeFileManager.ChunkState state = fileManager.navigateToIndex(targetIndex);
            applyStateUpdates(state, isStart ? 1 : -1);
            syncLocalToGlobalScroll();
        } catch (IOException ex) {
            showError("Boundary navigation failure: " + ex.getMessage());
        }
    }

    public void loadInitialFile(File file) {
        fileManager.setFile(file);
        isDirty = false;
        try {
            applyStateUpdates(fileManager.loadCurrentChunk(), 1);
            syncLocalToGlobalScroll();
        } catch (IOException ex) {
            showError("Failed to open command line file argument: " + ex.getMessage());
        }
    }

    private void applyStateUpdates(LargeFileManager.ChunkState state, int direction) {
        isNavigating = true; 
        textArea.setText(state.content());
        lineNumberPanel.setStartLine(state.startLine());
        lblStatus.setText(state.statusText());
        setTitle("Bearit Text Editor - " + state.fileName());
        globalScrollBar.setMaximum(state.totalChunks() * SCROLL_RESOLUTION);
        
        SwingUtilities.invokeLater(() -> {
            if (direction > 0) {
                textArea.setCaretPosition(0); 
                scrollPane.getVerticalScrollBar().setValue(0);
            } else if (direction < 0) {
                textArea.setCaretPosition(textArea.getDocument().getLength());
                JScrollBar vbar = scrollPane.getVerticalScrollBar();
                vbar.setValue(vbar.getMaximum());
            }
            isNavigating = false;
            syncLocalToGlobalScroll();
        });
    }

    private void commitIfDirty() throws IOException {
        if (isDirty) {
            fileManager.commitCurrentChunk(textArea.getText());
            isDirty = false;
        }
    }

    private void triggerAutoNavigate(int direction) {
        try {
            commitIfDirty();
            int currentIdx = Integer.parseInt(lblStatus.getText().split(" ")[1]) - 1;
            LargeFileManager.ChunkState state = fileManager.navigateToIndex(currentIdx + direction);
            
            if (state.chunkIndex() != currentIdx) {
                applyStateUpdates(state, direction);
            }
        } catch (Exception ex) {
            // Ignore silent boundary constraints
        }
    }

    private void performNew() {
        fileManager.setNewFile();
        isDirty = false;
        isNavigating = true;
        textArea.setText("");
        isNavigating = false;
        lblStatus.setText("New file creation mode.");
        lineNumberPanel.setStartLine(1);
        setTitle("Bearit Text Editor - Untitled");
        globalScrollBar.setValue(0);
        globalScrollBar.setMaximum(SCROLL_RESOLUTION);
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

        try {
            LargeFileManager.ChunkState state = fileManager.saveAll(textArea.getText());
            isDirty = false;
            applyStateUpdates(state, 0);
        } catch (IOException ex) {
            showError("Streaming save operation failure: " + ex.getMessage());
        }
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
            g.setFont(textArea.getFont());
            g.setColor(new Color(110, 110, 110));
            
            FontMetrics fm = g.getFontMetrics();
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
                    g.drawString(stringLabel, alignedX, computedY + 2);
                }
                computedY += lineHeight;
            }
        }
    }
}