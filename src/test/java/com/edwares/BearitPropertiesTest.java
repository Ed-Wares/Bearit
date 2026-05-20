package com.edwares;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BearitPropertiesTest {

    private BearitProperties properties;

    @BeforeEach
    void setUp() {
        properties = BearitProperties.getInstance();
        // Clear recent files state before each test
        for (int i = 0; i < 15; i++) {
            properties.getRecentFiles().clear(); 
        }
    }

    @Test
    void testRecentFilesLruCapping() {
        // Act: Add 12 distinct file paths
        for (int i = 1; i <= 12; i++) {
            properties.addRecentFile("C:\\logs\\server_" + i + ".log");
        }

        List<String> recentFiles = properties.getRecentFiles();

        // Assert
        assertEquals(10, recentFiles.size(), "Recent files list should never exceed 10 items");
        
        // The most recently added file (12) should be at index 0
        assertEquals("C:\\logs\\server_12.log", recentFiles.get(0), "Most recent file must be at the top of the list");
        
        // The oldest files (1 and 2) should have been pushed out
        assertFalse(recentFiles.contains("C:\\logs\\server_1.log"), "Oldest file should be evicted");
    }

    @Test
    void testRecentFilesDuplicateHandling() {

        properties.clearRecentFiles(); // Clear state before test
        // Act: Add the same file multiple times
        properties.addRecentFile("C:\\logs\\active.log");
        properties.addRecentFile("C:\\logs\\archive.log");
        properties.addRecentFile("C:\\logs\\active.log"); // Add duplicate

        List<String> recentFiles = properties.getRecentFiles();

        // Assert
        assertEquals(2, recentFiles.size(), "Duplicates should be removed, leaving only unique entries");
        assertEquals("C:\\logs\\active.log", recentFiles.get(0), "Duplicate entry should be moved to the top");
    }
}