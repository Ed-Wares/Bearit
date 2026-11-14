package com.edwares;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class LargeFileManagerTest {

    private LargeFileManager fileManager;

    @BeforeEach
    void setUp() {
        fileManager = new LargeFileManager();
    }

    @Test
    void testGlobalMatchCounting(@TempDir Path tempDir) throws IOException {
        // Arrange: Create a temporary file with known content
        File testFile = tempDir.resolve("test_search.txt").toFile();
        String content = "Bearit is fast.\nBearit is memory efficient.\nThis editor handles massive files.";
        Files.writeString(testFile.toPath(), content);
        
        fileManager.setFile(testFile);

        // Act
        long count = fileManager.countGlobalMatches("Bearit");

        // Assert
        assertEquals(2, count, "Should find exactly two occurrences of 'Bearit'");
    }

    @Test
    void testGlobalReplaceAll(@TempDir Path tempDir) throws IOException {
        // Arrange
        File testFile = tempDir.resolve("test_replace.txt").toFile();
        String content = "Error 404: Not Found.\nError 404: Missing.\nSystem OK.";
        Files.writeString(testFile.toPath(), content);
        
        fileManager.setFile(testFile);

        // Act
        // Convert the search string to a Pattern, and provide dummy callbacks for progress and cancellation
        Pattern searchPattern = Pattern.compile(Pattern.quote("Error 404"));
        fileManager.replaceAllGlobal(searchPattern, "Warning 200", -1, -1, pct -> {}, () -> false);

        // Assert
        String updatedContent = fileManager.getChunkContent(0);
        assertFalse(updatedContent.contains("Error 404"), "Target string should be completely removed");
        assertTrue(updatedContent.contains("Warning 200: Not Found."), "Replacement string should be present");
    }

    @Test
    void testIsMatchInColumnRange() {
        String content = "Line1: abc\nLine2: def\nLine3: ghi";
        // "def" is at index 18 (Line1 is 10 chars + \n = 11. "Line2: def" -> 11 + 7 = 18)
        // start of "def" is 18. length is 3. end is 21.
        // line start is 11. startCol = 18 - 11 = 7. endCol = 21 - 11 = 10.
        
        // Match within range 5 to 15
        assertTrue(LargeFileManager.isMatchInColumnRange(content, 18, 21, 5, 15));
        
        // Match outside range 0 to 5
        assertFalse(LargeFileManager.isMatchInColumnRange(content, 18, 21, 0, 5));
        
        // Disabled range (-1)
        assertTrue(LargeFileManager.isMatchInColumnRange(content, 18, 21, -1, -1));
    }

    @Test
    void testGlobalReplaceAllWithColumnRange(@TempDir Path tempDir) throws IOException {
        File testFile = tempDir.resolve("test_replace_col.txt").toFile();
        String content = "COL1 COL2 COL3\nError 404 Error 404 Error 404\n";
        Files.writeString(testFile.toPath(), content);
        
        fileManager.setFile(testFile);

        Pattern searchPattern = Pattern.compile(Pattern.quote("Error 404"));
        // The first "Error 404" is at col 0-9. Second is 10-19. Third is 20-29.
        // Replace only the second one (col 10 to 19)
        fileManager.replaceAllGlobal(searchPattern, "Warning 200", 10, 19, pct -> {}, () -> false);

        String updatedContent = fileManager.getChunkContent(0);
        assertTrue(updatedContent.contains("Error 404 Warning 200 Error 404"), "Should only replace the match within the column range");
    }

    @Test
    void testChunkMetricsCalculation(@TempDir Path tempDir) throws IOException {
        // Arrange: Create a 30MB file to force exactly 2 chunks (25MB limit)
        File testFile = tempDir.resolve("large_metrics.txt").toFile();
        byte[] largeData = new byte[30 * 1024 * 1024]; 
        Files.write(testFile.toPath(), largeData);
        
        // Act
        fileManager.setFile(testFile);

        // Assert
        assertEquals(2, fileManager.getTotalChunks(), "A 30MB file must be split into exactly 2 chunks");
    }

    @Test
    void testStandardSaveOverwritesCorrectly(@TempDir Path tempDir) throws IOException {
        // Arrange
        File testFile = tempDir.resolve("test_save.txt").toFile();
        Files.writeString(testFile.toPath(), "Initial Data");
        fileManager.setFile(testFile);

        String updatedText = "This is the newly saved data.";

        // Act
        fileManager.saveAll(updatedText, null);

        // Assert
        String diskContent = Files.readString(testFile.toPath());
        assertEquals(updatedText, diskContent, "File on disk should match the text passed to saveAll");
    }

@Test
    void testSaveAsCreatesNewFileAndLeavesOriginal(@TempDir Path tempDir) throws Exception {
        // Arrange
        File originalFile = tempDir.resolve("original.txt").toFile();
        Files.writeString(originalFile.toPath(), "Source Content");
        fileManager.setFile(originalFile);

        File newSaveAsFile = tempDir.resolve("new_location.txt").toFile();
        
        // --- Use Reflection to securely set the private pendingSaveAsFile variable ---
        java.lang.reflect.Field pendingField = LargeFileManager.class.getDeclaredField("pendingSaveAsFile");
        pendingField.setAccessible(true);
        pendingField.set(fileManager, newSaveAsFile);
        
        String newText = "Diverged Content";

        // Act
        fileManager.saveAll(newText, null);

        // Assert
        assertTrue(newSaveAsFile.exists(), "The Save As target file must be created");
        assertEquals("Diverged Content", Files.readString(newSaveAsFile.toPath()), "New file should have the updated content");
        assertEquals("Source Content", Files.readString(originalFile.toPath()), "The original file should remain completely unmodified");
        
        // --- Use Reflection to verify the private variable was cleared ---
        assertNull(pendingField.get(fileManager), "pendingSaveAsFile should be cleared after a successful save");
    }

    @Test
    void testSaveThrowsExceptionWhenNoTargetExists() {
        // Arrange
        LargeFileManager emptyManager = new LargeFileManager();
        // currentFile and pendingSaveAsFile are both implicitly null

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            emptyManager.saveAll("Some text", null);
        });
        
        assertEquals("No valid target file to apply save operation.", exception.getMessage());
    }

    @Test
    void testSaveRetainsPosixExecutePermissions(@TempDir Path tempDir) throws IOException {
        // Arrange: Skip this test on Windows as it does not support POSIX permissions natively
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return; 
        }

        File bashScript = tempDir.resolve("script.sh").toFile();
        Files.writeString(bashScript.toPath(), "echo 'Hello World'");
        
        // Manually set strictly controlled permissions (rwxr-xr-x)
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(bashScript.toPath(), perms);
        
        fileManager.setFile(bashScript);

        // Act
        fileManager.saveAll("echo 'Updated World'", null);

        // Assert
        Set<PosixFilePermission> savedPerms = Files.getPosixFilePermissions(bashScript.toPath());
        assertTrue(savedPerms.contains(PosixFilePermission.OWNER_EXECUTE), "Owner should retain execute permission");
        assertTrue(savedPerms.contains(PosixFilePermission.GROUP_EXECUTE), "Group should retain execute permission");
        assertEquals(perms, savedPerms, "The exact POSIX permission matrix should survive the temporary file swap intact");
    }

    @Test
    void testSaveCompletelyEmptyFile(@TempDir Path tempDir) throws IOException {
        // Arrange
        File testFile = tempDir.resolve("empty_test.txt").toFile();
        Files.writeString(testFile.toPath(), "There is data here initially.");
        fileManager.setFile(testFile);

        // Act: User deletes everything and hits save
        fileManager.saveAll("", null);

        // Assert
        long fileSize = Files.size(testFile.toPath());
        assertEquals(0, fileSize, "The resulting file should be exactly 0 bytes");
    }
    
    @Test
    void testLargeMultiChunkFileSavesCorrectly(@TempDir Path tempDir) throws IOException {
        // Arrange: Create a 30MB file (forces 2 chunks)
        File largeFile = tempDir.resolve("large_save.bin").toFile();
        byte[] largeData = new byte[30 * 1024 * 1024]; 
        
        // Fill the very end of the file with a known marker
        largeData[largeData.length - 1] = 99; 
        Files.write(largeFile.toPath(), largeData);
        
        fileManager.setFile(largeFile);
        
        // Act: Save with modified text for the first chunk
        // Note: For a binary file, string manipulation might warp encoding, but the test ensures 
        // the un-dirtied chunks (chunk 2) copy cleanly through the FileChannel.transferTo block.
        fileManager.saveAll("Modified First Chunk", null);

        // Assert
        byte[] resultingData = Files.readAllBytes(largeFile.toPath());
        assertEquals(99, resultingData[resultingData.length - 1], "The un-dirtied second chunk must transfer cleanly during the save channel stream");
    }

    @Test
    void testCrossChunkBridgeCountAndReplaceAll(@TempDir Path tempDir) throws IOException {
        int originalChunkSize = LargeFileManager.CHUNK_SIZE;
        LargeFileManager.CHUNK_SIZE = 600 * 1024;
        try {
            // Force a hard-cut boundary at CHUNK_SIZE + 500*1024 (no newlines)
            int cutOffset = LargeFileManager.CHUNK_SIZE + 500 * 1024;
            String target = "and efficiently. 949276 ";
            // Place "an" at the end of chunk 0 (cutOffset - 2 .. cutOffset) and
            // "d efficiently. 949276 " at the start of chunk 1 (cutOffset .. cutOffset + 22)
            String prefix = "x".repeat(cutOffset - 2) + "an";
            String suffix = "d efficiently. 949276 " + "y".repeat(200) + target + "z".repeat(200);

            File testFile = tempDir.resolve("bridge_search.txt").toFile();
            Files.writeString(testFile.toPath(), prefix + suffix);

            fileManager.setFile(testFile);
            assertEquals(2, fileManager.getTotalChunks());
            assertTrue(fileManager.getChunkContent(0).endsWith("an"), "Chunk 0 must end with 'an'");
            assertTrue(fileManager.getChunkContent(1).startsWith("d efficiently. 949276 "),
                    "Chunk 1 must start with 'd efficiently. 949276 '");

            // 1. Count must find both the bridging occurrence and the occurrence inside chunk 1
            assertEquals(2, fileManager.countGlobalMatches(target),
                    "countGlobalMatches must count the match bridging chunk 0 and chunk 1 as well as the match in chunk 1");

            // 2. Replace All must replace both occurrences cleanly across chunks
            Pattern p = Pattern.compile(Pattern.quote(target));
            int replaced = fileManager.replaceAllGlobal(p, "[REPLACED]", -1, -1, null, null);
            assertEquals(2, replaced, "replaceAllGlobal must replace both the bridging match and the in-chunk match");

            String combinedAfter = fileManager.getChunkContent(0) + fileManager.getChunkContent(1);
            assertFalse(combinedAfter.contains(target), "No original target strings should remain after Replace All");
            assertEquals("x".repeat(cutOffset - 2) + "[REPLACED]" + "y".repeat(200) + "[REPLACED]" + "z".repeat(200),
                    combinedAfter);
        } finally {
            LargeFileManager.CHUNK_SIZE = originalChunkSize;
        }
    }

    @Test
    void testCrossChunkBridgeFindNextPrevAndSingleReplace(@TempDir Path tempDir) throws Exception {
        int originalChunkSize = LargeFileManager.CHUNK_SIZE;
        LargeFileManager.CHUNK_SIZE = 600 * 1024;
        try {
            int cutOffset = LargeFileManager.CHUNK_SIZE + 500 * 1024;
            String target = "and efficiently. 949276 ";
            String prefix = "x".repeat(cutOffset - 2) + "an";
            String suffix = "d efficiently. 949276 " + "y".repeat(100) + target + "z".repeat(100);

            File testFile = tempDir.resolve("bridge_editor_find.txt").toFile();
            Files.writeString(testFile.toPath(), prefix + suffix);

            AdvancedTextEditorPanel[] panelHolder = new AdvancedTextEditorPanel[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                AdvancedTextEditorPanel panel = new AdvancedTextEditorPanel();
                panel.loadFile(testFile);
                panel.getTextArea().setCaretPosition(0);
                panelHolder[0] = panel;
            });
            AdvancedTextEditorPanel panel = panelHolder[0];

            // 1. Find Next from start of chunk 0 should find the bridging match at the end of chunk 0
            javax.swing.SwingUtilities.invokeAndWait(() -> panel.performFind(target, true));
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                assertEquals("an", panel.getTextArea().getSelectedText(),
                        "Bridging match should select 'an' at the end of chunk 0");
                assertEquals(22, panel.getActiveBridgeEndInNextChunk(),
                        "Bridging match should record 22 characters consumed in chunk 1");
            });

            // 2. Single-step Replace on the active bridging match should replace across both chunks
            // and advance to the next match in chunk 1
            javax.swing.SwingUtilities.invokeAndWait(() -> panel.performReplace(target, "FAST_BRIDGE "));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                String sel = panel.getTextArea().getSelectedText();
                if (target.equals(sel != null ? sel.replace("\u200B\n", "").replace("\u200B", "") : null)) {
                    break;
                }
                Thread.sleep(20);
            }
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                String sel = panel.getTextArea().getSelectedText();
                assertNotNull(sel, "After replacing the bridging match, performReplace should select the next match in chunk 1");
                assertEquals(target, sel.replace("\u200B\n", "").replace("\u200B", ""));
                assertEquals(0, panel.getActiveBridgeEndInNextChunk(),
                        "The second match is wholly inside chunk 1 (not bridging)");
            });
            panel.getFileManager().stopIndexer();
        } finally {
            LargeFileManager.CHUNK_SIZE = originalChunkSize;
        }
    }
}