package com.edwares;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BinarySyncTest {

    // Helper mirror methods to test the logic directly
    private String encodeBinaryForView(String rawContent) {
        StringBuilder sb = new StringBuilder(rawContent.length());
        for (int i = 0; i < rawContent.length(); i++) {
            char c = rawContent.charAt(i);
            if (c == '\n' || c == '\t' || (c >= 32 && c <= 126)) {
                sb.append(c);
            } else {
                sb.append((char) (0xE000 + (c & 0xFF)));
            }
        }
        return sb.toString();
    }

    private String decodeViewToBinary(String uiText) {
        StringBuilder sb = new StringBuilder(uiText.length());
        for (int i = 0; i < uiText.length(); i++) {
            char c = uiText.charAt(i);
            if (c >= 0xE000 && c <= 0xE0FF) {
                sb.append((char) (c - 0xE000));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Test
    public void testEncodeDecodeIdentity() {
        // Create a string with printable text, newlines, carriage returns, and raw binary bytes
        String originalRaw = "Hello\r\nWorld\t" + (char)0x00 + (char)0x7F + (char)0xFF + "\n\n"+
        "next\r\nline\t" + (char)0x01 + (char)0x7F + (char)0xFF;
        
        String encoded = encodeBinaryForView(originalRaw);
        String decoded = decodeViewToBinary(encoded);
        
        // Assert that the cycle results in zero data loss
        assertEquals(originalRaw, decoded, "Decode should perfectly reverse the encoded binary view.");
    }

    @Test
    public void testCarriageReturnProtection() {
        String originalRaw = "Line1\r\nLine2";
        String encoded = encodeBinaryForView(originalRaw);
        
        // Assert \n stayed native
        assertTrue(encoded.contains("\n"), "Newline should remain native for editor formatting.");
        
        // Assert \r was pushed to the PUA (0xE00D) to prevent Swing from swallowing it
        assertFalse(encoded.contains("\r"), "Carriage return must be removed from raw string.");
        assertTrue(encoded.contains(String.valueOf((char)0xE00D)), "Carriage return must be mapped to PUA.");
    }

    @Test
    public void testBinaryByteMapping() {
        // Test Null Byte (0x00)
        String encodedNull = encodeBinaryForView(String.valueOf((char)0x00));
        assertEquals((char)0xE000, encodedNull.charAt(0), "Null byte should map to E000");

        // Test Execute/Delete Byte (0x7F)
        String encodedDel = encodeBinaryForView(String.valueOf((char)0x7F));
        assertEquals((char)0xE07F, encodedDel.charAt(0), "0x7F byte should map to E07F");
        
        // Test 1-to-1 String length retention
        String mixedData = "A" + (char)0x01 + "B" + (char)0xFF;
        String encodedMixed = encodeBinaryForView(mixedData);
        assertEquals(4, encodedMixed.length(), "Encoded string length must perfectly match raw byte length.");
    }
}
