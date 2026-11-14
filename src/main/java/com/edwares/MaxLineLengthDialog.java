package com.edwares;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.function.IntConsumer;

/**
 * A small modal dialog for changing the editor's maximum line length (the column at which
 * long lines are soft-wrapped with a {@code \u200B\n} marker).
 *
 * <p>Features:
 * <ul>
 *   <li>Numeric input field restricted (via a {@link DocumentFilter}) to whole numbers within
 *       {@link BearitProperties#MIN_MAX_LINE_LENGTH} ({@code 1}) to
 *       {@link BearitProperties#MAX_MAX_LINE_LENGTH} ({@code 15001}). Any keystroke or paste that
 *       would produce a value outside that range, or non-digit text, is rejected.</li>
 *   <li>Up/Down arrow keys increment/decrement the value (clamped to the allowed range).</li>
 *   <li><b>Reset</b> button restores {@link BearitProperties#DEFAULT_MAX_LINE_LENGTH} ({@code 15000}).</li>
 *   <li><b>OK</b> (or Enter) applies the value through the supplied callback; <b>Cancel</b> (or Escape) closes
 *       without changes.</li>
 *   <li>Light and Dark theme support via {@link DialogUtil#themeDialog(JDialog)}.</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * BearitProperties props = BearitProperties.getInstance();
 * MaxLineLengthDialog dialog = new MaxLineLengthDialog(
 *         parentFrame,
 *         props.getMaxLineLength(),
 *         newLength -> {
 *             props.setMaxLineLength(newLength);
 *             props.save();
 *             editor.setEditorMaxLineLength(newLength);
 *         }
 * );
 * dialog.setVisible(true);
 * }</pre>
 */
public class MaxLineLengthDialog extends JDialog {

    private final JTextField lengthField;
    private final JButton resetButton;
    private final JButton okButton;
    private final JButton cancelButton;
    private final IntConsumer onApply;

