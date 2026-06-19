package com.edwares;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.function.BiConsumer;

public class FileGenUtil {

    
    public static void generateTestFile(double sizeInGb) throws IOException {
        File defaultDest = new File(String.format(java.util.Locale.US, "bearit_test_file_%.2fGB.txt", sizeInGb));
        generateTestFile(defaultDest, sizeInGb, false, null);
    }

    // UI User-initiated usage with progress callback
    public static void generateTestFile(File targetFile, double sizeInGb, boolean preventNewLines, BiConsumer<Long, Long> progressCallback) throws IOException {
        long totalBytesTarget = (long) (sizeInGb * 1024L * 1024L * 1024L);
        
        System.out.println("Generating test file: " + targetFile.getAbsolutePath());
        System.out.println("Target size: " + sizeInGb + " GB...");

        String recurringLine = "Bearit Test String. Line padding block designed to generate volume safely and efficiently. ";
        byte[] lineBytes = recurringLine.getBytes(StandardCharsets.UTF_8);
        String newLine = preventNewLines ? "" : "\n";
        try (FileChannel channel = FileChannel.open(targetFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.allocate(LargeFileManager.CHUNK_SIZE);
            long bytesWritten = 0;
            int lastPrintedProgress = -1;
            long lineCnt = 1;
            long lastReportTime = 0;
            while (bytesWritten < totalBytesTarget) {
                buffer.clear();
                while (buffer.remaining() > lineBytes.length + 20 && bytesWritten + buffer.position() < totalBytesTarget) {
                    buffer.put(lineBytes);
                    String lineNumStr = String.format("%-20d%s", lineCnt++, newLine); //- space padding to be added to the right of the lineCnt
                    byte[] lineNumBytes = lineNumStr.getBytes(StandardCharsets.UTF_8);
                    buffer.put(lineNumBytes);                
                    if (Thread.currentThread().isInterrupted()) { // Allows the SwingWorker to cleanly cancel the file generation
                        break;
                    }                    
                }
                buffer.flip();
                bytesWritten += channel.write(buffer);

                // Throttle UI updates to prevent the UI thread from freezing
                long now = System.currentTimeMillis();
                if (progressCallback != null && (now - lastReportTime > 300)) {
                    progressCallback.accept(bytesWritten, totalBytesTarget);
                    lastReportTime = now;
                }
                
                // Print progress to console if no callback is provided
                int progressPercent = (int) ((bytesWritten * 100L) / totalBytesTarget);
                if (progressPercent > lastPrintedProgress) {
                    lastPrintedProgress = progressPercent;
                    System.out.print("\rProgress: " + progressPercent + "%");
                }
            }
            // Send final 100% completion tick
            if (progressCallback != null) {
                progressCallback.accept(bytesWritten, totalBytesTarget);
            }
        }
        System.out.println("\nGeneration complete! File is ready for testing.");
    }

    /**
     * CLI wrapper for binary file generation
     */
    public static void generateBinaryTestFile(double sizeInGb) throws IOException {
        File defaultDest = new File(String.format(java.util.Locale.US, "bearit_binary_test_%.2fGB.bin", sizeInGb));
        generateBinaryTestFile(defaultDest, sizeInGb, null);
    }

    /**
     * Core generation logic for binary test files with embedded ASCII words.
     */
    public static void generateBinaryTestFile(File targetFile, double sizeInGb, BiConsumer<Long, Long> progressCallback) throws IOException {
        long totalBytesTarget = (long) (sizeInGb * 1024L * 1024L * 1024L);
        
        System.out.println("Generating binary test file: " + targetFile.getAbsolutePath());
        System.out.println("Target size: " + sizeInGb + " GB...");

        // A simple dictionary pool for generating our readable words
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        byte[] alphabetBytes = alphabet.getBytes(StandardCharsets.US_ASCII);

        try (FileChannel channel = FileChannel.open(targetFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.allocate(LargeFileManager.CHUNK_SIZE);
            byte[] randomBytes = new byte[LargeFileManager.CHUNK_SIZE];
            java.util.concurrent.ThreadLocalRandom rand = java.util.concurrent.ThreadLocalRandom.current();
            
            long bytesWritten = 0;
            int lastPrintedProgress = -1;
            long lastReportTime = 0;

            while (bytesWritten < totalBytesTarget) {
                int bytesToGenerate = (int) Math.min(LargeFileManager.CHUNK_SIZE, totalBytesTarget - bytesWritten);
                
                // Fill the array with pure random bytes (guarantees all 0x00 - 0xFF values)
                rand.nextBytes(randomBytes);
                
                // Inject random 5+ character words intermittently throughout the buffer
                // We attempt an injection roughly every 500 to 1500 bytes
                int cursor = rand.nextInt(1000); 
                while (cursor < bytesToGenerate - 20) {
                    // Generate a word length between 5 and 12 characters
                    int wordLen = 5 + rand.nextInt(8); 
                    
                    // Inject the word into the random byte array
                    for (int j = 0; j < wordLen; j++) {
                        randomBytes[cursor + j] = alphabetBytes[rand.nextInt(alphabetBytes.length)];
                    }
                    
                    // Jump forward a random amount before the next word injection
                    cursor += wordLen + 500 + rand.nextInt(1000);
                }

                // Write the hybrid buffer to the disk
                buffer.clear();
                buffer.put(randomBytes, 0, bytesToGenerate);
                buffer.flip();
                bytesWritten += channel.write(buffer);

                // --- Throttle UI / Console updates ---
                long now = System.currentTimeMillis();
                if (progressCallback != null && (now - lastReportTime > 300)) {
                    progressCallback.accept(bytesWritten, totalBytesTarget);
                    lastReportTime = now;
                }
                
                int progressPercent = (int) ((bytesWritten * 100L) / totalBytesTarget);
                if (progressPercent > lastPrintedProgress) {
                    lastPrintedProgress = progressPercent;
                    System.out.print("\rProgress: " + progressPercent + "%");
                }
                
                if (Thread.currentThread().isInterrupted()) { 
                    break; 
                }
            }
            
            if (progressCallback != null) {
                progressCallback.accept(bytesWritten, totalBytesTarget);
            }
        }
        System.out.println("\nBinary generation complete! File is ready for testing.");
    }
}
