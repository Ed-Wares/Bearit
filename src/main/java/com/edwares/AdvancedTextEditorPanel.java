package com.edwares;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.awt.print.PrinterException;
import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private JPopupMenu editorContextMenu;
    
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
    private boolean isTransient = false; // --- Tracks if this is a temporary tab (like Tool Output)
    
    private boolean showWhitespace = false;
    private boolean showEol = false;
    private String currentTheme = "Light";
    private boolean autoFillSearch = true;

    private int loadedChunkIndex = 0;
    private int pendingTargetChunk = -1;
    private double pendingLocalPercent = -1;
    private boolean pendingPreviewRequest = false;
    
    private boolean isCurrentlyPreview = false;
    private double lastRequestedLocalPercent = -1;
    private final Timer settleTimer; 
    private long currentChunkStartOffset = 0;
    
    // Block Selection Tracking Variables
    private boolean isBlockSelecting = false;
    private boolean isDragging = false; 
    private boolean isBlockArrowNavigating = false;
    private int blockStartLine = -1;
    private int blockEndLine = -1;
    private int blockStartX = -1;
    private int blockEndX = -1;
    private int lastKnownCaretPos = -1;

    private String currentTitle = "Untitled";

    // --- Search Dialog Components ---
    private JDialog searchDialog;
    private JComboBox<String> comboSearch;
    private JComboBox<String> comboReplace;
    private JComboBox<String> lastActiveCombo = null;    
    private JCheckBox chkCaseInsensitive;
    private JCheckBox chkRegex;
    private JButton btnFindPrev;
    private JButton btnFindNext;
    private JButton btnCount;
    private JButton btnReplace;
    private JButton btnReplaceAll;
    private JButton btnSwap;
    private SwingWorker<?, ?> activeSearchWorker = null; // Tracks the currently running search/replace background thread

    private final DocumentListener editorDocumentListener = new DocumentListener() {
        public void insertUpdate(DocumentEvent e) { registerEdit(); }
        public void removeUpdate(DocumentEvent e) { registerEdit(); }
        public void changedUpdate(DocumentEvent e) { registerEdit(); }
        
        private void registerEdit() {
            // Ignore edits if the tab is transient ---
            if (!isNavigating && !isCurrentlyPreview && !isTransient) {
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

        textArea = new JTextArea() {
            
            // Intercept Raw Hardware Keystrokes to bypass Swing's disabled native actions ---
            @Override
            protected void processComponentKeyEvent(KeyEvent e) {
                //System.out.println("Key Event: " + KeyEvent.getKeyText(e.getKeyCode()) + " | Modifiers: " + KeyEvent.getModifiersExText(e.getModifiersEx()) + " | ID: " + e.getID());
                if (e.getID() == KeyEvent.KEY_PRESSED) {
                    int code = e.getKeyCode();
                    boolean isAlt = (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0;
                    boolean isShift = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
                    boolean isCtrl = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;

                    // System.out.println("Detected Key Press: " + KeyEvent.getKeyText(code) + " | Alt: " + isAlt + " | Shift: " + isShift + " | Ctrl: " + isCtrl);

                    // Handle Alt+Shift+Arrows for block selection
                    if (isAlt && isShift) {
                        if (code == KeyEvent.VK_UP) { moveBlockSelection(0, -1); e.consume(); return; }
                        if (code == KeyEvent.VK_DOWN) { moveBlockSelection(0, 1); e.consume(); return; }
                        if (code == KeyEvent.VK_LEFT) { moveBlockSelection(-1, 0); e.consume(); return; }
                        if (code == KeyEvent.VK_RIGHT) { moveBlockSelection(1, 0); e.consume(); return; }
                    }

                    // Handle Custom Block Clipboard/Delete Operations
                    if (isBlockSelecting && !isCurrentlyPreview) {
                        if (code == KeyEvent.VK_DELETE || code == KeyEvent.VK_BACK_SPACE) {
                            if (hasValidBlockSelection()) {
                                deleteBlockSelection();
                                e.consume();
                                return;
                            }
                        } else if (isCtrl && (code == KeyEvent.VK_C || code == KeyEvent.VK_INSERT)) {
                            if (hasValidBlockSelection()) {
                                copyBlock();
                                e.consume();
                                return;
                            }
                        } else if ((isCtrl && code == KeyEvent.VK_X) || (isShift && code == KeyEvent.VK_DELETE)) {
                            if (hasValidBlockSelection()) {
                                cutBlock();
                                e.consume();
                                return;
                            }
                        } else if ((isCtrl && code == KeyEvent.VK_V) || (isShift && code == KeyEvent.VK_INSERT)) {
                            pasteBlock();
                            e.consume();
                            return;
                        }
                    }
                } else if (e.getID() == KeyEvent.KEY_TYPED) {
                    //System.out.println("Key Typed: " + e.getKeyChar() + " | Modifiers: " + KeyEvent.getModifiersExText(e.getModifiersEx()));
                    // Feature: Delete the block if the user starts typing normally over it
                    if (isBlockSelecting && hasValidBlockSelection() && !isCurrentlyPreview && !e.isControlDown() && !e.isAltDown()) {
                        char c = e.getKeyChar();
                        if (c != KeyEvent.VK_BACK_SPACE && c != KeyEvent.VK_DELETE && c != KeyEvent.VK_ESCAPE) {
                            deleteBlockSelection();
                            // Do not consume, allow the character to be typed normally
                        }
                    }
                }
                // System.out.println("Processing Key Event: " + KeyEvent.getKeyText(e.getKeyCode()) + " | ID: " + e.getID());
                // Swallow Alt key to prevent it from stealing focus for the menu bar
                if (isBlockSelecting && e.getKeyCode() == KeyEvent.VK_ALT) {
                    e.consume();
                    return;
                }
                super.processComponentKeyEvent(e);
            }

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                try {
                    Rectangle r = modelToView2D(getCaretPosition()).getBounds();
                    g.setColor(currentTheme.equals("Dark") ? new Color(75, 110, 175, 80) : new Color(235, 245, 255)); 
                    g.fillRect(0, r.y, getWidth(), r.height);
                } catch (Exception e) {}
                
                setOpaque(false);
                super.paintComponent(g);
                setOpaque(true);
                
                if (isBlockSelecting) {
                    g.setColor(currentTheme.equals("Dark") ? new Color(60, 90, 140, 120) : new Color(100, 150, 220, 120));
                    int minLine = Math.min(blockStartLine, blockEndLine);
                    int maxLine = Math.max(blockStartLine, blockEndLine);
                    int minX = Math.min(blockStartX, blockEndX);
                    int maxX = Math.max(blockStartX, blockEndX);
                    int h = g.getFontMetrics().getHeight();
                    
                    for (int i = minLine; i <= maxLine; i++) {
                        try {
                            int y = modelToView2D(getLineStartOffset(i)).getBounds().y;
                            g.fillRect(minX, y, maxX - minX, h);
                        } catch(Exception e) {}
                    }
                }
                
                if (showWhitespace || showEol) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    
                    // Apply heavy bold font and high-contrast color for visibility ---
                    g2.setFont(getFont().deriveFont(Font.BOLD));
                    g2.setColor(currentTheme.equals("Dark") ? new Color(160, 160, 160) : new Color(140, 140, 140));
                    
                    FontMetrics fm = g2.getFontMetrics();
                    int ascent = fm.getAscent();
                    int charW = fm.charWidth(' ');
                    int spaceSymbolW = fm.stringWidth("·");
                    int tabSymbolW = fm.stringWidth("→");

                    try {
                        Rectangle clip = getVisibleRect();
                        int startOffset = viewToModel2D(new Point(0, clip.y));
                        int endOffset = viewToModel2D(new Point(0, clip.y + clip.height + fm.getHeight()));
                        String text = getDocument().getText(startOffset, endOffset - startOffset);
                        
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (showWhitespace && c == ' ') {
                                Rectangle r = modelToView2D(startOffset + i).getBounds();
                                g2.drawString("·", r.x + (charW - spaceSymbolW) / 2, r.y + ascent);
                            } else if (showWhitespace && c == '\t') {
                                Rectangle r = modelToView2D(startOffset + i).getBounds();
                                g2.drawString("→", r.x + (charW - tabSymbolW) / 2, r.y + ascent);
                            } else if (showEol && c == '\n') {
                                Rectangle r = modelToView2D(startOffset + i).getBounds();
                                g2.drawString("¶", r.x + charW / 2, r.y + ascent);
                            }
                        }
                    } catch (Exception ex) {}
                    g2.dispose();
                }
            }
        };
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        
        // --- Custom Caret repaints BOTH the old line and new line to prevent highlight ghosting
        DefaultCaret customCaret = new DefaultCaret() {
            private Rectangle lastRect = null;
            
            @Override
            protected synchronized void damage(Rectangle r) {
                if (r != null) {
                    try {
                        JTextComponent comp = getComponent();
                        if (lastRect != null) {
                            comp.repaint(0, lastRect.y, comp.getWidth(), lastRect.height);
                        }
                        comp.repaint(0, r.y, comp.getWidth(), r.height);
                        lastRect = (Rectangle) r.clone();
                    } catch (Exception e) {}
                }
                super.damage(r);
            }
        };
        customCaret.setUpdatePolicy(DefaultCaret.UPDATE_WHEN_ON_EDT);
        // Re-enable the blinking cursor (500 milliseconds is the standard OS default) ---
        customCaret.setBlinkRate(500);
        textArea.setCaret(customCaret);
        
        // Intelligent Focus Listener ---
        textArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) { wasEditorFocused = true; }
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
        
        // --- Wrap the JTextArea's TransferHandler to correctly intercept OS File Drops ---
        TransferHandler originalHandler = textArea.getTransferHandler();
        textArea.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    support.setDropAction(TransferHandler.COPY); // Force the 'Copy/Add' OS Cursor Icon
                    return true;
                }
                return originalHandler != null && originalHandler.canImport(support);
            }

            @Override
            public boolean importData(TransferSupport support) {
                try {
                    // Check if a file is in the clipboard/drop
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        java.util.List<File> files = (java.util.List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                        
                        if (support.isDrop()) {
                            // It was Dragged & Dropped! Tell BearitFrame to open the tabs.
                            firePropertyChange("filesDropped", null, files);
                            return true;
                        } else {
                            // It was Pasted! Insert the clean absolute paths as text.
                            StringBuilder sb = new StringBuilder();
                            for (File f : files) {
                                sb.append(f.getAbsolutePath()).append("\n");
                            }
                            textArea.replaceSelection(sb.toString());
                            return true;
                        }
                    }
                    
                    // Fallback for standard text copying/pasting
                    if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        String text = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                        textArea.replaceSelection(text);
                        return true;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return false;
            }

            @Override
            public int getSourceActions(JComponent c) {
                return originalHandler != null ? originalHandler.getSourceActions(c) : super.getSourceActions(c);
            }

            @Override
            public void exportToClipboard(JComponent comp, Clipboard clip, int action) throws IllegalStateException {
                if (originalHandler != null) {
                    originalHandler.exportToClipboard(comp, clip, action);
                } else {
                    super.exportToClipboard(comp, clip, action);
                }
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                if (originalHandler != null) {
                    try {
                        java.lang.reflect.Method m = TransferHandler.class.getDeclaredMethod("createTransferable", JComponent.class);
                        m.setAccessible(true);
                        return (Transferable) m.invoke(originalHandler, c);
                    } catch (Exception e) {}
                }
                return super.createTransferable(c);
            }

            @Override
            protected void exportDone(JComponent source, Transferable data, int action) {
                if (originalHandler != null) {
                    try {
                        java.lang.reflect.Method m = TransferHandler.class.getDeclaredMethod("exportDone", JComponent.class, Transferable.class, int.class);
                        m.setAccessible(true);
                        m.invoke(originalHandler, source, data, action);
                    } catch (Exception e) {}
                } else {
                    super.exportDone(source, data, action);
                }
            }
        });

        // --- Generate the popup menu once for reuse ---
        editorContextMenu = createContextMenu();

        // Mouse Listeners (Updated for Popup Menu & Block Selection) ---
        textArea.addMouseListener(new MouseAdapter() {
            
            // Helper method to catch cross-platform right-clicks
            private void checkPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    editorContextMenu.show(textArea, e.getX(), e.getY());
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                checkPopup(e); // Check for Mac/Linux right-click
                
                if (e.isAltDown() && !e.isPopupTrigger()) {
                    isBlockSelecting = true;
                    textArea.requestFocusInWindow(); 
                    try {
                        blockStartLine = textArea.getLineOfOffset(textArea.viewToModel2D(e.getPoint()));
                        blockStartX = e.getX();
                        blockEndLine = blockStartLine;
                        blockEndX = blockStartX;
                        
                        int offset = textArea.viewToModel2D(e.getPoint());
                        lastKnownCaretPos = offset;
                        textArea.setCaretPosition(offset);
                    } catch(Exception ex){}
                } else if (!e.isPopupTrigger()) {
                    isBlockSelecting = false;
                }
                textArea.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                checkPopup(e); // Check for Windows right-click
                
                SwingUtilities.invokeLater(() -> isDragging = false);
            }
        });

        textArea.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                isDragging = true;
                if (e.isAltDown() || isBlockSelecting) {
                    textArea.requestFocusInWindow(); 
                    if (!isBlockSelecting) {
                        isBlockSelecting = true;
                        try {
                            blockStartLine = textArea.getLineOfOffset(textArea.viewToModel2D(e.getPoint()));
                            blockStartX = e.getX();
                        } catch(Exception ex){}
                    }
                    try {
                        blockEndLine = textArea.getLineOfOffset(textArea.viewToModel2D(e.getPoint()));
                        blockEndX = e.getX();
                        
                        int newOffset = textArea.viewToModel2D(e.getPoint());
                        lastKnownCaretPos = newOffset;
                        textArea.setCaretPosition(newOffset);
                        
                        textArea.scrollRectToVisible(new Rectangle(e.getX(), e.getY(), 1, 1));
                        textArea.repaint();
                    } catch(Exception ex){}
                }
            }
        });

        lblStatus = new JLabel("No file active.");
        lblLoadingStatus = new JLabel("");
        lblLoadingStatus.setFont(lblLoadingStatus.getFont().deriveFont(Font.BOLD));
        lblLoadingStatus.setForeground(new Color(220, 100, 0)); 
        
        chunkLoadProgressBar = new JProgressBar();
        chunkLoadProgressBar.setIndeterminate(true);
        chunkLoadProgressBar.setPreferredSize(new Dimension(100, 14));
        chunkLoadProgressBar.setVisible(false);
        
        lblIndexingStatus = new JLabel(""); 
        lblIndexingStatus.setForeground(new Color(120, 120, 120));
        lblFontInfo = new JLabel("Font: 14pt"); 
        lblCursorInfo = new JLabel("Line: 1 | Pos: 0");

        lineNumberPanel = new LineNumberPanel(this, textArea);

        // Caret Listener Sync ---
        textArea.addCaretListener(e -> {
            if (isNavigating) return;
            
            // Only disable block mode if the user triggered a standard click or normal arrow key movement
            if (isBlockSelecting && !isDragging && !isBlockArrowNavigating) {
                if (e.getDot() != lastKnownCaretPos) {
                    isBlockSelecting = false;
                    textArea.repaint(); 
                }
            }
            lastKnownCaretPos = e.getDot();
            
            updateCursorStatus();
            try {
                int line = textArea.getLineOfOffset(textArea.getCaretPosition());
                lineNumberPanel.setCurrentLine(line);
            } catch (Exception ex) {}
        });

        setupKeyboardShortcuts();

        scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scrollPane.setRowHeaderView(lineNumberPanel);
        
        // Prevent Swing from hijacking the scroll wheel for horizontal movement ---
        scrollPane.setWheelScrollingEnabled(false); 
        
        BearitProperties props = BearitProperties.getInstance();
        setWordWrap(props.isWordWrap());
        setShowWhitespace(props.isShowWhitespace());
        setShowEol(props.isShowEol());
        applyTheme(props.getTheme());
        
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!isSyncingScroll && !isNavigating && !isLoadingChunk) {
                syncLocalToGlobalScroll();
            }
        });

        scrollPane.addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                if (e.getWheelRotation() < 0) { adjustFontSize(2); } 
                else { adjustFontSize(-2); }
            } else if (!isLoadingChunk && !isNavigating) {
                if (e.isShiftDown()) {
                    // Manual Horizontal Scroll
                    JScrollBar hBar = scrollPane.getHorizontalScrollBar();
                    hBar.setValue(hBar.getValue() + (e.getUnitsToScroll() * textArea.getFontMetrics(textArea.getFont()).charWidth('m')));
                } else {
                    // Manual Vertical Scroll Override
                    JScrollBar vBar = scrollPane.getVerticalScrollBar();
                    int scrollAmount = e.getUnitsToScroll() * textArea.getFontMetrics(textArea.getFont()).getHeight();
                    
                    if (e.getWheelRotation() > 0 && vBar.getValue() + vBar.getVisibleAmount() >= vBar.getMaximum()) {
                        triggerAutoNavigate(1);
                    } else if (e.getWheelRotation() < 0 && vBar.getValue() <= 0) {
                        triggerAutoNavigate(-1);
                    } else {
                        vBar.setValue(vBar.getValue() + scrollAmount);
                    }
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

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        
        JPanel leftStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        leftStatusPanel.add(lblStatus);
        leftStatusPanel.add(lblLoadingStatus);
        leftStatusPanel.add(chunkLoadProgressBar);
        
        JPanel rightStatusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        rightStatusPanel.add(lblIndexingStatus);
        rightStatusPanel.add(lblFontInfo);
        rightStatusPanel.add(lblCursorInfo);
        
        statusBar.add(leftStatusPanel, BorderLayout.WEST);
        statusBar.add(rightStatusPanel, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);
    }
    
    // --- Safe Property Reader ---
    private int getEditorMaxLineLength() {
        try {
            return BearitProperties.getInstance().getMaxLineLength();
        } catch (Exception e) {
            return 20000; 
        }
    }

    private Document createWrappedDocument() {
        Document newDoc = new PlainDocument();
        ((AbstractDocument) newDoc).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                super.insertString(fb, offset, processString(fb, offset, string), attr);
            }
            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                super.replace(fb, offset, length, processString(fb, offset, text), attrs);
            }
            private String processString(FilterBypass fb, int offset, String text) throws BadLocationException {
                if (text == null || text.isEmpty()) return text;
                int maxLen = getEditorMaxLineLength();
                if (maxLen <= 0) return text; 
                
                Element root = fb.getDocument().getDefaultRootElement();
                int lineIdx = root.getElementIndex(offset);
                int lineStart = root.getElement(lineIdx).getStartOffset();
                int charsBefore = offset - lineStart;
                
                return LargeFileManager.forceWrapLongLinesDynamic(text, maxLen, charsBefore);
            }
        });
        return newDoc;
    }
    
    // --- True Line Bound Calculation (Scans Past Soft-Wrap \u200B Markers) ---
    private int getTrueLineStart(int offset) {
        try {
            Document doc = textArea.getDocument();
            Element root = doc.getDefaultRootElement();
            int lineIdx = root.getElementIndex(offset);
            
            while (lineIdx > 0) {
                Element prevLine = root.getElement(lineIdx - 1);
                int prevEnd = prevLine.getEndOffset() - 1; 
                if (prevEnd >= 1 && "\u200B".equals(doc.getText(prevEnd - 1, 1))) {
                    lineIdx--; // The previous line ended in a soft wrap, keep scanning backwards
                } else {
                    break;
                }
            }
            return root.getElement(lineIdx).getStartOffset();
        } catch (Exception e) {
            return offset;
        }
    }

    private int getTrueLineEnd(int offset) {
        try {
            Document doc = textArea.getDocument();
            Element root = doc.getDefaultRootElement();
            int lineIdx = root.getElementIndex(offset);
            
            while (lineIdx < root.getElementCount() - 1) {
                Element currLine = root.getElement(lineIdx);
                int currEnd = currLine.getEndOffset() - 1; 
                if (currEnd >= 1 && "\u200B".equals(doc.getText(currEnd - 1, 1))) {
                    lineIdx++; // The current line ends in a soft wrap, keep scanning forwards
                } else {
                    break;
                }
            }
            Element finalLine = root.getElement(lineIdx);
            int endOff = finalLine.getEndOffset();
            if (endOff > finalLine.getStartOffset() && doc.getText(endOff - 1, 1).equals("\n")) {
                return endOff - 1; // Put cursor right before the true newline character
            }
            return endOff;
        } catch (Exception e) {
            return offset;
        }
    }
    
    private boolean hasValidBlockSelection() {
        return isBlockSelecting && (Math.abs(blockStartX - blockEndX) > 0 || Math.abs(blockStartLine - blockEndLine) > 0);
    }
    
    // --- Block Action Helpers ---
    
    private void moveBlockSelection(int dxChars, int dyLines) {
        try {
            isBlockArrowNavigating = true;
            if (!isBlockSelecting) {
                isBlockSelecting = true;
                Rectangle r = textArea.modelToView2D(textArea.getCaretPosition()).getBounds();
                blockStartX = r.x;
                blockEndX = r.x;
                blockStartLine = textArea.getLineOfOffset(textArea.getCaretPosition());
                blockEndLine = blockStartLine;
                textArea.setSelectionStart(textArea.getCaretPosition());
                textArea.setSelectionEnd(textArea.getCaretPosition());
            }
            
            blockEndLine += dyLines;
            blockEndLine = Math.max(0, Math.min(textArea.getLineCount() - 1, blockEndLine));
            
            int charW = textArea.getFontMetrics(textArea.getFont()).charWidth('m');
            blockEndX += (dxChars * charW);
            blockEndX = Math.max(0, blockEndX); 
            
            // Explicitly sync the actual cursor to the visual box bounds
            int y = textArea.modelToView2D(textArea.getLineStartOffset(blockEndLine)).getBounds().y;
            int newOffset = textArea.viewToModel2D(new Point(blockEndX, y));
            
            textArea.setCaretPosition(newOffset);
            textArea.scrollRectToVisible(new Rectangle(blockEndX, y, charW, textArea.getFontMetrics(textArea.getFont()).getHeight()));
            
            textArea.repaint();
        } catch(Exception e) {
        } finally {
            isBlockArrowNavigating = false;
        }
    }

    private String getBlockSelectedText() {
        if (!hasValidBlockSelection()) return null;
        StringBuilder sb = new StringBuilder();
        int minLine = Math.min(blockStartLine, blockEndLine);
        int maxLine = Math.max(blockStartLine, blockEndLine);
        int minX = Math.min(blockStartX, blockEndX);
        int maxX = Math.max(blockStartX, blockEndX);
        
        for (int i = minLine; i <= maxLine; i++) {
            try {
                int lineStart = textArea.getLineStartOffset(i);
                int lineEnd = textArea.getLineEndOffset(i);
                if (i < textArea.getLineCount() - 1) lineEnd--; // Exclude \n
                
                int y = textArea.modelToView2D(lineStart).getBounds().y + 2; // target top of line
                
                int off1 = textArea.viewToModel2D(new Point(minX, y));
                int off2 = textArea.viewToModel2D(new Point(maxX, y));
                
                off1 = Math.max(lineStart, Math.min(lineEnd, off1));
                off2 = Math.max(lineStart, Math.min(lineEnd, off2));
                
                int startOffset = Math.min(off1, off2);
                int endOffset = Math.max(off1, off2);
                
                if (startOffset <= endOffset) {
                    sb.append(textArea.getText(startOffset, endOffset - startOffset).replace("\u200B\n", "").replace("\u200B", ""));
                }
                if (i < maxLine) sb.append("\n");
            } catch(Exception e) {}
        }
        return sb.toString();
    }

    private void copyBlock() {
        String txt = getBlockSelectedText();
        if (txt != null && !txt.isEmpty()) {
            StringSelection selection = new StringSelection(txt);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        }
    }

    private void cutBlock() {
        copyBlock();
        deleteBlockSelection();
    }

    private void pasteBlock() {
        if (hasValidBlockSelection()) {
            deleteBlockSelection();
        }
        textArea.paste(); 
    }

    private void deleteBlockSelection() {
        if (!hasValidBlockSelection()) return;
        int minLine = Math.min(blockStartLine, blockEndLine);
        int maxLine = Math.max(blockStartLine, blockEndLine);
        int minX = Math.min(blockStartX, blockEndX);
        int maxX = Math.max(blockStartX, blockEndX);
        
        try {
            // Traverse BACKWARDS so offsets don't shift as we delete higher rows
            for (int i = maxLine; i >= minLine; i--) {
                int lineStart = textArea.getLineStartOffset(i);
                int lineEnd = textArea.getLineEndOffset(i);
                if (i < textArea.getLineCount() - 1) lineEnd--; 
                
                int y = textArea.modelToView2D(lineStart).getBounds().y + 2; 
                
                int off1 = textArea.viewToModel2D(new Point(minX, y));
                int off2 = textArea.viewToModel2D(new Point(maxX, y));
                
                off1 = Math.max(lineStart, Math.min(lineEnd, off1));
                off2 = Math.max(lineStart, Math.min(lineEnd, off2));
                
                int s = Math.min(off1, off2);
                int e = Math.max(off1, off2);
                
                if (e > s) {
                    textArea.getDocument().remove(s, e - s);
                }
            }
        } catch(Exception e) {}
        
        isBlockSelecting = false;
        textArea.repaint();
    }
    
    // --- Public Editor Methods explicitly trigger stripped operations ---
    
    public void copy() { 
        if (hasValidBlockSelection()) {
            copyBlock();
        } else {
            String selected = textArea.getSelectedText();
            if (selected != null) {
                selected = selected.replace("\u200B\n", "").replace("\u200B", "");
                StringSelection selection = new StringSelection(selected);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
            }
        }
    }
    
    public void cut() { 
        if (!isCurrentlyPreview) {
            if (hasValidBlockSelection()) {
                cutBlock();
            } else {
                String selected = textArea.getSelectedText();
                if (selected != null) {
                    selected = selected.replace("\u200B\n", "").replace("\u200B", "");
                    StringSelection selection = new StringSelection(selected);
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
                    textArea.replaceSelection(""); 
                }
            }
        }
    }
    
    public void paste() { 
        if (!isCurrentlyPreview) {
            if (isBlockSelecting) pasteBlock(); 
            else textArea.paste(); 
        }
    }

    // --- Existing Utility Methods ---

    public void adjustFontSize(int delta) {
        Font current = textArea.getFont();
        int newSize = Math.max(6, Math.min(80, current.getSize() + delta)); 
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
    
    public void setShowWhitespace(boolean show) {
        this.showWhitespace = show;
        textArea.repaint();
    }
    
    public void setShowEol(boolean show) {
        this.showEol = show;
        textArea.repaint();
    }
    
    public void applyTheme(String theme) {
        this.currentTheme = theme;
        if ("Dark".equals(theme)) {
            textArea.setBackground(new Color(43, 43, 43));
            textArea.setForeground(new Color(169, 183, 198));
            textArea.setCaretColor(Color.WHITE);
            lineNumberPanel.setBackground(new Color(49, 51, 53));
            lineNumberPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(80, 80, 80)));
        } else {
            textArea.setBackground(Color.WHITE);
            textArea.setForeground(Color.BLACK);
            textArea.setCaretColor(Color.BLACK);
            lineNumberPanel.setBackground(new Color(245, 245, 245));
            lineNumberPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.LIGHT_GRAY));
        }
        lineNumberPanel.repaint();
        DialogUtil.themePopupMenu(editorContextMenu);
        textArea.repaint();
    }


    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges;
    }

    public void setUnsavedChanges(boolean b) {
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
    public String getCommitText() {
        String text = textArea.getText().replace("\u200B\n", "").replace("\u200B", "");
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

    public void showGotoLineDialog() {
        //String input = JOptionPane.showInputDialog(this, "Enter Destination Line Number:", "Go To Line", JOptionPane.QUESTION_MESSAGE);
        String input = DialogUtil.showInputDialog(getDialogParent(), "Enter Destination Line Number:", "Go To Line");
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
                //JOptionPane.showMessageDialog(this, "Please enter a valid numeric line value.", "Invalid Input", JOptionPane.ERROR_MESSAGE);
                DialogUtil.showMessageDialog(this, "Please enter a valid numeric line value.", "Invalid Input", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private Component getDialogParent() {
        return (searchDialog != null && searchDialog.isVisible()) ? searchDialog : this;
    }

    // --- Added Search Dialog Helper Components ---
    
    // Globally compile the search term correctly based on case insensitivity and regex checkboxes
    private Pattern getSearchPattern(String target) {
        int flags = 0;
        if (chkCaseInsensitive != null && chkCaseInsensitive.isSelected()) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        if (chkRegex == null || !chkRegex.isSelected()) {
            target = Pattern.quote(target);
        }
        return Pattern.compile(target, flags);
    }
    
    // UI Locking state manager for search dialog
    private void setSearchDialogEnabled(boolean enabled) {
        if (comboSearch != null) comboSearch.setEnabled(enabled);
        if (comboReplace != null) comboReplace.setEnabled(enabled);
        if (chkCaseInsensitive != null) chkCaseInsensitive.setEnabled(enabled);
        if (chkRegex != null) chkRegex.setEnabled(enabled);
        if (btnFindPrev != null) btnFindPrev.setEnabled(enabled);
        if (btnFindNext != null) btnFindNext.setEnabled(enabled);
        if (btnCount != null) btnCount.setEnabled(enabled);
        if (btnReplace != null) btnReplace.setEnabled(enabled);
        if (btnReplaceAll != null) btnReplaceAll.setEnabled(enabled);
        if (btnSwap != null) btnSwap.setEnabled(enabled);
        // --- Automatically pull focus back when re-enabling UI ---
        if (enabled && lastActiveCombo != null) {
            SwingUtilities.invokeLater(() -> {
                Component editor = lastActiveCombo.getEditor().getEditorComponent();
                if (editor != null) {
                    editor.requestFocusInWindow();
                    // Optionally select all text so they can immediately type a new search term!
                    if (editor instanceof JTextField) {
                        ((JTextField) editor).selectAll(); 
                    }
                }
            });
        }
    }
    
    public void performCountMatches(String target) {
        if (target == null || target.isEmpty()) return;
        setSearchDialogEnabled(false);
        lblLoadingStatus.setText("Running full file match count... 0%");
        
        String commitText = getCommitText();
        boolean wasDirty = isDirty;
        isDirty = false;
        
        activeSearchWorker = new SwingWorker<Long, Integer>() {
            @Override
            protected Long doInBackground() throws Exception {
                if (wasDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(commitText);
                }
                long count = 0;
                Pattern p = getSearchPattern(target);
                int totalChunks = fileManager.getTotalChunks();
                
                for (int i = 0; i < totalChunks; i++) {
                    // ABORT check
                    if (isCancelled()) return count; 

                    String content = fileManager.getChunkContent(i);
                    Matcher m = p.matcher(content);
                    while (m.find()) {
                        if (isCancelled()) return count; // Inner ABORT check
                        count++;
                    }
                    
                    int pct = (int) (((i + 1) / (double) totalChunks) * 100);
                    publish(pct);
                }
                return count;
            }
            
            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int pct = chunks.get(chunks.size() - 1);
                    lblLoadingStatus.setText("Running full file match count... " + pct + "%");
                }
            }
            
            @Override
            protected void done() {
                try {
                    if (isCancelled()) return; // Don't show dialog if aborted
                    //JOptionPane.showMessageDialog(getDialogParent(), "Total occurrences found: " + get(), "Match Count", JOptionPane.INFORMATION_MESSAGE);
                    DialogUtil.showMessageDialog(getDialogParent(), "Total occurrences found: " + get(), "Match Count", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                } finally {
                    lblLoadingStatus.setText("");
                    setSearchDialogEnabled(true);
                }
            }
        };
        activeSearchWorker.execute();
    }

    public void performReplaceAll(String target, String replacement) {
        if (target == null || target.isEmpty()) return;
        
        setSearchDialogEnabled(false);
        lblLoadingStatus.setText("Replacing all globally... 0%");
        
        String commitText = getCommitText();
        boolean wasDirty = isDirty;
        isDirty = false;

        activeSearchWorker = new SwingWorker<Integer, Integer>() {
            @Override
            protected Integer doInBackground() throws Exception {
                // Save any pending edits in the current view before global replace starts
                if (wasDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(commitText);
                }

                // Compile the global search settings
                Pattern p = getSearchPattern(target);
                String rep = (chkRegex != null && chkRegex.isSelected()) ? replacement : Matcher.quoteReplacement(replacement);
                
                // Pass the logic down to the file manager, injecting lambda callbacks 
                // for both the progress publisher and the cancellation checker.
                return fileManager.replaceAllGlobal(p, rep, 
                    pct -> publish(pct), 
                    () -> isCancelled()
                );
            }
            
            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (isCancelled()) return;
                if (!chunks.isEmpty()) {
                    int pct = chunks.get(chunks.size() - 1);
                    lblLoadingStatus.setText("Replacing all globally... " + pct + "%");
                }
            }
            
            @Override
            protected void done() {
                try {
                    if (isCancelled()) return; 
                    
                    int count = get();
                    
                    // Wipe the caches since the entire global file just changed
                    documentCache.clear(); 
                    globalUndoManager.discardAllEdits();
                    setUnsavedChanges(true); 
                    
                    // Reload the current chunk so the UI reflects the new text
                    triggerAsyncLoad(loadedChunkIndex, 0, -1, false, () -> {
                        lblLoadingStatus.setText("");
                        restartBackgroundIndexer();
                        //JOptionPane.showMessageDialog(getDialogParent(), "Global replacement complete.\nTotal replacements: " + count, "Replace All", JOptionPane.INFORMATION_MESSAGE);
                        DialogUtil.showMessageDialog(getDialogParent(), "Global replacement complete.\nTotal replacements: " + count, "Replace All", JOptionPane.INFORMATION_MESSAGE);
                    });
                    
                } catch (Exception e) {
                    if (!isCancelled()) {
                        showError("Replace all failed: " + e.getMessage());
                    }
                } finally {
                    lblLoadingStatus.setText("");
                    setSearchDialogEnabled(true);
                }
            }
        };
        activeSearchWorker.execute();
    }

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
        
        Document newDoc = createWrappedDocument();
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

    private void restartBackgroundIndexer() {
        lblIndexingStatus.setText("⚙ Indexing lines: 0%");
        fileManager.buildIndexCacheAsync((indexedChunk) -> {
            int total = Math.max(1, fileManager.getTotalChunks());
            int pct = (int) (((indexedChunk + 1) * 100.0) / total);
            
            if (indexedChunk < total - 1) {
                lblIndexingStatus.setText("⚙ Indexing lines: " + pct + "%");
            } else {
                lblIndexingStatus.setText(""); // Complete
            }
            
            // If the chunk we are currently looking at finishes indexing, snap the numbers into place
            if (indexedChunk == loadedChunkIndex) {
                long exactLine = fileManager.getExactLineOffset(loadedChunkIndex);
                if (exactLine != -1 && exactLine != lineNumberPanel.getStartLine()) {
                    lineNumberPanel.setStartLine(exactLine);
                    updateCursorStatus();
                    lineNumberPanel.repaint(); 
                }
            }
        }); 
    }

    public void loadFile(File file) {
        this.activeFile = file;
        fileManager.setFile(file);
        restartBackgroundIndexer();
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
        try {
            fileManager.setCurrentFile(file);
            executeSaveRoutine();
            
            this.activeFile = file;
            setUnsavedChanges(false);
            isDirty = false;
            updateTitle(file.getName());
        } catch (Exception e) {
            showError("Could not save file: " + e.getMessage());
        }
    }
    
    public boolean saveSynchronously() {
        try {
            // --- Pass null to progressCallback since this runs entirely on the main UI thread ---
            fileManager.saveAll(getCommitText(), null);
            isDirty = false;
            setUnsavedChanges(false);
            restartBackgroundIndexer();
            return true;
        } catch (Exception e) {
            showError("Failed to save file: " + e.getMessage());
            return false;
        }
    }

    // This method will perform the save operation directly on the calling thread, blocking until it completes.
    public boolean saveAsSynchronously(File file) {
        try {
            this.activeFile = file;
            fileManager.setCurrentFile(file);
            // Perform the I/O on the current thread (blocking)
            fileManager.saveAll(getCommitText(), null);
            
            // Update state synchronously
            isDirty = false;
            setUnsavedChanges(false);
            restartBackgroundIndexer();
            return true;
        } catch (Exception e) {
            showError("Failed to save file: " + e.getMessage());
            return false;
        }
    }

    public boolean hasActiveFile() { return fileManager.hasFile(); }
    public File getActiveFile() { return activeFile; }
    public String getCurrentTitle() { return currentTitle; }

    private void executeSaveRoutine() {
        lblLoadingStatus.setText("Saving file... 0%");
        isNavigating = true; 
        
        String saveText = isCurrentlyPreview ? "" : getCommitText();
        
        // --- Worker now captures Integer progress updates ---
        new SwingWorker<LargeFileManager.ChunkState, Integer>() {
            @Override
            protected LargeFileManager.ChunkState doInBackground() throws Exception {
                return fileManager.saveAll(saveText, (chunkIndex, pct) -> {
                    publish(pct);
                });
            }
            
            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int pct = chunks.get(chunks.size() - 1);
                    lblLoadingStatus.setText("Saving file... " + pct + "%");
                }
            }
            
            @Override
            protected void done() {
                try {
                    isDirty = false;
                    setUnsavedChanges(false);
                    pendingTargetChunk = -1;
                    applyStateUpdates(get(), 0, -1, null);
                    restartBackgroundIndexer();
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
        
        // Dynamically update the search dialog title if it's currently open
        if (searchDialog != null && searchDialog.isVisible()) {
            searchDialog.setTitle("Search & Replace - " + newTitle);
        }
    }

    // --- Explicitly override native keys to map directly to our stripped methods ---
    private void setupKeyboardShortcuts() {
        InputMap im = textArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = textArea.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "customCopy");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.CTRL_DOWN_MASK), "customCopy"); 
        am.put("customCopy", new AbstractAction() { public void actionPerformed(ActionEvent e) { copy(); }});

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), "customCut");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, InputEvent.SHIFT_DOWN_MASK), "customCut"); 
        am.put("customCut", new AbstractAction() { public void actionPerformed(ActionEvent e) { cut(); }});

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "customPaste");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK), "customPaste"); 
        am.put("customPaste", new AbstractAction() { public void actionPerformed(ActionEvent e) { paste(); }});
        
        // --- Custom Home/End Keys to bypass Soft-Wraps ---
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "customHome");
        am.put("customHome", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isBlockSelecting || isCurrentlyPreview) return;
                textArea.setCaretPosition(getTrueLineStart(textArea.getCaretPosition()));
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.SHIFT_DOWN_MASK), "customShiftHome");
        am.put("customShiftHome", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isCurrentlyPreview) return;
                textArea.moveCaretPosition(getTrueLineStart(textArea.getCaretPosition()));
            }
        });
        
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), "customEnd");
        am.put("customEnd", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isBlockSelecting || isCurrentlyPreview) return;
                textArea.setCaretPosition(getTrueLineEnd(textArea.getCaretPosition()));
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.SHIFT_DOWN_MASK), "customShiftEnd");
        am.put("customShiftEnd", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isCurrentlyPreview) return;
                textArea.moveCaretPosition(getTrueLineEnd(textArea.getCaretPosition()));
            }
        });

        // Ctrl Home/End Chunk Navigation
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

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "blockUp");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "blockDown");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "blockLeft");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "blockRight");
        
        am.put("blockUp", new AbstractAction() { public void actionPerformed(ActionEvent e) { moveBlockSelection(0, -1); }});
        am.put("blockDown", new AbstractAction() { public void actionPerformed(ActionEvent e) { moveBlockSelection(0, 1); }});
        am.put("blockLeft", new AbstractAction() { public void actionPerformed(ActionEvent e) { moveBlockSelection(-1, 0); }});
        am.put("blockRight", new AbstractAction() { public void actionPerformed(ActionEvent e) { moveBlockSelection(1, 0); }});

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

    public void triggerAsyncLoad(int targetChunk, int direction, double localPercentForScroll, boolean requestPreview, Runnable postLoadAction) {
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
            // Dynamically calculate scrollbar arrows & track clicks ---
            int totalLines = Math.max(1, textArea.getLineCount());
            int fontHeight = Math.max(1, textArea.getFontMetrics(textArea.getFont()).getHeight());
            int visibleLines = Math.max(1, scrollPane.getViewport().getHeight() / fontHeight);
            
            globalScrollBar.setUnitIncrement(Math.max(1, (int)((3.0 / totalLines) * SCROLL_RESOLUTION)));
            globalScrollBar.setBlockIncrement(Math.max(1, (int)(((double)visibleLines / totalLines) * SCROLL_RESOLUTION)));
            // -------------------------------------------------------------------
            
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
            Document newDoc = createWrappedDocument();
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
        //JOptionPane.showMessageDialog(this, message, "System IO Exception Error", JOptionPane.ERROR_MESSAGE);
        DialogUtil.showMessageDialog(this, message, "System IO Exception Error", JOptionPane.ERROR_MESSAGE);
    }

    public void appendText(String text) {
        try {
            Document doc = textArea.getDocument();
            doc.insertString(doc.getLength(), text, null);
            textArea.setCaretPosition(doc.getLength());
        } catch (Exception e) {}
    }

    public void setCustomTitle(String title) {
        updateTitle(title);
    }

    public void setTransient(boolean b) {
        this.isTransient = b;
        if (b) {
            isDirty = false;
            setUnsavedChanges(false);
        }
    }

    // --- Search Helper Methods ---
    private String getSearchText() {
        Object item = comboSearch.getEditor().getItem();
        return item != null ? item.toString() : "";
    }

    private String getReplaceText() {
        Object item = comboReplace.getEditor().getItem();
        return item != null ? item.toString() : "";
    }

    private void updateSearchHistory(String target) {
        if (target == null || target.isEmpty()) return;
        try {
            BearitProperties.getInstance().addSearchHistory(target);
            updateComboModel(comboSearch, target);
        } catch (Exception e) {}
    }

    private void updateReplaceHistory(String target) {
        if (target == null || target.isEmpty()) return;
        try {
            BearitProperties.getInstance().addReplaceHistory(target);
            updateComboModel(comboReplace, target);
        } catch (Exception e) {}
    }

    private void updateComboModel(JComboBox<String> combo, String item) {
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) combo.getModel();
        model.removeElement(item);
        model.insertElementAt(item, 0);
        combo.setSelectedIndex(0);
        if (model.getSize() > 15) {
            model.removeElementAt(model.getSize() - 1);
        }
    }

    public void showSearchDialog() {
        boolean isFirstOpen = (searchDialog == null);

        if (isFirstOpen) {
            Window parentWindow = SwingUtilities.getWindowAncestor(this);
            searchDialog = new JDialog(parentWindow, "Search & Replace - " + currentTitle);
            searchDialog.setModal(false);
            searchDialog.setAlwaysOnTop(true);
            searchDialog.setResizable(true); 
            searchDialog.setLayout(new BorderLayout());
            
            // --- WINDOW LISTENER ---
            searchDialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    // Cancel the background task if it is currently running
                    if (activeSearchWorker != null && !activeSearchWorker.isDone()) {
                        activeSearchWorker.cancel(true);
                        lblLoadingStatus.setText("");
                        setSearchDialogEnabled(true);
                    }
                }
            });

            JPanel inputPanel = new JPanel(new GridBagLayout());
            inputPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 10, 15));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(5, 5, 5, 5);
            
            // --- ROW 0: Find Label ---
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0; gbc.gridheight = 1;
            inputPanel.add(new JLabel("Find:"), gbc);
            
            // --- ROW 0: Find Combo Box ---
            gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0; 
            comboSearch = new JComboBox<>();
            comboSearch.setEditable(true);
            comboSearch.setPreferredSize(new Dimension(250, 26));
            inputPanel.add(comboSearch, gbc);

            // --- ROW 0 & 1: Swap Button (Spans vertically across both fields) ---
            gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0; gbc.gridheight = 2; 
            gbc.fill = GridBagConstraints.VERTICAL; // Stretch to fill the height of both rows
            btnSwap = new JButton("⇅");
            btnSwap.setToolTipText("Swap Find and Replace text");
            btnSwap.setMargin(new Insets(2, 6, 2, 6)); // Make it a bit wider
            btnSwap.addActionListener(e -> {
                String currentSearch = getSearchText();
                String currentReplace = getReplaceText();
                comboSearch.getEditor().setItem(currentReplace);
                comboReplace.getEditor().setItem(currentSearch);
            });
            inputPanel.add(btnSwap, gbc);
            
            // --- ROW 1: Replace Label ---
            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0; gbc.gridheight = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            inputPanel.add(new JLabel("Replace:"), gbc);
            
            // --- ROW 1: Replace Combo Box ---
            gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0; 
            comboReplace = new JComboBox<>();
            comboReplace.setEditable(true);
            comboReplace.setPreferredSize(new Dimension(250, 26));
            inputPanel.add(comboReplace, gbc);
            
            // --- ROW 2: Checkboxes (Wrapped in a horizontal panel) ---
            JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            chkCaseInsensitive = new JCheckBox("Case Insensitive");
            chkRegex = new JCheckBox("Regular Expression");
            optionsPanel.add(chkCaseInsensitive);
            optionsPanel.add(Box.createHorizontalStrut(20)); // Spacer
            optionsPanel.add(chkRegex);

            gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0; gbc.gridwidth = 2; 
            inputPanel.add(optionsPanel, gbc);

            // --- Buttons Panel ---
            JPanel pnlBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            btnFindPrev = new JButton("⬆ Previous");
            btnFindNext = new JButton("⬇ Next");
            btnCount = new JButton("Count Matches");
            btnReplace = new JButton("Replace");
            btnReplaceAll = new JButton("Replace All");
            
            btnFindPrev.addActionListener(e -> {
                String txt = getSearchText();
                updateSearchHistory(txt);
                performFindPrevious(txt);
            });
            btnFindNext.addActionListener(e -> {
                String txt = getSearchText();
                updateSearchHistory(txt);
                performFindNext(txt);
            });
            btnReplace.addActionListener(e -> {
                String stxt = getSearchText();
                String rtxt = getReplaceText();
                updateSearchHistory(stxt);
                updateReplaceHistory(rtxt);
                performReplace(stxt, rtxt);
            });
            btnCount.addActionListener(e -> {
                String txt = getSearchText();
                updateSearchHistory(txt);
                performCountMatches(txt);
            });
            btnReplaceAll.addActionListener(e -> {
                String stxt = getSearchText();
                String rtxt = getReplaceText();
                updateSearchHistory(stxt);
                updateReplaceHistory(rtxt);
                performReplaceAll(stxt, rtxt);
            });
            
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
        
        try {
            DefaultComboBoxModel<String> sModel = new DefaultComboBoxModel<>();
            for (String s : BearitProperties.getInstance().getSearchHistory()) sModel.addElement(s);
            Object currentSearch = comboSearch.getEditor().getItem();
            comboSearch.setModel(sModel);
            if (currentSearch != null && !currentSearch.toString().isEmpty()) {
                comboSearch.getEditor().setItem(currentSearch);
            }

            DefaultComboBoxModel<String> rModel = new DefaultComboBoxModel<>();
            for (String s : BearitProperties.getInstance().getReplaceHistory()) rModel.addElement(s);
            Object currentReplace = comboReplace.getEditor().getItem();
            comboReplace.setModel(rModel);
            if (currentReplace != null && !currentReplace.toString().isEmpty()) {
                comboReplace.getEditor().setItem(currentReplace);
            }
        } catch (Exception e) {}

        // --- Auto Populate Logic ---
        if (isFirstOpen) {
            comboSearch.setSelectedIndex(-1);
            comboSearch.getEditor().setItem("");
            comboReplace.setSelectedIndex(-1);
            comboReplace.getEditor().setItem("");
        }

        searchDialog.setTitle("Search & Replace - " + currentTitle);
        DialogUtil.themeDialog(searchDialog);
        // --- Wire the Search Combobox to the Find Button after setting themes to prevent the listener from breaking ---
        Component searchEditor = comboSearch.getEditor().getEditorComponent();
        if (searchEditor instanceof JTextField) {
            // Use a KeyAdapter to catch the raw hardware Enter key press
            searchEditor.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
                        btnFindNext.doClick();
                        e.consume(); // Tell Java we handled it so the Combobox doesn't complain
                    }
                }
            });
        }

        // --- Wire the Replace Combobox to the Replace Button ---
        Component replaceEditor = comboReplace.getEditor().getEditorComponent();
        if (replaceEditor instanceof JTextField) {
            replaceEditor.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
                        btnReplace.doClick();
                        e.consume();
                    }
                }
            });
        }

        // Themed, safe auto-population ---
        if (this.autoFillSearch) {
            String activeSelection = getSafeSelectedText();
            if (activeSelection != null) {
                comboSearch.getEditor().setItem(activeSelection);
            }
        }

        // --- Track which box the user is actually typing in ---
        lastActiveCombo = comboSearch; // Default
        searchEditor.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) { lastActiveCombo = comboSearch; }
        });

        replaceEditor.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) { lastActiveCombo = comboReplace; }
        });
        searchDialog.pack();
        searchDialog.setVisible(true);
        comboSearch.requestFocus();
        Component editorComp = comboSearch.getEditor().getEditorComponent();
        if (editorComp instanceof JTextField) {
            JTextField tf = (JTextField) editorComp;
            if (!tf.getText().isEmpty()) {
                tf.selectAll();
            }
        }
    }
    
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
            int maximumDigits = String.valueOf(startLine + totalLines).length() + 2; 
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
                Document doc = textArea.getDocument();
                Element root = doc.getDefaultRootElement();
                
                int startOffset = textArea.viewToModel2D(new Point(0, viewportClip.y));
                int endOffset = textArea.viewToModel2D(new Point(0, viewportClip.y + viewportClip.height + fm.getHeight()));
                
                int startLineIdx = root.getElementIndex(startOffset);
                int endLineIdx = root.getElementIndex(endOffset);

                int realLines = 0;
                for(int i = 0; i < startLineIdx; i++) {
                    int endOff = root.getElement(i).getEndOffset();
                    if (!(endOff >= 2 && "\u200B".equals(doc.getText(endOff - 2, 1)))) {
                        realLines++;
                    }
                }

                for (int i = startLineIdx; i <= endLineIdx; i++) {
                    int offset = root.getElement(i).getStartOffset();
                    Rectangle r = textArea.modelToView2D(offset).getBounds();
                    
                    if (r.y + r.height < viewportClip.y) continue;
                    if (r.y > viewportClip.y + viewportClip.height) break;
                    
                    boolean prevWasFake = false;
                    if (i > 0) {
                        int prevEndOff = root.getElement(i - 1).getEndOffset();
                        if (prevEndOff >= 2 && "\u200B".equals(doc.getText(prevEndOff - 2, 1))) {
                            prevWasFake = true;
                        }
                    }

                    if (!prevWasFake) {
                        String stringLabel = (isIndexing ? "~" : "") + String.valueOf(startLine + realLines);
                        int alignedX = getWidth() - fm.stringWidth(stringLabel) - 6;
                        
                        if (i == currentLocalLine) {
                            g.setFont(textArea.getFont().deriveFont(Font.BOLD));
                            g.setColor(parent.currentTheme.equals("Dark") ? new Color(200, 200, 200) : new Color(40, 40, 40));
                        } else {
                            g.setFont(textArea.getFont());
                            g.setColor(parent.currentTheme.equals("Dark") ? new Color(110, 110, 110) : new Color(110, 110, 110));
                        }
                        
                        g.drawString(stringLabel, alignedX, r.y + fm.getAscent());
                    }

                    int thisEndOff = root.getElement(i).getEndOffset();
                    if (!(thisEndOff >= 2 && "\u200B".equals(doc.getText(thisEndOff - 2, 1)))) {
                        realLines++;
                    }
                }
            } catch (Exception e) {}
        }
    }

    // --- Find Search Offset Mappers ---

    private int rawToVisualIndex(int rawIndex) {
        try {
            String uiText = textArea.getText();
            int len = uiText.length();
            int rIdx = 0;
            int vIdx = 0;
            while (rIdx < rawIndex && vIdx < len) {
                char c = uiText.charAt(vIdx);
                if (c == '\u200B') {
                    vIdx++;
                    if (vIdx < len && uiText.charAt(vIdx) == '\n') {
                        vIdx++;
                    }
                } else {
                    rIdx++;
                    vIdx++;
                }
            }
            return vIdx;
        } catch (Exception e) {
            return rawIndex;
        }
    }

    private int visualToRawIndex(int visualIndex) {
        try {
            String uiText = textArea.getText();
            int len = uiText.length();
            int rIdx = 0;
            int vIdx = 0;
            while (vIdx < visualIndex && vIdx < len) {
                char c = uiText.charAt(vIdx);
                if (c == '\u200B') {
                    vIdx++;
                    if (vIdx < len && uiText.charAt(vIdx) == '\n') {
                        vIdx++;
                    }
                } else {
                    rIdx++;
                    vIdx++;
                }
            }
            return rIdx;
        } catch (Exception e) {
            return visualIndex;
        }
    }

    private void performFindNext(String target) {
        if (target == null || target.isEmpty()) return;
        setSearchDialogEnabled(false);

        Pattern p = getSearchPattern(target);
        int visualCaret = textArea.getCaretPosition();
        String selected = textArea.getSelectedText();
        
        if (selected != null) {
            String strippedSelection = selected.replace("\u200B\n", "").replace("\u200B", "");
            if (p.matcher(strippedSelection).matches()) {
                visualCaret = textArea.getSelectionEnd();
            }
        }

        int rawCaret = visualToRawIndex(visualCaret);
        String rawText = textArea.getText().replace("\u200B\n", "").replace("\u200B", "");
        Matcher m = p.matcher(rawText);
        
        if (m.find(rawCaret)) {
            int visualStart = Math.min(rawToVisualIndex(m.start()), textArea.getDocument().getLength());
            int visualEnd = Math.min(rawToVisualIndex(m.end()), textArea.getDocument().getLength());
            textArea.setCaretPosition(visualStart);
            textArea.moveCaretPosition(visualEnd);
            setSearchDialogEnabled(true);
            return;
        }

        lblLoadingStatus.setText("Scanning file for next match...");
        String commitText = getCommitText();
        boolean wasDirty = isDirty;
        isDirty = false;
        
        activeSearchWorker = new SwingWorker<Integer, Integer>() {
            int foundIdx = -1;
            int foundLen = 0;
            
            @Override
            protected Integer doInBackground() throws Exception {
                if (wasDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(commitText);
                }
                int total = fileManager.getTotalChunks();
                int chunksToScan = total - (loadedChunkIndex + 1);
                int chunksScanned = 0;
                
                for (int i = loadedChunkIndex + 1; i < total; i++) {
                    if (isCancelled()) return -1; // ABORT check

                    String content = fileManager.getChunkContent(i);
                    Matcher mc = p.matcher(content);
                    if (mc.find()) {
                        foundIdx = mc.start();
                        foundLen = mc.end() - mc.start();
                        return i;
                    }
                    
                    chunksScanned++;
                    int pct = (int) ((chunksScanned / (double) Math.max(1, chunksToScan)) * 100);
                    publish(pct);
                }
                return -1;
            }
            
            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int pct = chunks.get(chunks.size() - 1);
                    lblLoadingStatus.setText("Scanning file for next match... " + pct + "%");
                }
            }
            
            @Override
            protected void done() {
                try {
                    if (isCancelled()) return; // Don't prompt wrap-around if aborted
                    int targetChunk = get();
                    if (targetChunk != -1) {
                        triggerAsyncLoad(targetChunk, 0, -1, false, () -> {
                            int visualStart = Math.min(rawToVisualIndex(foundIdx), textArea.getDocument().getLength());
                            int visualEnd = Math.min(rawToVisualIndex(foundIdx + foundLen), textArea.getDocument().getLength());
                            textArea.setCaretPosition(visualStart);
                            textArea.moveCaretPosition(visualEnd);
                        });
                    } else {
                        //int response = JOptionPane.showConfirmDialog(getDialogParent(), "Reached end of file. Start again from the top?", "Search Wrap Around", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                        int response = DialogUtil.showConfirmDialog(getDialogParent(), "Reached end of file. Start again from the top?", "Search Wrap Around", JOptionPane.YES_NO_OPTION);
                        if (response == JOptionPane.YES_OPTION) {
                            triggerAsyncLoad(0, 1, -1, false, () -> {
                                textArea.setCaretPosition(0);
                                performFindNext(target);
                            });
                        }
                    }
                } catch (Exception e) {
                } finally {
                    lblLoadingStatus.setText("");
                    setSearchDialogEnabled(true);
                }
            }
        };
        activeSearchWorker.execute();
    }

    private void performFindPrevious(String target) {
        if (target == null || target.isEmpty()) return;
        setSearchDialogEnabled(false);

        Pattern p = getSearchPattern(target);
        int visualCaret = textArea.getSelectionStart();
        int rawCaret = visualToRawIndex(visualCaret);
        String rawText = textArea.getText().replace("\u200B\n", "").replace("\u200B", "");
        
        String searchableRawText = rawText.substring(0, rawCaret);
        Matcher m = p.matcher(searchableRawText);
        
        int lastIdx = -1;
        int lastLen = 0;
        while (m.find()) {
            lastIdx = m.start();
            lastLen = m.end() - m.start();
        }
        
        if (lastIdx != -1) {
            int visualStart = Math.min(rawToVisualIndex(lastIdx), textArea.getDocument().getLength());
            int visualEnd = Math.min(rawToVisualIndex(lastIdx + lastLen), textArea.getDocument().getLength());
            textArea.setCaretPosition(visualStart);
            textArea.moveCaretPosition(visualEnd);
            setSearchDialogEnabled(true);
            return;
        }

        lblLoadingStatus.setText("Scanning file for previous match... 0%");
        String commitText = getCommitText();
        boolean wasDirty = isDirty;
        isDirty = false;
        
        activeSearchWorker = new SwingWorker<Integer, Integer>() {
            int foundIdx = -1;
            int foundLen = 0;
            
            @Override
            protected Integer doInBackground() throws Exception {
                if (wasDirty && !isCurrentlyPreview) {
                    fileManager.commitCurrentChunk(commitText);
                }
                
                int chunksToScan = loadedChunkIndex;
                int chunksScanned = 0;
                
                for (int i = loadedChunkIndex - 1; i >= 0; i--) {
                    if (isCancelled()) return -1; // ABORT check

                    String content = fileManager.getChunkContent(i);
                    Matcher mc = p.matcher(content);
                    int tempIdx = -1;
                    int tempLen = 0;
                    while (mc.find()) {
                        if (isCancelled()) return -1; // Inner ABORT check
                        tempIdx = mc.start();
                        tempLen = mc.end() - mc.start();
                    }
                    if (tempIdx != -1) {
                        foundIdx = tempIdx;
                        foundLen = tempLen;
                        return i;
                    }
                    
                    chunksScanned++;
                    int pct = (int) ((chunksScanned / (double) Math.max(1, chunksToScan)) * 100);
                    publish(pct);
                }
                return -1;
            }
            
            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int pct = chunks.get(chunks.size() - 1);
                    lblLoadingStatus.setText("Scanning file for previous match... " + pct + "%");
                }
            }
            
            @Override
            protected void done() {
                try {
                    if (isCancelled()) return; // Don't prompt wrap-around if aborted
                    int targetChunk = get();
                    lblLoadingStatus.setText("");
                    if (targetChunk != -1) {
                        triggerAsyncLoad(targetChunk, 0, -1, false, () -> {
                            int visualStart = Math.min(rawToVisualIndex(foundIdx), textArea.getDocument().getLength());
                            int visualEnd = Math.min(rawToVisualIndex(foundIdx + foundLen), textArea.getDocument().getLength());
                            textArea.setCaretPosition(visualStart);
                            textArea.moveCaretPosition(visualEnd);
                        });
                    } else {
                        //int response = JOptionPane.showConfirmDialog(getDialogParent(), "Reached beginning of file. Search again from the bottom?", "Search Wrap Around", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                        int response = DialogUtil.showConfirmDialog(getDialogParent(), "Reached beginning of file. Search again from the bottom?", "Search Wrap Around", JOptionPane.YES_NO_OPTION);  
                        if (response == JOptionPane.YES_OPTION) {
                            int lastChunk = Math.max(0, fileManager.getTotalChunks() - 1);
                            triggerAsyncLoad(lastChunk, -1, -1, false, () -> {
                                textArea.setCaretPosition(textArea.getDocument().getLength());
                                performFindPrevious(target);
                            });
                        }
                    }
                } catch (Exception e) {
                } finally {
                    lblLoadingStatus.setText("");
                    setSearchDialogEnabled(true);
                }
            }
        };
        activeSearchWorker.execute();
    }

    private void performReplace(String target, String replacement) {
        if (target == null || target.isEmpty()) return;
        
        String selected = textArea.getSelectedText();
        if (selected != null) {
            String strippedSelection = selected.replace("\u200B\n", "").replace("\u200B", "");
            Pattern p = getSearchPattern(target);
            if (p.matcher(strippedSelection).matches() && !isCurrentlyPreview) {
                String rep = (chkRegex != null && chkRegex.isSelected()) ? replacement : Matcher.quoteReplacement(replacement);
                Matcher m = p.matcher(strippedSelection);
                textArea.replaceSelection(m.replaceFirst(rep));
            }
        }
        performFindNext(target);
    }
    
    private void jumpToLocalLine(long absoluteTargetLine) {
        long startLine = lineNumberPanel.getStartLine();
        long targetRealLineLocal = absoluteTargetLine - startLine; 
        
        if (targetRealLineLocal >= 0) {
            try {
                Document doc = textArea.getDocument();
                Element root = doc.getDefaultRootElement();
                int realLineCount = 0;
                
                for (int i = 0; i < root.getElementCount(); i++) {
                    if (realLineCount == targetRealLineLocal) {
                        int offset = root.getElement(i).getStartOffset();
                        textArea.setCaretPosition(offset);
                        textArea.requestFocus();
                        return;
                    }
                    
                    int endOff = root.getElement(i).getEndOffset();
                    if (!(endOff >= 2 && "\u200B".equals(doc.getText(endOff - 2, 1)))) {
                        realLineCount++;
                    }
                }
            } catch (Exception e) {}
        }
    }

    // --- Text Case Conversion Methods ---
