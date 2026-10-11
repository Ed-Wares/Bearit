package com.edwares;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.table.TableModel;
import java.awt.*;
import java.io.File;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

public class ReadOnlyModeTest {

    @Test
    public void testHexEditorPanelReadOnly() throws Exception {
        HexEditorPanel hexEditor = new HexEditorPanel();
        byte[] testBytes = new byte[]{(byte) 0x12, (byte) 0x34, (byte) 0x56};
        hexEditor.loadData(testBytes, 0);

        Field tableField = HexEditorPanel.class.getDeclaredField("hexTable");
        tableField.setAccessible(true);
        JTable hexTable = (JTable) tableField.get(hexEditor);
        TableModel model = hexTable.getModel();

        // Default state is not read-only
        assertFalse(hexEditor.isReadOnlyMode());
        assertFalse(model.isCellEditable(0, 0), "Offset column should not be editable");
        assertTrue(model.isCellEditable(0, 1), "Hex byte column should be editable");

        // Enable read-only mode
        hexEditor.setReadOnlyMode(true);
        assertTrue(hexEditor.isReadOnlyMode());
        assertFalse(model.isCellEditable(0, 0), "Offset column should not be editable in read-only mode");
        assertFalse(model.isCellEditable(0, 1), "Hex byte column should not be editable in read-only mode");
        assertFalse(model.isCellEditable(0, 17), "ASCII column should not be editable in read-only mode");

        // Attempt to edit a cell
        model.setValueAt("FF", 0, 1);
        Field dataBytesField = HexEditorPanel.class.getDeclaredField("dataBytes");
        dataBytesField.setAccessible(true);
        byte[] currentBytes = (byte[]) dataBytesField.get(hexEditor);
        assertEquals((byte) 0x12, currentBytes[0], "Data must not change when read-only mode is enabled");

        // Disable read-only mode
        hexEditor.setReadOnlyMode(false);
        assertFalse(hexEditor.isReadOnlyMode());
        assertTrue(model.isCellEditable(0, 1), "Hex byte column should be editable again");

        // Now editing should succeed
        model.setValueAt("FF", 0, 1);
        currentBytes = (byte[]) dataBytesField.get(hexEditor);
        assertEquals((byte) 0xFF, currentBytes[0], "Data should change when read-only mode is disabled");
    }

    @Test
    public void testBearitTextHexWrapperReadOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AdvancedTextEditorPanel textEditor = new AdvancedTextEditorPanel();
            textEditor.createNewDocument();
            BearitTextHexWrapper wrapper = new BearitTextHexWrapper(textEditor);

            assertFalse(wrapper.isReadOnlyMode());
            assertFalse(wrapper.getHexEditor().isReadOnlyMode());
            assertFalse(wrapper.getHiddenTextEditor().isReadOnlyMode());

            wrapper.setReadOnlyMode(true);
            assertTrue(wrapper.isReadOnlyMode());
            assertTrue(wrapper.getHexEditor().isReadOnlyMode());
            assertTrue(wrapper.getHiddenTextEditor().isReadOnlyMode());

            wrapper.setReadOnlyMode(false);
            assertFalse(wrapper.isReadOnlyMode());
            assertFalse(wrapper.getHexEditor().isReadOnlyMode());
            assertFalse(wrapper.getHiddenTextEditor().isReadOnlyMode());
        });
    }

    @Test
    public void testAdvancedTextEditorPanelReadOnlyStatus() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
            editor.createNewDocument();

            assertFalse(editor.isReadOnlyMode());
            assertEquals("", editor.getReadOnlyStatus());

            editor.setReadOnlyMode(true);
            assertTrue(editor.isReadOnlyMode());
            assertTrue(editor.getReadOnlyStatus().contains("***READ-ONLY***"));

            editor.setReadOnlyMode(false);
            assertFalse(editor.isReadOnlyMode());
            assertEquals("", editor.getReadOnlyStatus());
        });
    }

    @Test
    public void testBearitPropertiesReadOnlyMode() {
        BearitProperties props = BearitProperties.getInstance();
        boolean original = props.isReadOnlyMode();
        try {
            props.setReadOnlyMode(true);
            assertTrue(props.isReadOnlyMode());

            props.setReadOnlyMode(false);
            assertFalse(props.isReadOnlyMode());
        } finally {
            props.setReadOnlyMode(original);
        }
    }

    @Test
    public void testBearitFrameTitleReadOnlyMode() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BearitProperties props = BearitProperties.getInstance();
            boolean original = props.isReadOnlyMode();
            BearitFrame frame = null;
            try {
                props.setReadOnlyMode(false);
                frame = new BearitFrame();
                String title = frame.getTitle();
                assertFalse(title.contains("[Read-Only]"), "Title should not contain [Read-Only] when disabled. Got: " + title);

                // Enable read-only mode via reflection on setGlobalReadOnlyMode
                java.lang.reflect.Method setReadOnlyMethod = BearitFrame.class.getDeclaredMethod("setGlobalReadOnlyMode", boolean.class);
                setReadOnlyMethod.setAccessible(true);
                setReadOnlyMethod.invoke(frame, true);

                String roTitle = frame.getTitle();
                assertTrue(roTitle.contains("[Read-Only]"), "Title must contain [Read-Only] when enabled. Got: " + roTitle);

                // Disable read-only mode
                setReadOnlyMethod.invoke(frame, false);
                String revertedTitle = frame.getTitle();
                assertFalse(revertedTitle.contains("[Read-Only]"), "Title should not contain [Read-Only] after disabling. Got: " + revertedTitle);
            } catch (Exception e) {
                fail(e);
            } finally {
                props.setReadOnlyMode(original);
                if (frame != null) {
                    frame.dispose();
                }
            }
        });
    }
}
