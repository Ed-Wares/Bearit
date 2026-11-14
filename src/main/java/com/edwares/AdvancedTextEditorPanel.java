package com.edwares;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainDocument;
import javax.swing.text.Segment;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import javax.swing.text.WrappedPlainView;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;
import javax.swing.text.Highlighter.HighlightPainter;
import javax.swing.text.DefaultHighlighter;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A highly scalable, reusable text editor component designed to handle massive
 * files
 * (50GB+) with a strict 4GB memory footprint using asynchronous chunk loading.
 */
public class AdvancedTextEditorPanel extends JPanel {
    private static final int SCROLL_RESOLUTION = 10000;

    private final JTextArea textArea;
    private final LineNumberPanel lineNumberPanel;
    private final ColumnRulerPanel columnRulerPanel;
    private final JPanel columnRulerCorner;
    private final JScrollPane scrollPane;

    private final JTextField lblStatus;
    private final JTextField lblLoadingStatus;
    private final JTextField lblCursorInfo;
    private final JTextField lblFontInfo;
    private final JTextField lblIndexingStatus; // Background tracking label
    private final JProgressBar chunkLoadProgressBar;
    private final JScrollBar globalScrollBar;
    private JPopupMenu editorContextMenu;
    private Consumer<Font> fontChangeListener;

    private final LargeFileManager fileManager;
    private File activeFile = null;
    private String chunkStatus = "";
    private String fileSizeDateStatus = "";
    private String hiddenBoundaryNewline = "";
    private int editorMaxLineLength = 15000;

    // Tracks the active load task so we can kill it if the user scrolls past it
    private SwingWorker<LargeFileManager.ChunkState, Void> activeChunkWorker = null;

    // --- Global Undo Architecture ---
    private final GlobalUndoManager globalUndoManager = new GlobalUndoManager();

