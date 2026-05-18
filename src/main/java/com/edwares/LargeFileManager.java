package com.edwares;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class LargeFileManager {
    private static final int CHUNK_SIZE = 25 * 1024 * 1024; // 25MB Chunks

    private File currentFile;
    private long currentChunkStartByte = 0;
    private int currentChunkLoadedSize = 0;

    public record ChunkState(
        String content, 
        long startLine, 
        boolean hasPrev, 
        boolean hasNext, 
        String statusText, 
        String fileName
    ) {}

    // --- NEW: Test File Generation Routine ---
    public static void generateTestFile(double sizeInGb) throws IOException {
        long totalBytesTarget = (long) (sizeInGb * 1024L * 1024L * 1024L);
        File targetFile = new File(String.format("bearit_test_file_%.2fGB.txt", sizeInGb));
        
        System.out.println("Generating test file: " + targetFile.getAbsolutePath());
        System.out.println("Target size: " + sizeInGb + " GB...");

        String recurringLine = "Bearit Test String. Line padding block designed to generate volume safely and efficiently. ";
        byte[] lineBytes = recurringLine.getBytes(StandardCharsets.UTF_8);

        try (FileChannel channel = FileChannel.open(targetFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.allocate(CHUNK_SIZE);
            long bytesWritten = 0;
            int lastPrintedProgress = -1;
            int lineCnt = 1;
            while (bytesWritten < totalBytesTarget) {
                buffer.clear();
                while (buffer.remaining() > lineBytes.length && bytesWritten + buffer.position() < totalBytesTarget) {
                    buffer.put(lineBytes);
                    String lineNumStr = String.valueOf(lineCnt++) + "\n";
                    byte[] lineNumBytes = lineNumStr.getBytes(StandardCharsets.UTF_8);
                    buffer.put(lineNumBytes);
                }
                buffer.flip();
                bytesWritten += channel.write(buffer);

                int progressPercent = (int) ((bytesWritten * 100L) / totalBytesTarget);
                if (progressPercent > lastPrintedProgress) {
                    lastPrintedProgress = progressPercent;
                    System.out.print("\rProgress: " + progressPercent + "%");
                }
            }
        }
        System.out.println("\nGeneration complete! File is ready for testing.");
    }
    // -----------------------------------------

    public void setNewFile() {
        this.currentFile = null;
        this.currentChunkStartByte = 0;
        this.currentChunkLoadedSize = 0;
    }

    public void setFile(File file) {
        this.currentFile = file;
        this.currentChunkStartByte = 0;
        this.currentChunkLoadedSize = 0;
    }

    public boolean hasFile() {
        return currentFile != null;
    }

    public void setCurrentFile(File file) {
        this.currentFile = file;
    }

    public ChunkState loadCurrentChunk() throws IOException {
        if (currentFile == null) {
            return new ChunkState("", 1, false, false, "New file creation mode.", "Untitled");
        }

        Path path = currentFile.toPath();
        long totalFileSize = Files.size(path);
        long bytesToRead = Math.min(CHUNK_SIZE, totalFileSize - currentChunkStartByte);

        String content = "";
        if (bytesToRead > 0) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocate((int) bytesToRead);
                channel.position(currentChunkStartByte);
                channel.read(buffer);
                content = new String(buffer.array(), StandardCharsets.UTF_8);
                currentChunkLoadedSize = buffer.array().length;
            }
        } else {
            currentChunkLoadedSize = 0;
        }

        long absoluteStartLine = computeAbsoluteLineOffset(path, currentChunkStartByte);
        boolean hasPrev = currentChunkStartByte > 0;
        boolean hasNext = (currentChunkStartByte + currentChunkLoadedSize) < totalFileSize;

        double displayedMB = (double) (currentChunkStartByte + currentChunkLoadedSize) / (1024 * 1024);
        double totalMB = (double) totalFileSize / (1024 * 1024);
        String statusText = String.format("Offset: %.2f MB / Total File Size: %.2f MB", displayedMB, totalMB);

        return new ChunkState(content, absoluteStartLine, hasPrev, hasNext, statusText, currentFile.getName());
    }

    public ChunkState navigateChunk(int direction) throws IOException {
        if (direction > 0) {
            currentChunkStartByte += currentChunkLoadedSize;
        } else {
            currentChunkStartByte = Math.max(0, currentChunkStartByte - CHUNK_SIZE);
        }
        return loadCurrentChunk();
    }

    public ChunkState saveCurrentChunk(String text) throws IOException {
        if (currentFile == null) {
            throw new IllegalStateException("No file target open to run save processing.");
        }

        Path path = currentFile.toPath();
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        try {
            long totalFileSize = Files.exists(path) ? Files.size(path) : 0;

            try (FileChannel srcChannel = Files.exists(path) ? FileChannel.open(path, StandardOpenOption.READ) : null;
                 FileChannel destChannel = FileChannel.open(tempPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                if (srcChannel != null && currentChunkStartByte > 0) {
                    long pos = 0;
                    while (pos < currentChunkStartByte) {
                        pos += srcChannel.transferTo(pos, currentChunkStartByte - pos, destChannel);
                    }
                }

                destChannel.write(ByteBuffer.wrap(textBytes));

                if (srcChannel != null) {
                    long oldChunkEndByte = currentChunkStartByte + currentChunkLoadedSize;
                    long pos = oldChunkEndByte;
                    while (pos < totalFileSize) {
                        pos += srcChannel.transferTo(pos, totalFileSize - pos, destChannel);
                    }
                }
            }

            try {
                Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ioEx) {
                Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            currentChunkLoadedSize = textBytes.length;
        } finally {
            Files.deleteIfExists(tempPath);
        }

        return loadCurrentChunk();
    }

    private long computeAbsoluteLineOffset(Path path, long endOffset) throws IOException {
        if (endOffset <= 0) return 1;
        long lineCounter = 1;

        try (InputStream in = Files.newInputStream(path);
             BufferedInputStream bin = new BufferedInputStream(in)) {
            byte[] streamBuffer = new byte[16384];
            long overallBytesRead = 0;
            int bytesRead;

            while ((bytesRead = bin.read(streamBuffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    if (overallBytesRead + i >= endOffset) {
                        return lineCounter;
                    }
                    if (streamBuffer[i] == '\n') {
                        lineCounter++;
                    }
                }
                overallBytesRead += bytesRead;
            }
        }
        return lineCounter;
    }
}