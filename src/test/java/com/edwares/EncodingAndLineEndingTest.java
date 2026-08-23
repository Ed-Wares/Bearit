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
}