    // LRU Cache: Keeps up to 40 Documents (1GB max) in RAM to preserve their Undo
    // history safely
    private final Map<Integer, Document> documentCache = new LinkedHashMap<Integer, Document>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Document> eldest) {
            if (size() > 40) {
                // If we exceed 40 edited chunks, we clear global history to prevent a 4GB OOM
                // crash
                globalUndoManager.discardAllEdits();
                return true;
            }
            return false;
        }
    };

    private boolean isSyncingScroll = false;
    private boolean isLoadingChunk = false;
    private boolean isSystemEdit = false;
    private boolean isDirty = false;
    private boolean hasUnsavedChanges = false; // Tracks global file edits
    private boolean isNavigating = false;
    private Timer fileWatcherTimer;
    private long lastKnownModifiedTime = 0;

    // Tracks intelligent focus state to survive chained background loads
    private boolean wasEditorFocused = false;
    private boolean isTransient = false; // --- Tracks if this is a temporary tab (like Tool Output)

    private String autoSaveId = null;

    public String getAutoSaveId() { return autoSaveId; }
    public void setAutoSaveId(String id) { this.autoSaveId = id; }

    private boolean showWhitespace = false;
    private boolean showEol = false;
    private boolean oneBasedColumnIndex = false;
    private boolean showColumnRuler = false;
    private boolean readOnlyMode = false;
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

    private String currentHighlightText = null;
    private final HighlightPainter matchHighlightPainter = new DefaultHighlighter.DefaultHighlightPainter(
            new Color(150, 150, 150, 100));
    private final Timer highlightDebounceTimer;

    // --- Search Dialog Components ---
    private JDialog searchDialog;
    private JComboBox<String> comboSearch;
    private JComboBox<String> comboReplace;
    private JComboBox<String> lastActiveCombo = null;
    private JCheckBox chkCaseInsensitive;
    private JCheckBox chkRegex;
    private JCheckBox chkAllTabs;
    private JCheckBox chkSearchInColumn;
    private JTextField txtFromColumn;
    private JTextField txtToColumn;
    private JLabel lblOneBasedColumn;
    private JButton btnFindPrev;
    private JButton btnFindNext;
    private JButton btnCount;
    private JButton btnReplace;
    private JButton btnReplaceAll;
    private JButton btnSwap;
    private SwingWorker<?, ?> activeSearchWorker = null; // Tracks the currently running search/replace background
                                                         // thread
    private SearchPropertiesListener searchPropertiesListener;
    private boolean lastGotoLineFlag = true;
    private String lastGotoValue = "";

    private final DocumentListener editorDocumentListener = new DocumentListener() {
        public void insertUpdate(DocumentEvent e) {
            registerEdit();
        }

        public void removeUpdate(DocumentEvent e) {
            registerEdit();
        }

        public void changedUpdate(DocumentEvent e) {
            registerEdit();
        }

        private void registerEdit() {
            // Ignore edits if the tab is transient ---
            if (!isNavigating && !isCurrentlyPreview && !isTransient) {
                isDirty = true;
                setUnsavedChanges(true); // Flag the entire file as unsaved
            }
            lineNumberPanel.adjustMetricSizing();
            if (showColumnRuler && columnRulerPanel != null) {
                columnRulerPanel.repaint();
            }
            if (!isNavigating)
                syncLocalToGlobalScroll();
        }
    };

    public AdvancedTextEditorPanel() {
        setLayout(new BorderLayout());
        this.fileManager = new LargeFileManager();

        globalUndoManager.setLimit(2500);

        highlightDebounceTimer = new Timer(300, e -> applyMatchingHighlights());
        highlightDebounceTimer.setRepeats(false);

        settleTimer = new Timer(350, e -> {
            if (isCurrentlyPreview && !isLoadingChunk && !isNavigating) {
                triggerAsyncLoad(loadedChunkIndex, 0, lastRequestedLocalPercent, false, null);
            }
        });
        settleTimer.setRepeats(false);
        // --- External File Modification Watcher ---
        fileWatcherTimer = new Timer(2000, e -> checkExternalModification());
        fileWatcherTimer.start();
        // --- Instant Tab-Switch Validation ---
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                // Immediately check for external modifications the moment the tab becomes
                // active!
                checkExternalModification();
            }
        });

        textArea = new JTextArea() {
            private Font lastPaintFont;
            private java.awt.font.FontRenderContext lastPaintFrc;
            private FontMetrics lastPaintFontMetrics;

            // Optimization: Install custom FastTextAreaUI (FastWrappedPlainView / FastPlainView)
            // to avoid O(N^2) line measurement and full-line rendering on 25MB chunks.
            @Override
            public void updateUI() {
                setUI(new FastTextAreaUI());
            }

            // Keep component FontMetrics synchronized with the active Graphics2D FontRenderContext
            // when painting onto scaled/HiDPI surfaces whose transform differs from the window's default
            // GraphicsConfiguration (e.g., mixed-DPI multi-monitor setups or offscreen scaled buffers).
            @Override
            public void paint(Graphics g) {
                if (g instanceof Graphics2D) {
                    Font f = getFont();
                    if (f != null) {
                        FontMetrics gFm = g.getFontMetrics(f);
                        if (gFm != null) {
                            java.awt.font.FontRenderContext gFrc = gFm.getFontRenderContext();
                            if (f != lastPaintFont || lastPaintFontMetrics == null || !gFrc.equals(lastPaintFrc)) {
                                lastPaintFont = f;
                                lastPaintFrc = gFrc;
                                lastPaintFontMetrics = gFm;
                                javax.swing.plaf.TextUI ui = getUI();
                                if (ui != null) {
                                    View root = ui.getRootView(this);
                                    if (root != null && root.getViewCount() > 0) {
                                        View child = root.getView(0);
                                        if (child instanceof FastWrappedPlainView) {
                                            ((FastWrappedPlainView) child).syncGraphicsMetrics(f, gFm, gFrc);
                                        } else if (child instanceof FastPlainView) {
                                            ((FastPlainView) child).syncGraphicsMetrics(f, gFm, gFrc);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                super.paint(g);
            }

            @Override
            public FontMetrics getFontMetrics(Font font) {
                if (font != null && font == lastPaintFont && lastPaintFontMetrics != null && lastPaintFrc != null) {
                    java.awt.GraphicsConfiguration gc = getGraphicsConfiguration();
                    if (gc == null || !gc.getDefaultTransform().equals(lastPaintFrc.getTransform())) {
                        return lastPaintFontMetrics;
                    }
                }
                return super.getFontMetrics(font);
            }

            // Optimization: Short-circuit viewport height tracking using lineCount * fontHeight
            // so JViewport validation does not call getPreferredSize() on a 25MB document.
            @Override
            public boolean getScrollableTracksViewportHeight() {
                Container parent = getParent();
                if (parent instanceof JViewport) {
                    FontMetrics fm = getFontMetrics(getFont());
                    if (getLineCount() * fm.getHeight() > parent.getHeight()) {
                        return false;
                    }
                }
                return super.getScrollableTracksViewportHeight();
            }

            // Intercept Raw Hardware Keystrokes to bypass Swing's disabled native actions
            // ---
            @Override
            protected void processComponentKeyEvent(KeyEvent e) {
                // System.out.println("Key Event: " + KeyEvent.getKeyText(e.getKeyCode()) + " |
                // Modifiers: " + KeyEvent.getModifiersExText(e.getModifiersEx()) + " | ID: " +
                // e.getID());
                if (e.getID() == KeyEvent.KEY_PRESSED) {
                    int code = e.getKeyCode();
                    boolean isAlt = (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0;
                    boolean isShift = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
                    boolean isCtrl = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;

                    if (readOnlyMode || isCurrentlyPreview) {
                        if (code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE || code == KeyEvent.VK_ENTER || code == KeyEvent.VK_TAB) {
                            e.consume();
                            return;
                        }
                        if (isCtrl && (code == KeyEvent.VK_V || code == KeyEvent.VK_X)) {
                            e.consume();
                            return;
                        }
                    }

                    // System.out.println("Detected Key Press: " + KeyEvent.getKeyText(code) + " |
                    // Alt: " + isAlt + " | Shift: " + isShift + " | Ctrl: " + isCtrl);

                    // Handle Alt+Shift+Arrows for block selection
                    if (isAlt && isShift) {
                        if (code == KeyEvent.VK_UP) {
                            moveBlockSelection(0, -1);
                            e.consume();
                            return;
                        }
                        if (code == KeyEvent.VK_DOWN) {
                            moveBlockSelection(0, 1);
                            e.consume();
                            return;
                        }
                        if (code == KeyEvent.VK_LEFT) {
                            moveBlockSelection(-1, 0);
                            e.consume();
                            return;
                        }
                        if (code == KeyEvent.VK_RIGHT) {
                            moveBlockSelection(1, 0);
                            e.consume();
                            return;
                        }
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
                    if (readOnlyMode || isCurrentlyPreview) {
                        e.consume();
                        return;
                    }
                    // System.out.println("Key Typed: " + e.getKeyChar() + " | Modifiers: " +
                    // KeyEvent.getModifiersExText(e.getModifiersEx()));
                    // Feature: Delete the block if the user starts typing normally over it
                    if (isBlockSelecting && hasValidBlockSelection() && !e.isControlDown()
                            && !e.isAltDown()) {
                        char c = e.getKeyChar();
                        if (c != KeyEvent.VK_BACK_SPACE && c != KeyEvent.VK_DELETE && c != KeyEvent.VK_ESCAPE) {
                            deleteBlockSelection();
                            // Do not consume, allow the character to be typed normally
                        }
                    }
                }
                // System.out.println("Processing Key Event: " +
                // KeyEvent.getKeyText(e.getKeyCode()) + " | ID: " + e.getID());
                // Swallow Alt key to prevent it from stealing focus for the menu bar
                if (isBlockSelecting && e.getKeyCode() == KeyEvent.VK_ALT) {
                    e.consume();
                    return;
                }
                super.processComponentKeyEvent(e);
            }

            @Override
            protected void paintComponent(Graphics g) {
                // Optimization: Restrict background and current-line highlight fills to the active clip bounds
                // (instead of the entire 135,000px x 33,000px canvas) and compute the caret line's Y coordinate
                // in O(1) via the line's start offset when word wrap is disabled.
                Rectangle gClip = g.getClipBounds();
                int fillX = (gClip != null) ? gClip.x : 0;
                int fillY = (gClip != null) ? gClip.y : 0;
                int fillW = (gClip != null) ? gClip.width : getWidth();
                int fillH = (gClip != null) ? gClip.height : getHeight();
                g.setColor(getBackground());
                g.fillRect(fillX, fillY, fillW, fillH);
                try {
                    int caretPos = getCaretPosition();
                    int queryPos = getLineWrap() ? caretPos : getLineStartOffset(getLineOfOffset(caretPos));
                    Rectangle r = modelToView2D(queryPos).getBounds();
                    if (gClip == null || (r.y + r.height >= fillY && r.y <= fillY + fillH)) {
                        g.setColor(currentTheme.equals("Dark") ? new Color(75, 110, 175, 80) : new Color(235, 245, 255));
                        g.fillRect(fillX, r.y, fillW, r.height);
                    }
                } catch (Exception e) {
                }

                setOpaque(false);
                super.paintComponent(g);
                setOpaque(true);

                if (isBlockSelecting) {
                    g.setColor(
                            currentTheme.equals("Dark") ? new Color(60, 90, 140, 120) : new Color(100, 150, 220, 120));
                    int minLine = Math.min(blockStartLine, blockEndLine);
                    int maxLine = Math.max(blockStartLine, blockEndLine);
                    int minX = Math.min(blockStartX, blockEndX);
                    int maxX = Math.max(blockStartX, blockEndX);
                    int h = g.getFontMetrics().getHeight();

                    for (int i = minLine; i <= maxLine; i++) {
                        try {
                            int y = modelToView2D(getLineStartOffset(i)).getBounds().y;
                            g.fillRect(minX, y, maxX - minX, h);
                        } catch (Exception e) {
                        }
                    }
                }

                if (showWhitespace || showEol) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                    // Apply heavy bold font and high-contrast color for visibility ---
                    Font baseFont = getFont();
                    Font whitespaceFont = baseFont.deriveFont(Font.BOLD);
                    
                    float newSize = baseFont.getSize() + 6f; // Larger size for line endings
                    Font eolFont = baseFont.deriveFont(Font.BOLD, newSize);
                    if (showEol && !eolFont.canDisplay('\u240D')) {
                        eolFont = new Font("Segoe UI Symbol", Font.BOLD, (int)newSize);
                        if (!eolFont.canDisplay('\u240D')) {
                            eolFont = new Font("Lucida Sans Unicode", Font.BOLD, (int)newSize);
                        }
                        if (!eolFont.canDisplay('\u240D')) {
                            eolFont = new Font("Monospaced", Font.BOLD, (int)newSize);
                        }
                    }

                    g2.setColor(currentTheme.equals("Dark") ? new Color(135, 206, 250) : new Color(100, 180, 255)); // Light blue for all whitespace and EOL

                    FontMetrics baseFm = g2.getFontMetrics(baseFont);
                    int ascent = baseFm.getAscent();
                    int charW = baseFm.charWidth(' ');
                    java.awt.font.FontRenderContext baseFrc = baseFm.getFontRenderContext();
                    float charWFloat = Math.max(1f, (float) baseFont.getStringBounds(" ", baseFrc).getWidth());

                    FontMetrics wsFm = g2.getFontMetrics(whitespaceFont);
                    int spaceSymbolW = wsFm.stringWidth("·");
                    int tabSymbolW = wsFm.stringWidth("→");
                    
                    FontMetrics eolFm = g2.getFontMetrics(eolFont);
                    int crSymbolW = eolFm.stringWidth("\u240D");

                    try {
                        // Optimization: Intersect visible bounds with the paint clip and, when word wrap is disabled,
                        // horizontally cull each visible line to [visStart, visEnd] and advance X in O(1) per character
                        // instead of scanning and calling modelToView2D across 825,000 characters on 15,000-char lines.
                        final String CR_SYMBOL = String.valueOf((char) 0x240D);
                        final String LF_SYMBOL = String.valueOf((char) 0x240A);
                        final char SOFT_WRAP = 0x200B;
                        Rectangle vis = getVisibleRect();
                        Rectangle paintClip = (gClip != null) ? vis.intersection(gClip) : vis;
                        if (paintClip.width <= 0 || paintClip.height <= 0) {
                            paintClip = vis;
                        }
                        Document doc = getDocument();
                        if (!getLineWrap()) {
                            // Advance X with the FastPlainView's own width table / tab stops so the symbols land in
                            // exactly the same cells as the drawn text (also for proportional fonts and non-ASCII).
                            FastPlainView fastView = null;
                            javax.swing.text.View rootView = getUI().getRootView(this);
                            if (rootView != null && rootView.getViewCount() > 0
                                    && rootView.getView(0) instanceof FastPlainView) {
                                fastView = (FastPlainView) rootView.getView(0);
                            }
                            Element root = doc.getDefaultRootElement();
                            int elemCount = root.getElementCount();
                            int startLineIdx = Math.max(0,
                                    root.getElementIndex(viewToModel2D(new Point(0, paintClip.y))));
                            int endLineIdx = Math.min(elemCount - 1, root.getElementIndex(
                                    viewToModel2D(new Point(0, paintClip.y + paintClip.height + baseFm.getHeight()))));
                            Segment seg = new Segment();
                            int docLen = doc.getLength();
                            int clipLeftX = Math.max(0, paintClip.x - charW * 2);
                            int clipRightX = paintClip.x + paintClip.width + charW * 2;
                            for (int lineIdx = startLineIdx; lineIdx <= endLineIdx; lineIdx++) {
                                Element lineElem = root.getElement(lineIdx);
                                int lineStart = lineElem.getStartOffset();
                                int lineEnd = Math.min(docLen, lineElem.getEndOffset());
                                if (lineEnd <= lineStart) {
                                    continue;
                                }
                                Rectangle lineRect = modelToView2D(lineStart).getBounds();
                                int lineY = lineRect.y;
                                if (lineY + lineRect.height < paintClip.y) {
                                    continue;
                                }
                                if (lineY > paintClip.y + paintClip.height) {
                                    break;
                                }
                                int visStart = viewToModel2D(new Point(clipLeftX, lineY));
                                int visEnd = viewToModel2D(new Point(clipRightX, lineY));
                                visStart = Math.max(lineStart, Math.min(lineEnd, visStart));
                                visEnd = Math.max(visStart, Math.min(lineEnd, visEnd + 2));
                                if (visEnd <= visStart) {
                                    continue;
                                }
                                doc.getText(visStart, visEnd - visStart, seg);
                                float curX = (float) modelToView2D(visStart).getX();
                                char[] arr = seg.array;
                                int off = seg.offset;
                                int cnt = seg.count;
                                for (int i = 0; i < cnt; i++) {
                                    char c = arr[off + i];
                                    if (c == SOFT_WRAP) {
                                        continue;
                                    }
                                    if (c == ' ') {
                                        float spaceW = (fastView != null)
                                                ? fastView.advanceX(arr, off + i, off, off + cnt, curX, visStart + i) - curX
                                                : charWFloat;
                                        if (showWhitespace && curX + spaceW >= paintClip.x && curX <= clipRightX) {
                                            g2.setFont(whitespaceFont);
                                            g2.drawString("·", curX + (spaceW - spaceSymbolW) * 0.5f, (float) (lineY + ascent));
                                        }
                                        curX += spaceW;
                                    } else if (c == '\t') {
                                        float tabW;
                                        if (fastView != null) {
                                            tabW = fastView.advanceX(arr, off + i, off, off + cnt, curX, visStart + i) - curX;
                                        } else {
                                            java.awt.geom.Rectangle2D rNext = modelToView2D(Math.min(docLen, visStart + i + 1));
                                            tabW = (rNext != null && (int) rNext.getY() == lineY && (float) rNext.getX() > curX)
                                                    ? ((float) rNext.getX() - curX)
                                                    : (charWFloat * textArea.getTabSize());
                                        }
                                        if (showWhitespace && curX + tabW >= paintClip.x && curX <= clipRightX) {
                                            g2.setFont(whitespaceFont);
                                            g2.drawString("→", curX + (tabW - tabSymbolW) * 0.5f, (float) (lineY + ascent));
                                        }
                                        curX += tabW;
                                    } else if (c == '\r') {
                                        if (showEol && curX <= clipRightX) {
                                            g2.setFont(eolFont);
                                            g2.drawString(CR_SYMBOL, curX + charWFloat * 0.5f, (float) (lineY + ascent));
                                        }
                                    } else if (c == '\n') {
                                        if (showEol && curX <= clipRightX) {
                                            boolean prevSoft = (i > 0 && arr[off + i - 1] == SOFT_WRAP);
                                            if (!prevSoft && visStart + i > lineStart) {
                                                Segment prevSeg = new Segment();
                                                doc.getText(visStart + i - 1, 1, prevSeg);
                                                prevSoft = (prevSeg.count > 0 && prevSeg.array[prevSeg.offset] == SOFT_WRAP);
                                            }
                                            if (!prevSoft) {
                                                g2.setFont(eolFont);
                                                int offsetX = (i > 0 && arr[off + i - 1] == '\r') ? (crSymbolW + 4) : 0;
                                                g2.drawString(LF_SYMBOL, curX + charWFloat * 0.5f + offsetX, (float) (lineY + ascent));
                                            }
                                        }
                                    } else if (fastView != null) {
                                        curX = fastView.advanceX(arr, off + i, off, off + cnt, curX, visStart + i);
                                    } else {
                                        curX += (float) baseFont.getStringBounds(arr, off + i, off + i + 1, baseFrc).getWidth();
                                    }
                                    if (curX > clipRightX + charWFloat * 4) {
                                        break;
                                    }
                                }
                            }
                        } else {
                            int startOffset = viewToModel2D(new Point(0, paintClip.y));
                            int endOffset = viewToModel2D(
                                    new Point(paintClip.x + paintClip.width, paintClip.y + paintClip.height + baseFm.getHeight()));
                            if (endOffset > startOffset) {
                                String text = doc.getText(startOffset, endOffset - startOffset);
                                for (int i = 0; i < text.length(); i++) {
                                    char c = text.charAt(i);
                                    // Use the view's fractional X positions (not truncated Rectangle bounds) so symbols
                                    // stay centered in their cells on HiDPI scaling and with proportional fonts.
                                    if (showWhitespace && c == ' ') {
                                        g2.setFont(whitespaceFont);
                                        java.awt.geom.Rectangle2D r = modelToView2D(startOffset + i);
                                        g2.drawString("·", (float) r.getX() + (charWFloat - spaceSymbolW) * 0.5f,
                                                (float) (r.getY() + ascent));
                                    } else if (showWhitespace && c == '\t') {
                                        g2.setFont(whitespaceFont);
                                        java.awt.geom.Rectangle2D r = modelToView2D(startOffset + i);
                                        java.awt.geom.Rectangle2D rNext = modelToView2D(startOffset + i + 1);
                                        float tabW = (rNext != null && (int) rNext.getY() == (int) r.getY() && rNext.getX() > r.getX())
                                                ? (float) (rNext.getX() - r.getX())
                                                : (charWFloat * textArea.getTabSize());
                                        g2.drawString("→", (float) r.getX() + (tabW - tabSymbolW) * 0.5f,
                                                (float) (r.getY() + ascent));
                                    } else if (showEol && c == '\r') {
                                        g2.setFont(eolFont);
                                        java.awt.geom.Rectangle2D r = modelToView2D(startOffset + i);
                                        g2.drawString(CR_SYMBOL, (float) r.getX() + charWFloat * 0.5f, (float) (r.getY() + ascent));
                                    } else if (showEol && c == '\n') {
                                        if (i > 0 && text.charAt(i - 1) == SOFT_WRAP) {
                                            continue;
                                        }
                                        g2.setFont(eolFont);
                                        java.awt.geom.Rectangle2D r = modelToView2D(startOffset + i);
                                        int offsetX = 0;
                                        if (i > 0 && text.charAt(i - 1) == '\r') {
                                            offsetX = crSymbolW + 4;
                                        }
                                        g2.drawString(LF_SYMBOL, (float) r.getX() + charWFloat * 0.5f + offsetX,
                                                (float) (r.getY() + ascent));
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                    }
                    g2.dispose();
                }
            }
        };
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));

        // --- Custom Caret repaints BOTH the old line and new line to prevent highlight
        // ghosting
        DefaultCaret customCaret = new DefaultCaret() {
            private Rectangle lastRect = null;

            private int adjustForCRLF(int dot) {
                try {
                    JTextComponent comp = getComponent();
                    if (comp != null) {
                        Document doc = comp.getDocument();
                        if (dot > 0 && dot < doc.getLength()) {
                            if ("\n".equals(doc.getText(dot, 1)) && "\r".equals(doc.getText(dot - 1, 1))) {
                                return dot - 1;
                            }
                        }
                    }
                } catch (Exception ex) {}
                return dot;
            }

            @Override
            public void setDot(int dot) {
                super.setDot(adjustForCRLF(dot));
            }

            @Override
            public void moveDot(int dot) {
                super.moveDot(adjustForCRLF(dot));
            }

            @Override
            public void setDot(int dot, javax.swing.text.Position.Bias dotBias) {
                super.setDot(adjustForCRLF(dot), dotBias);
            }

            @Override
            public void moveDot(int dot, javax.swing.text.Position.Bias dotBias) {
                super.moveDot(adjustForCRLF(dot), dotBias);
            }

            // Optimization: Constrain caret line-highlight repaints to the visible viewport width
            // and skip off-screen lines so caret blinking never unions with scroll repaint strips
            // to force a full-viewport repaint during JViewport blit scrolling.
            @Override
            protected synchronized void damage(Rectangle r) {
                if (r != null) {
                    try {
                        JTextComponent comp = getComponent();
                        Rectangle vis = comp.getVisibleRect();
                        int repX = (vis != null && vis.width > 0) ? vis.x : 0;
                        int repW = (vis != null && vis.width > 0) ? vis.width : comp.getWidth();
                        if (lastRect != null && lastRect.y != r.y) {
                            if (vis == null || (lastRect.y + lastRect.height >= vis.y && lastRect.y <= vis.y + vis.height)) {
                                comp.repaint(repX, lastRect.y, repW, lastRect.height);
                            }
                        }
                        if (vis == null || (r.y + r.height >= vis.y && r.y <= vis.y + vis.height)) {
                            comp.repaint(repX, r.y, repW, r.height);
                        }
                        lastRect = (Rectangle) r.clone();
                    } catch (Exception e) {
                    }
                }
                super.damage(r);
            }
        };
        customCaret.setUpdatePolicy(DefaultCaret.UPDATE_WHEN_ON_EDT);
        // Re-enable the blinking cursor (500 milliseconds is the standard OS default)
        // ---
        customCaret.setBlinkRate(500);
        textArea.setCaret(customCaret);
        customCaret.setSelectionVisible(true);

        // Intelligent Focus Listener ---
        textArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                wasEditorFocused = true;
            }

            @Override
            public void focusLost(FocusEvent e) {
                // Only mark focus as lost if the user explicitly clicked away.
                // If isEnabled() is false, the focus loss was artificially caused by the chunk
                // loader locking the UI.
                if (textArea.isEnabled()) {
                    wasEditorFocused = false;
                }
                textArea.getCaret().setSelectionVisible(true);
            }
        });

        // --- Wrap the JTextArea's TransferHandler to correctly intercept OS File Drops
        // ---
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
                        java.util.List<File> files = (java.util.List<File>) support.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);

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
                        java.lang.reflect.Method m = TransferHandler.class.getDeclaredMethod("createTransferable",
                                JComponent.class);
                        m.setAccessible(true);
                        return (Transferable) m.invoke(originalHandler, c);
                    } catch (Exception e) {
                    }
                }
                return super.createTransferable(c);
            }

            @Override
            protected void exportDone(JComponent source, Transferable data, int action) {
                if (originalHandler != null) {
                    try {
                        java.lang.reflect.Method m = TransferHandler.class.getDeclaredMethod("exportDone",
                                JComponent.class, Transferable.class, int.class);
                        m.setAccessible(true);
                        m.invoke(originalHandler, source, data, action);
                    } catch (Exception e) {
                    }
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
                    } catch (Exception ex) {
                    }
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
                        } catch (Exception ex) {
                        }
                    }
                    try {
                        blockEndLine = textArea.getLineOfOffset(textArea.viewToModel2D(e.getPoint()));
                        blockEndX = e.getX();

                        int newOffset = textArea.viewToModel2D(e.getPoint());
                        lastKnownCaretPos = newOffset;
                        textArea.setCaretPosition(newOffset);

                        textArea.scrollRectToVisible(new Rectangle(e.getX(), e.getY(), 1, 1));
                        textArea.repaint();
                    } catch (Exception ex) {
                    }
                }
            }
        });

        lblStatus = newLabelTextField("No file active.");
        lblLoadingStatus = newLabelTextField("");
        lblLoadingStatus.setFont(lblLoadingStatus.getFont().deriveFont(Font.BOLD));
        lblLoadingStatus.setForeground(new Color(220, 100, 0));

        chunkLoadProgressBar = new JProgressBar();
        chunkLoadProgressBar.setIndeterminate(true);
        chunkLoadProgressBar.setPreferredSize(new Dimension(100, 14));
        chunkLoadProgressBar.setVisible(false);

        lblIndexingStatus = newLabelTextField("");
        lblIndexingStatus.setForeground(new Color(120, 120, 120));
        lblFontInfo = newLabelTextField("Font: 14pt");
        lblCursorInfo = newLabelTextField("| Line: 1 | Pos: 0");

        lineNumberPanel = new LineNumberPanel(this, textArea);
        columnRulerPanel = new ColumnRulerPanel(this, textArea);
        columnRulerCorner = new JPanel();
        columnRulerCorner.setBackground(new Color(245, 245, 245));
        columnRulerCorner.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.LIGHT_GRAY));

        // Caret Listener Sync ---
        textArea.addCaretListener(e -> {
            if (isNavigating)
                return;

            // Only disable block mode if the user triggered a standard click or normal
            // arrow key movement
            if (isBlockSelecting && !isDragging && !isBlockArrowNavigating) {
                if (e.getDot() != lastKnownCaretPos) {
                    isBlockSelecting = false;
                    textArea.repaint();
                }
            }
            lastKnownCaretPos = e.getDot();

            updateCursorStatus();

            // --- Highlight selection ---
            String selected = textArea.getSelectedText();
            if (selected != null && !selected.isEmpty()) {
                if (!selected.equals(currentHighlightText)) {
                    currentHighlightText = selected;
                    highlightDebounceTimer.restart();
                }
            } else {
                if (currentHighlightText != null) {
                    currentHighlightText = null;
                    applyMatchingHighlights();
                }
            }

            try {
                int line = textArea.getLineOfOffset(textArea.getCaretPosition());
                lineNumberPanel.setCurrentLine(line);
            } catch (Exception ex) {
            }
        });

        setupKeyboardShortcuts();

        scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scrollPane.setRowHeaderView(lineNumberPanel);

        scrollPane.getViewport().addChangeListener(e -> {
            if (currentHighlightText != null) {
                highlightDebounceTimer.restart();
            }
            if (showColumnRuler && columnRulerPanel != null) {
                columnRulerPanel.repaint();
            }
        });

        // Initialize with local defaults. The parent frame will push saved user
        // preferences
        // immediately after instantiating the panel, ensuring perfect decoupling.
        setWordWrap(false);
        setShowWhitespace(this.showWhitespace);
        setShowEol(this.showEol);
        setOneBasedColumnIndex(this.oneBasedColumnIndex);
        setShowColumnRuler(this.showColumnRuler);
        setReadOnlyMode(this.readOnlyMode);
        applyTheme(this.currentTheme);

        // Prevent Swing from hijacking the scroll wheel for horizontal movement ---
        scrollPane.setWheelScrollingEnabled(false);
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
                if (e.isShiftDown()) {
                    // Manual Horizontal Scroll
                    JScrollBar hBar = scrollPane.getHorizontalScrollBar();
                    hBar.setValue(hBar.getValue()
                            + (e.getUnitsToScroll() * textArea.getFontMetrics(textArea.getFont()).charWidth('m')));
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

        // Optimization: Make statusBar a validate root so frequent status/cursor/indexing label updates
        // remain isolated to the bottom bar and never invalidate or relayout the editor JScrollPane.
        JPanel statusBar = new JPanel(new BorderLayout()) {
            @Override
            public boolean isValidateRoot() {
                return true;
            }
        };
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
        DialogUtil.applyFontToContainer(statusBar, DialogUtil.DEFAULT_STATUSLBL_FONT);
        add(statusBar, BorderLayout.SOUTH);
    }

    // Creates a JTextField that you can highlight and copy text to the clipboard
    private JTextField newLabelTextField(String text) {
        JTextField newLbl = new JTextField(text) {
            // Optimization: Skip redundant setText() calls and only revalidate when string length
            // changes, avoiding layout invalidations on every cursor move or indexing tick.
            @Override
            public void setText(String t) {
                String old = getText();
                if (java.util.Objects.equals(old, t)) {
                    return;
                }
                super.setText(t);
                // Force layout recalculation when text length changes
                int oldLen = (old != null) ? old.length() : 0;
                int newLen = (t != null) ? t.length() : 0;
                if (oldLen != newLen) {
                    revalidate();
                    if (getParent() != null) {
                        getParent().revalidate();
                    }
                }
                repaint();
                if (getParent() != null) {
                    getParent().repaint();
                }
            }

            @Override
            public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                String currentText = getText();

                if (currentText != null) {
                    // Manually calculate the exact pixel width of the current string
                    java.awt.FontMetrics fm = getFontMetrics(getFont());
                    java.awt.Insets insets = getInsets();

                    // Add the text width + left/right padding + a 4-pixel buffer
                    size.width = fm.stringWidth(currentText) + insets.left + insets.right + 4;
                }
                return size;
            }
        };

        // FLAG to prevent theme from adding borders
        newLbl.putClientProperty("isFlatLabel", true);
        // Make it read-only so the user can highlight and copy, but not type
        newLbl.setEditable(false);

        // Strip away the text box styling so it looks exactly like a flat label
        newLbl.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 5, 2, 5)); // Adds slight padding
        newLbl.setOpaque(false); // Makes the background transparent

        // Force it to use the exact same font and color as a standard JLabel
        newLbl.setFont(javax.swing.UIManager.getFont("Label.font"));
        newLbl.setForeground(javax.swing.UIManager.getColor("Label.foreground"));
        return newLbl;
    }

    public int getEditorMaxLineLength() {
        return editorMaxLineLength;
    }

    public void setEditorMaxLineLength(int maxLineLength) {
        int clamped = BearitProperties.clampMaxLineLength(maxLineLength);
        if (clamped == this.editorMaxLineLength) {
            return;
        }
        this.editorMaxLineLength = clamped;
        // Every cached chunk document was wrapped with the old length; rebuild them on demand
        documentCache.clear();
        rewrapCurrentDocument();
    }

    /**
     * Re-applies soft wrapping to the visible document using the current max line length, preserving
     * the text, caret position, and dirty/unsaved state. Undo history is reset because document
     * offsets change when wrap markers are added or removed.
     */
    private void rewrapCurrentDocument() {
        if (textArea == null || isCurrentlyPreview || isLoadingChunk) {
            return;
        }
        Document oldDoc = textArea.getDocument();
        if (oldDoc == null) {
            return;
        }
        if (oldDoc.getLength() == 0) {
            documentCache.put(loadedChunkIndex, oldDoc);
            return;
        }
        String raw;
        try {
            raw = oldDoc.getText(0, oldDoc.getLength());
        } catch (BadLocationException e) {
            return;
        }
        int oldCaret = textArea.getCaretPosition();
        int rawCaret = 0;
        for (int i = 0; i < oldCaret && i < raw.length(); i++) {
            if (raw.charAt(i) != '\u200B' && !(raw.charAt(i) == '\n' && i > 0 && raw.charAt(i - 1) == '\u200B')) {
                rawCaret++;
            }
        }
        String unwrapped = raw.replace("\u200B\n", "").replace("\u200B", "");

        Object startColProp = oldDoc.getProperty(CHUNK_START_COLUMN_PROP);
        long startCol = (startColProp instanceof Long) ? (Long) startColProp : 0L;

        boolean prevNavigating = isNavigating;
        isNavigating = true;
        try {
            Document newDoc = createWrappedDocument(-1, startCol);
            newDoc.putProperty("bearit.hiddenBoundaryNewline", hiddenBoundaryNewline);
            newDoc.insertString(0, unwrapped, null);
            newDoc.addDocumentListener(editorDocumentListener);
            newDoc.addUndoableEditListener(e -> {
                if (!isNavigating && !isCurrentlyPreview) {
                    globalUndoManager.addEdit(new ChunkAwareEdit(loadedChunkIndex, e.getEdit()));
                }
            });
            textArea.setDocument(newDoc);
            documentCache.put(loadedChunkIndex, newDoc);
            globalUndoManager.discardAllEdits();

            // Map the unwrapped caret offset back into the re-wrapped document
            String wrapped = newDoc.getText(0, newDoc.getLength());
            int newCaret = 0;
            int seen = 0;
            while (newCaret < wrapped.length() && seen < rawCaret) {
                char c = wrapped.charAt(newCaret);
                boolean marker = c == '\u200B'
                        || (c == '\n' && newCaret > 0 && wrapped.charAt(newCaret - 1) == '\u200B');
                if (!marker) {
                    seen++;
                }
                newCaret++;
            }
            textArea.setCaretPosition(Math.min(newCaret, newDoc.getLength()));
        } catch (BadLocationException ignored) {
        } finally {
            isNavigating = prevNavigating;
        }

        if (lineNumberPanel != null) {
            lineNumberPanel.adjustMetricSizing();
            lineNumberPanel.repaint();
        }
        if (showColumnRuler && columnRulerPanel != null) {
            columnRulerPanel.repaint();
        }
        textArea.revalidate();
        textArea.repaint();
        updateCursorStatus();
    }

    private Document createWrappedDocument() {
        return createWrappedDocument(-1, 0L);
    }

    private Document createWrappedDocument(int customMaxLineLength) {
        return createWrappedDocument(customMaxLineLength, 0L);
    }

    /** Document property holding the logical column at which the chunk's first character sits. */
    static final String CHUNK_START_COLUMN_PROP = "bearit.chunkStartColumn";
    /** Document property holding a fixed custom wrap length (used for scrollbar preview documents). */
    static final String WRAP_LENGTH_PROP = "bearit.wrapLength";

    // Optimization: Support a custom soft-wrap length so scrollbar preview chunks in no-wrap mode
    // can wrap at 200 chars to fill the vertical viewport and scroll smoothly during fast thumb dragging.
    // chunkStartColumn: column of the first character within its logical line (non-zero when a chunk
    // boundary cuts through a long line) so wrap points stay aligned across chunk boundaries.
    private Document createWrappedDocument(int customMaxLineLength, long chunkStartColumn) {
        Document newDoc = new PlainDocument();
        newDoc.putProperty(CHUNK_START_COLUMN_PROP, Math.max(0L, chunkStartColumn));
        if (customMaxLineLength > 0) {
            newDoc.putProperty(WRAP_LENGTH_PROP, customMaxLineLength);
        }
        ((AbstractDocument) newDoc).setDocumentFilter(new DocumentFilter() {
            private boolean shouldBlock(FilterBypass fb) {
                if (!readOnlyMode) return false;
                if (isSystemEdit || isLoadingChunk || isNavigating) return false;
                if (textArea != null && textArea.getDocument() != fb.getDocument()) return false;
                return true;
            }

            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                    throws BadLocationException {
                if (shouldBlock(fb)) return;
                super.insertString(fb, offset, processString(fb, offset, string), attr);
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                    throws BadLocationException {
                if (shouldBlock(fb)) return;
                super.replace(fb, offset, length, processString(fb, offset, text), attrs);
            }

            @Override
            public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
                if (shouldBlock(fb)) return;
                super.remove(fb, offset, length);
            }

            private String processString(FilterBypass fb, int offset, String text) throws BadLocationException {
                if (text == null || text.isEmpty())
                    return text;
                int maxLen = (customMaxLineLength > 0) ? customMaxLineLength : getEditorMaxLineLength();
                if (maxLen <= 0)
                    return text;

                Element root = fb.getDocument().getDefaultRootElement();
                int lineIdx = root.getElementIndex(offset);
                int lineStart = root.getElement(lineIdx).getStartOffset();
                int charsBefore = offset - lineStart;
                if (lineIdx == 0) {
                    // The first line may continue a line from the previous chunk; keep wrap points aligned
                    charsBefore += computeFirstLineColumnShift(fb.getDocument(), maxLen);
                }

                return LargeFileManager.forceWrapLongLinesDynamic(text, maxLen, charsBefore);
            }
        });
        return newDoc;
    }

    /**
     * Returns how many columns of the logical line precede the first character of {@code doc}
     * (modulo the wrap length), i.e. the column the document's first line starts at. Non-zero only for
     * chunks that begin in the middle of a long line.
     */
    static int computeFirstLineColumnShift(Document doc, int fallbackMaxLen) {
        if (doc == null) return 0;
        Object sc = doc.getProperty(CHUNK_START_COLUMN_PROP);
        if (!(sc instanceof Long) || (Long) sc <= 0) return 0;
        Object wl = doc.getProperty(WRAP_LENGTH_PROP);
        int maxLen = (wl instanceof Integer && (Integer) wl > 0) ? (Integer) wl : fallbackMaxLen;
        if (maxLen <= 0) return 0;
        return (int) ((Long) sc % maxLen);
    }

    /** Column shift to add to displayed column numbers for the given document line. */
    int getLineColumnShift(int docLine) {
        if (docLine != 0 || textArea == null) return 0;
        return computeFirstLineColumnShift(textArea.getDocument(), getEditorMaxLineLength());
    }

    /** Resolves the start column for a chunk being loaded (cache-only for scrollbar previews). */
    private long resolveChunkStartColumn(LargeFileManager.ChunkState state, int maxChunksToScan) {
        if (state == null || isBinaryMode()) return 0;
        try {
            long col = state.isPreview()
                    ? fileManager.getCachedChunkStartColumn(state.chunkIndex())
                    : fileManager.getChunkStartColumn(state.chunkIndex(), maxChunksToScan);
            return Math.max(0, col);
        } catch (Exception e) {
            return 0;
        }
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
                if (endOff > finalLine.getStartOffset() + 1 && doc.getText(endOff - 2, 1).equals("\r")) {
                    return endOff - 2;
                }
                return endOff - 1; // Put cursor right before the true newline character
            }
            return endOff;
        } catch (Exception e) {
            return offset;
        }
    }

    private boolean hasValidBlockSelection() {
        return isBlockSelecting
                && (Math.abs(blockStartX - blockEndX) > 0 || Math.abs(blockStartLine - blockEndLine) > 0);
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
            textArea.scrollRectToVisible(
                    new Rectangle(blockEndX, y, charW, textArea.getFontMetrics(textArea.getFont()).getHeight()));

            textArea.repaint();
        } catch (Exception e) {
        } finally {
            isBlockArrowNavigating = false;
        }
    }

    private String getBlockSelectedText() {
        if (!hasValidBlockSelection())
            return null;
        StringBuilder sb = new StringBuilder();
        int minLine = Math.min(blockStartLine, blockEndLine);
        int maxLine = Math.max(blockStartLine, blockEndLine);
        int minX = Math.min(blockStartX, blockEndX);
        int maxX = Math.max(blockStartX, blockEndX);

        for (int i = minLine; i <= maxLine; i++) {
            try {
                int lineStart = textArea.getLineStartOffset(i);
                int lineEnd = textArea.getLineEndOffset(i);
                if (i < textArea.getLineCount() - 1)
                    lineEnd--; // Exclude \n

                int y = textArea.modelToView2D(lineStart).getBounds().y + 2; // target top of line

                int off1 = textArea.viewToModel2D(new Point(minX, y));
                int off2 = textArea.viewToModel2D(new Point(maxX, y));

                off1 = Math.max(lineStart, Math.min(lineEnd, off1));
                off2 = Math.max(lineStart, Math.min(lineEnd, off2));

                int startOffset = Math.min(off1, off2);
                int endOffset = Math.max(off1, off2);

                if (startOffset <= endOffset) {
                    sb.append(textArea.getText(startOffset, endOffset - startOffset).replace("\u200B\n", "")
                            .replace("\u200B", ""));
                }
                if (i < maxLine)
                    sb.append("\n");
            } catch (Exception e) {
            }
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
        if (!hasValidBlockSelection())
            return;
        int minLine = Math.min(blockStartLine, blockEndLine);
        int maxLine = Math.max(blockStartLine, blockEndLine);
        int minX = Math.min(blockStartX, blockEndX);
        int maxX = Math.max(blockStartX, blockEndX);

        try {
            // Traverse BACKWARDS so offsets don't shift as we delete higher rows
            for (int i = maxLine; i >= minLine; i--) {
                int lineStart = textArea.getLineStartOffset(i);
                int lineEnd = textArea.getLineEndOffset(i);
                if (i < textArea.getLineCount() - 1)
                    lineEnd--;

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
        } catch (Exception e) {
        }

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
        if (!isCurrentlyPreview && !readOnlyMode) {
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
        if (!isCurrentlyPreview && !readOnlyMode) {
            if (isBlockSelecting)
                pasteBlock();
            else
                textArea.paste();
        }
    }

    // --- Existing Utility Methods ---
    public void setOnFontChangeListener(Consumer<Font> listener) {
        this.fontChangeListener = listener;
    }

    public void adjustFontSize(int delta) {
        Font current = textArea.getFont();
        int newSize = Math.max(6, Math.min(80, current.getSize() + delta));
        if (newSize == current.getSize())
            return;

        // Broadcast the event
        if (fontChangeListener != null) {
            fontChangeListener.accept(new Font(current.getFamily(), Font.PLAIN, newSize));
        }
    }

    @Override
    public void setFont(Font font) {
        super.setFont(font);
        if (textArea != null) {
            textArea.setFont(font);
            if (lineNumberPanel != null) {
                lineNumberPanel.setFont(lineNumberPanel.getFont().deriveFont(font.getSize2D()));
                lineNumberPanel.adjustMetricSizing();
            }
            if (columnRulerPanel != null) {
                columnRulerPanel.revalidate();
                columnRulerPanel.repaint();
            }
            if (lblFontInfo != null) {
                lblFontInfo.setText(" | Font: " + font.getName() + " " + font.getSize() + "pt ");
            }

            // Ensure caret is visible after font size changes the view bounds
            // Optimization: Skip scheduling modelToView/scrollRectToVisible when the editor is not yet
            // showing or the caret is at 0 so initial tab creation does not force synchronous layout.
            if (textArea.isShowing() && textArea.getCaretPosition() > 0) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        int pos = textArea.getCaretPosition();
                        java.awt.Rectangle rect = textArea.modelToView(pos);
                        if (rect != null) {
                            textArea.scrollRectToVisible(rect);
                        }
                    } catch (Exception e) {
                    }
                });
            }
        }
    }

    public boolean isReadOnlyMode() {
        return readOnlyMode;
    }

    public void setReadOnlyMode(boolean readOnly) {
        if (this.readOnlyMode == readOnly) return;
        this.readOnlyMode = readOnly;
        if (textArea.getCaret() != null) {
            textArea.getCaret().setBlinkRate(readOnly ? 0 : 500);
            textArea.getCaret().setVisible(true);
        }
        if (lblStatus != null) {
            updateStatusLabel(chunkStatus, getFileInfoString(activeFile));
        }
        textArea.repaint();
    }

    public void setWordWrap(boolean wrap) {
        textArea.setLineWrap(wrap);
        textArea.setWrapStyleWord(wrap);
        scrollPane.setHorizontalScrollBarPolicy(
                wrap ? JScrollPane.HORIZONTAL_SCROLLBAR_NEVER : JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        lineNumberPanel.revalidate();
        lineNumberPanel.repaint();
        if (columnRulerPanel != null) {
            columnRulerPanel.revalidate();
            columnRulerPanel.repaint();
        }
    }

    public void setShowWhitespace(boolean show) {
        this.showWhitespace = show;
        textArea.repaint();
    }

    public void setShowEol(boolean show) {
        this.showEol = show;
        textArea.repaint();
    }

    public void setShowColumnRuler(boolean show) {
        this.showColumnRuler = show;
        if (show) {
            scrollPane.setColumnHeaderView(columnRulerPanel);
            scrollPane.setCorner(JScrollPane.UPPER_LEFT_CORNER, columnRulerCorner);
            if (scrollPane.getColumnHeader() != null) {
                scrollPane.getColumnHeader().setBackground(columnRulerPanel.getBackground());
            }
            columnRulerPanel.revalidate();
            columnRulerPanel.repaint();
        } else {
            scrollPane.setColumnHeaderView(null);
            scrollPane.setColumnHeader(null);
            scrollPane.setCorner(JScrollPane.UPPER_LEFT_CORNER, null);
        }
        scrollPane.revalidate();
        scrollPane.repaint();
    }

    public boolean isShowColumnRuler() {
        return showColumnRuler;
    }

    ColumnRulerPanel getColumnRulerPanel() {
        return columnRulerPanel;
    }

    public void setOneBasedColumnIndex(boolean oneBasedColumn) {
        if (this.oneBasedColumnIndex != oneBasedColumn) {
            int diff = oneBasedColumn ? 1 : -1;
            if (txtFromColumn != null && !txtFromColumn.getText().trim().isEmpty()) {
                try {
                    int from = Integer.parseInt(txtFromColumn.getText().trim());
                    txtFromColumn.setText(String.valueOf(from + diff));
                } catch (Exception e) {}
            }
            if (txtToColumn != null && !txtToColumn.getText().trim().isEmpty()) {
                try {
                    int to = Integer.parseInt(txtToColumn.getText().trim());
                    txtToColumn.setText(String.valueOf(to + diff));
                } catch (Exception e) {}
            }
        }
        this.oneBasedColumnIndex = oneBasedColumn;
        if (lblOneBasedColumn != null) {
            boolean colsVisible = chkSearchInColumn != null && chkSearchInColumn.isSelected();
            lblOneBasedColumn.setVisible(colsVisible && oneBasedColumn);
            if (searchDialog != null && searchDialog.isVisible()) {
                searchDialog.pack();
            }
        }
        updateCursorStatus();
    }

    public boolean isOneBasedColumnIndex() {
        return oneBasedColumnIndex;
    }

    JLabel getLblOneBasedColumn() {
        return lblOneBasedColumn;
    }

    JCheckBox getChkSearchInColumn() {
        return chkSearchInColumn;
    }

    JTextField getTxtFromColumn() {
        return txtFromColumn;
    }

    JTextField getTxtToColumn() {
        return txtToColumn;
    }

    JDialog getSearchDialog() {
        return searchDialog;
    }

    public void applyTheme(String theme) {
        this.currentTheme = theme;
        if ("Dark".equals(theme)) {
            textArea.setBackground(new Color(43, 43, 43));
            textArea.setForeground(new Color(169, 183, 198));
            textArea.setCaretColor(Color.WHITE);
            lineNumberPanel.setBackground(new Color(49, 51, 53));
            lineNumberPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(80, 80, 80)));
            columnRulerPanel.setBackground(new Color(49, 51, 53));
            columnRulerPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(80, 80, 80)));
            columnRulerCorner.setBackground(new Color(49, 51, 53));
            columnRulerCorner.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(80, 80, 80)));
        } else {
            textArea.setBackground(Color.WHITE);
            textArea.setForeground(Color.BLACK);
            textArea.setCaretColor(Color.BLACK);
            lineNumberPanel.setBackground(new Color(245, 245, 245));
            lineNumberPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.LIGHT_GRAY));
            columnRulerPanel.setBackground(new Color(245, 245, 245));
            columnRulerPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
            columnRulerCorner.setBackground(new Color(245, 245, 245));
            columnRulerCorner.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.LIGHT_GRAY));
        }
        if (scrollPane.getColumnHeader() != null) {
            scrollPane.getColumnHeader().setBackground(columnRulerPanel.getBackground());
        }
        lineNumberPanel.repaint();
        columnRulerPanel.repaint();
        columnRulerCorner.repaint();
        DialogUtil.themePopupMenu(editorContextMenu);
        // --- Pass the theme value directly into the custom UI! ---
        scrollPane.getVerticalScrollBar().setUI(new ThemedScrollBarUI(theme));
        scrollPane.getHorizontalScrollBar().setUI(new ThemedScrollBarUI(theme));
        if (globalScrollBar != null) {
            globalScrollBar.setUI(new ThemedScrollBarUI(theme));
        }
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

    public void formatDocument(String type) {
        if (isCurrentlyPreview || readOnlyMode) return;

        try {
            fileManager.commitCurrentChunk(getCommitText());
        } catch (Exception e) {}

        chunkLoadProgressBar.setVisible(true);
        chunkLoadProgressBar.setStringPainted(true);
        chunkLoadProgressBar.setString("Formatting...");
        chunkLoadProgressBar.setIndeterminate(false);

        java.util.Map<Integer, java.io.File> oldDirtyChunks = fileManager.getDirtyChunksSnapshot();
        boolean oldUnsaved = this.hasUnsavedChanges;
        int currentChunkForUndo = loadedChunkIndex;

        SwingWorker<Boolean, Integer> worker = new SwingWorker<Boolean, Integer>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return fileManager.formatGlobal(type, this::publish, () -> false);
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (chunks.isEmpty()) return;
                int latestProgress = chunks.get(chunks.size() - 1);
                chunkLoadProgressBar.setValue(latestProgress);
                chunkLoadProgressBar.setString("Formatting " + latestProgress + "%");
            }

            @Override
            protected void done() {
                chunkLoadProgressBar.setVisible(false);
                chunkLoadProgressBar.setStringPainted(false);
                try {
                    boolean changed = get();
                    if (changed) {
                        java.util.Map<Integer, java.io.File> newDirtyChunks = fileManager.getDirtyChunksSnapshot();
                        globalUndoManager.addEdit(new GlobalFormatEdit(oldDirtyChunks, oldUnsaved, newDirtyChunks, true, currentChunkForUndo));
                        setUnsavedChanges(true);
                        
                        documentCache.clear();
                        int currentChunk = loadedChunkIndex;
                        loadedChunkIndex = -1; // Force a complete visual reload of the current chunk
                        
                        // IMPORTANT: Clear the dirty flag BEFORE triggering the load. 
                        // Otherwise, triggerAsyncLoad will see the editor is dirty, read the OLD unformatted
                        // text currently sitting in the editor, and commit it, overwriting the freshly formatted chunk!
                        isDirty = false;
                        
                        triggerAsyncLoad(currentChunk, 0, -1, false, null);
                    }
                } catch (Exception e) {}
            }
        };
        worker.execute();
    }

    /**
     * Helper to safely append the hidden boundary newline before committing
     * edits to the File Manager, ensuring chunks don't accidentally merge.
     */
    public String getCommitText() {
        String text = textArea.getText().replace("\u200B\n", "").replace("\u200B", "");
        if (isBinaryMode()) {
            text = decodeViewToBinary(text); // --- REVERSE THE BINARY ENCODING ---
        } else {
            String le = fileManager.getDetectedLineEndings();
            if ("CRLF".equals(le)) {
                text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
            } else if ("CR".equals(le)) {
                text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r");
            } else if ("LF".equals(le)) {
                text = text.replace("\r\n", "\n").replace("\r", "\n");
            }
        }

        // --- ONLY APPEND WHAT WAS STRIPPED ---
        if (loadedChunkIndex < fileManager.getTotalChunks() - 1) {
            return text + hiddenBoundaryNewline;
        }
        return text;
    }

    private void updateCursorStatus() {
        if (isCurrentlyPreview)
            return;
        try {
            int dot = textArea.getCaret().getDot();
            int mark = textArea.getCaret().getMark();
            int localLine = textArea.getLineOfOffset(dot);
            long absoluteLine = lineNumberPanel.getStartLine() + localLine;
            int col = dot - textArea.getLineStartOffset(localLine);

            int colOffset = oneBasedColumnIndex ? 1 : 0;
            col += colOffset + getLineColumnShift(localLine);

            long absDot = currentChunkStartOffset + dot;
            long absMark = currentChunkStartOffset + mark;

            if (dot == mark) {
                lblCursorInfo.setText(String.format("| Line: %d | Col: %d | Pos: %d", absoluteLine, col, absDot));
            } else {
                long selStart = Math.min(absDot, absMark);
                long selEnd = Math.max(absDot, absMark);
                long width = selEnd - selStart;
                
                int startOffset = Math.min(dot, mark);
                int endOffset = Math.max(dot, mark);
                int startLineIdx = textArea.getLineOfOffset(startOffset);
                int endLineIdx = textArea.getLineOfOffset(endOffset);
                int startCol = startOffset - textArea.getLineStartOffset(startLineIdx) + colOffset
                        + getLineColumnShift(startLineIdx);
                int endCol = endOffset - textArea.getLineStartOffset(endLineIdx) + colOffset
                        + getLineColumnShift(endLineIdx);
                
                lblCursorInfo.setText(String.format("| Line: %d | Col: %d - %d | Sel: %d - %d (width: %d)", absoluteLine, startCol, endCol,
                        selStart, selEnd, width));
            }
            if (showColumnRuler && columnRulerPanel != null) {
                columnRulerPanel.repaint();
            }
        } catch (Exception e) {
        }
    }

    public void showGotoDialog() {
        JPanel panel = new JPanel(new BorderLayout(5, 10));
        panel.setOpaque(false);

        JPanel radPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        radPanel.setOpaque(false);
        JRadioButton radLine = new JRadioButton("Line", true);
        radLine.setOpaque(false);
        radLine.setSelected(lastGotoLineFlag);
        JRadioButton radPosition = new JRadioButton("Position (Byte Offset)");
        radPosition.setOpaque(false);
        radPosition.setSelected(!lastGotoLineFlag);

        ButtonGroup group = new ButtonGroup();
        group.add(radLine);
        group.add(radPosition);
        radPanel.add(radLine);
        radPanel.add(radPosition);

        JTextField txtInput = new JTextField(15);
        txtInput.setText(lastGotoValue);
        txtInput.selectAll();

        // Ensure the text field grabs focus automatically
        txtInput.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(txtInput::requestFocusInWindow));
            }

            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
            }

            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }
        });

        panel.add(radPanel, BorderLayout.NORTH);
        panel.add(txtInput, BorderLayout.CENTER);

        int result = DialogUtil.showConfirmDialog(getDialogParent(), panel, "Go To...", JOptionPane.OK_CANCEL_OPTION);

        if (result == JOptionPane.OK_OPTION) {
            String input = txtInput.getText();
            if (input != null && !input.trim().isEmpty()) {
                try {
                    long target = Long.parseLong(input.trim());
                    lastGotoValue = input;
                    lastGotoLineFlag = radLine.isSelected();
                    if (radLine.isSelected()) {
                        gotoLine(target);
                    } else {
                        // setGlobalSelection flawlessly triggers chunk-loading and absolute byte
                        // positioning!
                        setGlobalSelection(target, target);
                    }
                } catch (NumberFormatException ex) {
                    DialogUtil.showMessageDialog(this, "Please enter a valid numeric value.", "Invalid Input",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    // goto and line in the file in any chunk
    public void gotoLine(long targetLineNum) {
        lblLoadingStatus.setText("Searching for line position...");

        // Optimization: Only extract the 25MB commit string from the document when the current chunk has unsaved edits.
        boolean wasDirty = isDirty && !isCurrentlyPreview;
        String commitText = wasDirty ? getCommitText() : "";
        isDirty = false;
        if (wasDirty) {
            // Edits can move where following chunks start within their line (soft-wrap alignment)
            final int editedChunk = loadedChunkIndex;
            documentCache.keySet().removeIf(key -> key > editedChunk);
        }

        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                if (wasDirty) {
                    fileManager.commitCurrentChunk(commitText);
                }
                return fileManager.getChunkForLine(targetLineNum);
            }

            @Override
            protected void done() {
                try {
                    int targetChunk = get();
                    if (targetChunk == loadedChunkIndex) {
                        jumpToLocalLine(targetLineNum);
                        lblLoadingStatus.setText("");
                    } else {
                        triggerAsyncLoad(targetChunk, 0, -1, false, () -> jumpToLocalLine(targetLineNum));
                    }
                } catch (Exception e) {
                    lblLoadingStatus.setText("");
                }
            }
        }.execute();
    }

    // select text in editor from any chunk in the file
    public void setGlobalSelection(long globalStart, long globalEnd) {
        if (textArea == null)
            return;

        try {
            // Find the exact chunk that contains the starting byte
            // Optimization: Estimate the target chunk index in O(1) via globalStart / CHUNK_SIZE and
            // adjust locally instead of scanning getChunkBoundaries() linearly from chunk 0.
            int totalChunks = fileManager.getTotalChunks();
            int targetChunk = (int) Math.min(Math.max(0, globalStart / LargeFileManager.CHUNK_SIZE), Math.max(0, totalChunks - 1));
            while (targetChunk > 0 && globalStart < fileManager.getChunkBoundaries(targetChunk)[0]) {
                targetChunk--;
            }
            while (targetChunk < totalChunks - 1 && globalStart >= fileManager.getChunkBoundaries(targetChunk)[1]) {
                targetChunk++;
            }

            final int finalTargetChunk = targetChunk;

            // Wrap the highlighting math in a Runnable so we can delay it if a chunk load
            // is needed
            Runnable applySelection = () -> {
                try {
                    long chunkStartOffset = fileManager.getChunkBoundaries(finalTargetChunk)[0];

                    int localStartByte = (int) Math.max(0, globalStart - chunkStartOffset);
                    int localEndByte = (int) Math.max(0, globalEnd - chunkStartOffset);

                    int visualStart = 0;
                    int visualEnd = 0;

                    if (isBinaryMode()) {
                        visualStart = localStartByte;
                        visualEnd = localEndByte;
                    } else {
                        String rawChunkText = fileManager.getChunkContent(finalTargetChunk);
                        int currentByteCount = 0;
                        int originalStringIdx = 0;
                        int strippedIndex = 0;

                        while (originalStringIdx < rawChunkText.length() && currentByteCount < localEndByte) {
                            char c = rawChunkText.charAt(originalStringIdx);

                            if (c <= 0x7F) {
                                currentByteCount += 1;
                            } else if (c <= 0x7FF) {
                                currentByteCount += 2;
                            } else if (Character.isHighSurrogate(c)) {
                                currentByteCount += 4;
                                strippedIndex++; // Increment an extra time for the skipped low surrogate
                                originalStringIdx++;
                            } else {
                                currentByteCount += 3;
                            }

                            // Count the \r natively, ignoring only stripped controls
                            if (c == '\r' || c == '\n' || c == '\t' || (Character.getType(c) != Character.CONTROL
                                    && Character.getType(c) != Character.FORMAT)) {
                                strippedIndex++;
                            }

                            if (currentByteCount == localStartByte
                                    || (currentByteCount > localStartByte && visualStart == 0 && localStartByte > 0)) {
                                visualStart = rawToVisualIndex(strippedIndex);
                            }
                            originalStringIdx++;
                        }
                        visualEnd = rawToVisualIndex(strippedIndex);
                    }

                    if (visualStart >= 0 && visualEnd <= textArea.getDocument().getLength()) {
                        textArea.setCaretPosition(visualStart);
                        textArea.moveCaretPosition(visualEnd);
                        textArea.requestFocusInWindow();

                        java.awt.Rectangle viewRect = textArea.modelToView2D(visualEnd).getBounds();
                        textArea.scrollRectToVisible(viewRect);
                    }
                } catch (Exception e) {
                }
            };

            // Either run it immediately, or trigger an async load and run it when finished!
            if (finalTargetChunk == loadedChunkIndex) {
                applySelection.run();
            } else {
                lblLoadingStatus.setText("Searching for byte position...");
                triggerAsyncLoad(finalTargetChunk, 0, -1, false, () -> {
                    lblLoadingStatus.setText("");
                    applySelection.run();
                });
            }

        } catch (Exception e) {
        }
    }

    private Component getDialogParent() {
        return (searchDialog != null && searchDialog.isVisible()) ? searchDialog : this;
    }

    // --- Added Search Dialog Helper Components ---

    // Globally compile the search term correctly based on case insensitivity and
    // regex checkboxes
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
        if (comboSearch != null)
            comboSearch.setEnabled(enabled);
        if (comboReplace != null)
            comboReplace.setEnabled(enabled);
        if (chkCaseInsensitive != null)
            chkCaseInsensitive.setEnabled(enabled);
        if (chkRegex != null)
            chkRegex.setEnabled(enabled);
        if (chkAllTabs != null)
            chkAllTabs.setEnabled(enabled);
        if (chkSearchInColumn != null)
            chkSearchInColumn.setEnabled(enabled);
        if (txtFromColumn != null)
            txtFromColumn.setEnabled(enabled);
        if (txtToColumn != null)
            txtToColumn.setEnabled(enabled);
        if (lblOneBasedColumn != null)
            lblOneBasedColumn.setEnabled(enabled);
        if (btnFindPrev != null)
            btnFindPrev.setEnabled(enabled);
        if (btnFindNext != null)
            btnFindNext.setEnabled(enabled);
        if (btnCount != null)
            btnCount.setEnabled(enabled);
        if (btnReplace != null)
            btnReplace.setEnabled(enabled);
        if (btnReplaceAll != null)
            btnReplaceAll.setEnabled(enabled);
            
        if (enabled) {
            updateSearchButtonState();
        }
            
        if (btnSwap != null)
            btnSwap.setEnabled(enabled);
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

    private void updateSearchButtonState() {
        boolean enabled = true;
        if (chkSearchInColumn != null && chkSearchInColumn.isSelected()) {
            if (txtFromColumn != null && txtToColumn != null) {
                if (txtFromColumn.getText().trim().isEmpty() || txtToColumn.getText().trim().isEmpty()) {
                    enabled = false;
                }
            }
        }
        if (btnFindPrev != null) btnFindPrev.setEnabled(enabled);
        if (btnFindNext != null) btnFindNext.setEnabled(enabled);
        if (btnCount != null) btnCount.setEnabled(enabled);
        if (btnReplace != null) btnReplace.setEnabled(enabled);
        if (btnReplaceAll != null) btnReplaceAll.setEnabled(enabled);
    }

    int[] getSearchColumnRange() {
        int minCol = -1, maxCol = -1;
        if (chkSearchInColumn != null && chkSearchInColumn.isSelected()) {
            int colOffset = oneBasedColumnIndex ? 1 : 0;
            if (txtFromColumn != null) {
                try {
                    int val = Integer.parseInt(txtFromColumn.getText().trim());
                    minCol = Math.max(0, val - colOffset);
                } catch (Exception e) {}
            }
            if (txtToColumn != null) {
                try {
                    int val = Integer.parseInt(txtToColumn.getText().trim());
                    maxCol = Math.max(0, val - colOffset);
                } catch (Exception e) {}
            }
        }
        return new int[]{ minCol, maxCol };
    }

    public void performCountMatches(String target) {
        if (target == null || target.isEmpty())
            return;
        setSearchDialogEnabled(false);
        lblLoadingStatus.setText("Running full match count... 0%");

        int[] colRange = getSearchColumnRange();
        final int fMinCol = colRange[0];
        final int fMaxCol = colRange[1];

        java.util.List<AdvancedTextEditorPanel> targets = (chkAllTabs != null && chkAllTabs.isSelected())
                ? getAllOpenEditors()
                : java.util.Arrays.asList(this);

        for (AdvancedTextEditorPanel editor : targets) {
            if (editor.isDirty && !editor.isCurrentlyPreview) {
                try {
                    editor.fileManager.commitCurrentChunk(editor.getCommitText());
                } catch (Exception e) {
                }
                editor.isDirty = false;
            }
        }

        activeSearchWorker = new SwingWorker<Long, Integer>() {
            @Override
            protected Long doInBackground() throws Exception {
                long totalCount = 0;
                Pattern p = getSearchPattern(target);
                int totalGlobalChunks = targets.stream().mapToInt(e -> e.fileManager.getTotalChunks()).sum();
                int[] chunksProcessed = { 0 };

                for (AdvancedTextEditorPanel editor : targets) {
                    if (isCancelled())
                        return totalCount;
                    int editorChunks = editor.fileManager.getTotalChunks();
                    totalCount += editor.fileManager.countGlobalMatches(p, fMinCol, fMaxCol,
                            pct -> {
                                int currentBase = chunksProcessed[0];
                                double localContrib = (pct / 100.0) * editorChunks;
                                if (totalGlobalChunks > 0)
                                    publish((int) (((currentBase + localContrib) / totalGlobalChunks) * 100));
                            },
                            () -> isCancelled());
                    chunksProcessed[0] += editorChunks;
                }
                return totalCount;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int pct = chunks.get(chunks.size() - 1);
                    lblLoadingStatus.setText("Running full match count... " + pct + "%");
                }
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled())
                        return;
                    DialogUtil.showMessageDialog(getDialogParent(), "Total occurrences found: " + get(), "Match Count",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                } finally {
                    lblLoadingStatus.setText("");
                    setSearchDialogEnabled(true);
                }
            }
        };
        activeSearchWorker.execute();
    }

    public void performCountSummary() {
        lblLoadingStatus.setText("Calculating summary... 0%");
        
        SwingWorker<String, Integer> worker = new SwingWorker<String, Integer>() {
            @Override
            protected String doInBackground() throws Exception {
                long totalLines = 0;
                long totalWords = 0;
                long totalChars = 0;
                int totalChunks = fileManager.getTotalChunks();

                for (int i = 0; i < totalChunks; i++) {
                    if (isCancelled()) return null;
                    String content = fileManager.getChunkContent(i);
                    totalChars += content.length();
                    
                    for (int j = 0; j < content.length(); j++) {
                        if (content.charAt(j) == '\n') totalLines++;
                    }
                    
                    boolean inWord = false;
                    for (int j = 0; j < content.length(); j++) {
                        if (Character.isWhitespace(content.charAt(j))) {
                            inWord = false;
                        } else {
                            if (!inWord) {
                                totalWords++;
                                inWord = true;
                            }
                        }
                    }

                    int pct = (int) (((i + 1) * 100.0) / totalChunks);
                    publish(pct);
                }
                
                if (totalChars > 0) totalLines++;
                
                String fileName = activeFile != null ? activeFile.getName() : "Untitled";

                return String.format("<html><b>File: %s</b><br><br><b>Lines:</b> %,d<br><b>Words:</b> %,d<br><b>Characters:</b> %,d</html>", fileName, totalLines, totalWords, totalChars);
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    lblLoadingStatus.setText("Calculating summary... " + chunks.get(chunks.size() - 1) + "%");
                }
            }

            @Override
            protected void done() {
                lblLoadingStatus.setText("");
                try {
                    String result = get();
                    if (result != null) {
                        JOptionPane pane = new JOptionPane(result, JOptionPane.INFORMATION_MESSAGE);
                        JDialog dialog = pane.createDialog(getDialogParent(), "Count Summary");
                        dialog.setModal(false);
                        dialog.setVisible(true);
                    }
                } catch (Exception ex) {
                }
            }
        };
        worker.execute();
    }

    public void performReplaceAll(String target, String replacement) {
        if (target == null || target.isEmpty())
            return;
        setSearchDialogEnabled(false);
        lblLoadingStatus.setText("Replacing all globally... 0%");

        int[] colRange = getSearchColumnRange();
        final int fMinCol = colRange[0];
        final int fMaxCol = colRange[1];

        java.util.List<AdvancedTextEditorPanel> targets = (chkAllTabs != null && chkAllTabs.isSelected())
                ? getAllOpenEditors()
                : java.util.Arrays.asList(this);

        for (AdvancedTextEditorPanel editor : targets) {
            if (editor.isDirty && !editor.isCurrentlyPreview) {
                try {
                    editor.fileManager.commitCurrentChunk(editor.getCommitText());
                } catch (Exception e) {
                }
                editor.isDirty = false;
            }
        }

        activeSearchWorker = new SwingWorker<Integer, Integer>() {
            @Override
            protected Integer doInBackground() throws Exception {
                int totalReplaced = 0;
                Pattern p = getSearchPattern(target);
                String rep = (chkRegex != null && chkRegex.isSelected()) ? replacement
                        : Matcher.quoteReplacement(replacement);
                int totalGlobalChunks = targets.stream().mapToInt(e -> e.fileManager.getTotalChunks()).sum();
                int[] chunksProcessed = { 0 };

                for (AdvancedTextEditorPanel editor : targets) {
                    if (isCancelled())
                        break;
                    int replacedInEditor = editor.fileManager.replaceAllGlobal(p, rep, fMinCol, fMaxCol,
                            pct -> {
                                int currentBase = chunksProcessed[0];
                                double localContrib = (pct / 100.0) * editor.fileManager.getTotalChunks();
                                if (totalGlobalChunks > 0)
                                    publish((int) (((currentBase + localContrib) / totalGlobalChunks) * 100));
                            },
                            () -> isCancelled());
                    
                    if (replacedInEditor > 0) {
                        SwingUtilities.invokeLater(() -> {
                            editor.documentCache.clear();
                            editor.globalUndoManager.discardAllEdits();
                            editor.setUnsavedChanges(true);

                            int currentChunk = editor.loadedChunkIndex;
                            editor.loadedChunkIndex = -1; // Force a complete visual reload of the current chunk

                            if (editor == AdvancedTextEditorPanel.this) {
                                editor.triggerAsyncLoad(currentChunk, 0, -1, false,
                                        () -> editor.restartBackgroundIndexer());
                            } else {
                                editor.triggerAsyncLoad(currentChunk, 0, -1,
                                        false, () -> editor.restartBackgroundIndexer());
                            }
                        });
                    }
                    
                    totalReplaced += replacedInEditor;
                    chunksProcessed[0] += editor.fileManager.getTotalChunks();
                }
                return totalReplaced;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (isCancelled())
                    return;
                if (!chunks.isEmpty()) {
                    int pct = chunks.get(chunks.size() - 1);
                    lblLoadingStatus.setText("Replacing all globally... " + pct + "%");
                }
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled())
                        return;
                    int count = get();

                    lblLoadingStatus.setText("");
                    DialogUtil.showMessageDialog(getDialogParent(),
                            "Global replacement complete.\nTotal replacements: " + count, "Replace All",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    if (!isCancelled())
                        showError("Replace all failed: " + e.getMessage());
                } finally {
                    lblLoadingStatus.setText("");
                    setSearchDialogEnabled(true);
                }
            }
        };
        activeSearchWorker.execute();
    }

    public void undo() {
        if (readOnlyMode || isCurrentlyPreview || !globalUndoManager.canUndo())
            return;
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
        if (readOnlyMode || isCurrentlyPreview || !globalUndoManager.canRedo())
            return;
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
        lastKnownModifiedTime = 0;
        fileSizeDateStatus = "";
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
        updateStatusLabel("New file", "");
        lineNumberPanel.setStartLine(1);
        updateTitle("Untitled");
        globalScrollBar.setValue(0);
        globalScrollBar.setMaximum(SCROLL_RESOLUTION + globalScrollBar.getVisibleAmount());
        updateCursorStatus();
    }

    // Optimization: Defer background line indexing briefly after initial load so the new tab renders
    // immediately, and throttle status-bar / line-gutter updates to whole-percentage steps.
    private javax.swing.Timer deferredIndexerTimer;

    private void restartBackgroundIndexer() {
        if (deferredIndexerTimer != null && deferredIndexerTimer.isRunning()) {
            deferredIndexerTimer.stop();
        }
        lblIndexingStatus.setText("⚙ Indexing lines: 0%");
        final int[] lastPct = { 0 };
        fileManager.buildIndexCacheAsync((indexedChunk) -> {
            int total = Math.max(1, fileManager.getTotalChunks());
            int pct = (int) (((indexedChunk + 1) * 100.0) / total);

            if (indexedChunk < total - 1) {
                if (pct != lastPct[0]) {
                    lastPct[0] = pct;
                    lblIndexingStatus.setText("⚙ Indexing lines: " + pct + "%");
                }
            } else {
                lblIndexingStatus.setText(""); // Complete
            }

            // If the chunk we are currently looking at finishes indexing, snap the numbers
            // into place
            boolean updatedStartLine = false;
            if (indexedChunk >= loadedChunkIndex) {
                long exactLine = fileManager.getExactLineOffset(loadedChunkIndex);
                if (exactLine != -1 && exactLine != lineNumberPanel.getStartLine()) {
                    lineNumberPanel.setStartLine(exactLine);
                    updateCursorStatus();
                    updatedStartLine = true;
                }
            }
            if (updatedStartLine || indexedChunk == loadedChunkIndex || indexedChunk >= total - 1) {
                lineNumberPanel.repaint();
            }
        });
    }

    public void loadFile(File file) {
        loadFile(file, -1);
    }

    public void loadFile(File file, long explicitPosition) {
        // --- Capture global position if reloading the same file ---
        boolean isReload = (this.activeFile != null
                && this.activeFile.getAbsolutePath().equals(file.getAbsolutePath()));
        final long savedPosition = explicitPosition != -1 ? explicitPosition : (isReload ? getGlobalCaretByteOffset() : 0);

        if (deferredIndexerTimer != null && deferredIndexerTimer.isRunning()) {
            deferredIndexerTimer.stop();
        }
        fileManager.stopIndexer();

        this.activeFile = file;
        fileManager.setFile(file);
        this.lastKnownModifiedTime = file.lastModified();
        lblIndexingStatus.setText("⚙ Indexing lines: 0%");
        isDirty = false;
        setUnsavedChanges(false);
        loadedChunkIndex = 0;
        pendingTargetChunk = -1;
        fileSizeDateStatus = getFileInfoString(activeFile);

        documentCache.clear();
        globalUndoManager.discardAllEdits();

        // --- Create a callback to restore the position and start background indexing after the initial chunk loads ---
        Runnable postLoadAction = () -> {
            if ((isReload || explicitPosition != -1) && savedPosition > 0) {
                long fileLen = activeFile.length();
                // Ensure we don't try to place the cursor past the end if the file was
                // truncated by the external program
                long safePosition = Math.min(savedPosition, fileLen);

                // setGlobalSelection automatically handles chunk-switching if the saved
                // position is deep in the file!
                setGlobalSelection(safePosition, safePosition);
            }
            // Optimization: Start the background line indexer 15ms after the initial chunk is displayed
            // so the new file tab paints immediately without competing with disk I/O and EDT callbacks.
            deferredIndexerTimer = new javax.swing.Timer(15, e -> restartBackgroundIndexer());
            deferredIndexerTimer.setRepeats(false);
            deferredIndexerTimer.start();
        };

        try {
            // Pass the callback to applyStateUpdates
            applyStateUpdates(fileManager.loadCurrentChunk(false), 1, -1, postLoadAction);
        } catch (IOException ex) {
            showError("Failed to open file: " + ex.getMessage());
        }
    }

    public void autoSaveTo(File destFile) {
        try {
            fileManager.exportUnsavedContent(getCommitText(), destFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void recoverUnsavedContent(String content) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (!isCurrentlyPreview) {
                    isSystemEdit = true;
                    try {
                        textArea.setText(content);
                        textArea.setCaretPosition(0);
                        isDirty = true;
                        setUnsavedChanges(true);
                    } finally {
                        isSystemEdit = false;
                    }
                }
            } catch (Exception e) {}
        });
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
            this.fileSizeDateStatus = getFileInfoString(activeFile);
            setUnsavedChanges(false);
            isDirty = false;
            updateTitle(file.getName());
        } catch (Exception e) {
            showError("Could not save file: " + e.getMessage());
        }
    }

    public boolean saveSynchronously() {
        try {
            // --- Pass null to progressCallback since this runs entirely on the main UI
            // thread ---
            fileManager.saveAll(getCommitText(), null);
            updateStatusLabel(chunkStatus, getFileInfoString(activeFile));
            isDirty = false;
            setUnsavedChanges(false);
            restartBackgroundIndexer();
            lastKnownModifiedTime = activeFile.lastModified();
            firePropertyChange("fileSaved", null, activeFile);
            return true;
        } catch (Exception e) {
            showError("Failed to save file: " + e.getMessage());
            return false;
        }
    }

    // This method will perform the save operation directly on the calling thread,
    // blocking until it completes.
    public boolean saveAsSynchronously(File file) {
        try {
            this.activeFile = file;
            fileManager.setCurrentFile(file);
            // Perform the I/O on the current thread (blocking)
            fileManager.saveAll(getCommitText(), null);
            updateStatusLabel(chunkStatus, getFileInfoString(activeFile));

            // Update state synchronously
            isDirty = false;
            setUnsavedChanges(false);
            restartBackgroundIndexer();
            lastKnownModifiedTime = activeFile.lastModified();
            firePropertyChange("fileSaved", null, activeFile);
            return true;
        } catch (Exception e) {
            showError("Failed to save file: " + e.getMessage());
            return false;
        }
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

    public JTextArea getTextArea() {
        return textArea;
    }

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
                    pendingTargetChunk = -1;
                    applyStateUpdates(get(), 0, -1, null);
                    updateStatusLabel(chunkStatus, getFileInfoString(activeFile));
                    isDirty = false;
                    setUnsavedChanges(false);
                    restartBackgroundIndexer();
                    if (activeFile != null) {
                        lastKnownModifiedTime = activeFile.lastModified();
                        AdvancedTextEditorPanel.this.firePropertyChange("fileSaved", null, activeFile);
                    }
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
        // Fires a standard Swing property change event so the parent window can update
        // its JFrame title
        firePropertyChange("editorTitle", oldTitle, newTitle);

        // Dynamically update the search dialog title if it's currently open
        if (searchDialog != null && searchDialog.isVisible()) {
            searchDialog.setTitle("Search & Replace - " + newTitle);
        }
    }

    // --- Explicitly override native keys to map directly to our stripped methods
    // ---
    private void toggleComment(String commentPrefix) {
        if (isCurrentlyPreview) return;
        try {
            int startPos = textArea.getSelectionStart();
            int endPos = textArea.getSelectionEnd();
            Document doc = textArea.getDocument();
            
            int startLine = textArea.getLineOfOffset(startPos);
            int endLine = textArea.getLineOfOffset(endPos);
            
            if (endPos == textArea.getLineStartOffset(endLine) && endLine > startLine) {
                endLine--;
            }
            
            boolean allCommented = true;
            for (int i = startLine; i <= endLine; i++) {
                int lineStart = textArea.getLineStartOffset(i);
                int lineEnd = textArea.getLineEndOffset(i);
                String lineText = doc.getText(lineStart, lineEnd - lineStart);
                if (lineText.length() > 0 && !lineText.equals("\n") && !lineText.equals("\r\n")) {
                    if (!lineText.startsWith(commentPrefix)) {
                        allCommented = false;
                        break;
                    }
                }
            }
            
            int replaceStart = textArea.getLineStartOffset(startLine);
            int replaceEnd = textArea.getLineEndOffset(endLine);
            StringBuilder replacement = new StringBuilder();
            
            for (int i = startLine; i <= endLine; i++) {
                int lineStart = textArea.getLineStartOffset(i);
                int lineEnd = textArea.getLineEndOffset(i);
                String lineText = doc.getText(lineStart, lineEnd - lineStart);
                
                if (lineText.length() == 0 || lineText.equals("\n") || lineText.equals("\r\n")) {
                    replacement.append(lineText);
                } else {
                    if (allCommented) {
                        replacement.append(lineText.substring(commentPrefix.length()));
                    } else {
                        replacement.append(commentPrefix).append(lineText);
                    }
                }
            }
            
            if (doc instanceof AbstractDocument) {
                ((AbstractDocument) doc).replace(replaceStart, replaceEnd - replaceStart, replacement.toString(), null);
            } else {
                doc.remove(replaceStart, replaceEnd - replaceStart);
                doc.insertString(replaceStart, replacement.toString(), null);
            }
            
            textArea.setSelectionStart(replaceStart);
            textArea.setSelectionEnd(replaceStart + replacement.length());
            
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void setupKeyboardShortcuts() {
        InputMap im = textArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = textArea.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "customCopy");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.CTRL_DOWN_MASK), "customCopy");
        am.put("customCopy", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                copy();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), "customCut");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, InputEvent.SHIFT_DOWN_MASK), "customCut");
        am.put("customCut", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                cut();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "customPaste");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK), "customPaste");
        am.put("customPaste", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                paste();
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "customTab");
        am.put("customTab", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isCurrentlyPreview) return;
                try {
                    int startPos = textArea.getSelectionStart();
                    int endPos = textArea.getSelectionEnd();
                    Document doc = textArea.getDocument();

                    if (startPos != endPos) {
                        int startLine = textArea.getLineOfOffset(startPos);
                        int endLine = textArea.getLineOfOffset(endPos);
                        
                        if (endPos == textArea.getLineStartOffset(endLine) && endLine > startLine) {
                            endLine--;
                        }
                        
                        int replaceStart = textArea.getLineStartOffset(startLine);
                        int replaceEnd = textArea.getLineEndOffset(endLine);
                        
                        StringBuilder replacement = new StringBuilder();
                        for (int i = startLine; i <= endLine; i++) {
                            int lineStart = textArea.getLineStartOffset(i);
                            int lineEnd = textArea.getLineEndOffset(i);
                            String lineText = doc.getText(lineStart, lineEnd - lineStart);
                            if (lineText.length() > 0 && (lineText.equals("\n") || lineText.equals("\r\n"))) {
                                replacement.append(lineText);
                            } else {
                                replacement.append("\t").append(lineText);
                            }
                        }
                        
                        if (doc instanceof AbstractDocument) {
                            ((AbstractDocument) doc).replace(replaceStart, replaceEnd - replaceStart, replacement.toString(), null);
                        } else {
                            doc.remove(replaceStart, replaceEnd - replaceStart);
                            doc.insertString(replaceStart, replacement.toString(), null);
                        }
                        
                        textArea.setSelectionStart(replaceStart);
                        textArea.setSelectionEnd(replaceStart + replacement.length());
                    } else {
                        textArea.replaceSelection("\t");
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "customShiftTab");
        am.put("customShiftTab", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isCurrentlyPreview) return;
                try {
                    int startPos = textArea.getSelectionStart();
                    int endPos = textArea.getSelectionEnd();
                    Document doc = textArea.getDocument();
                    int tabSize = textArea.getTabSize();

                    if (startPos != endPos) {
                        int startLine = textArea.getLineOfOffset(startPos);
                        int endLine = textArea.getLineOfOffset(endPos);
                        
                        if (endPos == textArea.getLineStartOffset(endLine) && endLine > startLine) {
                            endLine--;
                        }
                        
                        int replaceStart = textArea.getLineStartOffset(startLine);
                        int replaceEnd = textArea.getLineEndOffset(endLine);
                        
                        StringBuilder replacement = new StringBuilder();
                        for (int i = startLine; i <= endLine; i++) {
                            int lineStart = textArea.getLineStartOffset(i);
                            int lineEnd = textArea.getLineEndOffset(i);
                            String lineText = doc.getText(lineStart, lineEnd - lineStart);
                            
                            if (lineText.length() > 0 && lineText.charAt(0) == '\t') {
                                replacement.append(lineText.substring(1));
                            } else if (lineText.length() > 0 && lineText.charAt(0) == ' ') {
                                int spaces = 0;
                                while (spaces < tabSize && spaces < lineText.length() && lineText.charAt(spaces) == ' ') {
                                    spaces++;
                                }
                                replacement.append(lineText.substring(spaces));
                            } else {
                                replacement.append(lineText);
                            }
                        }
                        
                        if (doc instanceof AbstractDocument) {
                            ((AbstractDocument) doc).replace(replaceStart, replaceEnd - replaceStart, replacement.toString(), null);
                        } else {
                            doc.remove(replaceStart, replaceEnd - replaceStart);
                            doc.insertString(replaceStart, replacement.toString(), null);
                        }
                        
                        textArea.setSelectionStart(replaceStart);
                        textArea.setSelectionEnd(replaceStart + replacement.length());
                    } else {
                        int pos = textArea.getCaretPosition();
                        if (pos == 0) return;
                        
                        String leftChar = doc.getText(pos - 1, 1);
                        if ("\t".equals(leftChar)) {
                            doc.remove(pos - 1, 1);
                            return;
                        }
                        
                        int spacesCount = 0;
                        for (int i = 1; i <= tabSize && pos - i >= 0; i++) {
                            if (" ".equals(doc.getText(pos - i, 1))) {
                                spacesCount++;
                            } else {
                                break;
                            }
                        }
                        
                        if (spacesCount == tabSize) {
                            doc.remove(pos - tabSize, tabSize);
                            return;
                        }
                        
                        int newPos = Math.max(0, pos - 4);
                        textArea.setCaretPosition(newPos);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        // --- Custom CRLF Handling ---
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "customBackspace");
        am.put("customBackspace", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isCurrentlyPreview) return;
                try {
                    int start = textArea.getSelectionStart();
                    int end = textArea.getSelectionEnd();
                    if (start != end) {
                        textArea.replaceSelection("");
                        return;
                    }
                    int pos = textArea.getCaretPosition();
                    if (pos == 0) return;
                    Document doc = textArea.getDocument();
                    if (pos >= 2 && "\n".equals(doc.getText(pos - 1, 1)) && "\r".equals(doc.getText(pos - 2, 1))) {
                        doc.remove(pos - 2, 2);
                    } else {
                        Action defaultAction = textArea.getActionMap().get(DefaultEditorKit.deletePrevCharAction);
                        if (defaultAction != null) defaultAction.actionPerformed(e);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "customDelete");
        am.put("customDelete", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isCurrentlyPreview) return;
                try {
                    int start = textArea.getSelectionStart();
                    int end = textArea.getSelectionEnd();
                    if (start != end) {
                        textArea.replaceSelection("");
                        return;
                    }
                    int pos = textArea.getCaretPosition();
                    Document doc = textArea.getDocument();
                    if (pos >= doc.getLength()) return;
                    if (pos <= doc.getLength() - 2 && "\r".equals(doc.getText(pos, 1)) && "\n".equals(doc.getText(pos + 1, 1))) {
                        doc.remove(pos, 2);
                    } else {
                        Action defaultAction = textArea.getActionMap().get(DefaultEditorKit.deleteNextCharAction);
                        if (defaultAction != null) defaultAction.actionPerformed(e);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "customLeft");
        am.put("customLeft", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isBlockSelecting || isCurrentlyPreview) {
                    Action defaultAction = textArea.getActionMap().get(DefaultEditorKit.backwardAction);
                    if (defaultAction != null) defaultAction.actionPerformed(e);
                    return;
                }
                try {
                    int pos = textArea.getCaretPosition();
                    if (pos == 0) return;
                    Document doc = textArea.getDocument();
                    if (pos >= 2 && "\n".equals(doc.getText(pos - 1, 1)) && "\r".equals(doc.getText(pos - 2, 1))) {
                        textArea.setCaretPosition(pos - 2);
                    } else {
                        Action defaultAction = textArea.getActionMap().get(DefaultEditorKit.backwardAction);
                        if (defaultAction != null) defaultAction.actionPerformed(e);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK), "customShiftLeft");
        am.put("customShiftLeft", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isCurrentlyPreview) return;
                try {
                    int pos = textArea.getCaretPosition();
                    if (pos == 0) return;
                    Document doc = textArea.getDocument();
                    if (pos >= 2 && "\n".equals(doc.getText(pos - 1, 1)) && "\r".equals(doc.getText(pos - 2, 1))) {
                        textArea.moveCaretPosition(pos - 2);
                    } else {
                        Action defaultAction = textArea.getActionMap().get(DefaultEditorKit.selectionBackwardAction);
                        if (defaultAction != null) defaultAction.actionPerformed(e);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "customRight");
        am.put("customRight", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isBlockSelecting || isCurrentlyPreview) {
                    Action defaultAction = textArea.getActionMap().get(DefaultEditorKit.forwardAction);
                    if (defaultAction != null) defaultAction.actionPerformed(e);
                    return;
                }
                try {
                    int pos = textArea.getCaretPosition();
                    Document doc = textArea.getDocument();
                    if (pos >= doc.getLength()) return;
                    if (pos <= doc.getLength() - 2 && "\r".equals(doc.getText(pos, 1)) && "\n".equals(doc.getText(pos + 1, 1))) {
                        textArea.setCaretPosition(pos + 2);
                    } else {
                        Action defaultAction = textArea.getActionMap().get(DefaultEditorKit.forwardAction);
                        if (defaultAction != null) defaultAction.actionPerformed(e);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK), "customShiftRight");
        am.put("customShiftRight", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isCurrentlyPreview) return;
                try {
                    int pos = textArea.getCaretPosition();
                    Document doc = textArea.getDocument();
                    if (pos >= doc.getLength()) return;
                    if (pos <= doc.getLength() - 2 && "\r".equals(doc.getText(pos, 1)) && "\n".equals(doc.getText(pos + 1, 1))) {
                        textArea.moveCaretPosition(pos + 2);
                    } else {
                        Action defaultAction = textArea.getActionMap().get(DefaultEditorKit.selectionForwardAction);
                        if (defaultAction != null) defaultAction.actionPerformed(e);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        // --- Custom Home/End Keys to bypass Soft-Wraps ---
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "customHome");
        am.put("customHome", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isBlockSelecting || isCurrentlyPreview)
                    return;
                textArea.setCaretPosition(getTrueLineStart(textArea.getCaretPosition()));
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.SHIFT_DOWN_MASK), "customShiftHome");
        am.put("customShiftHome", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isCurrentlyPreview)
                    return;
                textArea.moveCaretPosition(getTrueLineStart(textArea.getCaretPosition()));
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), "customEnd");
        am.put("customEnd", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isBlockSelecting || isCurrentlyPreview)
                    return;
                textArea.setCaretPosition(getTrueLineEnd(textArea.getCaretPosition()));
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.SHIFT_DOWN_MASK), "customShiftEnd");
        am.put("customShiftEnd", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (isCurrentlyPreview)
                    return;
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

        // --- Comment Toggles ---
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, InputEvent.CTRL_DOWN_MASK), "toggleSlashComment");
        am.put("toggleSlashComment", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                toggleComment("//");
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_3, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "toggleHashComment");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_NUMBER_SIGN, InputEvent.CTRL_DOWN_MASK), "toggleHashCommentAlt");
        am.put("toggleHashComment", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                toggleComment("#");
            }
        });
        am.put("toggleHashCommentAlt", am.get("toggleHashComment"));

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "showSearch");
        am.put("showSearch", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                showSearchDialog();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "none"); // Disable default backspace
                                                                                          // behavior

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK), "showGotoLine");
        am.put("showGotoLine", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                showGotoDialog();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        am.put("undo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                undo();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        am.put("redo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                redo();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), "zoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, InputEvent.CTRL_DOWN_MASK), "zoomInNumPad");
        am.put("zoomIn", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                adjustFontSize(2);
            }
        });
        am.put("zoomInNumPad", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                adjustFontSize(2);
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "zoomOut");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, InputEvent.CTRL_DOWN_MASK), "zoomOutNumPad");
        am.put("zoomOut", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                adjustFontSize(-2);
            }
        });
        am.put("zoomOutNumPad", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                adjustFontSize(-2);
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "blockUp");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "blockDown");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "blockLeft");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "blockRight");

        am.put("blockUp", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                moveBlockSelection(0, -1);
            }
        });
        am.put("blockDown", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                moveBlockSelection(0, 1);
            }
        });
        am.put("blockLeft", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                moveBlockSelection(-1, 0);
            }
        });
        am.put("blockRight", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                moveBlockSelection(1, 0);
            }
        });

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
                if (isLoadingChunk || isNavigating)
                    return;

                int caretPos = textArea.getCaretPosition();
                int currentLine = 0;
                int totalLines = 0;

                try {
                    currentLine = textArea.getLineOfOffset(caretPos);
                    totalLines = textArea.getLineCount();
                } catch (Exception ex) {
                    if (direction > 0 && caretPos == textArea.getDocument().getLength()
                            && loadedChunkIndex < fileManager.getTotalChunks() - 1) {
                        triggerAsyncLoad(loadedChunkIndex + 1, direction, -1, false, null);
                    } else if (direction < 0 && caretPos == 0 && loadedChunkIndex > 0) {
                        triggerAsyncLoad(loadedChunkIndex - 1, direction, -1, false, null);
                    } else if (originalAction != null) {
                        originalAction.actionPerformed(e);
                    }
                    return;
                }

                // Trigger chunk load if on the boundary lines, otherwise rely on native cursor
                // movements
                if (direction > 0 && currentLine == totalLines - 1
                        && loadedChunkIndex < fileManager.getTotalChunks() - 1) {
                    triggerAsyncLoad(loadedChunkIndex + 1, direction, -1, false, null);
                } else if (direction < 0 && currentLine == 0 && loadedChunkIndex > 0) {
                    triggerAsyncLoad(loadedChunkIndex - 1, direction, -1, false, null);
                } else if (originalAction != null) {
                    originalAction.actionPerformed(e);
                }
            }
        });
    }

    public void triggerAsyncLoad(int targetChunk, int direction, double localPercentForScroll, boolean requestPreview,
            Runnable postLoadAction) {
        if (isNavigating)
            return;
        settleTimer.stop();

        int total = Math.max(1, fileManager.getTotalChunks());
        if (targetChunk >= total)
            targetChunk = total - 1;
        if (targetChunk < 0)
            targetChunk = 0;

        if (targetChunk == loadedChunkIndex && !isCurrentlyPreview && !requestPreview) {
            if (postLoadAction != null)
                postLoadAction.run();
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
        updateStatusLabel(String.format("Chunk %d of %d", targetChunk + 1, total), fileSizeDateStatus);

        // Optimization: Skip extracting the 25MB commit string when the current chunk has no unsaved edits,
        // and pre-build/populate the PlainDocument off the EDT inside SwingWorker.doInBackground().
        boolean wasDirty = isDirty && !isCurrentlyPreview;
        String currentText = wasDirty ? getCommitText() : "";
        isDirty = false;
        if (wasDirty) {
            // Edits can move where following chunks start within their line (soft-wrap alignment)
            final int editedChunk = loadedChunkIndex;
            documentCache.keySet().removeIf(key -> key > editedChunk);
        }

        final int finalTarget = targetChunk;
        final Document cachedDoc = (!requestPreview) ? documentCache.get(finalTarget) : null;

        // Assign to the tracking variable
        activeChunkWorker = new SwingWorker<LargeFileManager.ChunkState, Void>() {
            private Document preparedDoc = null;
            private String preparedHiddenNewline = "";

            @Override
            protected LargeFileManager.ChunkState doInBackground() throws Exception {
                if (wasDirty) {
                    fileManager.commitCurrentChunk(currentText);
                }
                // If cancelled before we even start the heavy IO, abort.
                if (isCancelled())
                    throw new java.util.concurrent.CancellationException();
                LargeFileManager.ChunkState state = fileManager.navigateToIndex(finalTarget, requestPreview);
                if (state != null && cachedDoc == null && !isCancelled()) {
                    // Optimization: In no-wrap mode, soft-wrap scrollbar preview chunks at 200 chars so the
                    // 64KB preview fills the viewport vertically and scrolls smoothly during thumb dragging.
                    int wrapLen = (state.isPreview() && !textArea.getLineWrap())
                            ? Math.min(getEditorMaxLineLength(), 200)
                            : getEditorMaxLineLength();
                    Document newDoc = createWrappedDocument(wrapLen, resolveChunkStartColumn(state, Integer.MAX_VALUE));
                    String content = state.content();
                    String boundaryNewline = "";
                    if (!isBinaryMode() && state.hasNext()) {
                        if (content.endsWith("\r\n")) {
                            boundaryNewline = "\r\n";
                            content = content.substring(0, content.length() - 2);
                        } else if (content.endsWith("\n")) {
                            boundaryNewline = "\n";
                            content = content.substring(0, content.length() - 1);
                        }
                    }
                    if (isBinaryMode()) {
                        content = encodeBinaryForView(content);
                    } else {
                        content = stripControlAndFormatChars(content);
                    }
                    if (!isCancelled()) {
                        newDoc.insertString(0, content, null);
                        preparedDoc = newDoc;
                        preparedHiddenNewline = boundaryNewline;
                    }
                }
                return state;
            }

            @Override
            protected void done() {
                try {
                    // If the user scrolled past this chunk, quietly exit without updating the UI
                    if (isCancelled())
                        return;

                    LargeFileManager.ChunkState state = get();
                    if (state != null) {
                        applyStateUpdates(state, direction, localPercentForScroll, postLoadAction, preparedDoc,
                                preparedHiddenNewline);
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
                if (wasEditorFocused && !isCurrentlyPreview)
                    textArea.requestFocusInWindow();
            }
        };

        activeChunkWorker.execute();
    }

    /** Safely terminates background threads when this tab is permanently closed. */
    public void dispose() {
        if (fileWatcherTimer != null) {
            fileWatcherTimer.stop();
        }
        if (fileManager != null) {
            fileManager.stopIndexer();
        }
    }

    private boolean hasPromptedForDelete = false;

    private void checkExternalModification() {
        // Do not interrupt the user if the editor is actively busy loading or saving
        if (activeFile == null || isNavigating || isLoadingChunk)
            return;

        // --- Only prompt if the user is actively viewing this specific tab! ---
        if (!this.isShowing())
            return;

        if (!activeFile.exists()) {
            if (!hasPromptedForDelete) {
                fileWatcherTimer.stop();
                hasPromptedForDelete = true;

                String msg = "The file '" + activeFile.getName()
                        + "' has been deleted externally.\n\nWould you like to keep the file opened in the editor?";
                int result = DialogUtil.showConfirmDialog(SwingUtilities.getWindowAncestor(this), msg,
                        "File Deleted Externally", JOptionPane.YES_NO_OPTION);

                if (result == JOptionPane.YES_OPTION) {
                    setUnsavedChanges(true);
                    fileWatcherTimer.start(); // keep running, but hasPromptedForDelete prevents re-prompting
                } else {
                    firePropertyChange("requestClose", false, true);
                }
            }
            return;
        } else {
            hasPromptedForDelete = false;
        }

        long currentMod = activeFile.lastModified();

        // Use a 1000ms buffer to avoid false positives from file system rounding quirks
        if (lastKnownModifiedTime > 0 && currentMod > lastKnownModifiedTime + 1000) {
            fileWatcherTimer.stop(); // Pause the timer so we don't spam the user with dialogs

            String msg = "The file '" + activeFile.getName()
                    + "' has been modified by another program.\n\nWould you like to reload it from disk?";

            // Append the warning if Bearit has unsaved changes
            if (hasUnsavedChanges()) {
                msg += "\n\nWARNING: You have unsaved changes in Bearit.\nReloading will DISCARD your changes!";
            }

            int result = DialogUtil.showConfirmDialog(SwingUtilities.getWindowAncestor(this), msg,
                    "File Modified Externally", JOptionPane.YES_NO_OPTION);

            if (result == JOptionPane.YES_OPTION) {
                // The user chose to reload. loadFile() will handle wiping the document and
                // resetting the UI.
                loadFile(activeFile);
            } else {
                // The user chose to ignore the external changes.
                // Update our tracker to the new time so we don't ask again unless it changes a
                // SECOND time.
                lastKnownModifiedTime = currentMod;
            }

            fileWatcherTimer.start(); // Resume watching
        }
    }

    private void syncLocalToGlobalScroll() {
        if (isNavigating || isLoadingChunk)
            return;
        isSyncingScroll = true;
        try {
            // Dynamically calculate scrollbar arrows & track clicks ---
            int totalLines = Math.max(1, textArea.getLineCount());
            int fontHeight = Math.max(1, textArea.getFontMetrics(textArea.getFont()).getHeight());
            int visibleLines = Math.max(1, scrollPane.getViewport().getHeight() / fontHeight);

            globalScrollBar.setUnitIncrement(Math.max(1, (int) ((3.0 / totalLines) * SCROLL_RESOLUTION)));
            globalScrollBar
                    .setBlockIncrement(Math.max(1, (int) (((double) visibleLines / totalLines) * SCROLL_RESOLUTION)));
            // -------------------------------------------------------------------

            int totalChunks = fileManager.getTotalChunks();
            int maxScrollRange = Math.max(1, totalChunks) * SCROLL_RESOLUTION;
            globalScrollBar.setMaximum(maxScrollRange + globalScrollBar.getVisibleAmount());

            JScrollBar localBar = scrollPane.getVerticalScrollBar();
            double localMax = localBar.getMaximum() - localBar.getVisibleAmount();
            double localPercent = localMax == 0 ? 0 : localBar.getValue() / localMax;

            int globalValue = (loadedChunkIndex * SCROLL_RESOLUTION) + (int) (localPercent * SCROLL_RESOLUTION);
            globalScrollBar.setValue(globalValue);
        } catch (Exception ex) {
        } finally {
            isSyncingScroll = false;
        }
    }

    private void syncGlobalToLocalScroll() {
        if (isNavigating)
            return;
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

            updateStatusLabel(String.format("Chunk %d of %d", targetChunk + 1, total), fileSizeDateStatus);

            if (isLoadingChunk) {
                pendingTargetChunk = targetChunk;
                pendingLocalPercent = localPercent;
                pendingPreviewRequest = true;
            } else if (targetChunk != loadedChunkIndex) {
                isSyncingScroll = false;
                triggerAsyncLoad(targetChunk, 0, localPercent, true, null);
            } else {
                JScrollBar localBar = scrollPane.getVerticalScrollBar();
                int targetLocalValue = (int) (localPercent * (localBar.getMaximum() - localBar.getVisibleAmount()));
                localBar.setValue(targetLocalValue);

                lastRequestedLocalPercent = localPercent;
                if (isCurrentlyPreview)
                    settleTimer.restart();
            }
        } catch (Exception ex) {
        } finally {
            isSyncingScroll = false;
        }
    }

    private void applyStateUpdates(LargeFileManager.ChunkState state, int direction, double localPercentForScroll,
            Runnable postLoadAction) {
        applyStateUpdates(state, direction, localPercentForScroll, postLoadAction, null, null);
    }

    // Optimization: Accept a pre-built Document constructed off the EDT by the background chunk worker
    // so switching or previewing chunks only swaps the document reference on the UI thread.
    private void applyStateUpdates(LargeFileManager.ChunkState state, int direction, double localPercentForScroll,
            Runnable postLoadAction, Document prebuiltDoc, String prebuiltHiddenNewline) {
        isNavigating = true;
        loadedChunkIndex = state.chunkIndex();
        isCurrentlyPreview = state.isPreview();
        currentChunkStartOffset = state.startOffset();

        Document doc = documentCache.get(loadedChunkIndex);

        if (doc != null && !isCurrentlyPreview) {
            Object hbn = doc.getProperty("bearit.hiddenBoundaryNewline");
            hiddenBoundaryNewline = (hbn instanceof String) ? (String) hbn : "";
            textArea.setDocument(doc);
        } else {
            Document newDoc;
            if (prebuiltDoc != null) {
                hiddenBoundaryNewline = prebuiltHiddenNewline != null ? prebuiltHiddenNewline : "";
                newDoc = prebuiltDoc;
            } else {
                int wrapLen = (isCurrentlyPreview && !textArea.getLineWrap())
                        ? Math.min(getEditorMaxLineLength(), 200)
                        : getEditorMaxLineLength();
                newDoc = createWrappedDocument(wrapLen, resolveChunkStartColumn(state, 2));
                try {
                    String content = state.content();
                    // Strip the trailing newline used strictly for chunk file boundaries
                    // --- CONDITIONAL BOUNDARY STRIPPING ---
                    hiddenBoundaryNewline = "";
                    if (!isBinaryMode() && state.hasNext()) {
                        if (content.endsWith("\r\n")) {
                            hiddenBoundaryNewline = "\r\n";
                            content = content.substring(0, content.length() - 2);
                        } else if (content.endsWith("\n")) {
                            hiddenBoundaryNewline = "\n";
                            content = content.substring(0, content.length() - 1);
                        }
                    }
                    // --- handle binary encoding logic ---
                    if (isBinaryMode()) {
                        content = encodeBinaryForView(content);
                    } else {
                        // Fallback to prevent crashes in minified UTF-8 text files
                        content = stripControlAndFormatChars(content);
                    }

                    newDoc.insertString(0, content, null);
                } catch (Exception ex) {
                }
            }

            newDoc.addDocumentListener(editorDocumentListener);
            newDoc.addUndoableEditListener(e -> {
                if (!isNavigating && !isCurrentlyPreview) {
                    globalUndoManager.addEdit(new ChunkAwareEdit(loadedChunkIndex, e.getEdit()));
                }
            });

            textArea.setDocument(newDoc);

            if (!isCurrentlyPreview) {
                newDoc.putProperty("bearit.hiddenBoundaryNewline", hiddenBoundaryNewline);
                documentCache.put(loadedChunkIndex, newDoc);
            }
        }

        textArea.setEnabled(true);
        if (textArea.getCaret() != null) {
            textArea.getCaret().setBlinkRate((readOnlyMode || isCurrentlyPreview) ? 0 : 500);
            textArea.getCaret().setVisible(true);
        }
        chunkLoadProgressBar.setVisible(false);

        lineNumberPanel.setStartLine(state.startLine());
        updateTitle(state.fileName() + (isCurrentlyPreview ? " [PREVIEW]" : ""));

        int maxScrollRange = state.totalChunks() * SCROLL_RESOLUTION;
        globalScrollBar.setMaximum(maxScrollRange + globalScrollBar.getVisibleAmount());

        if (pendingTargetChunk == -1) {
            updateStatusLabel(state.statusText(), fileSizeDateStatus);
        }

        SwingUtilities.invokeLater(() -> {
            if (localPercentForScroll >= 0) {
                JScrollBar localBar = scrollPane.getVerticalScrollBar();
                int targetLocalValue = (int) (localPercentForScroll
                        * (localBar.getMaximum() - localBar.getVisibleAmount()));
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
                    if (postLoadAction != null)
                        postLoadAction.run();
                }
            } else {
                syncLocalToGlobalScroll();

                if (wasEditorFocused && !isCurrentlyPreview) {
                    textArea.requestFocusInWindow();
                }

                if (postLoadAction != null)
                    postLoadAction.run();

                applyMatchingHighlights();
            }
        });
    }

    private void applyMatchingHighlights() {
        javax.swing.text.Highlighter highlighter = textArea.getHighlighter();
        javax.swing.text.Highlighter.Highlight[] highlights = highlighter.getHighlights();
        for (javax.swing.text.Highlighter.Highlight h : highlights) {
            if (h.getPainter() == matchHighlightPainter) {
                highlighter.removeHighlight(h);
            }
        }

        if (currentHighlightText != null && !currentHighlightText.isEmpty()) {
            try {
                // Determine visible range to improve performance
                // Optimization: When word wrap is disabled, restrict match highlighting on each visible line
                // to the horizontally visible character slice [visStart, visEnd] instead of extracting and
                // searching ~825,000 characters across the full 15,000-char width of all visible lines.
                Rectangle viewRect = scrollPane.getViewport().getViewRect();
                Document doc = textArea.getDocument();
                int docLen = doc.getLength();
                if (!textArea.getLineWrap()) {
                    Element root = doc.getDefaultRootElement();
                    int elemCount = root.getElementCount();
                    int startLineIdx = Math.max(0, root.getElementIndex(textArea.viewToModel(new Point(0, viewRect.y))));
                    int endLineIdx = Math.min(elemCount - 1,
                            root.getElementIndex(textArea.viewToModel(new Point(0, viewRect.y + viewRect.height))));
                    int padPx = Math.max(64, currentHighlightText.length() * 16);
                    int leftX = Math.max(0, viewRect.x - padPx);
                    int rightX = viewRect.x + viewRect.width + padPx;
                    int matchLen = currentHighlightText.length();
                    for (int lineIdx = startLineIdx; lineIdx <= endLineIdx; lineIdx++) {
                        Element lineElem = root.getElement(lineIdx);
                        int lineStart = lineElem.getStartOffset();
                        int lineEnd = Math.min(docLen, lineElem.getEndOffset());
                        if (lineEnd <= lineStart) {
                            continue;
                        }
                        int lineY = textArea.modelToView2D(lineStart).getBounds().y;
                        int visStart = Math.max(lineStart,
                                Math.min(lineEnd, textArea.viewToModel2D(new Point(leftX, lineY))));
                        int visEnd = Math.max(visStart,
                                Math.min(lineEnd, textArea.viewToModel2D(new Point(rightX, lineY)) + matchLen));
                        if (visEnd <= visStart) {
                            continue;
                        }
                        String lineSlice = doc.getText(visStart, visEnd - visStart);
                        int index = 0;
                        while ((index = lineSlice.indexOf(currentHighlightText, index)) >= 0) {
                            highlighter.addHighlight(visStart + index, visStart + index + matchLen,
                                    matchHighlightPainter);
                            index += matchLen;
                        }
                    }
                    return;
                }
                int startOffset = textArea.viewToModel(viewRect.getLocation());
                int endOffset = textArea
                        .viewToModel(new Point(viewRect.x + viewRect.width, viewRect.y + viewRect.height));

                if (startOffset < 0)
                    startOffset = 0;
                if (endOffset < 0 || endOffset > docLen) {
                    endOffset = docLen;
                }

                if (startOffset >= endOffset)
                    return;

                String text = textArea.getText(startOffset, endOffset - startOffset);
                int index = 0;
                while ((index = text.indexOf(currentHighlightText, index)) >= 0) {
                    highlighter.addHighlight(startOffset + index, startOffset + index + currentHighlightText.length(),
                            matchHighlightPainter);
                    index += currentHighlightText.length();
                }
            } catch (Exception ex) {
            }
        }
    }

    private void triggerAutoNavigate(int direction) {
        if (isLoadingChunk || isNavigating)
            return;
        triggerAsyncLoad(loadedChunkIndex + direction, direction, -1, false, null);
    }

    private void showError(String message) {
        // JOptionPane.showMessageDialog(this, message, "System IO Exception Error",
        // JOptionPane.ERROR_MESSAGE);
        DialogUtil.showMessageDialog(this, message, "System IO Exception Error", JOptionPane.ERROR_MESSAGE);
    }

    public void appendText(String text) {
        boolean wasSystemEdit = isSystemEdit;
        isSystemEdit = true; // Programmatic append (e.g. tool output) must bypass the read-only filter
        try {
            Document doc = textArea.getDocument();
            doc.insertString(doc.getLength(), text, null);
            textArea.setCaretPosition(doc.getLength());
        } catch (Exception e) {
        } finally {
            isSystemEdit = wasSystemEdit;
        }
    }

    public void setCustomTitle(String title) {
        updateTitle(title);
    }

    public boolean isTransient() {
        return isTransient;
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

    public void updateSearchHistory(String target) {
        if (target == null || target.isEmpty())
            return;
        try {
            if (searchPropertiesListener != null) {
                searchPropertiesListener.addSearchHistory(target);
            }
            updateComboModel(comboSearch, target);
        } catch (Exception e) {
        }
    }

    private void updateReplaceHistory(String target) {
        if (target == null || target.isEmpty())
            return;
        try {
            if (searchPropertiesListener != null) {
                searchPropertiesListener.addReplaceHistory(target);
            }
            updateComboModel(comboReplace, target);
        } catch (Exception e) {
        }
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

    // --- Added Search Properties Listener ---
    public interface SearchPropertiesListener {
        java.util.List<String> getSearchHistory();

        void addSearchHistory(String term);

        java.util.List<String> getReplaceHistory();

        void addReplaceHistory(String term);

        boolean isSearchCaseInsensitive();

        void setSearchCaseInsensitive(boolean caseInsensitive);

        boolean isSearchRegex();

        void setSearchRegex(boolean regex);

        boolean isSearchAllTabs();

        void setSearchAllTabs(boolean allTabs);
    }

    public void setSearchPropertiesListener(SearchPropertiesListener listener) {
        this.searchPropertiesListener = listener;
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

            // --- Escape Key to Close ---
            searchDialog.getRootPane().registerKeyboardAction(e -> searchDialog.setVisible(false),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);

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
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 0;
            gbc.gridheight = 1;
            inputPanel.add(new JLabel("Find:"), gbc);

            // --- ROW 0: Find Combo Box ---
            gbc.gridx = 1;
            gbc.gridy = 0;
            gbc.weightx = 1.0;
            comboSearch = new JComboBox<>();
            comboSearch.setEditable(true);
            comboSearch.setPreferredSize(new Dimension(250, 26));
            inputPanel.add(comboSearch, gbc);

            // --- ROW 0 & 1: Swap Button (Spans vertically across both fields) ---
            gbc.gridx = 2;
            gbc.gridy = 0;
            gbc.weightx = 0;
            gbc.gridheight = 2;
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
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.weightx = 0;
            gbc.gridheight = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            inputPanel.add(new JLabel("Replace:"), gbc);

            // --- ROW 1: Replace Combo Box ---
            gbc.gridx = 1;
            gbc.gridy = 1;
            gbc.weightx = 1.0;
            comboReplace = new JComboBox<>();
            comboReplace.setEditable(true);
            comboReplace.setPreferredSize(new Dimension(250, 26));
            inputPanel.add(comboReplace, gbc);

            // --- ROW 2: Checkboxes (Wrapped in a horizontal panel) ---
            JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            chkCaseInsensitive = new JCheckBox("Case Insensitive");
            chkRegex = new JCheckBox("Regular Expression");
            chkAllTabs = new JCheckBox("All Open Tabs");
            chkSearchInColumn = new JCheckBox("Search In Column");

            // Load saved states
            if (searchPropertiesListener != null) {
                chkCaseInsensitive.setSelected(searchPropertiesListener.isSearchCaseInsensitive());
                chkRegex.setSelected(searchPropertiesListener.isSearchRegex());
                chkAllTabs.setSelected(searchPropertiesListener.isSearchAllTabs());
            }
            // Save states dynamically when toggled
            chkCaseInsensitive.addActionListener(e -> {
                if (searchPropertiesListener != null)
                    searchPropertiesListener.setSearchCaseInsensitive(chkCaseInsensitive.isSelected());
            });
            chkRegex.addActionListener(e -> {
                if (searchPropertiesListener != null)
                    searchPropertiesListener.setSearchRegex(chkRegex.isSelected());
            });
            chkAllTabs.addActionListener(e -> {
                if (searchPropertiesListener != null)
                    searchPropertiesListener.setSearchAllTabs(chkAllTabs.isSelected());
            });

            optionsPanel.add(chkCaseInsensitive);
            optionsPanel.add(Box.createHorizontalStrut(15)); // Spacer
            optionsPanel.add(chkRegex);
            optionsPanel.add(Box.createHorizontalStrut(15));
            optionsPanel.add(chkAllTabs);
            optionsPanel.add(Box.createHorizontalStrut(15));
            optionsPanel.add(chkSearchInColumn);

            gbc.gridx = 1;
            gbc.gridy = 2;
            gbc.weightx = 1.0;
            gbc.gridwidth = 2;
            inputPanel.add(optionsPanel, gbc);

            // --- ROW 3: Column Range ---
            JPanel colPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            colPanel.add(new JLabel("From Column:"));
            txtFromColumn = new JTextField(5);
            colPanel.add(txtFromColumn);
            colPanel.add(Box.createHorizontalStrut(10));
            colPanel.add(new JLabel("To Column:"));
            txtToColumn = new JTextField(5);
            colPanel.add(txtToColumn);
            colPanel.add(Box.createHorizontalStrut(5));
            lblOneBasedColumn = new JLabel("(1-based)");
            lblOneBasedColumn.setToolTipText("1-Based Columns option is enabled");
            lblOneBasedColumn.setVisible(chkSearchInColumn.isSelected() && oneBasedColumnIndex);
            colPanel.add(lblOneBasedColumn);

            gbc.gridx = 1;
            gbc.gridy = 3;
            gbc.weightx = 1.0;
            gbc.gridwidth = 2;
            colPanel.setVisible(false);
            inputPanel.add(colPanel, gbc);

            chkSearchInColumn.addActionListener(e -> {
                boolean colsVisible = chkSearchInColumn.isSelected();
                colPanel.setVisible(colsVisible);
                if (lblOneBasedColumn != null) {
                    lblOneBasedColumn.setVisible(colsVisible && oneBasedColumnIndex);
                }
                searchDialog.pack();
                updateSearchButtonState();
            });
            
            javax.swing.event.DocumentListener docListener = new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { updateSearchButtonState(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { updateSearchButtonState(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { updateSearchButtonState(); }
            };
            txtFromColumn.getDocument().addDocumentListener(docListener);
            txtToColumn.getDocument().addDocumentListener(docListener);
            
            // --- Buttons Panel ---
            JPanel pnlBtns = new JPanel(new FlowLayout(FlowLayout.CENTER));
            btnFindPrev = new JButton("⬆ Previous");
            btnFindNext = new JButton("⬇ Next");
            btnCount = new JButton("Count Matches");
            btnReplace = new JButton("Replace");
            btnReplaceAll = new JButton("Replace All");

            btnFindPrev.addActionListener(e -> {
                String txt = getSearchText();
                updateSearchHistory(txt);
                performFind(txt, false);
            });
            btnFindNext.addActionListener(e -> {
                String txt = getSearchText();
                updateSearchHistory(txt);
                performFind(txt, true);
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

        if (chkSearchInColumn != null && txtFromColumn != null && txtToColumn != null) {
            int dot = textArea.getCaret().getDot();
            int mark = textArea.getCaret().getMark();
            if (dot != mark) {
                int startOffset = Math.min(dot, mark);
                int endOffset = Math.max(dot, mark);
                try {
                    int startLine = textArea.getLineOfOffset(startOffset);
                    int endLine = textArea.getLineOfOffset(endOffset);
                    int colOffset = oneBasedColumnIndex ? 1 : 0;
                    int startCol = startOffset - textArea.getLineStartOffset(startLine) + colOffset;
                    int endCol = endOffset - textArea.getLineStartOffset(endLine) + colOffset;
                    txtFromColumn.setText(String.valueOf(startCol));
                    txtToColumn.setText(String.valueOf(endCol));
                } catch (Exception ex) {}
            }
        }
        if (lblOneBasedColumn != null) {
            boolean colsVisible = chkSearchInColumn != null && chkSearchInColumn.isSelected();
            lblOneBasedColumn.setVisible(colsVisible && oneBasedColumnIndex);
        }
        
        updateSearchButtonState();

        try {
            // Load the search and replace history from the properties listener if available
            DefaultComboBoxModel<String> sModel = new DefaultComboBoxModel<>();
            java.util.List<String> searchHist = (searchPropertiesListener != null)
                    ? searchPropertiesListener.getSearchHistory()
                    : new java.util.ArrayList<>();
            for (String s : searchHist)
                sModel.addElement(s);

            Object currentSearch = comboSearch.getEditor().getItem();
            comboSearch.setModel(sModel);
            if (currentSearch != null) {
                comboSearch.setSelectedItem(currentSearch);
            }

            DefaultComboBoxModel<String> rModel = new DefaultComboBoxModel<>();
            java.util.List<String> replaceHist = (searchPropertiesListener != null)
                    ? searchPropertiesListener.getReplaceHistory()
                    : new java.util.ArrayList<>();
            for (String s : replaceHist)
                rModel.addElement(s);

            Object currentReplace = comboReplace.getEditor().getItem();
            comboReplace.setModel(rModel);
            if (currentReplace != null) {
                comboReplace.setSelectedItem(currentReplace);
            }
        } catch (Exception e) {
        }

        // --- Auto Populate Logic ---
        if (isFirstOpen) {
            comboSearch.setSelectedIndex(-1);
            comboSearch.getEditor().setItem("");
            comboReplace.setSelectedIndex(-1);
            comboReplace.getEditor().setItem("");
        }

        searchDialog.setTitle("Search & Replace - " + currentTitle);
        DialogUtil.themeDialog(searchDialog);
        // --- Wire the Search Combobox to the Find Button after setting themes to
        // prevent the listener from breaking ---
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
            public void focusGained(java.awt.event.FocusEvent e) {
                lastActiveCombo = comboSearch;
            }
        });

        replaceEditor.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                lastActiveCombo = comboReplace;
            }
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

        @Override
        public void undo() throws CannotUndoException {
            inner.undo();
        }

        @Override
        public boolean canUndo() {
            return inner.canUndo();
        }

        @Override
        public void redo() throws CannotRedoException {
            inner.redo();
        }

        @Override
        public boolean canRedo() {
            return inner.canRedo();
        }

        @Override
        public void die() {
            inner.die();
        }

        @Override
        public boolean addEdit(UndoableEdit anEdit) {
            if (anEdit instanceof ChunkAwareEdit) {
                ChunkAwareEdit other = (ChunkAwareEdit) anEdit;
                if (this.chunkIndex == other.chunkIndex) {
                    return inner.addEdit(other.inner);
                }
            }
            return false;
        }

        @Override
        public boolean replaceEdit(UndoableEdit anEdit) {
            if (anEdit instanceof ChunkAwareEdit) {
                ChunkAwareEdit other = (ChunkAwareEdit) anEdit;
                if (this.chunkIndex == other.chunkIndex) {
                    return inner.replaceEdit(other.inner);
                }
            }
            return false;
        }

        @Override
        public boolean isSignificant() {
            return inner.isSignificant();
        }

        @Override
        public String getPresentationName() {
            return inner.getPresentationName();
        }

        @Override
        public String getUndoPresentationName() {
            return inner.getUndoPresentationName();
        }

        @Override
        public String getRedoPresentationName() {
            return inner.getRedoPresentationName();
        }
    }

    private class GlobalFormatEdit extends javax.swing.undo.AbstractUndoableEdit {
        private final java.util.Map<Integer, java.io.File> oldDirtyChunks;
        private final java.util.Map<Integer, java.io.File> newDirtyChunks;
        private final boolean oldHasUnsavedChanges;
        private final boolean newHasUnsavedChanges;
        private final int targetChunkIndex;

        public GlobalFormatEdit(java.util.Map<Integer, java.io.File> oldDirtyChunks, boolean oldHasUnsavedChanges,
                                java.util.Map<Integer, java.io.File> newDirtyChunks, boolean newHasUnsavedChanges, int targetChunkIndex) {
            this.oldDirtyChunks = oldDirtyChunks;
            this.newDirtyChunks = newDirtyChunks;
            this.oldHasUnsavedChanges = oldHasUnsavedChanges;
            this.newHasUnsavedChanges = newHasUnsavedChanges;
            this.targetChunkIndex = targetChunkIndex;
        }

        @Override
        public void undo() throws javax.swing.undo.CannotUndoException {
            super.undo();
            fileManager.restoreDirtyChunksSnapshot(oldDirtyChunks);
            setUnsavedChanges(oldHasUnsavedChanges);
            isDirty = false;
            
            documentCache.clear();
            int currentChunk = loadedChunkIndex;
            loadedChunkIndex = -1;
            triggerAsyncLoad(targetChunkIndex, 0, -1, false, null);
        }

        @Override
        public void redo() throws javax.swing.undo.CannotRedoException {
            super.redo();
            fileManager.restoreDirtyChunksSnapshot(newDirtyChunks);
            setUnsavedChanges(newHasUnsavedChanges);
            isDirty = false;
            
            documentCache.clear();
            int currentChunk = loadedChunkIndex;
            loadedChunkIndex = -1;
            triggerAsyncLoad(targetChunkIndex, 0, -1, false, null);
        }
        
        @Override
        public String getPresentationName() {
            return "Document Format";
        }
    }

    // Optimization: Single-pass character scan that replaces regex String.replaceAll() on 25MB chunks
    // and returns the original String with zero allocations when no control/format characters exist.
    private static String stripControlAndFormatChars(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        int len = content.length();
        int firstBad = -1;
        for (int i = 0; i < len; i++) {
            char c = content.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.FORMAT) {
                firstBad = i;
                break;
            }
        }
        if (firstBad == -1) {
            return content;
        }
        StringBuilder sb = new StringBuilder(len - 1);
        sb.append(content, 0, firstBad);
        for (int i = firstBad + 1; i < len; i++) {
            char c = content.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(c);
                continue;
            }
            int type = Character.getType(c);
            if (type != Character.CONTROL && type != Character.FORMAT) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // Optimization: Custom TextArea UI that installs FastWrappedPlainView (for word-wrap mode) and
    // FastPlainView (for no-wrap mode), and seeds the initial wrap width from JViewport before layout.
    private static class FastTextAreaUI extends javax.swing.plaf.basic.BasicTextAreaUI {
        @Override
        public View create(Element elem) {
            JTextComponent c = getComponent();
            if (c instanceof JTextArea) {
                JTextArea area = (JTextArea) c;
                if (area.getLineWrap()) {
                    return new FastWrappedPlainView(elem, area.getWrapStyleWord());
                }
                return new FastPlainView(elem);
            }
            return super.create(elem);
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            if (c instanceof JTextArea && c.getParent() instanceof JViewport) {
                JTextArea area = (JTextArea) c;
                int vpW = c.getParent().getWidth();
                int vpH = c.getParent().getHeight();
                if (vpW <= 0) {
                    FontMetrics fm = area.getFontMetrics(area.getFont());
                    int lines = Math.max(1, area.getLineCount());
                    return new Dimension(400, lines * fm.getHeight());
                }
                if (area.getLineWrap() && area.getWidth() != vpW) {
                    area.setSize(vpW, Math.max(1, vpH));
                }
            }
            return super.getPreferredSize(c);
        }
    }

    // Optimization: High-performance PlainView for word-wrap-disabled mode on large/no-newline files.
    // Caches character metrics, tracks the longest line in O(1), horizontally clips text drawing and
    // coordinate conversions to the visible viewport, and skips zero-width soft-wrap markers (0x200B).
    private static class FastPlainView extends javax.swing.text.PlainView {
        private static final char SOFT_WRAP_CHAR = 0x200B;
        private Font cachedFont;
        private java.awt.font.FontRenderContext cachedFrc;
        private double[] cachedFloatWidths;
        private double cachedCharW;
        private int cachedMinCharW;
        private boolean isMonospacedAscii;
        private double cachedTabSize;
        private int tabBase;
        private Element longestLine;
        private int maxLineWidth = -1;
        private final Segment measureSegment = new Segment();

        FastPlainView(Element elem) {
            super(elem);
        }

        private void rebuildFontMetricsCache(Font f, FontMetrics fm, java.awt.font.FontRenderContext frc) {
            cachedFont = f;
            metrics = fm;
            cachedFrc = frc;
            double[] fWidths = new double[256];
            char[] singleChar = new char[1];
            for (int c = 0; c < 256; c++) {
                if (c == '\r' || c == '\n' || c == SOFT_WRAP_CHAR) {
                    fWidths[c] = 0.0;
                } else {
                    singleChar[0] = (char) c;
                    fWidths[c] = f.getStringBounds(singleChar, 0, 1, frc).getWidth();
                }
            }
            cachedFloatWidths = fWidths;
            cachedCharW = Math.max(1.0, fWidths['m']);
            double minW = cachedCharW;
            boolean mono = true;
            for (int c = 32; c < 127; c++) {
                double w = fWidths[c];
                if (Math.abs(w - cachedCharW) > 1e-4) {
                    mono = false;
                }
                if (w > 0.0 && w < minW) {
                    minW = w;
                }
            }
            cachedMinCharW = Math.max(1, (int) Math.floor(minW));
            isMonospacedAscii = mono;
            cachedTabSize = getTabSize() * cachedCharW;
            recalculateLongestLineFast();
        }

        void syncGraphicsMetrics(Font f, FontMetrics gFm, java.awt.font.FontRenderContext gFrc) {
            rebuildFontMetricsCache(f, gFm, gFrc);
        }

        @Override
        protected void updateMetrics() {
            Component host = getContainer();
            if (host != null) {
                Font f = host.getFont();
                FontMetrics hostFm = host.getFontMetrics(f);
                java.awt.font.FontRenderContext hostFrc = hostFm.getFontRenderContext();
                if (f != cachedFont || metrics == null || cachedFrc == null || !hostFrc.equals(cachedFrc)) {
                    rebuildFontMetricsCache(f, hostFm, hostFrc);
                }
            }
        }

        private double getCharWidthFloat(char ch) {
            if (ch == SOFT_WRAP_CHAR || ch == '\r' || ch == '\n') {
                return 0.0;
            }
            if (isMonospacedAscii && ch >= 32 && ch < 127) {
                return cachedCharW;
            }
            if (ch < 256 && cachedFloatWidths != null) {
                return cachedFloatWidths[ch];
            }
            if (cachedFont != null && cachedFrc != null) {
                return cachedFont.getStringBounds(new char[] { ch }, 0, 1, cachedFrc).getWidth();
            }
            return (metrics != null) ? metrics.charWidth(ch) : cachedCharW;
        }

        // Zero-width characters (variation selectors such as U+FE0F and Unicode FORMAT characters such as
        // zero-width joiners / BOM) are measured as 0 and never drawn, matching how Java2D shapes them in runs.
        private static boolean isZeroWidthChar(char ch) {
            return (ch >= 0xFE00 && ch <= 0xFE0F) || Character.getType(ch) == Character.FORMAT;
        }

        // Advance width of a non-Latin-1 character at arr[idx] (bounded by [begin, end)). Surrogate pairs are
        // measured as one glyph (low surrogate = 0) so caret/selection/whitespace-symbol positions match the
        // glyphs drawn individually by drawClippedText() for emoji and other supplementary characters.
        private double getComplexCharWidth(char[] arr, int idx, int begin, int end) {
            char ch = arr[idx];
            if (Character.isHighSurrogate(ch)) {
                if (idx + 1 < end && Character.isLowSurrogate(arr[idx + 1]) && cachedFont != null && cachedFrc != null) {
                    return cachedFont.getStringBounds(arr, idx, idx + 2, cachedFrc).getWidth();
                }
            } else if (Character.isLowSurrogate(ch)) {
                if (idx > begin && Character.isHighSurrogate(arr[idx - 1])) {
                    return 0.0;
                }
            }
            if (isZeroWidthChar(ch)) {
                return 0.0;
            }
            return getCharWidthFloat(ch);
        }

        // Returns the X coordinate after the character at arr[idx] using exactly the same widths, tab stops and
        // zero-width rules as painting/measurement. Used by the whitespace/EOL symbol overlay so symbols are
        // positioned in the same cells as the drawn text for monospaced and proportional fonts alike.
        float advanceX(char[] arr, int idx, int begin, int end, float x, int docOffset) {
            char ch = arr[idx];
            if (ch == SOFT_WRAP_CHAR || ch == '\r' || ch == '\n') {
                return x;
            }
            if (ch == '\t') {
                return nextTabStop(x, docOffset);
            }
            if (cachedFloatWidths == null) {
                updateMetrics();
                if (cachedFloatWidths == null) {
                    return x;
                }
            }
            if (ch >= 32 && ch < 127) {
                return (float) (x + (isMonospacedAscii ? cachedCharW : cachedFloatWidths[ch]));
            }
            if (ch < 256) {
                return (float) (x + cachedFloatWidths[ch]);
            }
            return (float) (x + getComplexCharWidth(arr, idx, begin, end));
        }

        private static void drawCharRun(Graphics g, char[] arr, int runStart, int runLen, float runX, float y) {
            if (g instanceof Graphics2D) {
                ((Graphics2D) g).drawString(new String(arr, runStart, runLen), runX, y);
            } else {
                g.drawChars(arr, runStart, runLen, Math.round(runX), Math.round(y));
            }
        }

        private void recalculateLongestLineFast() {
            Element root = getElement();
            int count = root.getElementCount();
            Element best = null;
            int bestCharLen = -1;
            for (int i = 0; i < count; i++) {
                Element line = root.getElement(i);
                int charLen = line.getEndOffset() - line.getStartOffset();
                if (charLen > bestCharLen) {
                    bestCharLen = charLen;
                    best = line;
                }
            }
            longestLine = best;
            maxLineWidth = (best != null) ? getLineWidthFast(best) : 0;
        }

        private int getLineWidthFast(Element line) {
            if (line == null || metrics == null) {
                return 0;
            }
            int p0 = line.getStartOffset();
            int p1 = line.getEndOffset() - 1;
            if (p1 <= p0) {
                return 0;
            }
            return (int) Math.ceil(measureSpan(p0, p1, tabBase));
        }

        private float measureSpan(int p0, int p1, float startX) {
            if (p1 <= p0) {
                return 0f;
            }
            if (metrics == null) {
                updateMetrics();
                if (metrics == null) {
                    return 0f;
                }
            }
            try {
                getDocument().getText(p0, p1 - p0, measureSegment);
                char[] arr = measureSegment.array;
                int off = measureSegment.offset;
                int count = measureSegment.count;
                double[] widths = cachedFloatWidths;
                boolean mono = isMonospacedAscii;
                double charW = cachedCharW;
                double x = startX;
                for (int i = 0; i < count; i++) {
                    char ch = arr[off + i];
                    if (ch >= 32 && ch < 127) {
                        x += mono ? charW : widths[ch];
                        continue;
                    }
                    if (ch == SOFT_WRAP_CHAR || ch == '\n' || ch == '\r') {
                        continue;
                    }
                    if (ch == '\t') {
                        x = nextTabStop((float) x, p0 + i);
                    } else if (ch < 256 && widths != null) {
                        x += widths[ch];
                    } else {
                        x += getComplexCharWidth(arr, off + i, off, off + count);
                    }
                }
                return (float) (x - startX);
            } catch (BadLocationException e) {
                return 0f;
            }
        }

        @Override
        public float getPreferredSpan(int axis) {
            updateMetrics();
            switch (axis) {
                case View.X_AXIS:
                    if (maxLineWidth < 0) {
                        recalculateLongestLineFast();
                    }
                    return maxLineWidth;
                case View.Y_AXIS:
                    return getElement().getElementCount() * (metrics != null ? metrics.getHeight() : 16);
                default:
                    throw new IllegalArgumentException("Invalid axis: " + axis);
            }
        }

        @Override
        public void paint(Graphics g, Shape a) {
            if (a != null) {
                tabBase = a.getBounds().x;
            }
            Component host = getContainer();
            if (g != null && host != null) {
                Font f = host.getFont();
                FontMetrics gFm = g.getFontMetrics(f);
                if (gFm != null) {
                    java.awt.font.FontRenderContext gFrc = gFm.getFontRenderContext();
                    if (f != cachedFont || metrics == null || cachedFrc == null || !gFrc.equals(cachedFrc)) {
                        rebuildFontMetricsCache(f, gFm, gFrc);
                    }
                }
            }
            super.paint(g, a);
        }

        @Override
        protected int drawUnselectedText(Graphics g, int x, int y, int p0, int p1) throws BadLocationException {
            return (int) drawUnselectedText((Graphics2D) g, (float) x, (float) y, p0, p1);
        }

        @Override
        protected float drawUnselectedText(Graphics2D g, float x, float y, int p0, int p1)
                throws BadLocationException {
            JTextComponent host = (JTextComponent) getContainer();
            Color fg = (host != null)
                    ? (host.isEnabled() ? host.getForeground() : host.getDisabledTextColor())
                    : g.getColor();
            return drawClippedText(g, x, y, p0, p1, fg);
        }

        @Override
        protected int drawSelectedText(Graphics g, int x, int y, int p0, int p1) throws BadLocationException {
            return (int) drawSelectedText((Graphics2D) g, (float) x, (float) y, p0, p1);
        }

        @Override
        protected float drawSelectedText(Graphics2D g, float x, float y, int p0, int p1) throws BadLocationException {
            JTextComponent host = (JTextComponent) getContainer();
            Color fg;
            if (host != null) {
                javax.swing.text.Caret caret = host.getCaret();
                if (caret != null && caret.isSelectionVisible() && host.getHighlighter() != null) {
                    fg = host.getSelectedTextColor();
                } else {
                    fg = host.isEnabled() ? host.getForeground() : host.getDisabledTextColor();
                }
            } else {
                fg = g.getColor();
            }
            return drawClippedText(g, x, y, p0, p1, fg);
        }

        private float drawClippedText(Graphics g, float x, float y, int p0, int p1, Color fg)
                throws BadLocationException {
            if (p0 >= p1) {
                return x;
            }
            if (metrics == null) {
                updateMetrics();
                if (metrics == null) {
                    return x;
                }
            }
            g.setColor(fg);
            Rectangle clip = g.getClipBounds();
            Component host = getContainer();
            if (host instanceof JComponent) {
                Rectangle vis = ((JComponent) host).getVisibleRect();
                if (vis != null && vis.width > 0) {
                    clip = (clip != null) ? clip.intersection(vis) : vis;
                }
            }
            int clipLeft = (clip != null) ? (clip.x - 32) : Integer.MIN_VALUE;
            int clipRight = (clip != null) ? (clip.x + clip.width + 32) : Integer.MAX_VALUE;
            if (x > clipRight) {
                return x;
            }

            int reqLen = p1 - p0;
            if (clipLeft <= (int) x && clipRight < Integer.MAX_VALUE && cachedMinCharW > 0) {
                int maxVisibleChars = ((clipRight - (int) x) / cachedMinCharW) + 32;
                if (maxVisibleChars > 0 && maxVisibleChars < reqLen) {
                    reqLen = maxVisibleChars;
                }
            }

            Segment segment = getLineBuffer();
            getDocument().getText(p0, reqLen, segment);
            char[] arr = segment.array;
            int start = segment.offset;
            int count = segment.count;
            double[] widths = cachedFloatWidths;
            boolean mono = isMonospacedAscii;
            double charW = cachedCharW;

            double curX = x;
            int i = 0;
            if (mono && curX < clipLeft) {
                while (i < count && curX + charW < clipLeft) {
                    char ch = arr[start + i];
                    if (ch >= 32 && ch < 127) {
                        curX += charW;
                        i++;
                    } else {
                        break;
                    }
                }
            }

            int runStart = -1;
            int runLen = 0;
            float runX = (float) curX;

            for (; i < count; i++) {
                if (curX > clipRight) {
                    break;
                }
                int idx = start + i;
                char ch = arr[idx];
                if (ch >= 32 && ch < 127) {
                    double cw = mono ? charW : widths[ch];
                    if (curX + cw >= clipLeft) {
                        if (runLen == 0) {
                            runStart = idx;
                            runX = (float) curX;
                        }
                        runLen++;
                    }
                    curX += cw;
                    continue;
                }
                if (ch == SOFT_WRAP_CHAR || ch == '\r' || ch == '\n') {
                    if (runLen > 0) {
                        drawCharRun(g, arr, runStart, runLen, runX, y);
                        runLen = 0;
                        runStart = -1;
                    }
                    continue;
                }
                if (ch == '\t') {
                    if (runLen > 0) {
                        drawCharRun(g, arr, runStart, runLen, runX, y);
                        runLen = 0;
                        runStart = -1;
                    }
                    curX = nextTabStop((float) curX, p0 + i);
                    continue;
                }
                if (ch >= 256) {
                    // Draw non-Latin-1 characters (CJK, emoji surrogate pairs, symbols) individually at their
                    // measured X so font fallback/shaping inside a run can never shift the following glyphs away
                    // from the caret, selection and whitespace-symbol positions.
                    if (runLen > 0) {
                        drawCharRun(g, arr, runStart, runLen, runX, y);
                        runLen = 0;
                        runStart = -1;
                    }
                    double cw = getComplexCharWidth(arr, idx, start, start + count);
                    int glyphLen = (Character.isHighSurrogate(ch) && i + 1 < count
                            && Character.isLowSurrogate(arr[idx + 1])) ? 2 : 1;
                    if (curX + cw >= clipLeft && !isZeroWidthChar(ch)
                            && !(Character.isLowSurrogate(ch) && cw == 0.0)) {
                        drawCharRun(g, arr, idx, glyphLen, (float) curX, y);
                    }
                    curX += cw;
                    i += glyphLen - 1;
                    continue;
                }
                double cw = (widths != null) ? widths[ch] : getCharWidthFloat(ch);
                if (curX + cw >= clipLeft) {
                    if (runLen == 0) {
                        runStart = idx;
                        runX = (float) curX;
                    }
                    runLen++;
                }
                curX += cw;
            }
            if (runLen > 0) {
                drawCharRun(g, arr, runStart, runLen, runX, y);
            }
            if (reqLen < p1 - p0 && curX <= clipRight) {
                curX = clipRight + 1.0;
            }
            return (float) curX;
        }

        @Override
        public Shape modelToView(int pos, Shape a, javax.swing.text.Position.Bias b) throws BadLocationException {
            Element root = getElement();
            int lineIndex = root.getElementIndex(pos);
            if (lineIndex < 0) {
                return lineToRect(a, 0);
            }
            Rectangle lineArea = lineToRect(a, lineIndex);
            tabBase = lineArea.x;
            Element line = root.getElement(lineIndex);
            int p0 = line.getStartOffset();
            if (pos <= p0) {
                return new java.awt.geom.Rectangle2D.Float(lineArea.x, lineArea.y, 1f, lineArea.height);
            }
            float span = measureSpan(p0, pos, tabBase);
            return new java.awt.geom.Rectangle2D.Float(lineArea.x + span, lineArea.y, 1f, lineArea.height);
        }

        private int clampLineEndOffset(Document doc, int p0, int p1) {
            if (p1 <= p0) {
                return p0;
            }
            try {
                doc.getText(p1 - 1, 1, measureSegment);
                if (measureSegment.count > 0) {
                    char lastCh = measureSegment.array[measureSegment.offset];
                    if (lastCh == SOFT_WRAP_CHAR || lastCh == '\r') {
                        return Math.max(p0, p1 - 1);
                    }
                }
            } catch (BadLocationException e) {
            }
            return p1;
        }

        @Override
        public int viewToModel(float fx, float fy, Shape a, javax.swing.text.Position.Bias[] bias) {
            if (bias != null && bias.length > 0) {
                bias[0] = javax.swing.text.Position.Bias.Forward;
            }
            updateMetrics();
            Rectangle alloc = a.getBounds();
            Document doc = getDocument();
            if (fy < alloc.y) {
                return getStartOffset();
            }
            if (fy > alloc.y + alloc.height) {
                return Math.max(0, getEndOffset() - 1);
            }
            Element root = doc.getDefaultRootElement();
            int fontHeight = (metrics != null) ? metrics.getHeight() : 16;
            int lineIndex = (fontHeight > 0)
                    ? Math.abs((int) ((fy - alloc.y) / fontHeight))
                    : (root.getElementCount() - 1);
            if (lineIndex >= root.getElementCount()) {
                return Math.max(0, getEndOffset() - 1);
            }
            Element line = root.getElement(lineIndex);
            int p0 = line.getStartOffset();
            int p1 = line.getEndOffset() - 1;
            if (fx <= alloc.x || p1 <= p0) {
                return p0;
            }
            if (fx > alloc.x + alloc.width) {
                return clampLineEndOffset(doc, p0, p1);
            }
            try {
                int reqLen = p1 - p0;
                if (cachedMinCharW > 0 && fx >= alloc.x) {
                    int maxNeeded = ((int) (fx - alloc.x) / cachedMinCharW) + 32;
                    if (maxNeeded > 0 && maxNeeded < reqLen) {
                        reqLen = maxNeeded;
                    }
                }
                doc.getText(p0, reqLen, measureSegment);
                char[] arr = measureSegment.array;
                int off = measureSegment.offset;
                int count = measureSegment.count;
                double[] widths = cachedFloatWidths;
                boolean mono = isMonospacedAscii;
                double charW = cachedCharW;
                tabBase = alloc.x;
                double curX = alloc.x;
                for (int i = 0; i < count; i++) {
                    char ch = arr[off + i];
                    if (ch >= 32 && ch < 127) {
                        double nextX = curX + (mono ? charW : widths[ch]);
                        if (fx < (curX + nextX) * 0.5) {
                            return p0 + i;
                        }
                        curX = nextX;
                        continue;
                    }
                    if (ch == SOFT_WRAP_CHAR || ch == '\r' || ch == '\n') {
                        continue;
                    }
                    double nextX;
                    if (ch == '\t') {
                        nextX = nextTabStop((float) curX, p0 + i);
                    } else {
                        double cw = (ch < 256 && widths != null) ? widths[ch]
                                : getComplexCharWidth(arr, off + i, off, off + count);
                        nextX = curX + cw;
                    }
                    if (fx < (curX + nextX) * 0.5) {
                        return p0 + i;
                    }
                    curX = nextX;
                }
                if (reqLen == p1 - p0 && count > 0) {
                    char lastCh = arr[off + count - 1];
                    if (lastCh == SOFT_WRAP_CHAR || lastCh == '\r') {
                        return Math.max(p0, p1 - 1);
                    }
                }
                return Math.min(p1, p0 + reqLen);
            } catch (BadLocationException e) {
                return -1;
            }
        }

        @Override
        public float nextTabStop(float x, int tabOffset) {
            if (cachedTabSize <= 0.0) {
                updateMetrics();
                if (cachedTabSize <= 0.0) {
                    return x;
                }
            }
            int ntabs = (int) ((x - tabBase) / cachedTabSize);
            return (float) (tabBase + (ntabs + 1) * cachedTabSize);
        }

        @Override
        protected void updateDamage(DocumentEvent changes, Shape a, javax.swing.text.ViewFactory f) {
            Component host = getContainer();
            updateMetrics();
            Element elem = getElement();
            DocumentEvent.ElementChange ec = changes.getChange(elem);
            Element[] added = (ec != null) ? ec.getChildrenAdded() : null;
            Element[] removed = (ec != null) ? ec.getChildrenRemoved() : null;
            if ((added != null && added.length > 0) || (removed != null && removed.length > 0)) {
                if (added != null) {
                    int bestLen = (longestLine != null)
                            ? (longestLine.getEndOffset() - longestLine.getStartOffset())
                            : -1;
                    boolean changedBest = false;
                    for (Element line : added) {
                        int len = line.getEndOffset() - line.getStartOffset();
                        if (len > bestLen) {
                            bestLen = len;
                            longestLine = line;
                            changedBest = true;
                        }
                    }
                    if (changedBest) {
                        maxLineWidth = getLineWidthFast(longestLine);
                    }
                }
                if (removed != null) {
                    for (Element line : removed) {
                        if (line == longestLine) {
                            recalculateLongestLineFast();
                            break;
                        }
                    }
                }
                preferenceChanged(null, true, true);
                if (host != null) {
                    host.repaint();
                }
            } else {
                int line = elem.getElementIndex(changes.getOffset());
                damageLineRange(line, line, a, host);
                if (changes.getType() == DocumentEvent.EventType.INSERT) {
                    Element editedLine = elem.getElement(line);
                    int w = getLineWidthFast(editedLine);
                    if (editedLine == longestLine) {
                        maxLineWidth = w;
                        preferenceChanged(null, true, false);
                    } else if (w > maxLineWidth) {
                        longestLine = editedLine;
                        maxLineWidth = w;
                        preferenceChanged(null, true, false);
                    }
                } else if (changes.getType() == DocumentEvent.EventType.REMOVE) {
                    if (elem.getElement(line) == longestLine) {
                        recalculateLongestLineFast();
                        preferenceChanged(null, true, false);
                    }
                }
            }
        }
    }

    // Optimization: Custom WrappedPlainView that bounds calculateBreakPosition() to the characters
    // that fit within the viewport width instead of measuring entire 15,000-character lines per wrap row.
    private static class FastWrappedPlainView extends WrappedPlainView {
        private static final char SOFT_WRAP_CHAR = 0x200B;
        private final boolean wrapStyleWord;
        private Font cachedFont;
        private java.awt.font.FontRenderContext cachedFrc;
        private FontMetrics cachedMetrics;
        private double cachedCharW;
        private double[] cachedFloatWidths;
        private boolean isMonospacedAscii;
        private int wrappedTabBase;

        FastWrappedPlainView(Element elem, boolean wrapStyleWord) {
            super(elem, wrapStyleWord);
            this.wrapStyleWord = wrapStyleWord;
        }

        private void rebuildWrappedFontMetrics(Font f, FontMetrics fm, java.awt.font.FontRenderContext frc) {
            cachedFont = f;
            cachedMetrics = fm;
            cachedFrc = frc;
            double[] fWidths = new double[256];
            char[] singleChar = new char[1];
            for (int ch = 0; ch < 256; ch++) {
                if (ch == '\r' || ch == '\n' || ch == SOFT_WRAP_CHAR) {
                    fWidths[ch] = 0.0;
                } else {
                    singleChar[0] = (char) ch;
                    fWidths[ch] = f.getStringBounds(singleChar, 0, 1, frc).getWidth();
                }
            }
            cachedFloatWidths = fWidths;
            cachedCharW = Math.max(1.0, fWidths['m']);
            boolean mono = true;
            for (int ch = 32; ch < 127; ch++) {
                if (Math.abs(fWidths[ch] - cachedCharW) > 1e-4) {
                    mono = false;
                    break;
                }
            }
            isMonospacedAscii = mono;
        }

        void syncGraphicsMetrics(Font f, FontMetrics gFm, java.awt.font.FontRenderContext gFrc) {
            rebuildWrappedFontMetrics(f, gFm, gFrc);
            preferenceChanged(null, true, true);
            getMinimumSpan(View.X_AXIS);
        }

        @Override
        public void paint(Graphics g, Shape a) {
            if (a != null) {
                wrappedTabBase = a.getBounds().x;
            }
            super.paint(g, a);
        }

        @Override
        protected void paintChild(Graphics g, Rectangle alloc, int index) {
            Container c = getContainer();
            if (g != null && c != null) {
                Font f = c.getFont();
                FontMetrics gFm = g.getFontMetrics(f);
                if (gFm != null) {
                    java.awt.font.FontRenderContext gFrc = gFm.getFontRenderContext();
                    if (f != cachedFont || cachedMetrics == null || cachedFrc == null || !gFrc.equals(cachedFrc)) {
                        rebuildWrappedFontMetrics(f, gFm, gFrc);
                    }
                }
            }
            super.paintChild(g, alloc, index);
        }

        @Override
        public float nextTabStop(float x, int pos) {
            int tabSize = getTabSize();
            if (tabSize == 0) {
                return x;
            }
            float tabW = (float) (tabSize * (cachedCharW > 0.0 ? cachedCharW : 8.0));
            int ntabs = (int) ((x - wrappedTabBase) / tabW);
            return wrappedTabBase + (ntabs + 1) * tabW;
        }

        private static boolean isWrappedZeroWidthChar(char ch) {
            return (ch >= 0xFE00 && ch <= 0xFE0F) || Character.getType(ch) == Character.FORMAT;
        }

        private double getWrappedComplexCharWidth(char[] arr, int idx, int begin, int end) {
            char ch = arr[idx];
            if (Character.isHighSurrogate(ch)) {
                if (idx + 1 < end && Character.isLowSurrogate(arr[idx + 1]) && cachedFont != null && cachedFrc != null) {
                    return cachedFont.getStringBounds(arr, idx, idx + 2, cachedFrc).getWidth();
                }
            } else if (Character.isLowSurrogate(ch)) {
                if (idx > begin && Character.isHighSurrogate(arr[idx - 1])) {
                    return 0.0;
                }
            }
            if (isWrappedZeroWidthChar(ch)) {
                return 0.0;
            }
            if (cachedFont != null && cachedFrc != null) {
                return cachedFont.getStringBounds(arr, idx, idx + 1, cachedFrc).getWidth();
            }
            return (cachedMetrics != null) ? cachedMetrics.charWidth(ch) : cachedCharW;
        }

        private float drawWrappedText(Graphics2D g, float x, float y, int p0, int p1, Color fg)
                throws BadLocationException {
            if (p0 >= p1) {
                return x;
            }
            Container c = getContainer();
            if (g != null && c != null) {
                Font f = c.getFont();
                FontMetrics gFm = g.getFontMetrics(f);
                if (gFm != null) {
                    java.awt.font.FontRenderContext gFrc = gFm.getFontRenderContext();
                    if (f != cachedFont || cachedMetrics == null || cachedFrc == null || !gFrc.equals(cachedFrc)) {
                        rebuildWrappedFontMetrics(f, gFm, gFrc);
                    }
                }
            }
            g.setColor(fg);
            Segment segment = getLineBuffer();
            getDocument().getText(p0, p1 - p0, segment);
            char[] arr = segment.array;
            int start = segment.offset;
            int count = segment.count;
            double[] widths = cachedFloatWidths;
            boolean mono = isMonospacedAscii;
            double charW = cachedCharW;
            double curX = x;
            int runStart = -1;
            int runLen = 0;
            float runX = (float) curX;

            for (int i = 0; i < count; i++) {
                int idx = start + i;
                char ch = arr[idx];
                if (ch >= 32 && ch < 127) {
                    double cw = (mono || widths == null) ? charW : widths[ch];
                    if (runLen == 0) {
                        runStart = idx;
                        runX = (float) curX;
                    }
                    runLen++;
                    curX += cw;
                    continue;
                }
                if (ch == SOFT_WRAP_CHAR || ch == '\r' || ch == '\n') {
                    if (runLen > 0) {
                        g.drawString(new String(arr, runStart, runLen), runX, y);
                        runLen = 0;
                        runStart = -1;
                    }
                    continue;
                }
                if (ch == '\t') {
                    if (runLen > 0) {
                        g.drawString(new String(arr, runStart, runLen), runX, y);
                        runLen = 0;
                        runStart = -1;
                    }
                    curX = nextTabStop((float) curX, p0 + i);
                    continue;
                }
                if (ch >= 256) {
                    if (runLen > 0) {
                        g.drawString(new String(arr, runStart, runLen), runX, y);
                        runLen = 0;
                        runStart = -1;
                    }
                    double cw = getWrappedComplexCharWidth(arr, idx, start, start + count);
                    int glyphLen = (Character.isHighSurrogate(ch) && i + 1 < count
                            && Character.isLowSurrogate(arr[idx + 1])) ? 2 : 1;
                    if (!isWrappedZeroWidthChar(ch) && !(Character.isLowSurrogate(ch) && cw == 0.0)) {
                        g.drawString(new String(arr, idx, glyphLen), (float) curX, y);
                    }
                    curX += cw;
                    i += glyphLen - 1;
                    continue;
                }
                double cw = (widths != null) ? widths[ch] : charW;
                if (runLen == 0) {
                    runStart = idx;
                    runX = (float) curX;
                }
                runLen++;
                curX += cw;
            }
            if (runLen > 0) {
                g.drawString(new String(arr, runStart, runLen), runX, y);
            }
            return (float) curX;
        }

        @Override
        protected float drawUnselectedText(Graphics2D g, float x, float y, int p0, int p1)
                throws BadLocationException {
            JTextComponent host = (JTextComponent) getContainer();
            Color fg = (host != null)
                    ? (host.isEnabled() ? host.getForeground() : host.getDisabledTextColor())
                    : g.getColor();
            return drawWrappedText(g, x, y, p0, p1, fg);
        }

        @Override
        protected float drawSelectedText(Graphics2D g, float x, float y, int p0, int p1)
                throws BadLocationException {
            JTextComponent host = (JTextComponent) getContainer();
            Color fg;
            if (host != null) {
                javax.swing.text.Caret caret = host.getCaret();
                if (caret != null && caret.isSelectionVisible() && host.getHighlighter() != null) {
                    fg = host.getSelectedTextColor();
                } else {
                    fg = host.isEnabled() ? host.getForeground() : host.getDisabledTextColor();
                }
            } else {
                fg = g.getColor();
            }
            return drawWrappedText(g, x, y, p0, p1, fg);
        }

        @Override
        @SuppressWarnings("deprecation")
        protected int calculateBreakPosition(int p0, int p1) {
            int width = getWidth();
            if (width <= 0 || p1 <= p0) {
                return p1;
            }
            Container c = getContainer();
            if (c != null) {
                if (width <= 150 && c.getParent() instanceof JViewport) {
                    int vpW = c.getParent().getWidth();
                    if (vpW > 0) {
                        Insets ins = ((JComponent) c).getInsets();
                        int avail = vpW - (ins != null ? ins.left + ins.right + 5 : 5);
                        if (avail > width) {
                            width = avail;
                        }
                    } else {
                        return p1;
                    }
                }
                Font f = c.getFont();
                FontMetrics hostFm = c.getFontMetrics(f);
                java.awt.font.FontRenderContext hostFrc = hostFm.getFontRenderContext();
                if (f != cachedFont || cachedMetrics == null || cachedFrc == null) {
                    rebuildWrappedFontMetrics(f, hostFm, hostFrc);
                } else if (!hostFrc.equals(cachedFrc) && (hostFrc.isTransformed() || !cachedFrc.isTransformed())) {
                    rebuildWrappedFontMetrics(f, hostFm, hostFrc);
                }
            }
            if (cachedMetrics == null) {
                return super.calculateBreakPosition(p0, p1);
            }
            int len = p1 - p0;
            try {
                Segment segment = getLineBuffer();
                int minCharW = Math.max(1, (int) Math.floor(cachedCharW));
                int maxCheck = Math.min(len, (width / minCharW) * 2 + 64);
                getDocument().getText(p0, maxCheck, segment);
                int breakPos = findBreak(segment, p0, width);
                if (breakPos >= 0) {
                    return breakPos;
                }
                if (maxCheck < len) {
                    getDocument().getText(p0, len, segment);
                    breakPos = findBreak(segment, p0, width);
                    if (breakPos >= 0) {
                        return breakPos;
                    }
                }
                return p1;
            } catch (BadLocationException e) {
                return p1;
            }
        }

        private int findBreak(Segment segment, int p0, int width) {
            double x = 0.0;
            int lastBreakOffset = -1;
            char[] arr = segment.array;
            int start = segment.offset;
            int count = segment.count;
            double[] widths = cachedFloatWidths;
            for (int i = 0; i < count; i++) {
                char ch = arr[start + i];
                if (ch == '\r' || ch == '\n' || ch == SOFT_WRAP_CHAR) {
                    continue;
                }
                double cw;
                if (ch == '\t') {
                    cw = nextTabStop((float) x, p0 + i) - x;
                } else if (ch < 256 && widths != null) {
                    cw = widths[ch];
                } else {
                    cw = cachedFont.getStringBounds(arr, start + i, start + i + 1,
                            cachedMetrics.getFontRenderContext()).getWidth();
                }
                if (x + cw > width) {
                    if (wrapStyleWord) {
                        if (Character.isWhitespace(ch)) {
                            return p0 + i + 1;
                        }
                        if (lastBreakOffset >= 0) {
                            return p0 + lastBreakOffset + 1;
                        }
                    }
                    return (i == 0) ? (p0 + 1) : (p0 + i);
                }
                x += cw;
                if (wrapStyleWord && Character.isWhitespace(ch)) {
                    lastBreakOffset = i;
                }
            }
            return -1;
        }
    }

    private static class GlobalUndoManager extends UndoManager {
        public int getUndoChunk() {
            UndoableEdit edit = editToBeUndone();
            if (edit instanceof ChunkAwareEdit) {
                return ((ChunkAwareEdit) edit).chunkIndex;
            }
            return -1;
        }

        public int getRedoChunk() {
            UndoableEdit edit = editToBeRedone();
            if (edit instanceof ChunkAwareEdit) {
                return ((ChunkAwareEdit) edit).chunkIndex;
            }
            return -1;
        }
    }

    private static class LineNumberPanel extends JPanel {
        private static final char SOFT_WRAP_CHAR = 0x200B;
        private final JTextArea textArea;
        private final AdvancedTextEditorPanel parent; // Access parent to check indexing
        private long startLine = 1;
        private int currentLocalLine = 0;
        private Document cachedDoc;
        private int cachedElementCount = -1;
        private int[] realLinesPrefix;
        private final Segment lineSeg = new Segment();

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

        public long getStartLine() {
            return this.startLine;
        }

        public void setCurrentLine(int line) {
            if (this.currentLocalLine != line) {
                this.currentLocalLine = line;
                repaint();
            }
        }

        public void adjustMetricSizing() {
            cachedElementCount = -1;
            revalidate();
            repaint();
        }

        // Optimization: Cache a prefix-sum array of true (non-soft-wrapped) line numbers per Document
        // so LineNumberPanel.paintComponent() can look up real line numbers in O(1) during scrolling.
        private int[] getRealLinesPrefix(Document doc, Element root) {
            try {
                int elemCount = root.getElementCount();
                if (doc != cachedDoc || elemCount != cachedElementCount || realLinesPrefix == null
                        || realLinesPrefix.length <= elemCount) {
                    cachedDoc = doc;
                    cachedElementCount = elemCount;
                    realLinesPrefix = new int[elemCount + 1];
                    int count = 0;
                    for (int i = 0; i < elemCount; i++) {
                        realLinesPrefix[i] = count;
                        int endOff = root.getElement(i).getEndOffset();
                        boolean isSoftWrap = false;
                        if (endOff >= 2) {
                            doc.getText(endOff - 2, 1, lineSeg);
                            isSoftWrap = (lineSeg.count > 0 && lineSeg.array[lineSeg.offset] == SOFT_WRAP_CHAR);
                        }
                        if (!isSoftWrap) {
                            count++;
                        }
                    }
                    realLinesPrefix[elemCount] = count;
                }
                return realLinesPrefix;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
            int totalLines = textArea.getLineCount();
            int maximumDigits = String.valueOf(startLine + totalLines).length() + 2;
            int functionalWidth = fm.stringWidth("0") * Math.max(maximumDigits, 4) + 12;

            // Optimization: Use textArea.getHeight() (or lineCount * fontHeight before layout) instead of
            // textArea.getPreferredSize() to avoid triggering full-document width measurement on 25MB chunks.
            int height = textArea.getHeight();
            if (height <= 0) {
                Insets ins = textArea.getInsets();
                int insH = (ins != null) ? (ins.top + ins.bottom) : 0;
                height = Math.max(1, totalLines) * fm.getHeight() + insH;
            }
            return new Dimension(functionalWidth, height);
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
                int endOffset = textArea
                        .viewToModel2D(new Point(0, viewportClip.y + viewportClip.height + fm.getHeight()));

                int startLineIdx = root.getElementIndex(startOffset);
                int endLineIdx = root.getElementIndex(endOffset);

                int[] prefix = getRealLinesPrefix(doc, root);
                int realLines = 0;
                for (int i = 0; prefix == null && i < startLineIdx; i++) {
                    int endOff = root.getElement(i).getEndOffset();
                    if (!(endOff >= 2 && "\u200B".equals(doc.getText(endOff - 2, 1)))) {
                        realLines++;
                    }
                }

                for (int i = startLineIdx; i <= endLineIdx; i++) {
                    // Optimization: Skip soft-wrapped continuation lines via the cached prefix array
                    // before calling modelToView2D() so no-newline files avoid per-line view lookups.
                    if (prefix != null) {
                        realLines = prefix[i];
                        boolean prevWasFake = (i > 0 && prefix[i] == prefix[i - 1]);
                        if (prevWasFake) {
                            continue;
                        }
                    }
                    int offset = root.getElement(i).getStartOffset();
                    Rectangle r = textArea.modelToView2D(offset).getBounds();

                    if (r.y + r.height < viewportClip.y)
                        continue;
                    if (r.y > viewportClip.y + viewportClip.height)
                        break;

                    boolean prevWasFake = (prefix != null) && (i > 0 && prefix[i] == prefix[i - 1]);
                    if (prefix == null && i > 0) {
                        int prevEndOff = root.getElement(i - 1).getEndOffset();
                        if (prevEndOff >= 2) {
                            doc.getText(prevEndOff - 2, 1, lineSeg);
                            if (lineSeg.count > 0 && lineSeg.array[lineSeg.offset] == SOFT_WRAP_CHAR) {
                                prevWasFake = true;
                            }
                        }
                    }

                    if (!prevWasFake) {
                        String stringLabel = (isIndexing ? "~" : "") + String.valueOf(startLine + realLines);
                        int alignedX = getWidth() - fm.stringWidth(stringLabel) - 6;

                        if (i == currentLocalLine) {
                            g.setFont(textArea.getFont().deriveFont(Font.BOLD));
                            g.setColor(parent.currentTheme.equals("Dark") ? new Color(200, 200, 200)
                                    : new Color(40, 40, 40));
                        } else {
                            g.setFont(textArea.getFont());
                            g.setColor(parent.currentTheme.equals("Dark") ? new Color(110, 110, 110)
                                    : new Color(110, 110, 110));
                        }

                        g.drawString(stringLabel, alignedX, r.y + fm.getAscent());
                    }

                    if (prefix != null) {
                        continue;
                    }
                    int thisEndOff = root.getElement(i).getEndOffset();
                    if (!(thisEndOff >= 2 && "\u200B".equals(doc.getText(thisEndOff - 2, 1)))) {
                        realLines++;
                    }
                }
            } catch (Exception e) {
            }
        }
    }

    static class ColumnRulerPanel extends JPanel {
        private static final char SOFT_WRAP_CHAR = 0x200B;
        private final AdvancedTextEditorPanel parent;
        private final JTextArea textArea;
        private final Segment lineSegment = new Segment();
        private Font cachedFont;
        private java.awt.font.FontRenderContext cachedFrc;
        private double[] cachedFloatWidths;
        private double cachedCharW = 8.0;
        private double cachedDefaultColW = 8.0;
        private boolean isMonospacedAscii = true;

        public ColumnRulerPanel(AdvancedTextEditorPanel parent, JTextArea textArea) {
            this.parent = parent;
            this.textArea = textArea;
            setOpaque(true);
            setBackground(new Color(245, 245, 245));
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));

            textArea.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (parent.showColumnRuler) {
                        revalidate();
                        repaint();
                    }
                }
            });
        }

        private static Font getLabelFont(Font baseFont, int style) {
            float baseSize = (baseFont != null) ? baseFont.getSize2D() : 14f;
            float labelSize = Math.max(9f, Math.round(baseSize * 0.8f));
            return (baseFont != null ? baseFont : new Font("Monospaced", Font.PLAIN, 14)).deriveFont(style, labelSize);
        }

        private void ensureMetricsCache(Font f, java.awt.font.FontRenderContext frc) {
            if (f == null) {
                f = textArea.getFont();
            }
            if (frc == null) {
                frc = textArea.getFontMetrics(f).getFontRenderContext();
            }
            if (f == cachedFont && cachedFrc != null && cachedFrc.equals(frc) && cachedFloatWidths != null) {
                return;
            }
            cachedFont = f;
            cachedFrc = frc;
            double[] fWidths = new double[256];
            char[] singleChar = new char[1];
            for (int c = 0; c < 256; c++) {
                if (c == '\r' || c == '\n' || c == SOFT_WRAP_CHAR) {
                    fWidths[c] = 0.0;
                } else {
                    singleChar[0] = (char) c;
                    fWidths[c] = f.getStringBounds(singleChar, 0, 1, frc).getWidth();
                }
            }
            cachedFloatWidths = fWidths;
            cachedCharW = Math.max(1.0, fWidths['m']);
            boolean mono = true;
            for (int c = 32; c < 127; c++) {
                if (Math.abs(fWidths[c] - cachedCharW) > 1e-4) {
                    mono = false;
                    break;
                }
            }
            isMonospacedAscii = mono;
            cachedDefaultColW = (!mono && fWidths['0'] > 1.0) ? fWidths['0'] : cachedCharW;
        }

        private static boolean isZeroWidthChar(char ch) {
            return (ch >= 0xFE00 && ch <= 0xFE0F) || Character.getType(ch) == Character.FORMAT;
        }

        private double getComplexCharWidth(char[] arr, int idx, int begin, int end) {
            char ch = arr[idx];
            if (Character.isHighSurrogate(ch)) {
                if (idx + 1 < end && Character.isLowSurrogate(arr[idx + 1]) && cachedFont != null && cachedFrc != null) {
                    return cachedFont.getStringBounds(arr, idx, idx + 2, cachedFrc).getWidth();
                }
            } else if (Character.isLowSurrogate(ch)) {
                if (idx > begin && Character.isHighSurrogate(arr[idx - 1])) {
                    return 0.0;
                }
            }
            if (isZeroWidthChar(ch)) {
                return 0.0;
            }
            if (cachedFont != null && cachedFrc != null) {
                return cachedFont.getStringBounds(arr, idx, idx + 1, cachedFrc).getWidth();
            }
            return cachedCharW;
        }

        private double advanceColumnX(char[] arr, int idx, int begin, int end, double curX, int leftInset) {
            char ch = arr[idx];
            if (ch >= 32 && ch < 127) {
                return curX + (isMonospacedAscii ? cachedCharW : cachedFloatWidths[ch]);
            }
            if (ch == SOFT_WRAP_CHAR || ch == '\r' || ch == '\n') {
                return curX;
            }
            if (ch == '\t') {
                double tabSize = Math.max(1, textArea.getTabSize()) * cachedCharW;
                int ntabs = (int) (((float) curX - leftInset) / tabSize);
                return leftInset + (ntabs + 1) * tabSize;
            }
            if (ch < 256 && cachedFloatWidths != null) {
                return curX + cachedFloatWidths[ch];
            }
            return curX + getComplexCharWidth(arr, idx, begin, end);
        }

        // Populates lineSegment with the current caret line's characters and returns
        // [rowStartCol, rowEndCol, curColOffsetFromLineStart, lineColumnShift].
        private int[] loadCurrentLineBounds(int leftInset) {
            int rowStartCol = 0;
            int rowEndCol = 0;
            int curColOffset = 0;
            int lineShift = 0;
            try {
                Document doc = textArea.getDocument();
                int docLen = doc.getLength();
                int dot = (textArea.getCaret() != null) ? Math.max(0, Math.min(docLen, textArea.getCaret().getDot())) : 0;
                Element root = doc.getDefaultRootElement();
                int lineIdx = root.getElementIndex(dot);
                lineShift = parent.getLineColumnShift(lineIdx);
                Element lineElem = root.getElement(lineIdx);
                int lineStart = lineElem.getStartOffset();
                int lineEnd = Math.min(docLen, lineElem.getEndOffset());
                doc.getText(lineStart, Math.max(0, lineEnd - lineStart), lineSegment);
                int count = lineSegment.count;
                char[] arr = lineSegment.array;
                int off = lineSegment.offset;
                while (count > 0) {
                    char last = arr[off + count - 1];
                    if (last == '\n' || last == '\r' || last == SOFT_WRAP_CHAR) {
                        count--;
                    } else {
                        break;
                    }
                }
                lineSegment.count = count;
                curColOffset = Math.max(0, Math.min(count, dot - lineStart));
                rowEndCol = count;

                if (textArea.getLineWrap() && textArea.getWidth() > 0 && count > 0) {
                    java.awt.geom.Rectangle2D dotRect = textArea.modelToView2D(dot);
                    java.awt.geom.Rectangle2D startRect = textArea.modelToView2D(lineStart);
                    if (dotRect != null && startRect != null && Math.abs(dotRect.getY() - startRect.getY()) > 1.0) {
                        int rowY = (int) Math.round(dotRect.getY() + dotRect.getHeight() * 0.5);
                        int vStart = textArea.viewToModel2D(new Point(leftInset, rowY));
                        int vEnd = textArea.viewToModel2D(new Point(textArea.getWidth(), rowY));
                        if (vStart >= lineStart && vStart <= lineStart + count) {
                            rowStartCol = vStart - lineStart;
                        }
                        if (vEnd >= lineStart + rowStartCol && vEnd <= lineStart + count) {
                            rowEndCol = vEnd - lineStart;
                        }
                    }
                }
            } catch (Exception e) {
                lineSegment.count = 0;
            }
            return new int[] { rowStartCol, rowEndCol, curColOffset, lineShift };
        }

        int getRegularTickHeight() {
            Font baseFont = textArea.getFont();
            FontMetrics fm = textArea.getFontMetrics(getLabelFont(baseFont, Font.BOLD));
            return Math.max(4, (int) Math.round(fm.getHeight() * 0.28));
        }

        int getFifthTickHeight() {
            Font baseFont = textArea.getFont();
            FontMetrics fm = textArea.getFontMetrics(getLabelFont(baseFont, Font.BOLD));
            return Math.max(7, (int) Math.round(fm.getHeight() * 0.48));
        }

        int getTenthTickHeight() {
            Font baseFont = textArea.getFont();
            FontMetrics fm = textArea.getFontMetrics(getLabelFont(baseFont, Font.BOLD));
            return Math.max(8, (int) Math.round(fm.getHeight() * 0.56));
        }

        double getCharAdvanceWidth(java.awt.font.FontRenderContext frc) {
            ensureMetricsCache(textArea.getFont(), frc);
            return cachedCharW;
        }

        int getTickXForColumnOffset(int colOffsetFromLineStart, java.awt.font.FontRenderContext frc) {
            ensureMetricsCache(textArea.getFont(), frc);
            Insets ins = textArea.getInsets();
            int leftInset = (ins != null) ? ins.left : 0;
            int[] bounds = loadCurrentLineBounds(leftInset);
            int rowStartCol = bounds[0];
            int rowEndCol = bounds[1];
            char[] arr = lineSegment.array;
            int off = lineSegment.offset;

            double curX = leftInset;
            int startCol = Math.min(rowStartCol, Math.max(0, colOffsetFromLineStart));
            for (int c = startCol; c < colOffsetFromLineStart; c++) {
                if (c < rowEndCol) {
                    curX = advanceColumnX(arr, off + c, off, off + rowEndCol, curX, leftInset);
                } else {
                    curX += cachedDefaultColW;
                }
            }
            return (int) (leftInset + (float) (curX - leftInset));
        }

        int getCurrentCursorColumnOffset(java.awt.font.FontRenderContext frc) {
            if (textArea == null) {
                return 0;
            }
            Insets ins = textArea.getInsets();
            int leftInset = (ins != null) ? ins.left : 0;
            int[] bounds = loadCurrentLineBounds(leftInset);
            return bounds[2];
        }

        int getCurrentCursorDisplayColumn() {
            int colBase = parent.oneBasedColumnIndex ? 1 : 0;
            Insets ins = textArea.getInsets();
            int[] bounds = loadCurrentLineBounds((ins != null) ? ins.left : 0);
            return bounds[2] + colBase + bounds[3];
        }

        @Override
        public Dimension getPreferredSize() {
            Font baseFont = textArea.getFont();
            Font boldLabelFont = getLabelFont(baseFont, Font.BOLD);
            FontMetrics fm = textArea.getFontMetrics(boldLabelFont);
            int tenthTickH = getTenthTickHeight();
            int height = fm.getAscent() + fm.getDescent() + tenthTickH + 6;
            int width = textArea.getWidth();
            if (parent.scrollPane != null && parent.scrollPane.getViewport() != null) {
                width = Math.max(width, parent.scrollPane.getViewport().getWidth());
            }
            if (width <= 0) {
                width = 800;
            }
            return new Dimension(width, height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Font baseFont = textArea.getFont();
            FontMetrics editorFm = g.getFontMetrics(baseFont);
            java.awt.font.FontRenderContext frc = (editorFm != null)
                    ? editorFm.getFontRenderContext()
                    : textArea.getFontMetrics(baseFont).getFontRenderContext();
            ensureMetricsCache(baseFont, frc);

            Insets ins = textArea.getInsets();
            int leftInset = (ins != null) ? ins.left : 0;

            int mainScrollX = (parent.scrollPane != null && parent.scrollPane.getViewport() != null)
                    ? parent.scrollPane.getViewport().getViewPosition().x
                    : 0;
            int headerScrollX = (getParent() instanceof JViewport)
                    ? ((JViewport) getParent()).getViewPosition().x
                    : 0;
            int scrollDelta = mainScrollX - headerScrollX;

            Rectangle clip = g.getClipBounds();
            Rectangle vis = getVisibleRect();
            int viewLeftInRuler;
            int viewRightInRuler;
            if (vis != null && vis.width > 0) {
                viewLeftInRuler = vis.x;
                viewRightInRuler = vis.x + vis.width;
                if (clip != null && clip.width > 0) {
                    viewLeftInRuler = Math.max(viewLeftInRuler, clip.x);
                    viewRightInRuler = Math.min(viewRightInRuler, clip.x + clip.width);
                }
            } else if (clip != null && clip.width > 0) {
                viewLeftInRuler = clip.x;
                viewRightInRuler = clip.x + clip.width;
            } else {
                viewLeftInRuler = 0;
                viewRightInRuler = Math.max(getWidth(), 800);
            }

            int textAreaLeft = viewLeftInRuler + scrollDelta - 80;
            int textAreaRight = viewRightInRuler + scrollDelta + 80;

            Font plainLabelFont = getLabelFont(baseFont, Font.PLAIN);
            Font boldLabelFont = getLabelFont(baseFont, Font.BOLD);
            FontMetrics plainFm = g.getFontMetrics(plainLabelFont);
            FontMetrics boldFm = g.getFontMetrics(boldLabelFont);

            boolean isDark = "Dark".equals(parent.currentTheme);
            Color normalColor = isDark ? new Color(125, 125, 125) : new Color(120, 120, 120);
            Color activeColor = isDark ? new Color(240, 240, 240) : new Color(15, 15, 15);

            int h = getHeight();
            int tickBottomY = Math.max(1, h - 2);
            int regTickH = getRegularTickHeight();
            int fifthTickH = getFifthTickHeight();
            int tenthTickH = getTenthTickHeight();
            int labelBaselineY = Math.max(boldFm.getAscent(), tickBottomY - tenthTickH - 2);

            int[] bounds = loadCurrentLineBounds(leftInset);
            int rowStartCol = bounds[0];
            int rowEndCol = bounds[1];
            int curColOffset = bounds[2];
            int lineShift = bounds[3];
            char[] arr = lineSegment.array;
            int off = lineSegment.offset;

            int colBase = (parent.oneBasedColumnIndex ? 1 : 0) + lineShift;
            int curDisplayCol = curColOffset + colBase;

            // Pre-compute the current cursor column's X coordinate so we can prevent label collisions
            double scanX = leftInset;
            for (int c = rowStartCol; c < curColOffset; c++) {
                if (c < rowEndCol) {
                    scanX = advanceColumnX(arr, off + c, off, off + rowEndCol, scanX, leftInset);
                } else {
                    scanX += cachedDefaultColW;
                }
            }
            int curTickRulerX = (int) (leftInset + (float) (scanX - leftInset)) - scrollDelta;
            String curLabel = String.valueOf(curDisplayCol);
            int curLabelW = boldFm.stringWidth(curLabel);
            int curLabelX = Math.max(1, curTickRulerX - (curLabelW / 2));
            int curLabelLeftPad = curLabelX - 2;
            int curLabelRightPad = curLabelX + curLabelW + 2;

            // 1. Walk columns along the current line, drawing regular, 5th, and 10th ticks and 10th labels
            g.setColor(normalColor);
            g.setFont(plainLabelFont);
            double curX = leftInset;
            int lastLabelRight = Integer.MIN_VALUE;
            boolean curTickInVisibleRange = false;

            for (int k = rowStartCol;; k++) {
                int tickTextAreaX = (int) (leftInset + (float) (curX - leftInset));
                if (tickTextAreaX > textAreaRight) {
                    break;
                }
                if (tickTextAreaX >= textAreaLeft) {
                    int displayCol = k + colBase;
                    int tickRulerX = tickTextAreaX - scrollDelta;

                    int tickH;
                    if (displayCol % 10 == 0) {
                        tickH = tenthTickH;
                    } else if (displayCol % 5 == 0) {
                        tickH = fifthTickH;
                    } else {
                        tickH = regTickH;
                    }

                    if (k == curColOffset) {
                        curTickInVisibleRange = true;
                    } else {
                        g.drawLine(tickRulerX, tickBottomY - tickH + 1, tickRulerX, tickBottomY);
                    }

                    if (displayCol % 10 == 0 && k != curColOffset) {
                        String label = String.valueOf(displayCol);
                        int labelW = plainFm.stringWidth(label);
                        int labelX = Math.max(1, tickRulerX - (labelW / 2));
                        if ((labelX + labelW < curLabelLeftPad || labelX > curLabelRightPad)
                                && labelX >= lastLabelRight + 2) {
                            g.drawString(label, labelX, labelBaselineY);
                            lastLabelRight = labelX + labelW;
                        }
                    }
                }

                if (k < rowEndCol) {
                    curX = advanceColumnX(arr, off + k, off, off + rowEndCol, curX, leftInset);
                } else {
                    curX += cachedDefaultColW;
                }
            }

            // 2. Draw the active cursor column tick and its column index number in bold and darker/pronounced color
            if (curTickInVisibleRange) {
                g.setColor(activeColor);
                g.setFont(boldLabelFont);
                g.fillRect(curTickRulerX, tickBottomY - tenthTickH + 1, 2, tenthTickH);
                g.drawString(curLabel, curLabelX, labelBaselineY);
            }
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

    // --- Cross-Chunk Bridging Match State ---
    // When a search match starts near the end of chunk i and extends into the beginning of chunk i + 1,
    // the portion in chunk i (startInChunk..endInChunk) is selected in textArea and endInNextChunk records
    // how many characters at the start of chunk i + 1 belong to the same match.
    private int activeBridgeChunkIndex = -1;
    private int activeBridgeStartInChunk = -1;
    private int activeBridgeEndInChunk = -1;
    private int activeBridgeEndInNextChunk = 0;

    private void setActiveBridge(int chunkIdx, int startInChunk, int endInChunk, int endInNextChunk) {
        this.activeBridgeChunkIndex = chunkIdx;
        this.activeBridgeStartInChunk = startInChunk;
        this.activeBridgeEndInChunk = endInChunk;
        this.activeBridgeEndInNextChunk = Math.max(0, endInNextChunk);
    }

    void clearActiveBridge() {
        this.activeBridgeChunkIndex = -1;
        this.activeBridgeStartInChunk = -1;
        this.activeBridgeEndInChunk = -1;
        this.activeBridgeEndInNextChunk = 0;
    }

    boolean isBridgeSelectionActive(int rawStart, int rawEnd) {
        return activeBridgeChunkIndex == loadedChunkIndex
                && activeBridgeEndInNextChunk > 0
                && rawStart == activeBridgeStartInChunk
                && rawEnd == activeBridgeEndInChunk;
    }

    int getActiveBridgeEndInNextChunk() {
        return activeBridgeEndInNextChunk;
    }

    private String getNextChunkHeadForView(int chunkIdx) {
        if (chunkIdx + 1 >= fileManager.getTotalChunks()) {
            return "";
        }
        try {
            String head = fileManager.getChunkHead(chunkIdx + 1, LargeFileManager.SEARCH_BRIDGE_WINDOW);
            if (head.isEmpty()) {
                return "";
            }
            return isBinaryMode() ? encodeBinaryForView(head) : stripControlAndFormatChars(head);
        } catch (Exception e) {
            return "";
        }
    }

    private void selectMatchInLoadedChunk(int rawStart, int rawLen, int endInNextChunk) {
        int docLen = textArea.getDocument().getLength();
        int visualStart = Math.min(rawToVisualIndex(rawStart), docLen);
        int visualEnd = Math.min(rawToVisualIndex(rawStart + rawLen), docLen);
        textArea.setCaretPosition(visualStart);
        textArea.moveCaretPosition(visualEnd);
        textArea.requestFocusInWindow();
        if (endInNextChunk > 0) {
            setActiveBridge(loadedChunkIndex, visualToRawIndex(visualStart), visualToRawIndex(visualEnd), endInNextChunk);
        } else {
            clearActiveBridge();
        }
    }

    public void performFind(String target, boolean searchForward) {
        if (target == null || target.isEmpty())
            return;
        setSearchDialogEnabled(false);

        // Explicitly make variables FINAL for the SwingWorker
        final String fTarget = target;
        final Pattern fPattern = getSearchPattern(fTarget);
        final boolean fForward = searchForward;
        
        int[] colRange = getSearchColumnRange();
        final int fMinCol = colRange[0];
        final int fMaxCol = colRange[1];

        // Get all tabs in left-to-right physical order
        final java.util.List<AdvancedTextEditorPanel> fTargets = (chkAllTabs != null && chkAllTabs.isSelected())
                ? getAllOpenEditors()
                : java.util.Arrays.asList(this);

        // Find the currently active editor on the screen, not just the one that spawned
        // this dialog
        Window window = SwingUtilities.getWindowAncestor(this);
        AdvancedTextEditorPanel tempActive = getCurrentlyActiveEditor(window);
        if (tempActive == null || !fTargets.contains(tempActive)) {
            tempActive = this;
        }
        final AdvancedTextEditorPanel activeEditor = tempActive;
        final int activeIndex = fTargets.indexOf(activeEditor);

        // Synchronous check in the CURRENTLY ACTIVE editor's loaded chunk (including any match
        // that starts near the end of the loaded chunk and bridges into the next chunk).
        int visualCaret = fForward ? activeEditor.textArea.getCaretPosition()
                : activeEditor.textArea.getSelectionStart();
        String selected = activeEditor.textArea.getSelectedText();
        String rawText = activeEditor.textArea.getText().replace("\u200B\n", "").replace("\u200B", "");
        String nextHeadForView = activeEditor.getNextChunkHeadForView(activeEditor.loadedChunkIndex);
        String boundaryAndNextHead = activeEditor.hiddenBoundaryNewline + nextHeadForView;
        int nextChunkInitialSkip = 0;

        if (fForward) {
            if (selected != null) {
                String strippedSelection = selected.replace("\u200B\n", "").replace("\u200B", "");
                int rawSelStart = activeEditor.visualToRawIndex(activeEditor.textArea.getSelectionStart());
                int rawSelEnd = activeEditor.visualToRawIndex(activeEditor.textArea.getSelectionEnd());
                if (activeEditor.isBridgeSelectionActive(rawSelStart, rawSelEnd)) {
                    int endInNext = Math.min(nextHeadForView.length(), activeEditor.activeBridgeEndInNextChunk);
                    String bridgedSel = strippedSelection + activeEditor.hiddenBoundaryNewline
                            + nextHeadForView.substring(0, endInNext);
                    if (fPattern.matcher(bridgedSel).matches()) {
                        visualCaret = activeEditor.textArea.getSelectionEnd();
                        nextChunkInitialSkip = activeEditor.activeBridgeEndInNextChunk;
                    }
                } else if (fPattern.matcher(strippedSelection).matches()) {
                    visualCaret = activeEditor.textArea.getSelectionEnd();
                }
            }
            int rawCaret = activeEditor.visualToRawIndex(visualCaret);
            LargeFileManager.ChunkMatch cm = LargeFileManager.findFirstMatchInChunk(
                    fPattern, rawText, boundaryAndNextHead, rawCaret, fMinCol, fMaxCol);
            if (cm != null) {
                int consumedInNext = cm.isBridging()
                        ? Math.max(0, cm.endInNextChunk() - activeEditor.hiddenBoundaryNewline.length())
                        : 0;
                activeEditor.selectMatchInLoadedChunk(cm.startInChunk(), cm.lengthInChunk(), consumedInNext);
                setSearchDialogEnabled(true);
                return;
            }
        } else {
            int rawCaret = activeEditor.visualToRawIndex(visualCaret);
            LargeFileManager.ChunkMatch cm = LargeFileManager.findLastMatchInChunk(
                    fPattern, rawText, boundaryAndNextHead, rawCaret, fMinCol, fMaxCol);
            if (cm != null) {
                int consumedInNext = cm.isBridging()
                        ? Math.max(0, cm.endInNextChunk() - activeEditor.hiddenBoundaryNewline.length())
                        : 0;
                activeEditor.selectMatchInLoadedChunk(cm.startInChunk(), cm.lengthInChunk(), consumedInNext);
                setSearchDialogEnabled(true);
                return;
            }
        }

        // Setup Background Search
        lblLoadingStatus.setText(fForward ? "Scanning for next match..." : "Scanning for previous match...");

        final String fCommitText = activeEditor.getCommitText();
        final boolean fWasDirty = activeEditor.isDirty;
        activeEditor.isDirty = false;
        final int fNextChunkInitialSkip = nextChunkInitialSkip;

        class MatchResult {
            AdvancedTextEditorPanel editor;
            int chunkIdx, foundIdx, foundLen, endInNextChunk;
        }

        activeSearchWorker = new SwingWorker<MatchResult, Integer>() {
            @Override
            protected MatchResult doInBackground() throws Exception {
                if (fWasDirty && !activeEditor.isCurrentlyPreview) {
                    activeEditor.fileManager.commitCurrentChunk(fCommitText);
                }

                int totalGlobalChunks = fTargets.stream().mapToInt(e -> e.fileManager.getTotalChunks()).sum();
                int chunksScanned = 0;

                // Loop exactly fTargets.size() times, using modulo math to seamlessly wrap
                // around tabs
                for (int count = 0; count < fTargets.size(); count++) {
                    int t;
                    if (fForward) {
                        t = (activeIndex + count) % fTargets.size();
                    } else {
                        // Modulo of negative numbers is weird in Java, so we add the size to prevent
                        // negative indices
                        t = (activeIndex - count + fTargets.size()) % fTargets.size();
                    }

                    AdvancedTextEditorPanel editor = fTargets.get(t);
                    int totalChunks = editor.fileManager.getTotalChunks();

                    int startChunk;
                    int endChunk;
                    int step;

                    if (fForward) {
                        startChunk = (count == 0) ? editor.loadedChunkIndex + 1 : 0;
                        endChunk = totalChunks - 1;
                        step = 1;
                    } else {
                        startChunk = (count == 0) ? editor.loadedChunkIndex - 1 : Math.max(0, totalChunks - 1);
                        endChunk = 0;
                        step = -1;
                    }

                    for (int i = startChunk; (fForward ? i <= endChunk : i >= endChunk); i += step) {
                        if (isCancelled())
                            return null;

                        String content = editor.fileManager.getChunkContent(i);
                        String c1Head = (i + 1 < totalChunks)
                                ? editor.fileManager.getChunkHead(i + 1, LargeFileManager.SEARCH_BRIDGE_WINDOW)
                                : "";

                        if (fForward) {
                            int fromIdx = (count == 0 && i == startChunk) ? fNextChunkInitialSkip : 0;
                            LargeFileManager.ChunkMatch cm = LargeFileManager.findFirstMatchInChunk(
                                    fPattern, content, c1Head, fromIdx, fMinCol, fMaxCol);
                            if (cm != null) {
                                MatchResult res = new MatchResult();
                                res.editor = editor;
                                res.chunkIdx = i;
                                res.foundIdx = cm.startInChunk();
                                res.foundLen = cm.lengthInChunk();
                                res.endInNextChunk = cm.endInNextChunk();
                                return res;
                            }
                        } else {
                            LargeFileManager.ChunkMatch cm = LargeFileManager.findLastMatchInChunk(
                                    fPattern, content, c1Head, content.length(), fMinCol, fMaxCol);
                            if (cm != null) {
                                MatchResult res = new MatchResult();
                                res.editor = editor;
                                res.chunkIdx = i;
                                res.foundIdx = cm.startInChunk();
                                res.foundLen = cm.lengthInChunk();
                                res.endInNextChunk = cm.endInNextChunk();
                                return res;
                            }
                        }

                        chunksScanned++;
                        if (totalGlobalChunks > 0)
                            publish((int) (((double) chunksScanned / totalGlobalChunks) * 100));
                    }
                }
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int pct = chunks.get(chunks.size() - 1);
                    lblLoadingStatus.setText(
                            (fForward ? "Scanning for next match... " : "Scanning for previous match... ") + pct + "%");
                }
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled())
                        return;
                    MatchResult result = get();

                    if (result != null) {
                        activateEditorTab(result.editor);
                        result.editor.triggerAsyncLoad(result.chunkIdx, 0, -1, false, () -> {
                            result.editor.selectMatchInLoadedChunk(
                                    result.foundIdx, result.foundLen, result.endInNextChunk);
                        });
                    } else {
                        String msg = (chkAllTabs != null && chkAllTabs.isSelected())
                                ? (fForward
                                        ? "Scanned all open files. No further matches found. Start again from the top of the first file?"
                                        : "Scanned all open files. No further matches found. Search again from the bottom of the last file?")
                                : (fForward ? "Reached end of file. Start again from the top?"
                                        : "Reached beginning of file. Search again from the bottom?");

                        int response = DialogUtil.showConfirmDialog(getDialogParent(), msg, "Search Wrap Around",
                                JOptionPane.YES_NO_OPTION);
                        if (response == JOptionPane.YES_OPTION) {

                            // Get the absolute first tab (0) or absolute last tab (size - 1)
                            AdvancedTextEditorPanel targetEditor = fForward ? fTargets.get(0)
                                    : fTargets.get(fTargets.size() - 1);
                            activateEditorTab(targetEditor);

                            int targetChunk = fForward ? 0 : Math.max(0, targetEditor.fileManager.getTotalChunks() - 1);

                            targetEditor.triggerAsyncLoad(targetChunk, (fForward ? 1 : -1), -1, false, () -> {
                                targetEditor.clearActiveBridge();
                                targetEditor.textArea.setCaretPosition(
                                        fForward ? 0 : targetEditor.textArea.getDocument().getLength());
                                // Recursively execute the search again now that we are positioned!
                                performFind(fTarget, fForward);
                            });
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    lblLoadingStatus.setText("");
                    setSearchDialogEnabled(true);
                }
            }
        };
        activeSearchWorker.execute();
    }

    public void performReplace(String target, String replacement) {
        if (target == null || target.isEmpty())
            return;

        String selected = textArea.getSelectedText();
        if (selected != null && !isCurrentlyPreview) {
            String strippedSelection = selected.replace("\u200B\n", "").replace("\u200B", "");
            Pattern p = getSearchPattern(target);
            int[] colRange = getSearchColumnRange();
            int minCol = colRange[0];
            int maxCol = colRange[1];

            int startOffset = textArea.getSelectionStart();
            int endOffset = textArea.getSelectionEnd();
            String rawText = textArea.getText().replace("\u200B\n", "").replace("\u200B", "");
            int rawStart = visualToRawIndex(startOffset);
            int rawEnd = visualToRawIndex(endOffset);
            String rep = (chkRegex != null && chkRegex.isSelected()) ? replacement
                    : Matcher.quoteReplacement(replacement);

            if (isBridgeSelectionActive(rawStart, rawEnd)) {
                int endInNext = activeBridgeEndInNextChunk;
                String nextHead = getNextChunkHeadForView(loadedChunkIndex);
                if (nextHead.length() >= endInNext) {
                    String boundaryAndNext = hiddenBoundaryNewline + nextHead;
                    String bridgedSelection = strippedSelection + hiddenBoundaryNewline
                            + nextHead.substring(0, endInNext);
                    int combinedEnd = rawEnd + hiddenBoundaryNewline.length() + endInNext;
                    if (p.matcher(bridgedSelection).matches()
                            && LargeFileManager.isMatchInColumnRange(rawText, boundaryAndNext, rawStart, combinedEnd, minCol, maxCol)) {
                        Matcher m = p.matcher(bridgedSelection);
                        String replaced = m.replaceFirst(rep);
                        hiddenBoundaryNewline = "";
                        textArea.replaceSelection(replaced);
                        Document curDoc = textArea.getDocument();
                        if (curDoc != null) {
                            curDoc.putProperty("bearit.hiddenBoundaryNewline", "");
                        }
                        try {
                            fileManager.commitCurrentChunk(getCommitText());
                            isDirty = false;
                            fileManager.trimLeadingCharsFromChunk(loadedChunkIndex + 1, endInNext);
                            final int curIdx = loadedChunkIndex;
                            documentCache.keySet().removeIf(k -> k > curIdx);
                            setUnsavedChanges(true);
                        } catch (Exception ignored) {
                        }
                        clearActiveBridge();
                    }
                }
            } else if (p.matcher(strippedSelection).matches()) {
                if (LargeFileManager.isMatchInColumnRange(rawText, rawStart, rawEnd, minCol, maxCol)) {
                    Matcher m = p.matcher(strippedSelection);
                    textArea.replaceSelection(m.replaceFirst(rep));
                    clearActiveBridge();
                }
            }
        }
        performFind(target, true);
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
            } catch (Exception e) {
            }
        }
    }

    // --- Text Case Conversion Methods ---
    public void convertSelectionCase(String mode) {
        if (isCurrentlyPreview)
            return; // Don't allow edits in preview mode

        String selected = textArea.getSelectedText();
        if (selected == null || selected.isEmpty())
            return;

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

        JMenuItem gotoItem = new JMenuItem("Go To Line or Position...");
        gotoItem.addActionListener(e -> showGotoDialog());

        JMenu formatMenu = new JMenu("Format Document");
        JMenuItem mnuFormatJson = new JMenuItem("JSON");
        mnuFormatJson.addActionListener(e -> formatDocument("json"));
        JMenuItem mnuFormatXml = new JMenuItem("XML");
        mnuFormatXml.addActionListener(e -> formatDocument("xml"));
        JMenuItem mnuFormatHtml = new JMenuItem("HTML");
        mnuFormatHtml.addActionListener(e -> formatDocument("html"));
        formatMenu.add(mnuFormatJson);
        formatMenu.add(mnuFormatXml);
        formatMenu.add(mnuFormatHtml);

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
        contextMenu.add(formatMenu);
        contextMenu.addSeparator();
        contextMenu.add(convertCaseMenu);

        return contextMenu;
    }

    // --- Print Functionality ---
    public void printFile() {
        if (textArea == null || textArea.getText().isEmpty()) {
            // JOptionPane.showMessageDialog(getDialogParent(), "The document is empty.",
            // "Print", JOptionPane.INFORMATION_MESSAGE);
            DialogUtil.showMessageDialog(getDialogParent(), "The document is empty.", "Print",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Create a header showing the filename, and a footer showing the page number
        MessageFormat header = new MessageFormat("File: " + currentTitle);
        MessageFormat footer = new MessageFormat("Page - {0}");

        // Let the user know we are preparing the print job
        lblLoadingStatus.setText("Preparing print job...");

        // Printing blocks the thread it runs on while the OS dialog is open and
        // spooling.
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

    public void setLoadingStatus(String statusText) {
        lblLoadingStatus.setText(statusText);
    }

    public void showChunkLoadProgressBar(boolean visibleFlag) {
        chunkLoadProgressBar.setVisible(visibleFlag);
    }

    /**
     * Used by the background Hex Wrapper to set the boundary correctly without
     * locking the UI
     */
    public void setHiddenBoundaryNewline(String hiddenNewline) {
        this.hiddenBoundaryNewline = hiddenNewline;
    }

    /**
     * Builds the heavy Swing Text Document structure completely off the UI thread.
     * Because it is not attached to a JTextArea yet, this is perfectly thread-safe
     * and absorbs the 3-5 second processing penalty in the background!
     */
    public javax.swing.text.Document buildDocumentOffThread(String text) throws javax.swing.text.BadLocationException {
        long startCol = 0;
        if (!isBinaryMode()) {
            try {
                startCol = Math.max(0, fileManager.getChunkStartColumn(loadedChunkIndex));
            } catch (Exception ignored) {
            }
        }
        javax.swing.text.Document newDoc = createWrappedDocument(-1, startCol);
        // Insert the massive string without triggering UI repaints or Undo history
        newDoc.insertString(0, text, null);
        return newDoc;
    }

    /**
     * Instantly applies a pre-built Document to the UI, bypassing the EDT freeze.
     */
    public void applyForceSetDocumentUI(javax.swing.text.Document newDoc, boolean hadUnsavedAsterisk) {
        if (documentCache != null)
            documentCache.clear();

        if (textArea != null) {
            // Attach the standard listeners NOW, so the initial load isn't tracked in Undo
            // history
            newDoc.addDocumentListener(editorDocumentListener);
            newDoc.addUndoableEditListener(e -> {
                if (!isNavigating && !isCurrentlyPreview) {
                    globalUndoManager.addEdit(new ChunkAwareEdit(loadedChunkIndex, e.getEdit()));
                }
            });

            // This takes ~10 milliseconds instead of 5 seconds!
            textArea.setDocument(newDoc);
            textArea.setCaretPosition(0);

            if (!isCurrentlyPreview) {
                newDoc.putProperty("bearit.hiddenBoundaryNewline", hiddenBoundaryNewline);
                documentCache.put(loadedChunkIndex, newDoc);
            }
        }

        this.isDirty = false;
        setUnsavedChanges(hadUnsavedAsterisk);
    }

    public void forceSetText(String text) {
        // Capture the exact save state before the JTextArea ruins it
        boolean hadUnsavedAsterisk = this.hasUnsavedChanges();

        if (documentCache != null) {
            documentCache.clear();
        }

        // --- APPLY BOUNDARY STRIPPING ON TAB SYNC ---
        hiddenBoundaryNewline = "";
        if (!isBinaryMode() && loadedChunkIndex < fileManager.getTotalChunks() - 1) {
            if (text.endsWith("\r\n")) {
                hiddenBoundaryNewline = "\r\n";
                text = text.substring(0, text.length() - 2);
            } else if (text.endsWith("\n")) {
                hiddenBoundaryNewline = "\n";
                text = text.substring(0, text.length() - 1);
            }
        }

        if (textArea != null) {
            // --- Apply protections when syncing from Hex Editor ---
            if (isBinaryMode()) {
                text = encodeBinaryForView(text);
            } else {
                text = stripControlAndFormatChars(text);
            }

            textArea.setText(text);
            textArea.setCaretPosition(0);
        }

        // Forcefully restore the exact state to whatever it was a millisecond ago
        this.isDirty = false;
        setUnsavedChanges(hadUnsavedAsterisk);
    }

    public int getRawCaretPosition() {
        if (textArea == null)
            return 0;
        return visualToRawIndex(textArea.getCaretPosition());
    }

    public void setRawCaretPosition(int rawIndex) {
        if (textArea == null)
            return;

        int visualIdx = rawToVisualIndex(rawIndex);
        if (visualIdx >= 0 && visualIdx <= textArea.getDocument().getLength()) {
            textArea.setCaretPosition(visualIdx);

            // Auto-scroll the text area to ensure the new cursor position is visible on
            // screen
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
            int rawCaret = getRawCaretPosition(); // Handles soft wrap \u200B removal natively
            long chunkStartOffset = fileManager.getChunkBoundaries(loadedChunkIndex)[0];
            // Optimization: Fast-path caret at offset 0 to return chunkStartOffset immediately
            // without reading and UTF-8 encoding the 25MB chunk text.
            if (rawCaret <= 0) {
                return chunkStartOffset;
            }

            // --- PERFECT BINARY MATH ---
            if (isBinaryMode()) {
                // Because \r is PUA encoded, Swing doesn't swallow it. Lengths map 1-to-1
                // perfectly!
                return chunkStartOffset + rawCaret;
            }

            // Standard Text Mode - Account for UTF-8 multi-byte characters and stripped
            // controls
            String rawChunkText = fileManager.getChunkContent(loadedChunkIndex);
            int jTextAreaIdx = 0;
            int originalStringIdx = 0;
            while (jTextAreaIdx < rawCaret && originalStringIdx < rawChunkText.length()) {
                char c = rawChunkText.charAt(originalStringIdx);
                // JTextArea preserves \r\n naturally. We only skip the hidden controls we
                // explicitly stripped on load.
                if (c == '\r' || c == '\n' || c == '\t'
                        || (Character.getType(c) != Character.CONTROL && Character.getType(c) != Character.FORMAT)) {
                    jTextAreaIdx++;
                }
                originalStringIdx++;
            }

            // Convert exact raw substring to bytes to get the true UTF-8 byte offset
            byte[] bytesUpToCaret = rawChunkText.substring(0, originalStringIdx)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

            // UX Protection: Prevent accidental multi-line or massive dumps into the
            // combobox
            if (selected.contains("\n") || selected.length() > 200) {
                return null;
            }
            return selected;
        }
        return null;
    }

    public void updateStatusLabel(String chunkStatus, String fileStatus) {
        if (!fileStatus.isEmpty())
            lblStatus.setText(chunkStatus + "  |  " + fileStatus); // chunk location and file size and modified date
        else {
            lblStatus.setText(chunkStatus); // chunk location only
        }
        // remember last status updates
        this.chunkStatus = chunkStatus;
        this.fileSizeDateStatus = fileStatus;
    }

    /**
     * Converts raw bytes into a clean, human-readable format (e.g., "45.2 KB", "1.2
     * MB").
     */
    public static String humanReadableByteCount(long bytes) {
        if (bytes < 1024)
            return bytes + " B";

        // Calculate the exponent (1 = KB, 2 = MB, 3 = GB, etc.)
        int exp = (int) (Math.log(bytes) / Math.log(1024));

        // Pick the correct prefix letter
        char pre = "KMGTPE".charAt(exp - 1);

        // Format to 1 decimal place (e.g., 45.2)
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * Formats a standard Unix timestamp into a readable Date/Time string.
     */
    public static String formatLastModified(long millis) {
        // You can adjust this pattern to whatever fits your preference!
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date(millis));
    }

    public String getReadOnlyStatus() {
        return (readOnlyMode || (fileManager.getCurrentFile() != null && fileManager.isReadOnly())) ? "  |  ***READ-ONLY*** " : "";
    }

    /**
     * Generates the combined status string for the UI.
     */
    public String getFileInfoString(File file) {
        if (file != null && file.exists()) {
            String sizeStr = humanReadableByteCount(file.length());
            String dateStr = formatLastModified(file.lastModified());
            
            String encInfo = fileManager.getDetectedEncoding();
            String leInfo = fileManager.getDetectedLineEndings();
            
            String extraInfo = "  |  " + encInfo;
            if (!"N/A".equals(leInfo) && !"Binary".equals(encInfo)) {
                extraInfo += " (" + leInfo + ")";
            }
            
            return "Size: " + sizeStr + "  |  Modified: " + dateStr + extraInfo + getReadOnlyStatus();
        }
        return "Unsaved/New File" + getReadOnlyStatus();
    }

    public boolean isBinaryMode() {
        return fileManager.isBinaryMode();
    }

    public void changeEncoding(String encodingName) {
        if (isBinaryMode() || fileManager.getCurrentFile() == null) return;
        
        // Don't do anything if it's the exact same encoding
        if (encodingName.equals(fileManager.getDetectedEncoding())) return;

        java.nio.charset.Charset newCharset;
        switch (encodingName) {
            case "UTF-8 BOM": newCharset = java.nio.charset.StandardCharsets.UTF_8; break;
            case "UTF-16 LE": newCharset = java.nio.charset.StandardCharsets.UTF_16LE; break;
            case "UTF-16 BE": newCharset = java.nio.charset.StandardCharsets.UTF_16BE; break;
            case "ISO-8859-1": newCharset = java.nio.charset.StandardCharsets.ISO_8859_1; break;
            case "UTF-8":
            default: newCharset = java.nio.charset.StandardCharsets.UTF_8; break;
        }
        
        fileManager.setDetectedEncodingAndCharset(encodingName, newCharset);
        
        if (!hasUnsavedChanges && activeFile != null) {
            fileManager.clearCachesForEncodingChange();
            try {
                applyStateUpdates(fileManager.loadCurrentChunk(false), 1, -1, null);
                isDirty = false;
                setUnsavedChanges(false);
            } catch (IOException ex) {
                showError("Failed to apply new encoding: " + ex.getMessage());
            }
            updateStatusLabel(chunkStatus, getFileInfoString(activeFile));
            return;
        }

        setUnsavedChanges(true);
        updateStatusLabel(chunkStatus, getFileInfoString(activeFile));
    }

    public void changeLineEndings(String le) {
        if (isBinaryMode() || fileManager.getCurrentFile() == null) return;
        fileManager.setDetectedLineEndings(le);
        setUnsavedChanges(true);
        updateStatusLabel(chunkStatus, getFileInfoString(activeFile));
    }

    /**
     * Translates dangerous control bytes into safe visual placeholders
     * while perfectly preserving their exact original hex values.
     */
    private String encodeBinaryForView(String rawContent) {
        if (!isBinaryMode())
            return rawContent;

        StringBuilder sb = new StringBuilder(rawContent.length());
        for (int i = 0; i < rawContent.length(); i++) {
            char c = rawContent.charAt(i);

            // Allow \n (0x0A) and \t (0x09) and printable ASCII to render natively.
            // Force \r (0x0D) and other controls to PUA to prevent Swing from mutating
            // them.
            if (c == '\n' || c == '\t' || (c >= 32 && c <= 126)) {
                sb.append(c);
            } else {
                sb.append((char) (0xE000 + (c & 0xFF)));
            }
        }
        return sb.toString();
    }

    /**
     * Reverses the visual placeholders back into their true underlying byte values.
     */
    private String decodeViewToBinary(String uiText) {
        if (!isBinaryMode())
            return uiText;

        StringBuilder sb = new StringBuilder(uiText.length());
        for (int i = 0; i < uiText.length(); i++) {
            char c = uiText.charAt(i);
            // Reverse the PUA characters back to their exact original byte values
            if (c >= 0xE000 && c <= 0xE0FF) {
                sb.append((char) (c - 0xE000));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Crawls up to the main window and extracts every open Text Editor instance,
     * ordering the active one first
     */
    private java.util.List<AdvancedTextEditorPanel> getAllOpenEditors() {
        java.util.List<AdvancedTextEditorPanel> editors = new java.util.ArrayList<>();
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            findEditors(window, editors);

            // Find the "this" active editor to put it at the front of the list
            if (editors.contains(this)) {
                // Rotate the list so "this" active editor is at index 0, maintaining the
                // wrap-around order
                int activeIndex = editors.indexOf(this);
                java.util.Collections.rotate(editors, -activeIndex);
            }
        }
        return editors;
    }

    private void findEditors(Container container, java.util.List<AdvancedTextEditorPanel> editors) {
        if (container instanceof JTabbedPane) {
            JTabbedPane tp = (JTabbedPane) container;
            for (int i = 0; i < tp.getTabCount(); i++) {
                Component comp = tp.getComponentAt(i);
                if (comp instanceof AdvancedTextEditorPanel) {
                    editors.add((AdvancedTextEditorPanel) comp);
                } else if (comp instanceof Container) {
                    findEditors((Container) comp, editors);
                }
            }
        } else {
            for (Component comp : container.getComponents()) {
                if (comp instanceof AdvancedTextEditorPanel) {
                    editors.add((AdvancedTextEditorPanel) comp);
                } else if (comp instanceof Container) {
                    findEditors((Container) comp, editors);
                }
            }
        }
    }

    /**
     * Recursively searches the UI tree to find the specifically selected
     * JTabbedPane tab
     */
    private AdvancedTextEditorPanel getCurrentlyActiveEditor(Container container) {
        if (container == null)
            return null;

        if (container instanceof JTabbedPane) {
            Component selected = ((JTabbedPane) container).getSelectedComponent();
            if (selected instanceof AdvancedTextEditorPanel) {
                return (AdvancedTextEditorPanel) selected;
            } else if (selected instanceof Container) {
                return getCurrentlyActiveEditor((Container) selected);
            }
        }

        for (Component comp : container.getComponents()) {
            if (comp instanceof AdvancedTextEditorPanel) {
                return (AdvancedTextEditorPanel) comp;
            } else if (comp instanceof Container) {
                AdvancedTextEditorPanel found = getCurrentlyActiveEditor((Container) comp);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /** Forces the JTabbedPane to visually switch to the tab containing the match */
    private void activateEditorTab(AdvancedTextEditorPanel targetEditor) {
        Component current = targetEditor;
        while (current != null) {
            Container parent = current.getParent();
            if (parent instanceof JTabbedPane) {
                ((JTabbedPane) parent).setSelectedComponent(current);
            }
            current = parent;
        }
        targetEditor.focusEditor();
    }

}