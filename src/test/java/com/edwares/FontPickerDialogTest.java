package com.edwares;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class FontPickerDialogTest {

    @Test
    void testFontPickerFilterResizableAndReset() throws Exception {
        AtomicReference<String> appliedFont = new AtomicReference<>();
        AtomicInteger appliedSize = new AtomicInteger(-1);

        SwingUtilities.invokeAndWait(() -> {
            FontPickerDialog dialog = new FontPickerDialog(
                    null,
                    "SansSerif",
                    22,
                    "Light",
                    (fontName, fontSize) -> {
                        appliedFont.set(fontName);
                        appliedSize.set(fontSize);
                    }
            );

            try {
                assertTrue(dialog.isResizable(), "Choose Font dialog must be resizable");
                assertEquals(new java.awt.Color(74, 136, 199), dialog.getFontList().getSelectionBackground(),
                        "Selected font highlight color should be Color(74, 136, 199)");
                assertEquals(dialog.getFontList().getSelectedValue(), dialog.getSelectedFontLabel().getText(),
                        "Selected font label next to Preview: must match selected font");

                // Verify the cell renderer actually paints the blue highlight background (not overridden by L&F)
                javax.swing.JList<String> list = dialog.getFontList();
                int selIdx = list.getSelectedIndex();
                assertTrue(selIdx >= 0, "Initial font must be selected in the list");
                java.awt.Component cellComp = list.getCellRenderer().getListCellRendererComponent(
                        list, list.getSelectedValue(), selIdx, true, true);
                cellComp.setSize(200, 24);
                java.awt.image.BufferedImage cellImg = new java.awt.image.BufferedImage(
                        200, 24, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D gCell = cellImg.createGraphics();
                cellComp.paint(gCell);
                gCell.dispose();
                assertEquals(new java.awt.Color(74, 136, 199).getRGB(), cellImg.getRGB(2, 2),
                        "Selected cell must paint blue highlight background Color(74, 136, 199)");

                int totalFonts = dialog.getFontList().getModel().getSize();
                assertTrue(totalFonts > 1, "Font list should contain available system fonts");

                // Filter for "mono"
                dialog.getFilterField().setText("mono");
                int filteredCount = dialog.getFontList().getModel().getSize();
                assertTrue(filteredCount > 0 && filteredCount <= totalFonts,
                        "Filtered list should contain matching fonts");
                for (int i = 0; i < filteredCount; i++) {
                    String item = dialog.getFontList().getModel().getElementAt(i);
                    assertTrue(item.toLowerCase().contains("mono"),
                            "Every filtered font entry must match 'mono': " + item);
                }

                // Filter to an unmatched string and then clear
                dialog.getFilterField().setText("zzz_nonexistent_font_name_zzz");
                assertEquals(0, dialog.getFontList().getModel().getSize(),
                        "Non-matching filter should show 0 fonts");

                dialog.getFilterField().setText("");
                assertEquals(totalFonts, dialog.getFontList().getModel().getSize(),
                        "Clearing filter should restore all fonts");

                // Apply a non-default filter and size, then click Reset
                dialog.getFilterField().setText("sans");
                dialog.getSizeSpinner().setValue(24);
                dialog.getResetButton().doClick();

                assertEquals("", dialog.getFilterField().getText(),
                        "Reset should clear the filter field");
                assertEquals(FontPickerDialog.DEFAULT_FONT_SIZE,
                        dialog.getSizeSpinner().getValue(),
                        "Reset should set font size to 14");
                assertNotNull(dialog.getFontList().getSelectedValue(),
                        "Reset should select Monospaced in the font list");
                assertTrue(FontPickerDialog.DEFAULT_FONT_NAME
                                .equalsIgnoreCase(dialog.getFontList().getSelectedValue()),
                        "Reset should select Monospaced");
                assertEquals(dialog.getFontList().getSelectedValue(), dialog.getSelectedFontLabel().getText(),
                        "Selected font label next to Preview: must update to Monospaced on Reset");
                assertEquals(FontPickerDialog.DEFAULT_FONT_SIZE,
                        dialog.getPreviewField().getFont().getSize(),
                        "Preview font size should reset to 14");

                // Click OK and verify callback receives Monospaced, 14
                dialog.getOkButton().doClick();
                assertTrue(FontPickerDialog.DEFAULT_FONT_NAME.equalsIgnoreCase(appliedFont.get()),
                        "OK should apply Monospaced font");
                assertEquals(14, appliedSize.get(), "OK should apply size 14");
            } finally {
                dialog.dispose();
            }
        });
    }
}

