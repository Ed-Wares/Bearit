package com.edwares;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.io.IOException;

public class TextEditorFrame extends JFrame {
    private final JTextArea textArea;
    private final JFileChooser fileChooser;
    private final LineNumberPanel lineNumberPanel;
    
    private final JButton btnPrev;
    private final JButton btnNext;
    private final JLabel lblStatus;

    private final LargeFileManager fileManager;

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

        JScrollPane scrollPane = new JScrollPane(textArea);
        lineNumberPanel = new LineNumberPanel(textArea);
        scrollPane.setRowHeaderView(lineNumberPanel);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel pagerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnPrev = new JButton("◀ Previous Chunk");
        btnNext = new JButton("Next Chunk ▶");
        lblStatus = new JLabel("No file active.");
        
        btnPrev.setEnabled(false);
        btnNext.setEnabled(false);

        btnPrev.addActionListener(e -> navigate(-1));
        btnNext.addActionListener(e -> navigate(1));

        pagerPanel.add(btnPrev);
        pagerPanel.add(btnNext);
        pagerPanel.add(lblStatus);
        mainPanel.add(pagerPanel, BorderLayout.SOUTH);

        add(mainPanel);
        fileChooser = new JFileChooser();
        setupMenuBar();
    }

    // --- NEW: Expose programmatic initialization for CLI arguments ---
    public void loadInitialFile(File file) {
        fileManager.setFile(file);
        try {
            LargeFileManager.ChunkState state = fileManager.loadCurrentChunk();
            applyStateUpdates(state);
        } catch (IOException ex) {
            showError("Failed to open command line file argument: " + ex.getMessage());
        }
    }
    // ----------------------------------------------------------------

    private void setupMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        JMenuItem newItem = new JMenuItem("New");
        JMenuItem openItem = new JMenuItem("Open...");
        JMenuItem saveItem = new JMenuItem("Save Current Chunk");
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

    private void applyStateUpdates(LargeFileManager.ChunkState state) {
        textArea.setText(state.content());
        lineNumberPanel.setStartLine(state.startLine());
        btnPrev.setEnabled(state.hasPrev());
        btnNext.setEnabled(state.hasNext());
        lblStatus.setText(state.statusText());
        setTitle("Bearit Text Editor - " + state.fileName());
        
        // Return caret to the top of the loaded chunk for UX ease
        textArea.setCaretPosition(0); 
    }

    private void performNew() {
        fileManager.setNewFile();
        textArea.setText("");
        btnPrev.setEnabled(false);
        btnNext.setEnabled(false);
        lblStatus.setText("New file creation mode.");
        lineNumberPanel.setStartLine(1);
        setTitle("Bearit Text Editor - Untitled");
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
            LargeFileManager.ChunkState state = fileManager.saveCurrentChunk(textArea.getText());
            applyStateUpdates(state);
        } catch (IOException ex) {
            showError("Streaming save operation failure: " + ex.getMessage());
        }
    }

    private void navigate(int direction) {
        try {
            LargeFileManager.ChunkState state = fileManager.navigateChunk(direction);
            applyStateUpdates(state);
        } catch (IOException ex) {
            showError("Chunk navigation failure: " + ex.getMessage());
        }
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

            textArea.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { adjustMetricSizing(); }
                public void removeUpdate(DocumentEvent e) { adjustMetricSizing(); }
                public void changedUpdate(DocumentEvent e) { adjustMetricSizing(); }
            });
            adjustMetricSizing();
        }

        public void setStartLine(long startLine) {
            this.startLine = startLine;
            adjustMetricSizing();
        }

        private void adjustMetricSizing() {
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