    /**
     * @param owner         parent window used for modality and positioning (may be {@code null})
     * @param initialLength the current max line length shown when the dialog opens (clamped to range)
     * @param onApply       callback invoked with the validated value when the user presses OK
     */
    public MaxLineLengthDialog(Window owner, int initialLength, IntConsumer onApply) {
        super(owner, "Set Max Line Length", ModalityType.APPLICATION_MODAL);
        this.onApply = onApply;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel contentPanel = new JPanel(new BorderLayout(10, 10));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JLabel promptLabel = new JLabel("Max line length:");
        lengthField = new JTextField(String.valueOf(BearitProperties.clampMaxLineLength(initialLength)), 8);
        lengthField.setHorizontalAlignment(JTextField.RIGHT);
        lengthField.setToolTipText("Enter a value between " + BearitProperties.MIN_MAX_LINE_LENGTH
                + " and " + BearitProperties.MAX_MAX_LINE_LENGTH);
        ((AbstractDocument) lengthField.getDocument()).setDocumentFilter(new RangeFilter());

        JLabel rangeLabel = new JLabel("(" + BearitProperties.MIN_MAX_LINE_LENGTH + " - "
                + BearitProperties.MAX_MAX_LINE_LENGTH + ")");

        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        inputPanel.setOpaque(false);
        inputPanel.add(promptLabel);
        inputPanel.add(lengthField);
        inputPanel.add(rangeLabel);

        resetButton = new JButton("Reset");
        resetButton.setToolTipText("Reset max line length to " + BearitProperties.DEFAULT_MAX_LINE_LENGTH);
        okButton = new JButton("OK");
        cancelButton = new JButton("Cancel");

        JPanel leftBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftBtnPanel.setOpaque(false);
        leftBtnPanel.add(resetButton);

        JPanel rightBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightBtnPanel.setOpaque(false);
        rightBtnPanel.add(okButton);
        rightBtnPanel.add(cancelButton);

        JPanel buttonPanel = new JPanel(new BorderLayout(16, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.add(leftBtnPanel, BorderLayout.WEST);
        buttonPanel.add(rightBtnPanel, BorderLayout.EAST);

        contentPanel.add(inputPanel, BorderLayout.CENTER);
        contentPanel.add(buttonPanel, BorderLayout.SOUTH);
        setContentPane(contentPanel);

        // OK is only enabled while the field holds a valid value (the field may be temporarily empty
        // while the user retypes a number). A plain JTextField is used on purpose: it never rewrites
        // its own text on focus loss, so a single click on OK always registers.
        lengthField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { syncOkState(); }
            @Override public void removeUpdate(DocumentEvent e) { syncOkState(); }
            @Override public void changedUpdate(DocumentEvent e) { syncOkState(); }
        });

        // Up/Down arrow keys step the value
        lengthField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "incLen");
        lengthField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "decLen");
        lengthField.getActionMap().put("incLen", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { stepValue(1); }
        });
        lengthField.getActionMap().put("decLen", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { stepValue(-1); }
        });

        resetButton.addActionListener(e -> resetToDefault());
        okButton.addActionListener(e -> applyAndClose());
        cancelButton.addActionListener(e -> dispose());

        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        syncOkState();
        DialogUtil.themeDialog(this);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);

        SwingUtilities.invokeLater(() -> {
            lengthField.requestFocusInWindow();
            lengthField.selectAll();
        });
    }

    /** Returns true when {@code text} is a whole number within {@code 1..15001}. */
    static boolean isValidLength(String text) {
        if (text == null || text.isEmpty() || text.length() > 6) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        try {
            int val = Integer.parseInt(text);
            return val >= BearitProperties.MIN_MAX_LINE_LENGTH && val <= BearitProperties.MAX_MAX_LINE_LENGTH;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** Rejects any edit that would leave the field non-empty and not a valid in-range number. */
    private static final class RangeFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            if (string != null && allowed(fb, offset, 0, string)) {
                super.insertString(fb, offset, string, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            if (allowed(fb, offset, length, text)) {
                super.replace(fb, offset, length, text, attrs);
            }
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            if (allowed(fb, offset, length, "")) {
                super.remove(fb, offset, length);
            }
        }

        private boolean allowed(FilterBypass fb, int offset, int length, String text) throws BadLocationException {
            String current = fb.getDocument().getText(0, fb.getDocument().getLength());
            String candidate = current.substring(0, offset) + (text == null ? "" : text)
                    + current.substring(offset + length);
            // Allow clearing the field so a new number can be typed; OK stays disabled meanwhile
            return candidate.isEmpty() || isValidLength(candidate);
        }
    }

    private void syncOkState() {
        okButton.setEnabled(isValidLength(lengthField.getText().trim()));
    }

    private void stepValue(int delta) {
        int base = isValidLength(lengthField.getText().trim())
                ? Integer.parseInt(lengthField.getText().trim())
                : BearitProperties.DEFAULT_MAX_LINE_LENGTH;
        lengthField.setText(String.valueOf(BearitProperties.clampMaxLineLength(base + delta)));
        lengthField.selectAll();
    }

    private void applyAndClose() {
        String txt = lengthField.getText().trim();
        if (!isValidLength(txt)) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        int val = Integer.parseInt(txt);
        dispose();
        if (onApply != null) {
            onApply.accept(val);
        }
    }

    /** Restores the default max line length ({@code 15000}) in the input field. */
    void resetToDefault() {
        lengthField.setText(String.valueOf(BearitProperties.DEFAULT_MAX_LINE_LENGTH));
        lengthField.requestFocusInWindow();
        lengthField.selectAll();
    }

    /** Returns the currently entered value, or -1 if the field does not hold a valid value. */
    int getEnteredValue() {
        String txt = lengthField.getText().trim();
        return isValidLength(txt) ? Integer.parseInt(txt) : -1;
    }

    JTextField getLengthField() { return lengthField; }
    JButton getResetButton() { return resetButton; }
    JButton getOkButton() { return okButton; }
    JButton getCancelButton() { return cancelButton; }
}

