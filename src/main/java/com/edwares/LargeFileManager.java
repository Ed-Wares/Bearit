package com.edwares;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;

public class LargeFileManager {
    private static final int CHUNK_SIZE = 25 * 1024 * 1024; // 25MB Base Target

    private File currentFile;
    private int currentChunkIndex = 0;
    private int totalChunks = 1;
    private long totalFileSize = 0;

    private final Map<Integer, File> dirtyChunks = new ConcurrentHashMap<>();
    
    // Asynchronous Preloading Architecture
    private final ExecutorService preloader = Executors.newSingleThreadExecutor();
    private final Map<Integer, ChunkState> preloadCache = new ConcurrentHashMap<>();

    public record ChunkState(
        String content, 
        long startLine, 
        int chunkIndex,
        int totalChunks,
        boolean hasPrev, 
        boolean hasNext, 
        String statusText, 
        String fileName
    ) {}


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
        this.currentChunkIndex = 0;
        this.totalChunks = 1;
        this.totalFileSize = 0;
        clearDirtyChunks();
        preloadCache.clear();
    }

    public void setFile(File file) {
        this.currentFile = file;
        this.currentChunkIndex = 0;
        clearDirtyChunks();
        preloadCache.clear();
        updateFileMetrics();
    }

    public boolean hasFile() {
        return currentFile != null;
    }

    public void setCurrentFile(File file) {
        this.currentFile = file;
    }

    public int getTotalChunks() {
        return Math.max(totalChunks, dirtyChunks.keySet().stream().max(Integer::compare).orElse(0) + 1);
    }

    private void updateFileMetrics() {
        if (currentFile != null && currentFile.exists()) {
            totalFileSize = currentFile.length();
            totalChunks = (int) Math.ceil((double) totalFileSize / CHUNK_SIZE);
            if (totalChunks == 0) totalChunks = 1;
        }
    }

    public void commitCurrentChunk(String text) throws IOException {
        File tempFile = Files.createTempFile("bearit_chunk_" + currentChunkIndex + "_", ".tmp").toFile();
        tempFile.deleteOnExit();
        Files.writeString(tempFile.toPath(), text, StandardCharsets.UTF_8);
        dirtyChunks.put(currentChunkIndex, tempFile);
        preloadCache.remove(currentChunkIndex); // Invalidate cache on edit
    }

    public ChunkState navigateToIndex(int index) throws IOException {
        int virtualTotalChunks = getTotalChunks();
        if (index < 0) index = 0;
        if (index >= virtualTotalChunks) index = virtualTotalChunks - 1;
        
        currentChunkIndex = index;
        ChunkState state = loadCurrentChunk();
        
        // Trigger background preloading for adjacent chunks
        final int targetNext = index + 1;
        final int targetPrev = index - 1;
        preloader.submit(() -> preloadChunk(targetNext));
        preloader.submit(() -> preloadChunk(targetPrev));
        
        // Cleanup distant cache objects to maintain strict memory footprint
        preloadCache.keySet().removeIf(key -> Math.abs(key - currentChunkIndex) > 1);
        
        return state;
    }

    private void preloadChunk(int index) {
        if (index < 0 || index >= getTotalChunks() || preloadCache.containsKey(index)) return;
        try {
            preloadCache.put(index, generateChunkState(index));
        } catch (IOException e) {
            // Background read failure silently ignored, will retry on synchronous foreground request
        }
    }

    public ChunkState loadCurrentChunk() throws IOException {
        if (preloadCache.containsKey(currentChunkIndex)) {
            return preloadCache.get(currentChunkIndex);
        }
        return generateChunkState(currentChunkIndex);
    }

    private ChunkState generateChunkState(int index) throws IOException {
        if (currentFile == null && dirtyChunks.isEmpty()) {
            return new ChunkState("", 1, index, 1, false, false, "New file creation mode.", "Untitled");
        }

        String content = "";
        
        if (dirtyChunks.containsKey(index)) {
            content = Files.readString(dirtyChunks.get(index).toPath(), StandardCharsets.UTF_8);
        } else if (currentFile != null && currentFile.exists()) {
            long[] boundaries = getChunkBoundaries(index);
            long bytesToRead = boundaries[1] - boundaries[0];

            if (bytesToRead > 0) {
                try (FileChannel channel = FileChannel.open(currentFile.toPath(), StandardOpenOption.READ)) {
                    ByteBuffer buffer = ByteBuffer.allocate((int) bytesToRead);
                    channel.position(boundaries[0]);
                    channel.read(buffer);
                    content = new String(buffer.array(), StandardCharsets.UTF_8);
                }
            }
        }

        long absoluteStartLine = computeAbsoluteLineOffset(index);
        int virtualTotalChunks = getTotalChunks();
        
        boolean hasPrev = index > 0;
        boolean hasNext = index < virtualTotalChunks - 1;

        String dirtyIndicator = dirtyChunks.isEmpty() ? "" : " [Unsaved Edits Pending]";
        String statusText = String.format("Chunk %d of %d %s", index + 1, virtualTotalChunks, dirtyIndicator);
        String fName = currentFile == null ? "Untitled" : currentFile.getName();

        return new ChunkState(content, absoluteStartLine, index, virtualTotalChunks, hasPrev, hasNext, statusText, fName);
    }

    // --- Dynamic Line Boundary Calculation ---
    private long[] getChunkBoundaries(int index) throws IOException {
        if (currentFile == null || !currentFile.exists()) return new long[]{0, 0};
        
        long theoreticalStart = (long) index * CHUNK_SIZE;
        long actualStart = findLineStartBoundary(theoreticalStart);
        
        long theoreticalEnd = (long) (index + 1) * CHUNK_SIZE;
        long actualEnd = theoreticalEnd >= totalFileSize ? totalFileSize : findLineStartBoundary(theoreticalEnd);
        
        return new long[]{actualStart, actualEnd};
    }

    private long findLineStartBoundary(long offset) throws IOException {
        if (offset <= 0) return 0;
        if (offset >= totalFileSize) return totalFileSize;
        
        try (FileChannel channel = FileChannel.open(currentFile.toPath(), StandardOpenOption.READ)) {
            // Check if the previous byte was a newline
            ByteBuffer checkBuffer = ByteBuffer.allocate(1);
            channel.position(offset - 1);
            channel.read(checkBuffer);
            checkBuffer.flip();
            if (checkBuffer.get() == '\n') return offset;

            // Otherwise, scan forward for the next newline to prevent fracturing lines
            ByteBuffer scanBuf = ByteBuffer.allocate(4096);
            channel.position(offset);
            while (channel.read(scanBuf) > 0) {
                scanBuf.flip();
                for (int i = 0; i < scanBuf.limit(); i++) {
                    if (scanBuf.get(i) == '\n') {
                        return offset + i + 1; 
                    }
                }
                offset += scanBuf.limit();
                scanBuf.clear();
            }
        }
        return totalFileSize;
    }

    public ChunkState saveAll(String currentText) throws IOException {
        if (currentFile == null) throw new IllegalStateException("No valid target file to apply save operation.");

        commitCurrentChunk(currentText);

        Path path = currentFile.toPath();
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        int virtualTotalChunks = getTotalChunks();

        try (FileChannel destChannel = FileChannel.open(tempPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             FileChannel srcChannel = Files.exists(path) ? FileChannel.open(path, StandardOpenOption.READ) : null) {

            for (int i = 0; i < virtualTotalChunks; i++) {
                if (dirtyChunks.containsKey(i)) {
                    byte[] bytes = Files.readAllBytes(dirtyChunks.get(i).toPath());
                    destChannel.write(ByteBuffer.wrap(bytes));
                } else if (srcChannel != null) {
                    long[] boundaries = getChunkBoundaries(i);
                    long bytesToTransfer = boundaries[1] - boundaries[0];
                    if (bytesToTransfer > 0) {
                        srcChannel.transferTo(boundaries[0], bytesToTransfer, destChannel);
                    }
                }
            }
        }

        try {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ioEx) {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        }

        clearDirtyChunks();
        updateFileMetrics();
        return loadCurrentChunk();
    }

    private void clearDirtyChunks() {
        dirtyChunks.values().forEach(File::delete);
        dirtyChunks.clear();
    }

    private long computeAbsoluteLineOffset(int targetIndex) throws IOException {
        if (targetIndex == 0) return 1;
        long lineCounter = 1;

        for (int i = 0; i < targetIndex; i++) {
            if (dirtyChunks.containsKey(i)) {
                lineCounter += countLinesInStream(new FileInputStream(dirtyChunks.get(i)));
            } else if (currentFile != null && currentFile.exists()) {
                long[] boundaries = getChunkBoundaries(i);
                long bytesToRead = boundaries[1] - boundaries[0];
                if (bytesToRead > 0) {
                    try (FileChannel channel = FileChannel.open(currentFile.toPath(), StandardOpenOption.READ)) {
                        channel.position(boundaries[0]);
                        InputStream stream = java.nio.channels.Channels.newInputStream(channel);
                        lineCounter += countLinesInLimitedStream(stream, bytesToRead);
                    }
                }
            }
        }
        return lineCounter;
    }

    private long countLinesInStream(InputStream in) throws IOException {
        long count = 0;
        try (BufferedInputStream bin = new BufferedInputStream(in)) {
            byte[] c = new byte[16384];
            int readChars;
            while ((readChars = bin.read(c)) != -1) {
                for (int i = 0; i < readChars; ++i) {
                    if (c[i] == '\n') ++count;
                }
            }
        }
        return count;
    }

    private long countLinesInLimitedStream(InputStream in, long limit) throws IOException {
        long count = 0;
        try (BufferedInputStream bin = new BufferedInputStream(in)) {
            byte[] c = new byte[16384];
            long bytesReadTotal = 0;
            int readChars;
            while (bytesReadTotal < limit && (readChars = bin.read(c, 0, (int) Math.min(c.length, limit - bytesReadTotal))) != -1) {
                for (int i = 0; i < readChars; ++i) {
                    if (c[i] == '\n') ++count;
                }
                bytesReadTotal += readChars;
            }
        }
        return count;
    }
}