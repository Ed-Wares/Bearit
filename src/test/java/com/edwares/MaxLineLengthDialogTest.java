package com.edwares;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class MaxLineLengthDialogTest {

    @Test
    void testDialogRangeEnforcementResetAndSingleClickOk() throws Exception {
        AtomicInteger applied = new AtomicInteger(-1);
        AtomicInteger applyCount = new AtomicInteger(0);

        SwingUtilities.invokeAndWait(() -> {
            MaxLineLengthDialog dialog = new MaxLineLengthDialog(null, 8000, v -> {
                applied.set(v);
                applyCount.incrementAndGet();
            });
            try {
                assertEquals(8000, dialog.getEnteredValue());

                dialog.getLengthField().setText("15002");
                assertEquals("8000", dialog.getLengthField().getText(), "Values above 15001 must be rejected");
                dialog.getLengthField().setText("0");
                assertEquals("8000", dialog.getLengthField().getText(), "Values below 1 must be rejected");
                dialog.getLengthField().setText("-5");
                assertEquals("8000", dialog.getLengthField().getText(), "Negative values must be rejected");
                dialog.getLengthField().setText("12a");
                assertEquals("8000", dialog.getLengthField().getText(), "Non-numeric input must be rejected");

                dialog.getLengthField().setText("1");
                assertEquals(1, dialog.getEnteredValue(), "Minimum 1 must be accepted");
                dialog.getLengthField().setText("15001");
                assertEquals(15001, dialog.getEnteredValue(), "Maximum 15001 must be accepted");

                // Clearing is allowed while typing, but OK is disabled until a valid value is entered
                dialog.getLengthField().setText("");
                assertFalse(dialog.getOkButton().isEnabled(), "OK must be disabled for an empty field");
                dialog.getLengthField().setText("110");
                assertTrue(dialog.getOkButton().isEnabled(), "OK must be enabled for a valid value");

                dialog.getResetButton().doClick();
                assertEquals("15000", dialog.getLengthField().getText(), "Reset must restore 15000");

                dialog.getLengthField().setText("110");
                dialog.getOkButton().doClick();
                assertEquals(1, applyCount.get(), "A single OK click must apply the value");
                assertEquals(110, applied.get());
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    void testPropertiesClampingForMaxLineLength() {
        BearitProperties props = BearitProperties.getInstance();
        int original = props.getMaxLineLength();
        try {
            props.setMaxLineLength(250);
            assertEquals(250, props.getMaxLineLength());
            props.setMaxLineLength(15001);
            assertEquals(15001, props.getMaxLineLength(), "15001 is the allowed maximum");
            props.setMaxLineLength(0);
            assertEquals(1, props.getMaxLineLength(), "Values below 1 must clamp to 1");
            props.setMaxLineLength(99999);
            assertEquals(15001, props.getMaxLineLength(), "Values above 15001 must clamp to 15001");
        } finally {
            props.setMaxLineLength(original);
        }
    }

    @Test
    void testEditorRewrapsImmediatelyWhenMaxLineLengthChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AdvancedTextEditorPanel panel = new AdvancedTextEditorPanel();
            panel.forceSetText("12345678901234567890");
            boolean unsavedBefore = panel.hasUnsavedChanges();

            panel.setEditorMaxLineLength(10);
            assertEquals("1234567890\u200B\n1234567890", panel.getTextArea().getText(),
                    "Lowering the max line length must re-wrap the open text immediately");
            assertEquals(unsavedBefore, panel.hasUnsavedChanges(), "Re-wrapping must not mark the file as modified");

            panel.setEditorMaxLineLength(15000);
            assertEquals("12345678901234567890", panel.getTextArea().getText(),
                    "Raising the max line length must remove the old soft wraps");
        });
    }

    @Test
    void testChunkStartColumnForHardCutChunks(@TempDir Path tempDir) throws Exception {
        int originalChunkSize = LargeFileManager.CHUNK_SIZE;
        LargeFileManager.CHUNK_SIZE = 1000;
        try {
            // "abc\n" then a 600 KB line with no newline: later chunks are hard-cut mid-line
            File file = tempDir.resolve("long_line.txt").toFile();
            Files.writeString(file.toPath(), "abc\n" + "z".repeat(600_000), StandardCharsets.UTF_8);

            LargeFileManager fm = new LargeFileManager();
            fm.setFile(file);
            assertEquals(0, fm.getChunkStartColumn(0));
            for (int idx = 1; idx <= 3; idx++) {
                long start = fm.getChunkBoundaries(idx)[0];
                assertEquals(start - 4, fm.getChunkStartColumn(idx),
                        "Chunk " + idx + " must start at its column within the long line");
            }

            // The background indexer must produce the same start columns
            LargeFileManager indexed = new LargeFileManager();
            indexed.setFile(file);
            indexed.buildIndexCacheAsync(null);
            long deadline = System.currentTimeMillis() + 10_000;
            while (indexed.getCachedChunkStartColumn(3) < 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            for (int idx = 1; idx <= 3; idx++) {
                assertEquals(fm.getChunkStartColumn(idx), indexed.getCachedChunkStartColumn(idx),
                        "Indexer start column for chunk " + idx + " must match the lazy computation");
            }
            indexed.stopIndexer();
        } finally {
            LargeFileManager.CHUNK_SIZE = originalChunkSize;
        }
    }

    @Test
    void testChunkStartColumnCountsCharactersNotBytes(@TempDir Path tempDir) throws Exception {
        int originalChunkSize = LargeFileManager.CHUNK_SIZE;
        LargeFileManager.CHUNK_SIZE = 1000;
        try {
            // UTF-8 BOM + a 2-byte character repeated with no newline
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            out.write("\u00e9".repeat(300_000).getBytes(StandardCharsets.UTF_8));
            File file = tempDir.resolve("utf8_bom_long_line.txt").toFile();
            Files.write(file.toPath(), out.toByteArray());

            LargeFileManager fm = new LargeFileManager();
            fm.setFile(file);
            long start = fm.getChunkBoundaries(1)[0];
            // BOM is not displayed; each 'é' is 2 bytes but 1 character. If the hard cut splits an 'é',
            // the dangling lead byte at the end of chunk 0 is displayed as one replacement character.
            long payloadBytes = start - 3;
            assertEquals((payloadBytes + 1) / 2, fm.getChunkStartColumn(1));
            assertEquals(new String(java.util.Arrays.copyOfRange(out.toByteArray(), 3, (int) start),
                    StandardCharsets.UTF_8).length(), fm.getChunkStartColumn(1),
                    "Start column must equal the decoded character count of the preceding text");
        } finally {
            LargeFileManager.CHUNK_SIZE = originalChunkSize;
        }
    }

    @Test
    void testChunkContinuationKeepsWrapAlignment() throws Exception {
        // 37 characters of the line were in the previous chunk; with max length 110, the first
        // soft wrap of the new chunk must come after 73 characters so later rows stay on the 110 grid.
        String chunkText = "x".repeat(73 + 110 + 5);
        String wrapped = LargeFileManager.forceWrapLongLinesDynamic(chunkText, 110, 37);
        String[] rows = wrapped.split("\u200B\n");
        assertEquals(73, rows[0].length());
        assertEquals(110, rows[1].length());
        assertEquals(5, rows[2].length());

        SwingUtilities.invokeAndWait(() -> {
            AdvancedTextEditorPanel panel = new AdvancedTextEditorPanel();
            javax.swing.text.PlainDocument doc = new javax.swing.text.PlainDocument();
            doc.putProperty(AdvancedTextEditorPanel.CHUNK_START_COLUMN_PROP, 110L * 5 + 37);
            assertEquals(37, AdvancedTextEditorPanel.computeFirstLineColumnShift(doc, 110),
                    "First-line column shift must be the chunk start column modulo the max line length");
            doc.putProperty(AdvancedTextEditorPanel.CHUNK_START_COLUMN_PROP, 0L);
            assertEquals(0, AdvancedTextEditorPanel.computeFirstLineColumnShift(doc, 110));
            panel.forceSetText("abc");
            assertEquals(0, panel.getLineColumnShift(0), "Chunks starting on a new line have no shift");
        });
    }
}

