package com.edwares;

import org.junit.jupiter.api.Test;

import javax.swing.*;

import static org.junit.jupiter.api.Assertions.*;

public class OneBasedColumnSearchTest {

    @Test
    public void testOneBasedColumnLabelVisibility() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
            editor.createNewDocument();

            // Open the search dialog
            editor.showSearchDialog();
            JDialog dialog = editor.getSearchDialog();
            assertNotNull(dialog, "Search dialog should exist");

            JLabel lblOneBased = editor.getLblOneBasedColumn();
            JCheckBox chkCol = editor.getChkSearchInColumn();
            assertNotNull(lblOneBased, "lblOneBasedColumn should exist");
            assertNotNull(chkCol, "chkSearchInColumn should exist");

            assertEquals("(1-based)", lblOneBased.getText());

            // Case 1: 0-based mode, chkSearchInColumn NOT selected -> label hidden
            editor.setOneBasedColumnIndex(false);
            chkCol.setSelected(false);
            chkCol.getActionListeners()[0].actionPerformed(null);
            assertFalse(lblOneBased.isVisible(), "Label should NOT be visible when column search is disabled (0-based)");

            // Case 2: 1-based mode, chkSearchInColumn NOT selected -> label MUST STILL BE HIDDEN
            // (Requirement: This new label should only be visible when the from and to columns are visible too.)
            editor.setOneBasedColumnIndex(true);
            assertFalse(lblOneBased.isVisible(), "Label should NOT be visible when column search is disabled even if 1-based is enabled");

            // Case 3: 1-based mode, chkSearchInColumn IS selected -> label MUST BE VISIBLE
            chkCol.setSelected(true);
            chkCol.getActionListeners()[0].actionPerformed(null);
            assertTrue(lblOneBased.isVisible(), "Label should be visible when both 1-based is enabled and column search is enabled");

            // Case 4: 0-based mode, chkSearchInColumn IS selected -> label MUST BE HIDDEN
            editor.setOneBasedColumnIndex(false);
            assertFalse(lblOneBased.isVisible(), "Label should NOT be visible when 0-based is enabled even if column search is enabled");

            // Case 5: Re-enabling 1-based while chkSearchInColumn is selected -> label visible again
            editor.setOneBasedColumnIndex(true);
            assertTrue(lblOneBased.isVisible(), "Label should be visible when 1-based is re-enabled with column search selected");

            // Case 6: Unchecking chkSearchInColumn while 1-based is enabled -> label hidden again
            chkCol.setSelected(false);
            chkCol.getActionListeners()[0].actionPerformed(null);
            assertFalse(lblOneBased.isVisible(), "Label should be hidden when column search is unchecked");

            dialog.dispose();
        });
    }

    @Test
    public void testGetSearchColumnRangeWithOneBasedAndZeroBased() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
            editor.createNewDocument();
            editor.showSearchDialog();

            JCheckBox chkCol = editor.getChkSearchInColumn();
            JTextField txtFrom = editor.getTxtFromColumn();
            JTextField txtTo = editor.getTxtToColumn();

            // When unchecked, range is disabled (-1, -1)
            chkCol.setSelected(false);
            int[] rangeUnchecked = editor.getSearchColumnRange();
            assertEquals(-1, rangeUnchecked[0]);
            assertEquals(-1, rangeUnchecked[1]);

            // When checked with 0-based indexing:
            chkCol.setSelected(true);
            editor.setOneBasedColumnIndex(false);
            txtFrom.setText("0");
            txtTo.setText("10");
            int[] range0Based = editor.getSearchColumnRange();
            assertEquals(0, range0Based[0], "0-based from column 0 should map to minCol 0");
            assertEquals(10, range0Based[1], "0-based to column 10 should map to maxCol 10");

            // When checked with 1-based indexing:
            editor.setOneBasedColumnIndex(true);
            // Setting 1-based will adjust "0" -> "1" and "10" -> "11"
            assertEquals("1", txtFrom.getText());
            assertEquals("11", txtTo.getText());

            int[] range1Based = editor.getSearchColumnRange();
            assertEquals(0, range1Based[0], "1-based from column 1 should map to 0-based minCol 0");
            assertEquals(10, range1Based[1], "1-based to column 11 should map to 0-based maxCol 10");

            // Explicit 1-based values
            txtFrom.setText("5");
            txtTo.setText("20");
            int[] custom1Based = editor.getSearchColumnRange();
            assertEquals(4, custom1Based[0], "1-based from column 5 should map to 0-based minCol 4");
            assertEquals(19, custom1Based[1], "1-based to column 20 should map to 0-based maxCol 19");

            editor.getSearchDialog().dispose();
        });
    }

    @Test
    public void testSetOneBasedColumnIndexTogglesFieldValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
            editor.createNewDocument();
            editor.showSearchDialog();

            JTextField txtFrom = editor.getTxtFromColumn();
            JTextField txtTo = editor.getTxtToColumn();

            editor.setOneBasedColumnIndex(false);
            txtFrom.setText("10");
            txtTo.setText("25");

            // Toggle to 1-based: values should increment by 1
            editor.setOneBasedColumnIndex(true);
            assertEquals("11", txtFrom.getText());
            assertEquals("26", txtTo.getText());

            // Toggle back to 0-based: values should decrement by 1
            editor.setOneBasedColumnIndex(false);
            assertEquals("10", txtFrom.getText());
            assertEquals("25", txtTo.getText());

            editor.getSearchDialog().dispose();
        });
    }

    @Test
    public void testBearitTextHexWrapperPropagatesOneBasedColumn() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
            editor.createNewDocument();
            BearitTextHexWrapper wrapper = new BearitTextHexWrapper(editor);

            assertFalse(editor.isOneBasedColumnIndex());
            wrapper.setOneBasedColumnIndex(true);
            assertTrue(editor.isOneBasedColumnIndex(), "Wrapper should propagate 1-based setting to hidden editor");

            wrapper.setOneBasedColumnIndex(false);
            assertFalse(editor.isOneBasedColumnIndex(), "Wrapper should propagate 0-based setting to hidden editor");
        });
    }

    @Test
    public void testLargeFileManagerMatchingWith1BasedTranslation() {
        String line = "HELLO WORLD"; // 'WORLD' is index 6 to 11
        // In 1-based, 'WORLD' spans columns 7 to 12
        int matchStart = 6;
        int matchEnd = 11;

        // 1-based inputs: From 7 to 12 -> translated to minCol 6, maxCol 11
        int colOffset = 1;
        int minCol = 7 - colOffset;
        int maxCol = 12 - colOffset;
        assertTrue(LargeFileManager.isMatchInColumnRange(line, matchStart, matchEnd, minCol, maxCol));

        // 1-based inputs: From 8 to 12 -> translated to minCol 7, maxCol 11 (should not match because startCol 6 < 7)
        int minColTooLate = 8 - colOffset;
        int maxColOk = 12 - colOffset;
        assertFalse(LargeFileManager.isMatchInColumnRange(line, matchStart, matchEnd, minColTooLate, maxColOk));

        // 1-based inputs: From 7 to 10 -> translated to minCol 6, maxCol 9 (should not match because endCol 11 > 9)
        int minColOk = 7 - colOffset;
        int maxColTooEarly = 10 - colOffset;
        assertFalse(LargeFileManager.isMatchInColumnRange(line, matchStart, matchEnd, minColOk, maxColTooEarly));
    }
}
