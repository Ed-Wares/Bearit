package com.edwares;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * A modal, resizable dialog for choosing an editor font family and size.
 *
 * <p>Features:
 * <ul>
 *   <li>Asynchronous background loading of system font families and sample {@link Font} instances so opening
 *       the dialog never blocks or freezes the UI thread.</li>
 *   <li>Live case-insensitive font-name filtering via the filter text field above the font list.</li>
 *   <li>Scrollable list of system font families displaying each font name alongside a sample test string
 *       ({@link #LIST_SAMPLE_TEXT}) rendered in that font, with high-contrast blue selection highlighting
 *       ({@code Color(74, 136, 199)}), automatically scrolled to the initial font on open.</li>
 *   <li>Font size spinner constrained to {@code 6..80} pt.</li>
 *   <li>Editable live preview field with the currently selected font name displayed next to {@code Preview:}.</li>
 *   <li><b>Reset</b> button to clear any filter and restore {@link #DEFAULT_FONT_NAME} ({@code Monospaced})
 *       at {@link #DEFAULT_FONT_SIZE} ({@code 14}).</li>
 *   <li>Light and Dark theme support via {@link DialogUtil#themeDialog(JDialog)}.</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * BearitProperties props = BearitProperties.getInstance();
 * FontPickerDialog dialog = new FontPickerDialog(
 *         parentFrame,
 *         props.getFontName(),
 *         props.getFontSize(),
 *         props.getTheme(),
 *         (fontName, fontSize) -> {
 *             props.setFontName(fontName);
 *             props.setFontSize(fontSize);
 *             editor.setFont(new Font(fontName, Font.PLAIN, fontSize));
 *         }
 * );
 * dialog.setVisible(true);
 * }</pre>
 */
public class FontPickerDialog extends JDialog {
    /** Default font family applied when resetting the dialog ({@value}). */
    public static final String DEFAULT_FONT_NAME = "Monospaced";
    /** Default font size in points applied when resetting the dialog ({@value}). */
    public static final int DEFAULT_FONT_SIZE = 14;
    /** Sample test string rendered in each font next to its name in the font list ({@value}). */
    public static final String LIST_SAMPLE_TEXT = "AaBbCc 123";
    private static final int LIST_SAMPLE_FONT_SIZE = 14;
    private static final int LIST_CELL_HEIGHT = 26;

    private static volatile String[] cachedSystemFontNames = null;
    private static final ConcurrentHashMap<String, Font> sampleFontCache = new ConcurrentHashMap<>();

    private volatile String[] allFontNames;
    private volatile boolean fontsLoaded = false;
    private final CountDownLatch backgroundFetchLatch = new CountDownLatch(1);
    private Thread fontLoaderThread;
    private final TitledBorder selectionBorder;
    private final DefaultListModel<String> fontListModel = new DefaultListModel<>();
    private final JList<String> fontList;
    private final JTextField filterField;
    private final JSpinner sizeSpinner;
    private final JLabel selectedFontLabel;
    private final JTextField previewField;
    private final JButton resetButton;
    private final JButton okButton;
    private final JButton cancelButton;
    private String currentSelectedFont;

    /**
     * Creates a new {@code FontPickerDialog}.
     *
     * @param owner           the parent window used for modality and centering (may be {@code null})
     * @param initialFontName the initially selected font family name (falls back to {@link #DEFAULT_FONT_NAME} if blank)
     * @param initialFontSize the initial font size in points (clamped to {@code 6..80})
     * @param theme           the active UI theme name ({@code "Light"} or {@code "Dark"})
     * @param onApply         callback invoked with {@code (fontName, fontSize)} when the user clicks {@code OK}
     */
    public FontPickerDialog(Window owner, String initialFontName, int initialFontSize, String theme,
                            BiConsumer<String, Integer> onApply) {
        super(owner, "Choose Font", ModalityType.APPLICATION_MODAL);
        setResizable(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        this.currentSelectedFont = (initialFontName != null && !initialFontName.isEmpty())
                ? initialFontName
                : DEFAULT_FONT_NAME;
        // Seed with the initial font immediately so the dialog opens without blocking the EDT
        this.allFontNames = new String[] { this.currentSelectedFont };

        Font dialogFont = new Font(Font.DIALOG, Font.PLAIN, 14);
        JPanel contentPanel = new JPanel(new BorderLayout(12, 12));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // --- Filter + Font Family List ---
        filterField = new JTextField();
        filterField.setFont(dialogFont);
        filterField.setToolTipText("Filter font names");

        JLabel filterLabel = new JLabel("Filter:");
        filterLabel.setFont(dialogFont);
        JPanel filterPanel = new JPanel(new BorderLayout(8, 0));
        filterPanel.setOpaque(false);
        filterPanel.add(filterLabel, BorderLayout.WEST);
        filterPanel.add(filterField, BorderLayout.CENTER);

        fontList = new JList<>(fontListModel);
        fontList.setFont(dialogFont);
        fontList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        boolean isDark = "Dark".equals(theme);
        Color selBg = new Color(74, 136, 199); // Blue highlight
        Color selFg = Color.WHITE;
        Color normalBg = isDark ? new Color(40, 40, 40) : Color.WHITE;
        Color normalFg = isDark ? new Color(200, 200, 200) : Color.BLACK;
        Color sampleFg = isDark ? new Color(175, 175, 175) : new Color(70, 70, 70);
        fontList.setCellRenderer(new DefaultListCellRenderer() {
            private String renderedFontName = "";
            private Font sampleFont = dialogFont;
            private boolean selectedCell = false;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                String fontName = value != null ? value.toString() : "";
                renderedFontName = fontName;
                sampleFont = getOrCreateSampleFont(fontName);
                selectedCell = isSelected;

                JLabel c = (JLabel) super.getListCellRendererComponent(
                        list, fontName + "   " + LIST_SAMPLE_TEXT, index, isSelected, cellHasFocus);
                c.setFont(sampleFont);
                c.setOpaque(true);
                c.setBackground(isSelected ? selBg : normalBg);
                c.setForeground(isSelected ? selFg : normalFg);
                return c;
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(getBackground());
                    g2.fillRect(0, 0, getWidth(), getHeight());

                    Insets insets = getInsets();
                    int leftX = (insets != null ? insets.left : 0) + 6;
                    FontMetrics nameFm = g2.getFontMetrics(dialogFont);
                    int nameBaselineY = (getHeight() - nameFm.getHeight()) / 2 + nameFm.getAscent();

                    g2.setColor(getForeground());
                    g2.setFont(dialogFont);
                    String nameText = renderedFontName != null ? renderedFontName : "";
                    g2.drawString(nameText, leftX, nameBaselineY);

                    int nameWidth = nameFm.stringWidth(nameText);
                    int sampleX = Math.max(leftX + 175, leftX + nameWidth + 16);
                    Font previewFont = (sampleFont != null) ? sampleFont : dialogFont;
                    FontMetrics sampleFm = g2.getFontMetrics(previewFont);
                    int sampleBaselineY = (getHeight() - sampleFm.getHeight()) / 2 + sampleFm.getAscent();

                    g2.setColor(selectedCell ? selFg : sampleFg);
                    g2.setFont(previewFont);
                    g2.drawString(LIST_SAMPLE_TEXT, sampleX, sampleBaselineY);
                } finally {
                    g2.dispose();
                }
            }
        });
        // Fixed cell width & height prevent BasicListUI from measuring all 300+ fonts on the EDT
        fontList.setPrototypeCellValue("MMMMMMMMMMMMMMMMMMMM   " + LIST_SAMPLE_TEXT);
        fontList.setFixedCellHeight(LIST_CELL_HEIGHT);

        JScrollPane fontScroll = new JScrollPane(fontList);
        fontScroll.setPreferredSize(new Dimension(380, 220));

        JPanel listContainer = new JPanel(new BorderLayout(0, 8));
        listContainer.setOpaque(false);
        listContainer.add(filterPanel, BorderLayout.NORTH);
        listContainer.add(fontScroll, BorderLayout.CENTER);

        // --- Font Size Spinner ---
        int clampedSize = Math.max(6, Math.min(80, initialFontSize));
        sizeSpinner = new JSpinner(new SpinnerNumberModel(clampedSize, 6, 80, 1));
        sizeSpinner.setFont(dialogFont);
        sizeSpinner.setPreferredSize(new Dimension(80, 30));

        JLabel sizeLabel = new JLabel("Size:");
        sizeLabel.setFont(dialogFont);
        JPanel sizePanel = new JPanel(new BorderLayout(6, 5));
        sizePanel.setOpaque(false);
        sizePanel.add(sizeLabel, BorderLayout.WEST);
        sizePanel.add(sizeSpinner, BorderLayout.CENTER);

        JPanel rightWrapper = new JPanel(new BorderLayout());
        rightWrapper.setOpaque(false);
        rightWrapper.add(sizePanel, BorderLayout.NORTH);

        JPanel selectionPanel = new JPanel(new BorderLayout(12, 12));
        selectionBorder = BorderFactory.createTitledBorder("Font Selection (Loading...):");
        selectionBorder.setTitleFont(dialogFont);
        selectionPanel.setBorder(BorderFactory.createCompoundBorder(
                selectionBorder,
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
        selectionPanel.setOpaque(false);
        selectionPanel.add(listContainer, BorderLayout.CENTER);
        selectionPanel.add(rightWrapper, BorderLayout.EAST);

        // --- Editable Preview Section ---
        JPanel previewPanel = new JPanel(new BorderLayout(4, 4));
        previewPanel.setOpaque(false);

        JLabel previewLabel = new JLabel("Preview:");
        previewLabel.setFont(dialogFont);

        selectedFontLabel = new JLabel(currentSelectedFont);
        selectedFontLabel.setFont(dialogFont.deriveFont(Font.BOLD));

        JPanel previewHeaderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        previewHeaderPanel.setOpaque(false);
        previewHeaderPanel.add(previewLabel);
        previewHeaderPanel.add(Box.createHorizontalStrut(8));
        previewHeaderPanel.add(selectedFontLabel);

        previewField = new JTextField("The quick brown fox jumps over the lazy dog");
        previewField.setHorizontalAlignment(JTextField.CENTER);
        previewField.setFont(new Font(currentSelectedFont, Font.PLAIN, clampedSize));
        previewField.setPreferredSize(new Dimension(320, 72));

        previewPanel.add(previewHeaderPanel, BorderLayout.NORTH);
        previewPanel.add(previewField, BorderLayout.CENTER);

        // --- Bottom Buttons (Reset, OK, Cancel) ---
        resetButton = new JButton("Reset");
        resetButton.setFont(dialogFont);
        resetButton.setToolTipText("Reset font to Monospaced size 14");

        okButton = new JButton("OK");
        okButton.setFont(dialogFont);

        cancelButton = new JButton("Cancel");
        cancelButton.setFont(dialogFont);

        JPanel leftBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftBtnPanel.setOpaque(false);
        leftBtnPanel.add(resetButton);

        JPanel rightBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightBtnPanel.setOpaque(false);
        rightBtnPanel.add(okButton);
        rightBtnPanel.add(cancelButton);

        JPanel buttonPanel = new JPanel(new BorderLayout());
        buttonPanel.setOpaque(false);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        buttonPanel.add(leftBtnPanel, BorderLayout.WEST);
        buttonPanel.add(rightBtnPanel, BorderLayout.EAST);

        JPanel bottomPanel = new JPanel(new BorderLayout(0, 10));
        bottomPanel.setOpaque(false);
        bottomPanel.add(previewPanel, BorderLayout.CENTER);
        bottomPanel.add(buttonPanel, BorderLayout.SOUTH);

        contentPanel.add(selectionPanel, BorderLayout.CENTER);
        contentPanel.add(bottomPanel, BorderLayout.SOUTH);
        setContentPane(contentPanel);

        // --- Populate Initial Selection and Wire Listeners ---
        applyFontFilter("");

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFontFilter(filterField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFontFilter(filterField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFontFilter(filterField.getText());
            }
        });

        fontList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String sel = fontList.getSelectedValue();
                if (sel != null) {
                    currentSelectedFont = sel;
                    updatePreviewFont();
                }
            }
        });

        sizeSpinner.addChangeListener(e -> updatePreviewFont());

        resetButton.addActionListener(e -> resetToDefault());

        okButton.addActionListener(e -> {
            String chosenFont = fontList.getSelectedValue() != null
                    ? fontList.getSelectedValue()
                    : currentSelectedFont;
            if (onApply != null && chosenFont != null) {
                onApply.accept(chosenFont, (Integer) sizeSpinner.getValue());
            }
            dispose();
        });

        cancelButton.addActionListener(e -> dispose());

        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        DialogUtil.themeDialog(this);
        // Re-apply list colors after DialogUtil.themeDialog so sweepComponents does not override them
        fontList.setBackground(normalBg);
        fontList.setForeground(normalFg);
        fontList.setSelectionBackground(selBg);
        fontList.setSelectionForeground(selFg);

        setMinimumSize(new Dimension(620, 380));
        pack();
        setLocationRelativeTo(owner);

        // Start loading all system font families and warming sample fonts in the background
        startBackgroundFontLoad();

        // Ensure the initially selected font is scrolled into view once the dialog is laid out and opened
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                int idx = fontList.getSelectedIndex();
                if (idx >= 0) {
                    fontList.ensureIndexIsVisible(idx);
                }
            }
        });
    }

    /** Starts a daemon thread to load system font families and pre-warm sample {@link Font} instances off the EDT. */
    private void startBackgroundFontLoad() {
        final String targetInitialFont = this.currentSelectedFont;
        fontLoaderThread = new Thread(() -> {
            String[] names = cachedSystemFontNames;
            if (names == null) {
                names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
                cachedSystemFontNames = names;
            }

            // Warm up the initial font, default font, top viewport rows, and rows around the initial selection
            warmSampleFont(targetInitialFont);
            warmSampleFont(DEFAULT_FONT_NAME);
            int topWarmCount = Math.min(names.length, 15);
            for (int i = 0; i < topWarmCount && !Thread.currentThread().isInterrupted(); i++) {
                warmSampleFont(names[i]);
            }
            int matchIdx = -1;
            for (int i = 0; i < names.length; i++) {
                if (names[i].equalsIgnoreCase(targetInitialFont)) {
                    matchIdx = i;
                    break;
                }
            }
            if (matchIdx >= 0) {
                int start = Math.max(0, matchIdx - 10);
                int end = Math.min(names.length, matchIdx + 15);
                for (int i = start; i < end && !Thread.currentThread().isInterrupted(); i++) {
                    warmSampleFont(names[i]);
                }
            }

            this.allFontNames = names;
            this.backgroundFetchLatch.countDown();
            SwingUtilities.invokeLater(this::applyLoadedFontsOnEdt);

            // Continue warming remaining sample fonts in the background so scrolling stays smooth
            for (String fontName : names) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                warmSampleFont(fontName);
            }
        }, "Bearit-FontLoader");
        fontLoaderThread.setDaemon(true);
        fontLoaderThread.start();
    }

    private static void warmSampleFont(String fontName) {
        if (fontName != null && !fontName.isEmpty()) {
            Font f = getOrCreateSampleFont(fontName);
            f.canDisplay('A');
        }
    }

    static Font getOrCreateSampleFont(String fontName) {
        if (fontName == null || fontName.isEmpty()) {
            return new Font(Font.DIALOG, Font.PLAIN, LIST_SAMPLE_FONT_SIZE);
        }
        return sampleFontCache.computeIfAbsent(fontName,
                name -> new Font(name, Font.PLAIN, LIST_SAMPLE_FONT_SIZE));
    }

    private void applyLoadedFontsOnEdt() {
        if (fontsLoaded) {
            return;
        }
        fontsLoaded = true;
        selectionBorder.setTitle("Font Selection:");
        applyFontFilter(filterField.getText());
        int idx = fontList.getSelectedIndex();
        if (idx >= 0) {
            fontList.ensureIndexIsVisible(idx);
        }
        repaint();
    }

    /**
     * Waits up to {@code timeoutMs} milliseconds for the background font loader to finish and applies
     * the loaded fonts to the list model on the EDT.
     */
    void waitForFontsLoaded(long timeoutMs) {
        try {
            backgroundFetchLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (SwingUtilities.isEventDispatchThread()) {
            applyLoadedFontsOnEdt();
        } else {
            try {
                SwingUtilities.invokeAndWait(this::applyLoadedFontsOnEdt);
            } catch (Exception ignored) {
            }
        }
    }

    boolean isFontsLoaded() {
        return fontsLoaded;
    }

    static void clearFontCacheForTesting() {
        cachedSystemFontNames = null;
        sampleFontCache.clear();
    }

    @Override
    public void dispose() {
        if (fontLoaderThread != null && fontLoaderThread.isAlive()) {
            fontLoaderThread.interrupt();
        }
        super.dispose();
    }

    /** Filters the displayed font list case-insensitively while preserving the active selection if still visible. */
    void applyFontFilter(String filterText) {
        if (!fontsLoaded && backgroundFetchLatch.getCount() == 0) {
            fontsLoaded = true;
            selectionBorder.setTitle("Font Selection:");
        }
        String query = (filterText == null) ? "" : filterText.trim().toLowerCase(Locale.ROOT);
        String previousSelection = fontList.getSelectedValue() != null
                ? fontList.getSelectedValue()
                : currentSelectedFont;

        String[] currentFonts = this.allFontNames;
        List<String> matches = new ArrayList<>(currentFonts.length);
        String exactMatch = null;
        for (String fontName : currentFonts) {
            if (query.isEmpty() || fontName.toLowerCase(Locale.ROOT).contains(query)) {
                matches.add(fontName);
                if (previousSelection != null && fontName.equalsIgnoreCase(previousSelection)) {
                    exactMatch = fontName;
                }
            }
        }

        fontListModel.clear();
        if (!matches.isEmpty()) {
            fontListModel.addAll(matches);
        }

        if (exactMatch != null) {
            fontList.setSelectedValue(exactMatch, true);
        } else if (!fontListModel.isEmpty()) {
            fontList.setSelectedIndex(0);
            currentSelectedFont = fontList.getSelectedValue();
        }
        updatePreviewFont();
    }

    /** Clears any active filter and resets the font selection and size to {@link #DEFAULT_FONT_NAME} at {@link #DEFAULT_FONT_SIZE}. */
    void resetToDefault() {
        currentSelectedFont = DEFAULT_FONT_NAME;
        if (!filterField.getText().isEmpty()) {
            filterField.setText("");
        } else {
            applyFontFilter("");
        }
        sizeSpinner.setValue(DEFAULT_FONT_SIZE);
        selectFontCaseInsensitive(DEFAULT_FONT_NAME);
        updatePreviewFont();
    }

    private void selectFontCaseInsensitive(String targetFont) {
        for (int i = 0; i < fontListModel.size(); i++) {
            String name = fontListModel.getElementAt(i);
            if (name.equalsIgnoreCase(targetFont)) {
                fontList.setSelectedIndex(i);
                fontList.ensureIndexIsVisible(i);
                currentSelectedFont = name;
                return;
            }
        }
    }

    private void updatePreviewFont() {
        String fontName = (fontList.getSelectedValue() != null)
                ? fontList.getSelectedValue()
                : currentSelectedFont;
        if (fontName != null) {
            selectedFontLabel.setText(fontName);
            previewField.setFont(new Font(fontName, Font.PLAIN, (Integer) sizeSpinner.getValue()));
        }
    }

    JTextField getFilterField() { return filterField; }
    JList<String> getFontList() { return fontList; }
    JSpinner getSizeSpinner() { return sizeSpinner; }
    JLabel getSelectedFontLabel() { return selectedFontLabel; }
    JTextField getPreviewField() { return previewField; }
    JButton getResetButton() { return resetButton; }
    JButton getOkButton() { return okButton; }
}

