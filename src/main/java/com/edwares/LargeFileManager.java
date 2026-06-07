package com.edwares;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class LargeFileManager {
    private static final int CHUNK_SIZE = 25 * 1024 * 1024; 
    private static final int PREVIEW_SIZE = 10 * 1024; 

    private File currentFile;
    private File pendingSaveAsFile = null; //Tracks the destination for Save As
    
    private int currentChunkIndex = 0;
    private int totalChunks = 1;
    private long totalFileSize = 0;
    private boolean isBinaryMode = false;

    private final Map<Integer, File> dirtyChunks = new ConcurrentHashMap<>();
    private final ExecutorService preloader = Executors.newSingleThreadExecutor();
    private final Map<Integer, ChunkState> preloadCache = new ConcurrentHashMap<>();

    // --- Index Caches ---
    private final Map<Integer, long[]> boundaryCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> previewCache = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lineOffsetCache = new ConcurrentHashMap<>();    
    private final Map<Integer, Long> chunkLineDeltas = new ConcurrentHashMap<>(); // ADD THIS

    public record ChunkState(
        String content, 
        long startLine, 
        long startOffset,
        int chunkIndex,
        int totalChunks,
        boolean hasPrev, 
        boolean hasNext, 
        String statusText, 
        String fileName,
        boolean isPreview
    ) {}

    /**
     * Dynamic Fast Soft-Wrapper for Long Lines:
     * Scans the payload and injects \u200B\n (Zero-Width Space + Newline) 
     * if a continuous line exceeds the user-defined character maximum.
     */
    public static String forceWrapLongLinesDynamic(String input, int maxLength, int initialOffset) {
        if (input == null || input.isEmpty() || maxLength <= 0) return input;
        
        StringBuilder sb = new StringBuilder(input.length() + (input.length() / maxLength) * 3);
        int currentLineLength = initialOffset;
        
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\n') {
                currentLineLength = 0;
                sb.append(c);
            } else {
                if (currentLineLength >= maxLength) {
                    sb.append('\u200B').append('\n');
                    currentLineLength = 0;
                }
                sb.append(c);
                currentLineLength++;
            }
        }
        return sb.toString();
    }

    public static void generateTestFile(double sizeInGb) throws IOException {
        File defaultDest = new File(String.format(java.util.Locale.US, "bearit_test_file_%.2fGB.txt", sizeInGb));
        generateTestFile(defaultDest, sizeInGb, null);
    }

    // UI User-initiated usage with progress callback
    public static void generateTestFile(File targetFile, double sizeInGb, BiConsumer<Long, Long> progressCallback) throws IOException {
        long totalBytesTarget = (long) (sizeInGb * 1024L * 1024L * 1024L);
        
        System.out.println("Generating test file: " + targetFile.getAbsolutePath());
        System.out.println("Target size: " + sizeInGb + " GB...");

        String recurringLine = "Bearit Test String. Line padding block designed to generate volume safely and efficiently. ";
        byte[] lineBytes = recurringLine.getBytes(StandardCharsets.UTF_8);

        try (FileChannel channel = FileChannel.open(targetFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.allocate(CHUNK_SIZE);
            long bytesWritten = 0;
            int lastPrintedProgress = -1;
            int lineCnt = 1;
            long lastReportTime = 0;
            while (bytesWritten < totalBytesTarget) {
                buffer.clear();
                while (buffer.remaining() > lineBytes.length + 20 && bytesWritten + buffer.position() < totalBytesTarget) {
                    buffer.put(lineBytes);
                    String lineNumStr = String.valueOf(lineCnt++) + "\n";
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

    public void setNewFile() {
        this.currentFile = null;
        this.pendingSaveAsFile = null;
        this.currentChunkIndex = 0;
        this.totalChunks = 1;
        this.totalFileSize = 0;
        clearDirtyChunks();
        preloadCache.clear();
        clearIndexCaches(); 
    }

    public void setFile(File file) {
        this.currentFile = file;
        this.pendingSaveAsFile = null;
        this.currentChunkIndex = 0;
        clearDirtyChunks();
        preloadCache.clear();
        clearIndexCaches();
        updateFileMetrics();
    }

    public boolean hasFile() { return currentFile != null || pendingSaveAsFile != null; }
    
    public void setCurrentFile(File file) { 
        this.pendingSaveAsFile = file; 
    }

    public void setBinaryMode(boolean isBinary) {
        this.isBinaryMode = isBinary;
        clearIndexCaches(); // Re-calculate boundaries strictly mathematically
    }

    public int getTotalChunks() {
        return Math.max(totalChunks, dirtyChunks.keySet().stream().max(Integer::compare).orElse(0) + 1);
    }

    public long getExactLineOffset(int index) {
        if (index == 0) return 1;
        return lineOffsetCache.getOrDefault(index, -1L);
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
        preloadCache.remove(currentChunkIndex); 
        
        // --- Calculate Line Delta for instant UI updates ---
        if (lineOffsetCache.containsKey(currentChunkIndex) && lineOffsetCache.containsKey(currentChunkIndex + 1)) {
            long originalLines = lineOffsetCache.get(currentChunkIndex + 1) - lineOffsetCache.get(currentChunkIndex);
            // Ultra-fast character stream filter to count newlines
            long newLines = text.chars().filter(ch -> ch == '\n').count();
            chunkLineDeltas.put(currentChunkIndex, newLines - originalLines);
        }
    }

    private void clearIndexCaches() {
        boundaryCache.clear();
        previewCache.clear();
        lineOffsetCache.clear();
        chunkLineDeltas.clear();
    }

    public void buildIndexCacheAsync(java.util.function.Consumer<Integer> onChunkIndexed) {
        if (currentFile == null || !currentFile.exists()) return;
        
        boundaryCache.clear();
        previewCache.clear();
        lineOffsetCache.clear();
        
        new Thread(() -> {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(currentFile, "r")) {
                int totalChunks = getTotalChunks();
                long currentGlobalLine = 1;
                
                for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
                    if (Thread.currentThread().isInterrupted()) break;
                    
                    long[] bounds = getChunkBoundaries(chunkIdx);
                    long start = bounds[0];
                    long end = bounds[1];
                    
                    lineOffsetCache.put(chunkIdx, currentGlobalLine);
                    
                    long previewEnd = Math.min(end, start + 8192); 
                    byte[] previewBytes = new byte[(int)(previewEnd - start)];
                    raf.seek(start);
                    raf.readFully(previewBytes);
                    
                    String previewStr = new String(previewBytes, java.nio.charset.StandardCharsets.UTF_8);
                    if (previewEnd < end) {
                        previewStr += "\n\n... [Scrolling Preview: Release scrollbar to render full chunk] ...";
                    }
                    previewCache.put(chunkIdx, previewStr);
                    
                    raf.seek(start);
                    byte[] buffer = new byte[8192];
                    long bytesRead = 0;
                    long chunkLen = end - start;
                    long linesInChunk = 0;
                    
                    while (bytesRead < chunkLen) {
                        if (Thread.currentThread().isInterrupted()) return;
                        int toRead = (int) Math.min(buffer.length, chunkLen - bytesRead);
                        int read = raf.read(buffer, 0, toRead);
                        if (read == -1) break;
                        for (int i = 0; i < read; i++) {
                            if (buffer[i] == '\n') linesInChunk++;
                        }
                        bytesRead += read;
                    }
                    
                    currentGlobalLine += linesInChunk;
                    
                    final int completedChunk = chunkIdx;
                    if (onChunkIndexed != null) {
                        javax.swing.SwingUtilities.invokeLater(() -> onChunkIndexed.accept(completedChunk));
                    }
                }
            } catch (Exception e) {
                System.err.println("Background file indexer failed: " + e.getMessage());
            }
        }).start();
    }

    public ChunkState navigateToIndex(int index, boolean requestPreview) throws IOException {
        int virtualTotalChunks = getTotalChunks();
        if (index < 0) index = 0;
        if (index >= virtualTotalChunks) index = virtualTotalChunks - 1;
        
        currentChunkIndex = index;
        ChunkState state = loadCurrentChunk(requestPreview);
        
        final int targetNext = index + 1;
        final int targetPrev = index - 1;
        preloader.submit(() -> preloadChunk(targetNext));
        preloader.submit(() -> preloadChunk(targetPrev));
        
        preloadCache.keySet().removeIf(key -> Math.abs(key - currentChunkIndex) > 1);
        
        return state;
    }

    private void preloadChunk(int index) {
        if (index < 0 || index >= getTotalChunks() || preloadCache.containsKey(index)) return;
        try {
            preloadCache.put(index, generateChunkState(index, false));
        } catch (IOException e) { }
    }

    public ChunkState loadCurrentChunk(boolean requestPreview) throws IOException {
        if (!requestPreview && preloadCache.containsKey(currentChunkIndex)) {
            return preloadCache.get(currentChunkIndex);
        }
        return generateChunkState(currentChunkIndex, requestPreview);
    }

    public String getChunkContent(int index) throws IOException {
        if (dirtyChunks.containsKey(index)) {
            return Files.readString(dirtyChunks.get(index).toPath(), StandardCharsets.UTF_8);
        } else if (currentFile != null && currentFile.exists()) {
            long[] boundaries = getChunkBoundaries(index);
            long bytesToRead = boundaries[1] - boundaries[0];
            if (bytesToRead > 0) {
                try (FileChannel channel = FileChannel.open(currentFile.toPath(), StandardOpenOption.READ)) {
                    ByteBuffer buffer = ByteBuffer.allocate((int) bytesToRead);
                    channel.position(boundaries[0]);
                    channel.read(buffer);
                    return new String(buffer.array(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    public long countGlobalMatches(String target) throws IOException {
        long count = 0;
        int virtualTotalChunks = getTotalChunks();
        for (int i = 0; i < virtualTotalChunks; i++) {
            String chunkContent = getChunkContent(i);
            int index = 0;
            while ((index = chunkContent.indexOf(target, index)) != -1) {
                count++;
                index += target.length();
            }
        }
        return count;
    }

    public int replaceAllGlobal(Pattern pattern, String replacement, Consumer<Integer> progressPublisher, Supplier<Boolean> isCancelled) throws IOException {
        int totalMatches = 0;
        if (currentFile == null) return totalMatches;
        
        Path path = currentFile.toPath();
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        int virtualTotalChunks = getTotalChunks();

        try (FileChannel destChannel = FileChannel.open(tempPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < virtualTotalChunks; i++) {
                
                // Check if the user closed the dialog before processing the chunk
                if (isCancelled != null && isCancelled.get()) {
                    destChannel.close();
                    Files.deleteIfExists(tempPath); // Clean up the aborted temp file
                    return 0; 
                }

                String chunkContent = getChunkContent(i);
                Matcher m = pattern.matcher(chunkContent);
                
                // StringBuffer is required here for broad Java version compatibility with Matcher
                StringBuffer sb = new StringBuffer(); 
                int chunkMatches = 0;
                
                // Perform Regex Replacement safely while checking for cancellation mid-chunk
                while (m.find()) {
                    if (isCancelled != null && isCancelled.get()) {
                        destChannel.close();
                        Files.deleteIfExists(tempPath);
                        return 0;
                    }
                    m.appendReplacement(sb, replacement);
                    chunkMatches++;
                }
                m.appendTail(sb);
                
                totalMatches += chunkMatches;

                // Write the replaced text chunk to the temporary file
                destChannel.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
                
                // Publish Progress back to the UI
                if (progressPublisher != null) {
                    int percent = (int) (((i + 1) * 100.0) / virtualTotalChunks);
                    progressPublisher.accept(percent);
                }
            }
        }

        // If we reach here, the job finished completely without cancellation. Commit the changes to disk.
        try {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ioEx) {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        }

        clearDirtyChunks();
        clearIndexCaches();
        updateFileMetrics();
        return totalMatches;
    }

    public int getChunkForLine(long targetLine) throws IOException {
        if (targetLine <= 1) return 0;
        long currentLine = 1;
        int virtualTotalChunks = getTotalChunks();
        
        for (int i = 0; i < virtualTotalChunks; i++) {
            long linesInChunk = 0;
            if (dirtyChunks.containsKey(i)) {
                linesInChunk = countLinesInStream(new FileInputStream(dirtyChunks.get(i)));
            } else if (currentFile != null && currentFile.exists()) {
                long[] boundaries = getChunkBoundaries(i);
                long bytesToRead = boundaries[1] - boundaries[0];
                if (bytesToRead > 0) {
                    try (FileChannel channel = FileChannel.open(currentFile.toPath(), StandardOpenOption.READ)) {
                        channel.position(boundaries[0]);
                        InputStream stream = java.nio.channels.Channels.newInputStream(channel);
                        linesInChunk = countLinesInLimitedStream(stream, bytesToRead);
                    }
                }
            }
            
            if (currentLine + linesInChunk > targetLine) {
                return i;
            }
            currentLine += linesInChunk;
        }
        return virtualTotalChunks - 1;
    }

    private ChunkState generateChunkState(int index, boolean isPreview) throws IOException {
        if (currentFile == null && dirtyChunks.isEmpty() && pendingSaveAsFile == null) {
            return new ChunkState("", 1, 0, index, 1, false, false, "New file creation mode.", "Untitled", false);
        }

        String content = "";
        long absoluteStartOffset = 0; 
        
        if (dirtyChunks.containsKey(index)) {
            content = Files.readString(dirtyChunks.get(index).toPath(), StandardCharsets.UTF_8);
            isPreview = false; 
            absoluteStartOffset = getChunkBoundaries(index)[0];
        } else if (currentFile != null && currentFile.exists()) {
            long[] boundaries = getChunkBoundaries(index);
            absoluteStartOffset = boundaries[0]; 
            long bytesToRead = boundaries[1] - boundaries[0];

            if (isPreview && bytesToRead > PREVIEW_SIZE) {
                bytesToRead = PREVIEW_SIZE;
            }

            if (bytesToRead > 0) {
                try (FileChannel channel = FileChannel.open(currentFile.toPath(), StandardOpenOption.READ)) {
                    ByteBuffer buffer = ByteBuffer.allocate((int) bytesToRead);
                    channel.position(boundaries[0]);
                    channel.read(buffer);
                    content = new String(buffer.array(), StandardCharsets.UTF_8);
                    
                    if (isPreview && bytesToRead == PREVIEW_SIZE) {
                        content += "\n\n... [Scrolling Preview: Release scrollbar to render full 25MB chunk] ...";
                    }
                }
            }
        }

        long absoluteStartLine = computeAbsoluteLineOffsetLazy(index);
        int virtualTotalChunks = getTotalChunks();
        boolean hasPrev = index > 0;
        boolean hasNext = index < virtualTotalChunks - 1;

        String dirtyIndicator = dirtyChunks.isEmpty() ? "" : " [Unsaved Edits Pending]";
        String statusText = String.format("Chunk %d of %d %s", index + 1, virtualTotalChunks, dirtyIndicator);
        
        File displayFile = pendingSaveAsFile != null ? pendingSaveAsFile : currentFile;
        String fName = displayFile == null ? "Untitled" : displayFile.getName();

        return new ChunkState(content, absoluteStartLine, absoluteStartOffset, index, virtualTotalChunks, hasPrev, hasNext, statusText, fName, isPreview);
    }

    // --- Ultra-fast buffered scanner to eliminate byte-by-byte IO overhead ---
    private long fastFindNewline(java.io.RandomAccessFile raf, long start, long limit) throws IOException {
        raf.seek(start);
        byte[] buf = new byte[16384]; // 16KB Buffer
        long current = start;
        while (current < limit) {
            if (Thread.currentThread().isInterrupted()) break;
            int toRead = (int) Math.min(buf.length, limit - current);
            int read = raf.read(buf, 0, toRead);
            if (read == -1) break;
            for (int i = 0; i < read; i++) {
                if (buf[i] == '\n') return current + i + 1;
            }
            current += read;
        }
        return current; // If no newline is found, execute the hard-cut Failsafe
    }

    public long[] getChunkBoundaries(int index) throws IOException {
        if (boundaryCache.containsKey(index)) return boundaryCache.get(index);
        if (currentFile == null) return new long[]{0, 0};

        long start = (long) index * CHUNK_SIZE; 
        long end = Math.min(start + CHUNK_SIZE, currentFile.length());

        // ONLY scan for newlines if we are in Text Mode
        if (!isBinaryMode) {
            final long MAX_SCAN_DISTANCE = 500 * 1024; // 500 KB Failsafe
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(currentFile, "r")) {
                if (start > 0 && start < currentFile.length()) {
                    start = fastFindNewline(raf, start, Math.min(start + MAX_SCAN_DISTANCE, currentFile.length()));
                }
                if (end < currentFile.length()) {
                    end = fastFindNewline(raf, end, Math.min(end + MAX_SCAN_DISTANCE, currentFile.length()));
                }
            }
        }
        
        long[] bounds = new long[]{start, end};
        boundaryCache.put(index, bounds);
        return bounds;
    }

    public byte[] getChunkBytes(int index) throws IOException {
        if (dirtyChunks.containsKey(index)) {
            return Files.readAllBytes(dirtyChunks.get(index).toPath());
        } else if (currentFile != null && currentFile.exists()) {
            long[] boundaries = getChunkBoundaries(index);
            long bytesToRead = boundaries[1] - boundaries[0];
            if (bytesToRead > 0) {
                try (FileChannel channel = FileChannel.open(currentFile.toPath(), StandardOpenOption.READ)) {
                    ByteBuffer buffer = ByteBuffer.allocate((int) bytesToRead);
                    channel.position(boundaries[0]);
                    channel.read(buffer);
                    return buffer.array();
                }
            }
        }
        return new byte[0];
    }

    public void commitCurrentChunkBytes(int index, byte[] data) throws IOException {
        File tempFile = Files.createTempFile("bearit_chunk_" + index + "_", ".tmp").toFile();
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), data);
        dirtyChunks.put(index, tempFile);
        preloadCache.remove(index); 
    }    

    // --- Accepts BiConsumer callback to provide real-time save progress ---
    public ChunkState saveAll(String currentText, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        File destFile = (pendingSaveAsFile != null) ? pendingSaveAsFile : currentFile;
        if (destFile == null) throw new IllegalStateException("No valid target file to apply save operation.");
        
        commitCurrentChunk(currentText);

        Path destPath = destFile.toPath();
        Path tempPath = destPath.resolveSibling(destPath.getFileName() + ".tmp");
        int virtualTotalChunks = getTotalChunks();

        try (FileChannel destChannel = FileChannel.open(tempPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             FileChannel srcChannel = (currentFile != null && Files.exists(currentFile.toPath())) ? FileChannel.open(currentFile.toPath(), StandardOpenOption.READ) : null) {
            
            for (int i = 0; i < virtualTotalChunks; i++) {
                // Publish Progress
                if (progressCallback != null) {
                    int percent = (int) (((i + 1) * 100.0) / virtualTotalChunks);
                    progressCallback.accept(i + 1, percent);
                }
                
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
            Files.move(tempPath, destPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ioEx) {
            Files.move(tempPath, destPath, StandardCopyOption.REPLACE_EXISTING);
        }

        this.currentFile = destFile;
        this.pendingSaveAsFile = null;

        clearDirtyChunks();
        clearIndexCaches();
        updateFileMetrics();
        return loadCurrentChunk(false); 
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

    private long computeAbsoluteLineOffsetLazy(int index) {
        if (index == 0) return 1;
        long baseLine;
        
        if (lineOffsetCache.containsKey(index)) {
            baseLine = lineOffsetCache.get(index);
        } else {
            long estimatedAvgLineLength = 85; 
            baseLine = ((long) index * CHUNK_SIZE) / estimatedAvgLineLength;
        }
        
        // Apply any unsaved deltas from preceding dirty chunks
        for (int i = 0; i < index; i++) {
            baseLine += chunkLineDeltas.getOrDefault(i, 0L);
        }
        
        return baseLine;
    }

    private long countLinesInStream(InputStream in) throws IOException {
        long count = 0;
        try (BufferedInputStream bin = new BufferedInputStream(in)) {
            byte[] c = new byte[16384];
            int readChars;
            while ((readChars = bin.read(c)) != -1) {
                for (int i = 0; i < readChars; ++i) { if (c[i] == '\n') ++count; }
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
                for (int i = 0; i < readChars; ++i) { if (c[i] == '\n') ++count; }
                bytesReadTotal += readChars;
            }
        }
        return count;
    }
}