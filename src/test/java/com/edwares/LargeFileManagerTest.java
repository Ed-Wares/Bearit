package com.edwares;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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
}