// --- Text Case Conversion Methods ---

    public void convertSelectionCase(String mode) {
        if (isCurrentlyPreview) return; // Don't allow edits in preview mode
        
        String selected = textArea.getSelectedText();
        if (selected == null || selected.isEmpty()) return;
        
        // Capture the exact start position of the selection
        int selectionStart = textArea.getSelectionStart();
        
        // Strip out the hidden soft-wrap markers before processing
        String cleanSelection = selected.replace("\u200B\n", "").replace("\u200B", "");
        String replacement = cleanSelection;
        
        switch (mode.toUpperCase()) {
            case "LOWER":
                replacement = cleanSelection.toLowerCase();
                break;
            case "UPPER":
                replacement = cleanSelection.toUpperCase();
                break;
            case "PROPER":
                replacement = toProperCase(cleanSelection);
                break;
        }
        
        // Only replace if the text actually changed
        if (!replacement.equals(cleanSelection)) {
            textArea.replaceSelection(replacement);
            
            // Re-apply the selection highlighting
            // The end position is simply the start position + the length of the new text
            textArea.setSelectionStart(selectionStart);
            textArea.setSelectionEnd(selectionStart + replacement.length());
        }
    }

    private String toProperCase(String input) {
        StringBuilder proper = new StringBuilder();
        boolean nextIsCapital = true;
        
        for (char c : input.toCharArray()) {
            if (Character.isWhitespace(c)) {
                proper.append(c);
                nextIsCapital = true; // The next character after a space/newline should be capitalized
            } else if (nextIsCapital) {
                proper.append(Character.toUpperCase(c));
                nextIsCapital = false;
            } else {
                proper.append(Character.toLowerCase(c));
            }
        }
        return proper.toString();
    }

    public void selectAll() {
        if (!isCurrentlyPreview) {
            textArea.selectAll();
            textArea.requestFocusInWindow();
        }
    }

    // --- Context Menu Setup ---
    private JPopupMenu createContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem mnuUndo = new JMenuItem("Undo");
        mnuUndo.addActionListener(e -> undo());
        
        JMenuItem mnuRedo = new JMenuItem("Redo");
        mnuRedo.addActionListener(e -> redo());
        
        JMenuItem mnuCut = new JMenuItem("Cut");
        mnuCut.addActionListener(e -> cut());
        
        JMenuItem mnuCopy = new JMenuItem("Copy");
        mnuCopy.addActionListener(e -> copy());
        
        JMenuItem mnuPaste = new JMenuItem("Paste");
        mnuPaste.addActionListener(e -> paste());

        JMenuItem mnuSelectAll = new JMenuItem("Select All");
        mnuSelectAll.addActionListener(e -> selectAll());

        JMenuItem searchItem = new JMenuItem("Search & Replace...");
        searchItem.addActionListener(e -> showSearchDialog());

        JMenuItem gotoItem = new JMenuItem("Go To Line...");
        gotoItem.addActionListener(e -> showGotoLineDialog());

        // Convert Case Sub-Menu
        JMenu convertCaseMenu = new JMenu("Convert Case");
        JMenuItem mnuLower = new JMenuItem("lower case");
        mnuLower.addActionListener(e -> convertSelectionCase("LOWER"));
        
        JMenuItem mnuUpper = new JMenuItem("UPPER CASE");
        mnuUpper.addActionListener(e -> convertSelectionCase("UPPER"));
        
        JMenuItem mnuProper = new JMenuItem("Proper Case");
        mnuProper.addActionListener(e -> convertSelectionCase("PROPER"));

        convertCaseMenu.add(mnuLower);
        convertCaseMenu.add(mnuUpper);
        convertCaseMenu.add(mnuProper);

        // Assemble the popup menu
        contextMenu.add(mnuUndo);
        contextMenu.add(mnuRedo);
        contextMenu.addSeparator();
        contextMenu.add(mnuCut);
        contextMenu.add(mnuCopy);
        contextMenu.add(mnuPaste);
        contextMenu.add(mnuSelectAll);
        contextMenu.addSeparator();
        contextMenu.add(searchItem);
        contextMenu.add(gotoItem);
        contextMenu.addSeparator();
        contextMenu.add(convertCaseMenu);

        return contextMenu;
    }

    // --- Print Functionality ---
    public void printFile() {
        if (textArea == null || textArea.getText().isEmpty()) {
            //JOptionPane.showMessageDialog(getDialogParent(), "The document is empty.", "Print", JOptionPane.INFORMATION_MESSAGE);
            DialogUtil.showMessageDialog(getDialogParent(), "The document is empty.", "Print", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Create a header showing the filename, and a footer showing the page number
        MessageFormat header = new MessageFormat("File: " + currentTitle);
        MessageFormat footer = new MessageFormat("Page - {0}");

        // Let the user know we are preparing the print job
        lblLoadingStatus.setText("Preparing print job...");

        // Printing blocks the thread it runs on while the OS dialog is open and spooling.
        // We run it on a background thread so the main UI remains responsive.
        Thread printThread = new Thread(() -> {
            try {
                // The print() method automatically opens the native OS print dialog
                // true = show print dialog, null = default print service, true = interactive
                boolean complete = textArea.print(header, footer, true, null, null, true);
                
                SwingUtilities.invokeLater(() -> {
                    lblLoadingStatus.setText("");
                    if (complete) {
                        System.out.println("Printing completed.");
                    } else {
                        System.out.println("Printing was cancelled by the user.");
                    }
                });

            } catch (PrinterException pe) {
                SwingUtilities.invokeLater(() -> {
                    lblLoadingStatus.setText("");
                    showError("Printing failed: " + pe.getMessage());
                });
            }
        });
        
        printThread.setDaemon(true);
        printThread.start();
    }

    public LargeFileManager getFileManager() {
        return fileManager;
    }

    public int getLoadedChunkIndex() {
        return loadedChunkIndex;
    }

    public void forceSetText(String text) {
        // Capture the exact save state before the JTextArea ruins it
        boolean hadUnsavedAsterisk = this.hasUnsavedChanges(); 
        
        if (documentCache != null) {
            documentCache.clear();
        }
        
        if (textArea != null) {
            // This triggers the background DocumentListener, which erroneously flags the file as dirty
            textArea.setText(text);
            textArea.setCaretPosition(0);
        }
        
        // Forcefully restore the exact state to whatever it was a millisecond ago
        this.isDirty = false; // Reset local text edits since we just forced a full synchronized override
        setUnsavedChanges(hadUnsavedAsterisk);
    }

    public int getRawCaretPosition() {
        if (textArea == null) return 0;
        return visualToRawIndex(textArea.getCaretPosition());
    }

    public void setRawCaretPosition(int rawIndex) {
        if (textArea == null) return;
        
        int visualIdx = rawToVisualIndex(rawIndex);
        if (visualIdx >= 0 && visualIdx <= textArea.getDocument().getLength()) {
            textArea.setCaretPosition(visualIdx);
            
            // Auto-scroll the text area to ensure the new cursor position is visible on screen
            try {
                java.awt.Rectangle viewRect = textArea.modelToView2D(visualIdx).getBounds();
                textArea.scrollRectToVisible(viewRect);
            } catch (Exception e) {
                // Ignore layout exceptions if UI hasn't fully rendered yet
            }
        }
    }
     
    public long getGlobalCaretByteOffset() {
        try {
            int rawCaret = getRawCaretPosition();
            long chunkStartOffset = fileManager.getChunkBoundaries(loadedChunkIndex)[0];
            String rawChunkText = fileManager.getChunkContent(loadedChunkIndex);
            
            // JTextArea strips '\r', so the visual text length is shorter than the raw string length.
            // We must map the visual caret back to the original raw string index.
            int jTextAreaIdx = 0;
            int originalStringIdx = 0;
            while (jTextAreaIdx < rawCaret && originalStringIdx < rawChunkText.length()) {
                if (rawChunkText.charAt(originalStringIdx) != '\r') {
                    jTextAreaIdx++;
                }
                originalStringIdx++;
            }
            
            // Convert exact raw substring to bytes to get the true UTF-8 byte offset
            byte[] bytesUpToCaret = rawChunkText.substring(0, originalStringIdx).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return chunkStartOffset + bytesUpToCaret.length;
        } catch (Exception e) {
            return 0;
        }
    }

    public void focusEditor() {
        if (textArea != null) {
            // requestFocusInWindow is the safest way to grab focus in Swing 
            // without overriding OS-level window layering
            textArea.requestFocusInWindow();
        }
    }

    public void setAutoFillSearch(boolean autoFill) {
        this.autoFillSearch = autoFill;
    }

    public boolean isAutoFillSearch() {
        return autoFillSearch;
    }

    private String getSafeSelectedText() {
        String selected = null;
        
        // Respect custom Alt+Drag block selections first
        if (hasValidBlockSelection()) {
            selected = getBlockSelectedText();
        } else {
            selected = textArea.getSelectedText();
        }
        
        if (selected != null && !selected.isEmpty()) {
            selected = selected.replace("\u200B\n", "").replace("\u200B", "");
            
            // UX Protection: Prevent accidental multi-line or massive dumps into the combobox
            if (selected.contains("\n") || selected.length() > 200) {
                return null;
            }
            return selected;
        }
        return null;
    }
}