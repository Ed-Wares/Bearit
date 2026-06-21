package com.edwares;

import org.junit.jupiter.api.*;
import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class RealFileSaveSyncTest {
    
    private File tempDir;
    private int originalChunkSize;

    @BeforeEach
    public void setup() throws Exception {
        // Lower chunk size to 50 bytes to make multi-chunk tests run in milliseconds
        originalChunkSize = LargeFileManager.CHUNK_SIZE;
        LargeFileManager.CHUNK_SIZE = 50; 

        // Create a temporary sandboxed directory for this specific test run
        tempDir = Files.createTempDirectory("bearit_integration_test").toFile();
    }

    @AfterEach
    public void teardown() {
        // Always restore the original 25MB chunk size so other tests aren't affected
        LargeFileManager.CHUNK_SIZE = originalChunkSize;
        
        // Clean up temp files
        for (File f : tempDir.listFiles()) {
            f.delete();
        }
        tempDir.delete();
    }

    /**
     * Helper method to pause the test thread until the Java AWT Event Dispatch 
     * Thread (EDT) finishes drawing the text to the JTextArea.
     */
    private void waitForUI() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
    }

    @Test
    public void testTextFile_MultiChunk_SavesPerfectly() throws Exception {
        // Setup: A standard text file with natural newlines
        File sourceFile = new File(tempDir, "text_source.txt");
        String textContent = "Line A".repeat(10) + "\n" + "Line B".repeat(10) + "\n";
        byte[] originalBytes = textContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(sourceFile.toPath(), originalBytes);

        AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
        
        // Load the file and wait for the Swing UI to finish rendering it
        editor.loadFile(sourceFile);
        waitForUI();

        // Perform the synchronous save operation
        assertTrue(editor.saveSynchronously(), "Save operation should return true on success.");
        waitForUI();

        // Verify: File must be byte-for-byte identical
        byte[] savedBytes = Files.readAllBytes(sourceFile.toPath());
        assertArrayEquals(originalBytes, savedBytes, "Saved file must exactly match original text file.");
    }

    @Test
    public void testMinifiedTextFile_NoNewlines_SavesPerfectly() throws Exception {
        // Setup: A 120-byte text file with NO newlines.
        // With CHUNK_SIZE=50, this forces the file into 3 distinct chunks, triggering the old bug.
        File sourceFile = new File(tempDir, "minified_source.txt");
        String textContent = "A".repeat(120); 
        byte[] originalBytes = textContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(sourceFile.toPath(), originalBytes);

        AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
        editor.loadFile(sourceFile);
        waitForUI();

        // Perform Save
        assertTrue(editor.saveSynchronously());
        waitForUI();

        // Verify
        byte[] savedBytes = Files.readAllBytes(sourceFile.toPath());
        assertArrayEquals(originalBytes, savedBytes, 
            "Saved minified text file must NOT have \\n injected at chunk boundaries.");
    }

    @Test
    public void testBinaryFile_MultiChunk_SavesPerfectly() throws Exception {
        // Setup: A 150-byte binary file
        File sourceFile = new File(tempDir, "binary_source.bin");
        byte[] binaryContent = new byte[150];
        for (int i = 0; i < binaryContent.length; i++) {
            binaryContent[i] = (byte) (i % 256);
        }
        
        // CRITICAL TEST: Manually place newlines (0x0A) exactly at the 50-byte 
        // chunk boundaries. This guarantees we catch the old duplication bug!
        binaryContent[49] = 0x0A;
        binaryContent[50] = 0x0A;
        binaryContent[99] = 0x0A;

        Files.write(sourceFile.toPath(), binaryContent);

        AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
        editor.loadFile(sourceFile);
        waitForUI();
        
        // Assert that the heuristic scan correctly identified it as binary
        assertTrue(editor.isBinaryMode(), "Editor should automatically detect the file as binary.");

        // Perform Save
        assertTrue(editor.saveSynchronously());
        waitForUI();

        // Verify
        byte[] savedBytes = Files.readAllBytes(sourceFile.toPath());
        assertArrayEquals(binaryContent, savedBytes, 
            "Saved binary file must exactly match original file. No bytes can be shifted, corrupted, or duplicated.");
    }
}