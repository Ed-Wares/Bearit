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
        fileManager.replaceAllGlobal(searchPattern, "Warning 200", pct -> {}, () -> false);

        // Assert
        String updatedContent = Files.readString(testFile.toPath());
        assertFalse(updatedContent.contains("Error 404"), "Target string should be completely removed");
        assertTrue(updatedContent.contains("Warning 200: Not Found."), "Replacement string should be present");
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
}