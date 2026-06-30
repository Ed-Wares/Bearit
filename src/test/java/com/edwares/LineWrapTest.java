package com.edwares;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LineWrapTest {

    static int MAX_WRAP_LEN = 15000;
    @Test
    public void testNoWrapNeeded() {
        String input = "Hello World\nThis is a standard text file.\nLine 3.";
        String result = LargeFileManager.forceWrapLongLinesDynamic(input, MAX_WRAP_LEN, 0);
        
        assertEquals(input, result, "String with standard newlines should not be modified.");
    }

    @Test
    public void testExactBoundary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_WRAP_LEN; i++) {
            sb.append("A");
        }
        String input = sb.toString();
        String result = LargeFileManager.forceWrapLongLinesDynamic(input, MAX_WRAP_LEN, 0);
        
        assertEquals(input, result, "Exact boundary string should not inject a newline.");
    }

    @Test
    public void testForcedWrapExceedsBoundary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20005; i++) {
            sb.append("A");
        }
        String input = sb.toString();
        String result = LargeFileManager.forceWrapLongLinesDynamic(input, MAX_WRAP_LEN, 0);
        
        assertTrue(result.contains("\u200B\n"), "Result must contain the soft break marker.");
        assertEquals(MAX_WRAP_LEN, result.indexOf('\u200B'), "Marker injected at incorrect index.");
        assertEquals(20007, result.length(), "Final string length is incorrect.");
    }

    @Test
    public void testMultipleWrapsOnMassiveLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50000; i++) {
            sb.append("B");
        }
        String input = sb.toString();
        String result = LargeFileManager.forceWrapLongLinesDynamic(input, MAX_WRAP_LEN, 0);

        assertEquals(MAX_WRAP_LEN, result.indexOf('\u200B'));
        assertEquals((MAX_WRAP_LEN * 2) + 2, result.indexOf('\u200B', MAX_WRAP_LEN + 2 )); 
        assertEquals((MAX_WRAP_LEN * 3) + 4, result.indexOf('\u200B', (MAX_WRAP_LEN * 2) + 4));
    }

    @Test
    public void testHandlesExistingNewlinesProperly() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("A");
        sb.append("\n");
        for (int i = 0; i < 20005; i++) sb.append("B");
        
        String input = sb.toString();
        String result = LargeFileManager.forceWrapLongLinesDynamic(input, MAX_WRAP_LEN, 0);
        
        assertEquals(10, result.indexOf('\n'));
        assertEquals(MAX_WRAP_LEN + 11, result.indexOf('\u200B', 11));
    }
}