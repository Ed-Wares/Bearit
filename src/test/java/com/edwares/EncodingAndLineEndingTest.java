package com.edwares;

import org.junit.jupiter.api.*;
import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class EncodingAndLineEndingTest {

    private File tempDir;
    private File resourcesDir;

    @BeforeAll
    public static void generateTestFilesIfMissing() throws Exception {
        File dir = new File("src/main/resources/app-content/test-files");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        createTestFile(dir, "test_utf8_crlf.txt", "Line 1\r\nLine 2\r\nLine 3".getBytes(StandardCharsets.UTF_8));
        createTestFile(dir, "test_utf8_lf.txt", "Line 1\nLine 2\nLine 3".getBytes(StandardCharsets.UTF_8));
        createTestFile(dir, "test_utf8_cr.txt", "Line 1\rLine 2\rLine 3".getBytes(StandardCharsets.UTF_8));
        
        byte[] utf8bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] contentLf = "Line 1\nLine 2\nLine 3".getBytes(StandardCharsets.UTF_8);
        byte[] combinedBomLf = new byte[utf8bom.length + contentLf.length];
        System.arraycopy(utf8bom, 0, combinedBomLf, 0, utf8bom.length);
        System.arraycopy(contentLf, 0, combinedBomLf, utf8bom.length, contentLf.length);
        createTestFile(dir, "test_utf8_bom_lf.txt", combinedBomLf);

        byte[] utf16leBom = new byte[]{(byte) 0xFF, (byte) 0xFE};
        byte[] content16le = "Line 1\r\nLine 2\r\nLine 3".getBytes(StandardCharsets.UTF_16LE);
        byte[] combined16le = new byte[utf16leBom.length + content16le.length];
        System.arraycopy(utf16leBom, 0, combined16le, 0, utf16leBom.length);
        System.arraycopy(content16le, 0, combined16le, utf16leBom.length, content16le.length);
        createTestFile(dir, "test_utf16le_crlf.txt", combined16le);

        byte[] utf16beBom = new byte[]{(byte) 0xFE, (byte) 0xFF};
        byte[] content16be = "Line 1\r\nLine 2\r\nLine 3".getBytes(StandardCharsets.UTF_16BE);
        byte[] combined16be = new byte[utf16beBom.length + content16be.length];
        System.arraycopy(utf16beBom, 0, combined16be, 0, utf16beBom.length);
        System.arraycopy(content16be, 0, combined16be, utf16beBom.length, content16be.length);
        createTestFile(dir, "test_utf16be_crlf.txt", combined16be);

        String isoContent = "Line 1\nLine 2\nLine 3 \u00A3\u00E9";
        createTestFile(dir, "test_iso88591.txt", isoContent.getBytes(StandardCharsets.ISO_8859_1));

        createTestFile(dir, "test_binary.bin", new byte[]{(byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0xFF, (byte) 0xFE, (byte) 0x0A, (byte) 0x0D, (byte) 0x00, (byte) 0x1A});
    }

    private static void createTestFile(File dir, String name, byte[] content) throws Exception {
        File f = new File(dir, name);
        if (!f.exists()) {
            Files.write(f.toPath(), content);
        }
    }

    @BeforeEach
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory("bearit_encoding_test").toFile();
        resourcesDir = new File("src/main/resources/app-content/test-files");
    }

    @AfterEach
    public void teardown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    private void waitForUI() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
    }

    private void runEncodingTest(String fileName, boolean expectBinary, String expectEncoding, String expectLineEndings, byte[] appendBytes) throws Exception {
        File sourceFile = new File(resourcesDir, fileName);
        assertTrue(sourceFile.exists(), "Source file missing: " + sourceFile.getAbsolutePath());

        File testFile = new File(tempDir, fileName);
        Files.copy(sourceFile.toPath(), testFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        byte[] originalBytes = Files.readAllBytes(testFile.toPath());

        AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
        editor.loadFile(testFile);
        waitForUI();

        // Verify loaded properties
        assertEquals(expectBinary, editor.isBinaryMode(), "Binary mode mismatch for " + fileName);
        String infoStr = editor.getFileInfoString(testFile);
        assertTrue(infoStr.contains(expectEncoding), "Encoding mismatch for " + fileName + ". Info string: " + infoStr);
        if (!expectBinary) {
            assertTrue(infoStr.contains("(" + expectLineEndings + ")"), "Line ending mismatch for " + fileName + ". Info string: " + infoStr);
        }

        // Add a new line simulating user input if not binary
        if (!expectBinary && appendBytes != null) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    editor.getTextArea().getDocument().insertString(
                        editor.getTextArea().getDocument().getLength(),
                        "\nLine 4", // The UI normalizes to \n
                        null
                    );
                } catch (Exception e) {
                    fail(e);
                }
            });
            waitForUI();
        }

        assertTrue(editor.saveSynchronously(), "Save failed for " + fileName);
        waitForUI();

        byte[] savedBytes = Files.readAllBytes(testFile.toPath());

        if (appendBytes == null) {
            assertArrayEquals(originalBytes, savedBytes, "Saved bytes must perfectly match original for " + fileName);
        } else {
            // Reconstruct the expected bytes
            byte[] expectedBytes = new byte[originalBytes.length + appendBytes.length];
            System.arraycopy(originalBytes, 0, expectedBytes, 0, originalBytes.length);
            System.arraycopy(appendBytes, 0, expectedBytes, originalBytes.length, appendBytes.length);
            assertArrayEquals(expectedBytes, savedBytes, "Saved bytes must perfectly match original + appended for " + fileName);
        }

        editor.dispose();
    }

    @Test
    public void testUtf8_CRLF() throws Exception {
        runEncodingTest("test_utf8_crlf.txt", false, "UTF-8", "CRLF", "\r\nLine 4".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testUtf8_LF() throws Exception {
        runEncodingTest("test_utf8_lf.txt", false, "UTF-8", "LF", "\nLine 4".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testUtf8_CR() throws Exception {
        runEncodingTest("test_utf8_cr.txt", false, "UTF-8", "CR", "\rLine 4".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testUtf8Bom_LF() throws Exception {
        runEncodingTest("test_utf8_bom_lf.txt", false, "UTF-8 BOM", "LF", "\nLine 4".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testUtf16Le_CRLF() throws Exception {
        runEncodingTest("test_utf16le_crlf.txt", false, "UTF-16 LE", "CRLF", "\r\nLine 4".getBytes(StandardCharsets.UTF_16LE));
    }

    @Test
    public void testUtf16Be_CRLF() throws Exception {
        runEncodingTest("test_utf16be_crlf.txt", false, "UTF-16 BE", "CRLF", "\r\nLine 4".getBytes(StandardCharsets.UTF_16BE));
    }

    @Test
    public void testBinary() throws Exception {
        runEncodingTest("test_binary.bin", true, "Binary", "N/A", null);
    }

    @Test
    public void testIso8859_1_AutoDetectionAndRendering() throws Exception {
        String fileName = "test_iso88591.txt";
        File sourceFile = new File(resourcesDir, fileName);
        assertTrue(sourceFile.exists(), "Source file missing: " + sourceFile.getAbsolutePath());

        File testFile = new File(tempDir, fileName);
        Files.copy(sourceFile.toPath(), testFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
        editor.loadFile(testFile);
        waitForUI();

        // Verify the encoding was auto-detected properly
        String infoStr = editor.getFileInfoString(testFile);
        assertTrue(infoStr.contains("ISO-8859-1"), "Encoding should be automatically detected as ISO-8859-1. Info string: " + infoStr);

        // Verify the document rendered correctly with proper character representation
        String text = editor.getTextArea().getText();
        assertTrue(text.contains("\u00A3\u00E9"), "The text area should render the ISO-8859-1 specific characters properly");

        editor.dispose();
    }

    @Test
    public void testIso8859_1_ManualEncoding() throws Exception {
        String fileName = "test_iso88591.txt";
        File sourceFile = new File(resourcesDir, fileName);
        assertTrue(sourceFile.exists(), "Source file missing: " + sourceFile.getAbsolutePath());

        File testFile = new File(tempDir, fileName);
        Files.copy(sourceFile.toPath(), testFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
        editor.loadFile(testFile);
        waitForUI();

        // Manually switch to ISO-8859-1
        editor.changeEncoding("ISO-8859-1");
        waitForUI();
        
        String infoStr = editor.getFileInfoString(testFile);
        assertTrue(infoStr.contains("ISO-8859-1"), "Encoding should be updated to ISO-8859-1. Info string: " + infoStr);

        // Add a line with a special character that is 1 byte in ISO-8859-1 but 2 in UTF-8
        SwingUtilities.invokeAndWait(() -> {
            try {
                editor.getTextArea().getDocument().insertString(
                    editor.getTextArea().getDocument().getLength(),
                    "\nLine 4 \u00A3", // Pound sign
                    null
                );
            } catch (Exception e) {
                fail(e);
            }
        });
        waitForUI();

        assertTrue(editor.saveSynchronously(), "Save failed for ISO-8859-1");
        waitForUI();

        byte[] savedBytes = Files.readAllBytes(testFile.toPath());
        String savedString = new String(savedBytes, StandardCharsets.ISO_8859_1);
        
        // Verify it was saved as ISO-8859-1
        assertTrue(savedString.endsWith("Line 4 \u00A3"), "Should be accurately read back as ISO-8859-1");
        
        // Verify it's different from UTF-8
        byte[] utf8Bytes = savedString.getBytes(StandardCharsets.UTF_8);
        assertTrue(savedBytes.length < utf8Bytes.length, "ISO-8859-1 encoding must be more compact for these characters than UTF-8");

        editor.dispose();
    }

    private void runLineEndingConversionTest(String fileName, String initialExpected, String targetLineEnding) throws Exception {
        File sourceFile = new File(resourcesDir, fileName);
        assertTrue(sourceFile.exists(), "Source file missing: " + sourceFile.getAbsolutePath());

        File testFile = new File(tempDir, fileName);
        Files.copy(sourceFile.toPath(), testFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
        editor.loadFile(testFile);
        waitForUI();

        // Verify initial loaded properties
        String infoStr = editor.getFileInfoString(testFile);
        assertTrue(infoStr.contains("(" + initialExpected + ")"), "Line endings should initially be " + initialExpected + ". Info string: " + infoStr);

        // Manually switch to target line ending
        editor.changeLineEndings(targetLineEnding);
        waitForUI();
        
        infoStr = editor.getFileInfoString(testFile);
        assertTrue(infoStr.contains("(" + targetLineEnding + ")"), "Line endings should be updated to " + targetLineEnding + ". Info string: " + infoStr);

        assertTrue(editor.saveSynchronously(), "Save failed for " + targetLineEnding + " conversion");
        waitForUI();

        byte[] savedBytes = Files.readAllBytes(testFile.toPath());
        String savedString = new String(savedBytes, StandardCharsets.UTF_8);
        
        // Verify it was saved as the target line ending
        if ("LF".equals(targetLineEnding)) {
            assertFalse(savedString.contains("\r\n"), "Saved text must not contain CRLF");
            String stripped = savedString.replace("\r\n", "");
            assertFalse(stripped.contains("\r"), "Saved text must not contain naked CR");
            assertTrue(savedString.contains("\n"), "Saved text must contain LF");
        } else if ("CRLF".equals(targetLineEnding)) {
            assertTrue(savedString.contains("\r\n"), "Saved text must contain CRLF");
            String stripped = savedString.replace("\r\n", "");
            assertFalse(stripped.contains("\n"), "Saved text must not contain naked LF");
            assertFalse(stripped.contains("\r"), "Saved text must not contain naked CR");
        } else if ("CR".equals(targetLineEnding)) {
            assertFalse(savedString.contains("\r\n"), "Saved text must not contain CRLF");
            assertFalse(savedString.contains("\n"), "Saved text must not contain naked LF");
            assertTrue(savedString.contains("\r"), "Saved text must contain CR");
        }

        editor.dispose();
    }

    @Test
    public void testCrlfToLfConversion() throws Exception {
        runLineEndingConversionTest("test_utf8_crlf.txt", "CRLF", "LF");
    }

    @Test
    public void testLfToCrlfConversion() throws Exception {
        runLineEndingConversionTest("test_utf8_lf.txt", "LF", "CRLF");
    }

    @Test
    public void testCrToCrlfConversion() throws Exception {
        runLineEndingConversionTest("test_utf8_cr.txt", "CR", "CRLF");
    }

    @Test
    public void testCrToLfConversion() throws Exception {
        runLineEndingConversionTest("test_utf8_cr.txt", "CR", "LF");
    }

    @Test
    public void testCrlfSelectionDoesNotShiftOrLeaveFragmentsOnHiDpi() throws Exception {
        File crlfFile = new File(tempDir, "hidpi_crlf.txt");
        String content = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz\r\n"
                + "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz\r\n";
        Files.write(crlfFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        for (double scale : new double[] { 1.0, 1.25, 1.5 }) {
            for (boolean wrap : new boolean[] { false, true }) {
                final javax.swing.JFrame[] frameHolder = new javax.swing.JFrame[1];
                AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
                SwingUtilities.invokeAndWait(() -> {
                    javax.swing.JFrame frame = new javax.swing.JFrame();
                    editor.setWordWrap(wrap);
                    editor.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 14));
                    frame.add(editor);
                    frame.setSize(900, 400);
                    frame.addNotify();
                    frame.validate();
                    editor.getTextArea().setSize(800, 400);
                    frameHolder[0] = frame;
                });
                editor.loadFile(crlfFile);
                waitForUI();

                SwingUtilities.invokeAndWait(() -> {
                    try {
                        javax.swing.JTextArea ta = editor.getTextArea();
                        ta.setCaretPosition(0);
                        int imgW = (int) Math.ceil(800 * scale);
                        int imgH = (int) Math.ceil(240 * scale);

                        java.awt.image.BufferedImage imgUnsel = new java.awt.image.BufferedImage(
                                imgW, imgH, java.awt.image.BufferedImage.TYPE_INT_RGB);
                        java.awt.Graphics2D gUnsel = imgUnsel.createGraphics();
                        gUnsel.scale(scale, scale);
                        gUnsel.setClip(0, 0, 800, 200);
                        ta.paint(gUnsel);
                        gUnsel.dispose();

                        // Select range 31..38 on line 0 and verify trailing text after the selection does not shift
                        ta.select(31, 38);
                        java.awt.image.BufferedImage imgSel = new java.awt.image.BufferedImage(
                                imgW, imgH, java.awt.image.BufferedImage.TYPE_INT_RGB);
                        java.awt.Graphics2D gSel = imgSel.createGraphics();
                        gSel.scale(scale, scale);
                        gSel.setClip(0, 0, 800, 200);
                        ta.paint(gSel);
                        gSel.dispose();

                        java.awt.FontMetrics fm = ta.getFontMetrics(ta.getFont());
                        int lineH = (int) Math.ceil(fm.getHeight() * scale);
                        javax.swing.text.View rootView = ta.getUI().getRootView(ta);
                        java.awt.Shape afterSelShape = rootView.modelToView(
                                40, new java.awt.Rectangle(0, 0, 800, 200), javax.swing.text.Position.Bias.Forward);
                        int xAfterSel = (int) Math.ceil(afterSelShape.getBounds2D().getX() * scale);
                        int maxScanX = Math.min(imgW, (int) Math.floor(750 * scale));
                        int trailingDiffs = 0;
                        for (int y = 0; y < lineH; y++) {
                            for (int x = xAfterSel; x < maxScanX; x++) {
                                if (imgUnsel.getRGB(x, y) != imgSel.getRGB(x, y)) {
                                    trailingDiffs++;
                                }
                            }
                        }
                        assertEquals(0, trailingDiffs,
                                "Trailing text after selection must not shift horizontally on HiDPI (scale=" + scale
                                        + ", wrap=" + wrap + ")");

                        // Unselect and repaint only the highlight clip bounds, verifying no shifted fragments remain
                        java.awt.Shape selShape = rootView.modelToView(
                                31, javax.swing.text.Position.Bias.Forward,
                                38, javax.swing.text.Position.Bias.Backward,
                                new java.awt.Rectangle(0, 0, 800, 200));
                        java.awt.Rectangle clipRect = selShape.getBounds();
                        ta.setCaretPosition(0);
                        java.awt.Graphics2D gPartial = imgSel.createGraphics();
                        gPartial.scale(scale, scale);
                        gPartial.setClip(clipRect.x - 1, clipRect.y, clipRect.width + 2, clipRect.height);
                        ta.paint(gPartial);
                        gPartial.dispose();

                        int leftoverFragments = 0;
                        for (int y = 0; y < lineH; y++) {
                            for (int x = (int) Math.ceil(16 * scale); x < maxScanX; x++) {
                                if (imgUnsel.getRGB(x, y) != imgSel.getRGB(x, y)) {
                                    leftoverFragments++;
                                }
                            }
                        }
                        assertEquals(0, leftoverFragments,
                                "Unselecting text must not leave shifted glyph fragments behind (scale=" + scale
                                        + ", wrap=" + wrap + ")");
                    } catch (Exception e) {
                        fail(e);
                    }
                });

                SwingUtilities.invokeAndWait(() -> {
                    if (frameHolder[0] != null) {
                        frameHolder[0].dispose();
                    }
                });
                editor.dispose();
            }
        }
    }

    @Test
    void testColumnRulerTicksNumbersScrollingAndCurrentColumnHighlight() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append((char) ('A' + (i % 26)));
        }
        File testFile = new File(tempDir, "column_ruler.txt");
        Files.write(testFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));

        AdvancedTextEditorPanel[] editorHolder = new AdvancedTextEditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            editorHolder[0] = new AdvancedTextEditorPanel();
            assertFalse(editorHolder[0].isShowColumnRuler(), "Column ruler should be disabled by default");
        });
        AdvancedTextEditorPanel editor = editorHolder[0];

        editor.loadFile(testFile);
        waitForUI();

        SwingUtilities.invokeAndWait(() -> {
            try {
                // Enable Column Ruler
                editor.setShowColumnRuler(true);
                assertTrue(editor.isShowColumnRuler(), "Column ruler should be enabled");
                AdvancedTextEditorPanel.ColumnRulerPanel ruler = editor.getColumnRulerPanel();
                assertNotNull(ruler, "ColumnRulerPanel must exist");

                javax.swing.JTextArea ta = editor.getTextArea();
                ta.setSize(2400, 200);
                ruler.setSize(2400, ruler.getPreferredSize().height);

                // 1. Verify tick X coordinates align with cursor X coordinates for columns 0..50
                java.awt.font.FontRenderContext frc = ta.getFontMetrics(ta.getFont()).getFontRenderContext();
                for (int col = 0; col <= 50; col++) {
                    int expectedCaretX = ta.modelToView2D(col).getBounds().x;
                    int rulerTickX = ruler.getTickXForColumnOffset(col, frc);
                    assertEquals(expectedCaretX, rulerTickX,
                            "Column ruler tick must align vertically with cursor X at column " + col);
                }

                // 2. Verify every 5th tick is larger than regular ticks and 10th tick is >= 5th tick
                assertTrue(ruler.getFifthTickHeight() > ruler.getRegularTickHeight(),
                        "Every 5th column tick must be larger than regular column ticks");
                assertTrue(ruler.getTenthTickHeight() >= ruler.getFifthTickHeight(),
                        "Every 10th column tick must be at least as large as 5th column ticks");

                // 3. Verify current cursor column index tracking (0-based and 1-based)
                ta.setCaretPosition(7);
                editor.setOneBasedColumnIndex(false);
                assertEquals(7, ruler.getCurrentCursorDisplayColumn(), "0-based current column should be 7");
                editor.setOneBasedColumnIndex(true);
                assertEquals(8, ruler.getCurrentCursorDisplayColumn(), "1-based current column should be 8");
                editor.setOneBasedColumnIndex(false);

                // 4. Verify painting highlights current cursor column tick with darker/more pronounced color
                int rulerH = ruler.getPreferredSize().height;
                java.awt.image.BufferedImage imgAtCol7 = new java.awt.image.BufferedImage(
                        600, rulerH, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g7 = imgAtCol7.createGraphics();
                g7.setClip(0, 0, 600, rulerH);
                ruler.paint(g7);
                g7.dispose();

                int tick7X = ruler.getTickXForColumnOffset(7, frc);
                int tick3X = ruler.getTickXForColumnOffset(3, frc);
                int tickSampleY = rulerH - 3;
                java.awt.Color activeTickColor = new java.awt.Color(imgAtCol7.getRGB(tick7X, tickSampleY));
                java.awt.Color normalTickColor = new java.awt.Color(imgAtCol7.getRGB(tick3X, tickSampleY));
                assertTrue(activeTickColor.getRed() < normalTickColor.getRed(),
                        "Active cursor column tick must be darker/more pronounced than normal column ticks in Light theme");

                // Disable Column Ruler
                editor.setShowColumnRuler(false);
                assertFalse(editor.isShowColumnRuler(), "Column ruler should be disabled after toggling off");
            } catch (Exception e) {
                fail(e);
            }
        });
        editor.dispose();
    }

    @Test
    void testColumnRulerDynamicLineSpacingWithProportionalFonts() throws Exception {
        String content = "iiiiilllll.....iiiiilllll\n"
                + "WWWWWMMMMMWWWWWMMMMMWWWWW\n"
                + "Mixed proportional line\twith tab and 1234567890\n";
        File testFile = new File(tempDir, "column_ruler_proportional.txt");
        Files.write(testFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        AdvancedTextEditorPanel[] editorHolder = new AdvancedTextEditorPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            editorHolder[0] = new AdvancedTextEditorPanel();
            editorHolder[0].setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 16));
            editorHolder[0].setShowColumnRuler(true);
        });
        AdvancedTextEditorPanel editor = editorHolder[0];

        editor.loadFile(testFile);
        waitForUI();

        SwingUtilities.invokeAndWait(() -> {
            try {
                javax.swing.JTextArea ta = editor.getTextArea();
                AdvancedTextEditorPanel.ColumnRulerPanel ruler = editor.getColumnRulerPanel();
                ta.setSize(2000, 300);
                ruler.setSize(2000, ruler.getPreferredSize().height);
                java.awt.font.FontRenderContext frc = ta.getFontMetrics(ta.getFont()).getFontRenderContext();

                // Line 0: narrow characters ("iiiiilllll.....iiiiilllll")
                int line0Start = ta.getLineStartOffset(0);
                ta.setCaretPosition(line0Start + 5);
                int line0Col10X = ruler.getTickXForColumnOffset(10, frc);
                for (int col = 0; col <= 25; col++) {
                    int expectedCaretX = ta.modelToView2D(line0Start + col).getBounds().x;
                    int rulerTickX = ruler.getTickXForColumnOffset(col, frc);
                    assertEquals(expectedCaretX, rulerTickX,
                            "Proportional font (line 0): tick X must match cursor X at column " + col);
                }

                // Line 1: wide characters ("WWWWWMMMMMWWWWWMMMMMWWWWW")
                int line1Start = ta.getLineStartOffset(1);
                ta.setCaretPosition(line1Start + 5);
                int line1Col10X = ruler.getTickXForColumnOffset(10, frc);
                for (int col = 0; col <= 25; col++) {
                    int expectedCaretX = ta.modelToView2D(line1Start + col).getBounds().x;
                    int rulerTickX = ruler.getTickXForColumnOffset(col, frc);
                    assertEquals(expectedCaretX, rulerTickX,
                            "Proportional font (line 1): tick X must match cursor X at column " + col);
                }

                assertTrue(line1Col10X > line0Col10X,
                        "Column 10 tick on wide-character line ('W'/'M') must be wider than on narrow-character line ('i'/'l')");

                // Line 2: mixed proportional characters with a tab
                int line2Start = ta.getLineStartOffset(2);
                ta.setCaretPosition(line2Start + 10);
                for (int col = 0; col <= 35; col++) {
                    int expectedCaretX = ta.modelToView2D(line2Start + col).getBounds().x;
                    int rulerTickX = ruler.getTickXForColumnOffset(col, frc);
                    assertEquals(expectedCaretX, rulerTickX,
                            "Proportional font with tab (line 2): tick X must match cursor X at column " + col);
                }
            } catch (Exception e) {
                fail(e);
            }
        });
        editor.dispose();
    }
}
