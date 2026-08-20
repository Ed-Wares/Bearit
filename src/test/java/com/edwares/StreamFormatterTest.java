package com.edwares;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class StreamFormatterTest {

    @Test
    public void testJsonFormattingSmall() {
        String input = "text before {\"a\": 1, \"b\": [2,3]} text after";
        StreamFormatter.FormatState state = new StreamFormatter.FormatState();
        String result = StreamFormatter.formatJsonChunk(input, state);
        
        String expected = "text before {\n" +
                          "  \"a\": 1,\n" +
                          "  \"b\": [\n" +
                          "    2,\n" +
                          "    3\n" +
                          "  ]\n" +
                          "} text after";
        
        assertEquals(expected, result);
        verifyDataIntegrity(input, result);
    }

    @Test
    public void testJsonFormattingLargeMultiChunk() {
        // Simulating a chunked JSON object
        String chunk1 = "some log info {\"key1\":\"val1\",\"arr\":[";
        String chunk2 = "1,2,3],\"key2\":";
        String chunk3 = "{\"sub\":true}} trailing text";

        StreamFormatter.FormatState state = new StreamFormatter.FormatState();
        String result1 = StreamFormatter.formatJsonChunk(chunk1, state);
        String result2 = StreamFormatter.formatJsonChunk(chunk2, state);
        String result3 = StreamFormatter.formatJsonChunk(chunk3, state);
        
        String combinedResult = result1 + result2 + result3;
        String combinedInput = chunk1 + chunk2 + chunk3;

        String expected = "some log info {\n" +
                          "  \"key1\": \"val1\",\n" +
                          "  \"arr\": [\n" +
                          "    1,\n" +
                          "    2,\n" +
                          "    3\n" +
                          "  ],\n" +
                          "  \"key2\": {\n" +
                          "    \"sub\": true\n" +
                          "  }\n" +
                          "} trailing text";

        assertEquals(expected, combinedResult);
        verifyDataIntegrity(combinedInput, combinedResult);
    }

    @Test
    public void testXmlFormattingSmall() {
        String input = "start text <root><child attr=\"1\">hello</child><self/></root> end text";
        StreamFormatter.FormatState state = new StreamFormatter.FormatState();
        String result = StreamFormatter.formatXmlChunk(input, state);

        String expected = "start text <root>\n" +
                          "  <child attr=\"1\">hello</child>\n" +
                          "  <self/>\n" +
                          "</root> end text";

        assertEquals(expected, result);
        verifyDataIntegrity(input, result);
    }

    @Test
    public void testXmlFormattingLargeMultiChunk() {
        String chunk1 = "<html lang=\"en\"><head><title>";
        String chunk2 = "Test</title></head><body><p>";
        String chunk3 = "Some text</p></body></html>";

        StreamFormatter.FormatState state = new StreamFormatter.FormatState();
        String result1 = StreamFormatter.formatXmlChunk(chunk1, state);
        String result2 = StreamFormatter.formatXmlChunk(chunk2, state);
        String result3 = StreamFormatter.formatXmlChunk(chunk3, state);

        String combinedResult = result1 + result2 + result3;
        String combinedInput = chunk1 + chunk2 + chunk3;
        
        verifyDataIntegrity(combinedInput, combinedResult);
    }
    
    @Test
    public void testJsonStringsWithEscapedQuotes() {
        String input = "{\"a\":\"\\\"hello\\\"\"}";
        StreamFormatter.FormatState state = new StreamFormatter.FormatState();
        String result = StreamFormatter.formatJsonChunk(input, state);
        
        String expected = "{\n  \"a\": \"\\\"hello\\\"\"\n}";
        assertEquals(expected, result);
        verifyDataIntegrity(input, result);
    }

    private void verifyDataIntegrity(String original, String formatted) {
        String strippedOriginal = original.replaceAll("\\s+", "");
        String strippedFormatted = formatted.replaceAll("\\s+", "");
        assertEquals(strippedOriginal, strippedFormatted, "Data integrity check failed: Non-whitespace characters do not match.");
    }

    @Test
    public void testJsonFormattingWithPrefixContainingSpecialChars() {
        String input = "Prefix string with a quote \" and a comma, and a colon: {\"a\": 1} and a suffix";
        StreamFormatter.FormatState state = new StreamFormatter.FormatState();
        String result = StreamFormatter.formatJsonChunk(input, state);
        
        String expected = "Prefix string with a quote \" and a comma, and a colon: {\n" +
                          "  \"a\": 1\n" +
                          "} and a suffix";
        assertEquals(expected, result);
    }
}
