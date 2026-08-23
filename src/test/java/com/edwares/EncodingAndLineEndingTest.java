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
}
