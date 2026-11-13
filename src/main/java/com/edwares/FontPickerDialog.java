package com.edwares;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * A modal, resizable dialog for choosing an editor font family and size.
 *
 * <p>Features:
 * <ul>
 *   <li>Live case-insensitive font-name filtering via the filter text field above the font list.</li>
 *   <li>Scrollable list of system font families with high-contrast blue selection highlighting
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

    private final String[] allFontNames;
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

        this.allFontNames = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        this.currentSelectedFont = (initialFontName != null && !initialFontName.isEmpty())
                ? initialFontName
                : DEFAULT_FONT_NAME;

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
        fontList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                c.setOpaque(true);
                c.setBackground(isSelected ? selBg : normalBg);
                c.setForeground(isSelected ? selFg : normalFg);
                return c;
            }

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                setOpaque(false);
                super.paintComponent(g);
                setOpaque(true);
            }
        });

        JScrollPane fontScroll = new JScrollPane(fontList);
        fontScroll.setPreferredSize(new Dimension(280, 220));

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
        TitledBorder border = BorderFactory.createTitledBorder("Font Selection:");
        border.setTitleFont(dialogFont);
        selectionPanel.setBorder(BorderFactory.createCompoundBorder(
                border,
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

        // --- Populate and Wire Listeners ---
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

        setMinimumSize(new Dimension(420, 380));
        pack();
        setLocationRelativeTo(owner);

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

    /** Filters the displayed font list case-insensitively while preserving the active selection if still visible. */
    void applyFontFilter(String filterText) {
        String query = (filterText == null) ? "" : filterText.trim().toLowerCase(Locale.ROOT);
        String previousSelection = fontList.getSelectedValue() != null
                ? fontList.getSelectedValue()
                : currentSelectedFont;

        fontListModel.clear();
        String exactMatch = null;
        for (String fontName : allFontNames) {
            if (query.isEmpty() || fontName.toLowerCase(Locale.ROOT).contains(query)) {
                fontListModel.addElement(fontName);
                if (previousSelection != null && fontName.equalsIgnoreCase(previousSelection)) {
                    exactMatch = fontName;
                }
            }
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

