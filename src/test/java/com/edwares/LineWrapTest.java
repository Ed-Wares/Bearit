package com.edwares;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LineWrapTest {

    @Test
    public void testNoWrapNeeded() {
        String input = "Hello World\nThis is a standard text file.\nLine 3.";
        String result = LargeFileManager.forceWrapLongLinesDynamic(input, 20000, 0);
        
        assertEquals(input, result, "String with standard newlines should not be modified.");
    }

    @Test
    public void testExactBoundary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append("A");
        }
        String input = sb.toString();
        String result = LargeFileManager.forceWrapLongLinesDynamic(input, 20000, 0);
        
        assertEquals(input, result, "Exact boundary string should not inject a newline.");
    }

    @Test
    public void testForcedWrapExceedsBoundary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20005; i++) {
            sb.append("A");
        }
        String input = sb.toString();
        String result = LargeFileManager.forceWrapLongLinesDynamic(input, 20000, 0);
        
        assertTrue(result.contains("\u200B\n"), "Result must contain the soft break marker.");
        assertEquals(20000, result.indexOf('\u200B'), "Marker injected at incorrect index.");
        assertEquals(20007, result.length(), "Final string length is incorrect.");
    }

    @Test
    public void testMultipleWrapsOnMassiveLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50000; i++) {
            sb.append("B");
        }
        String input = sb.toString();
        String result = LargeFileManager.forceWrapLongLinesDynamic(input, 20000, 0);

        assertEquals(20000, result.indexOf('\u200B'));
        assertEquals(40002, result.indexOf('\u200B', 20002)); 
        assertEquals(50004, result.length());
    }

    @Test
    public void testHandlesExistingNewlinesProperly() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("A");
        sb.append("\n");
        for (int i = 0; i < 20005; i++) sb.append("B");
        
        String input = sb.toString();
        String result = LargeFileManager.forceWrapLongLinesDynamic(input, 20000, 0);
        
        assertEquals(10, result.indexOf('\n'));
        assertEquals(20011, result.indexOf('\u200B', 11));
    }
}