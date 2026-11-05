package com.edwares;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class LargeFileManager {
    public static int CHUNK_SIZE = 25 * 1024 * 1024; // 25MB chunks
    private static final int PREVIEW_SIZE = 10 * 1024; 

    private File currentFile;
    private File pendingSaveAsFile = null; //Tracks the destination for Save As
    
    private int currentChunkIndex = 0;
    private int totalChunks = 1;
    private long totalFileSize = 0;
    private boolean isBinaryMode = false;
    private String detectedEncoding = "UTF-8";
    private String detectedLineEndings = "N/A";

    public void setDetectedEncodingAndCharset(String enc, Charset charset) {
        this.detectedEncoding = enc;
        this.activeCharset = charset;
    }

    public void setDetectedLineEndings(String le) {
        this.detectedLineEndings = le;
    }

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
     *
     * Optimization: Lazily allocates the StringBuilder only when a line exceeds
     * maxLength (returning the original String with zero allocations otherwise)
     * and appends contiguous character slices in bulk instead of char-by-char.
     */
    public static String forceWrapLongLinesDynamic(String input, int maxLength, int initialOffset) {
        if (input == null || input.isEmpty() || maxLength <= 0) return input;
        
        StringBuilder sb = null;
        int currentLineLength = initialOffset;
        int len = input.length();
        int lastAppend = 0;
        
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            if (c == '\n') {
                currentLineLength = 0;
            } else {
                if (currentLineLength >= maxLength) {
                    if (sb == null) {
                        sb = new StringBuilder(len + (len / maxLength) * 3);
                    }
                    sb.append(input, lastAppend, i);
                    lastAppend = i;
                    sb.append('\u200B').append('\n');
                    currentLineLength = 0;
                }
                currentLineLength++;
            }
        }
        if (sb != null) {
            sb.append(input, lastAppend, len);
            return sb.toString();
        }
        return input;
    }

    public void setNewFile() {
        this.currentFile = null;
        this.pendingSaveAsFile = null;
        this.currentChunkIndex = 0;
        this.totalChunks = 1;
        this.totalFileSize = 0;
        this.isBinaryMode = false;
        this.detectedEncoding = "UTF-8";
        this.detectedLineEndings = "N/A";
        this.activeCharset = StandardCharsets.UTF_8;
        clearDirtyChunks();
        preloadCache.clear();
        clearIndexCaches(); 
    }

    public Map<Integer, File> getDirtyChunksSnapshot() {
        return new ConcurrentHashMap<>(dirtyChunks);
    }

    public void restoreDirtyChunksSnapshot(Map<Integer, File> snapshot) {
        dirtyChunks.clear();
        if (snapshot != null) {
            dirtyChunks.putAll(snapshot);
        }
        updateFileMetrics();
    }

    public void setFile(File file) {
        this.currentFile = file;
        analyzeFileFormat(file);            
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

    public boolean isBinaryMode() {
        return this.isBinaryMode;
    }

    public void setBinaryMode(boolean isBinary) {
        this.isBinaryMode = isBinary;
        if (isBinary) {
            this.activeCharset = StandardCharsets.ISO_8859_1;
        } else {
            this.activeCharset = StandardCharsets.UTF_8;
        }
        clearIndexCaches(); // Re-calculate boundaries strictly mathematically
    }


    private Charset activeCharset = StandardCharsets.UTF_8;

    public String getDetectedEncoding() {
        return isBinaryMode ? "Binary" : detectedEncoding;
    }

    public String getDetectedLineEndings() {
        return isBinaryMode ? "N/A" : detectedLineEndings;
    }

    public Charset getActiveCharset() {
        return activeCharset;
    }

    private void analyzeFileFormat(File file) {
        this.isBinaryMode = false;
        this.detectedEncoding = "UTF-8";
        this.detectedLineEndings = "N/A";
        this.activeCharset = StandardCharsets.UTF_8;

        if (file == null || !file.exists() || file.length() == 0) {
            return;
        }

        int bufferSize = 163840; // 160KB
        try (java.io.InputStream is = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[bufferSize];
            int bytesRead = is.read(buffer);

            if (bytesRead == -1) return;

            boolean hasBom = false;
            // Check BOM
            if (bytesRead >= 3 && (buffer[0] & 0xFF) == 0xEF && (buffer[1] & 0xFF) == 0xBB && (buffer[2] & 0xFF) == 0xBF) {
                detectedEncoding = "UTF-8 BOM";
                activeCharset = StandardCharsets.UTF_8;
                hasBom = true;
            } else if (bytesRead >= 2 && (buffer[0] & 0xFF) == 0xFE && (buffer[1] & 0xFF) == 0xFF) {
                detectedEncoding = "UTF-16 BE";
                activeCharset = StandardCharsets.UTF_16BE;
                hasBom = true;
            } else if (bytesRead >= 2 && (buffer[0] & 0xFF) == 0xFF && (buffer[1] & 0xFF) == 0xFE) {
                detectedEncoding = "UTF-16 LE";
                activeCharset = StandardCharsets.UTF_16LE;
                hasBom = true;
            }

            if (hasBom) {
                isBinaryMode = false;
                String sample = new String(buffer, 0, Math.min(bytesRead, 8192), activeCharset);
                int crlf = 0, lf = 0, cr = 0;
                for (int i = 0; i < sample.length(); i++) {
                    char c = sample.charAt(i);
                    if (c == '\r') {
                        if (i + 1 < sample.length() && sample.charAt(i + 1) == '\n') {
                            crlf++;
                            i++;
                        } else {
                            cr++;
                        }
                    } else if (c == '\n') {
                        lf++;
                    }
                }
                if (crlf >= lf && crlf >= cr && crlf > 0) detectedLineEndings = "CRLF";
                else if (lf >= crlf && lf >= cr && lf > 0) detectedLineEndings = "LF";
                else if (cr > 0) detectedLineEndings = "CR";
                return;
            }

            int controlCount = 0;
            int crCount = 0;
            int lfCount = 0;
            int crlfCount = 0;

            for (int i = 0; i < bytesRead; i++) {
                byte b = buffer[i];

                if (activeCharset == StandardCharsets.UTF_8 && b == 0x00) {
                    isBinaryMode = true;
                    activeCharset = StandardCharsets.ISO_8859_1;
                    return;
                }

                if (b < 0x20 && b != 0x0A && b != 0x0D && b != 0x09) {
                    controlCount++;
                }

                if (b == 0x0D) { // CR
                    if (i + 1 < bytesRead && buffer[i + 1] == 0x0A) { // CRLF
                        crlfCount++;
                        i++; // Skip the LF
                    } else {
                        crCount++;
                    }
                } else if (b == 0x0A) { // LF
                    lfCount++;
                }
            }

            if (bytesRead > 0 && ((double) controlCount / bytesRead) > 0.10) {
                isBinaryMode = true;
                activeCharset = StandardCharsets.ISO_8859_1;
                return;
            }

            boolean isValidUtf8 = true;
            try {
                java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
                decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
                decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                decoder.decode(ByteBuffer.wrap(buffer, 0, bytesRead));
            } catch (java.nio.charset.CharacterCodingException e) {
                isValidUtf8 = false;
            }

            if (!isValidUtf8) {
                detectedEncoding = "ISO-8859-1";
                activeCharset = StandardCharsets.ISO_8859_1;
            }

            if (crlfCount >= lfCount && crlfCount >= crCount && crlfCount > 0) {
                detectedLineEndings = "CRLF";
            } else if (lfCount >= crlfCount && lfCount >= crCount && lfCount > 0) {
                detectedLineEndings = "LF";
            } else if (crCount > 0) {
                detectedLineEndings = "CR";
            }

        } catch (java.io.IOException e) {
            // Default to UTF-8 text mode
        }
    }

    private void reanalyzeLineEndings() {
        if (currentFile == null || !currentFile.exists() || isBinaryMode) return;
        try (java.io.InputStream is = new java.io.FileInputStream(currentFile)) {
            byte[] buffer = new byte[163840];
            int bytesRead = is.read(buffer);
            if (bytesRead == -1) return;

            boolean hasBom = false;
            int start = 0;
            if (bytesRead >= 3 && (buffer[0] & 0xFF) == 0xEF && (buffer[1] & 0xFF) == 0xBB && (buffer[2] & 0xFF) == 0xBF) { hasBom = true; start = 3; }
            else if (bytesRead >= 2 && (buffer[0] & 0xFF) == 0xFE && (buffer[1] & 0xFF) == 0xFF) { hasBom = true; start = 2; }
            else if (bytesRead >= 2 && (buffer[0] & 0xFF) == 0xFF && (buffer[1] & 0xFF) == 0xFE) { hasBom = true; start = 2; }

            if (hasBom) {
                String sample = new String(buffer, start, Math.min(bytesRead - start, 8192), activeCharset);
                int crlf = 0, lf = 0, cr = 0;
                for (int i = 0; i < sample.length(); i++) {
                    char c = sample.charAt(i);
                    if (c == '\r') {
                        if (i + 1 < sample.length() && sample.charAt(i + 1) == '\n') { crlf++; i++; } else { cr++; }
                    } else if (c == '\n') { lf++; }
                }
                if (crlf >= lf && crlf >= cr && crlf > 0) detectedLineEndings = "CRLF";
                else if (lf >= crlf && lf >= cr && lf > 0) detectedLineEndings = "LF";
                else if (cr > 0) detectedLineEndings = "CR";
                else detectedLineEndings = "N/A";
                return;
            }

            int crCount = 0;
            int lfCount = 0;
            int crlfCount = 0;
            for (int i = start; i < bytesRead; i++) {
                byte b = buffer[i];
                if (b == 0x0D) {
                    if (i + 1 < bytesRead && buffer[i + 1] == 0x0A) { crlfCount++; i++; } else { crCount++; }
                } else if (b == 0x0A) { lfCount++; }
            }
            if (crlfCount >= lfCount && crlfCount >= crCount && crlfCount > 0) {
                detectedLineEndings = "CRLF";
            } else if (lfCount >= crlfCount && lfCount >= crCount && lfCount > 0) {
                detectedLineEndings = "LF";
            } else if (crCount > 0) {
                detectedLineEndings = "CR";
            } else {
                detectedLineEndings = "N/A";
            }
        } catch (java.io.IOException e) {}
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
        
        try (java.io.OutputStream out = new java.io.FileOutputStream(tempFile)) {
            if (currentChunkIndex == 0) {
                if ("UTF-8 BOM".equals(detectedEncoding)) {
                    out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                } else if ("UTF-16 BE".equals(detectedEncoding)) {
                    out.write(new byte[]{(byte) 0xFE, (byte) 0xFF});
                } else if ("UTF-16 LE".equals(detectedEncoding)) {
                    out.write(new byte[]{(byte) 0xFF, (byte) 0xFE});
                }
            }
            out.write(text.getBytes(getActiveCharset()));
        }
        
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

    public void clearCachesForEncodingChange() {
        preloadCache.clear();
        clearIndexCaches();
    }

    private Thread indexerThread = null;

    public void stopIndexer() {
        if (indexerThread != null && indexerThread.isAlive()) {
            indexerThread.interrupt();
        }
    }

    public void buildIndexCacheAsync(java.util.function.Consumer<Integer> onChunkIndexed) {
        if (currentFile == null || !currentFile.exists()) return;
        
        stopIndexer();
        
        boundaryCache.clear();
        previewCache.clear();
        lineOffsetCache.clear();
        
        // Optimization: Reuse a single RandomAccessFile and 256 KB read buffer across all chunks,
        // populate previewCache from the first read pass without extra seeks/allocations,
        // coalesce EDT progress callbacks via uiUpdatePending so the UI thread is never flooded,
        // and run at slightly lower thread priority so indexing does not starve editor rendering.
        indexerThread = new Thread(() -> {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(currentFile, "r")) {
                int totalChunks = getTotalChunks();
                long currentGlobalLine = 1;
                byte[] buffer = new byte[256 * 1024];
                Charset charset = getActiveCharset();
                java.util.concurrent.atomic.AtomicInteger latestCompletedChunk = new java.util.concurrent.atomic.AtomicInteger(-1);
                java.util.concurrent.atomic.AtomicBoolean uiUpdatePending = new java.util.concurrent.atomic.AtomicBoolean(false);
                
                for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
                    if (Thread.currentThread().isInterrupted()) break;
                    
                    long[] bounds = getChunkBoundaries(chunkIdx, raf);
                    long start = bounds[0];
                    long end = bounds[1];
                    
                    lineOffsetCache.put(chunkIdx, currentGlobalLine);
                    
                    raf.seek(start);
                    long bytesRead = 0;
                    long chunkLen = end - start;
                    long linesInChunk = 0;
                    boolean previewBuilt = false;
                    
                    while (bytesRead < chunkLen) {
                        if (Thread.currentThread().isInterrupted()) return;
                        int toRead = (int) Math.min(buffer.length, chunkLen - bytesRead);
                        int read = raf.read(buffer, 0, toRead);
                        if (read == -1) break;
                        if (!previewBuilt) {
                            int previewLen = (int) Math.min(read, 8192);
                            String previewStr = new String(buffer, 0, previewLen, charset);
                            if (start + previewLen < end) {
                                previewStr += "\n\n... [Scrolling Preview: Release scrollbar to render full chunk] ...";
                            }
                            previewCache.put(chunkIdx, previewStr);
                            previewBuilt = true;
                        }
                        for (int i = 0; i < read; i++) {
                            if (buffer[i] == '\n') linesInChunk++;
                        }
                        bytesRead += read;
                    }
                    if (!previewBuilt) {
                        previewCache.put(chunkIdx, "");
                    }
                    
                    currentGlobalLine += linesInChunk;
                    
                    final int completedChunk = chunkIdx;
                    if (onChunkIndexed != null) {
                        latestCompletedChunk.set(completedChunk);
                        if (uiUpdatePending.compareAndSet(false, true)) {
                            javax.swing.SwingUtilities.invokeLater(() -> {
                                uiUpdatePending.set(false);
                                int latest = latestCompletedChunk.get();
                                if (latest >= 0) {
                                    onChunkIndexed.accept(latest);
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Background file indexer failed: " + e.getMessage());
            }
        }, "Bearit-LineIndexer");
        indexerThread.setDaemon(true);
        indexerThread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        indexerThread.start();
    }

    public ChunkState navigateToIndex(int index, boolean requestPreview) throws IOException {
        int virtualTotalChunks = getTotalChunks();
        if (index < 0) index = 0;
        if (index >= virtualTotalChunks) index = virtualTotalChunks - 1;
        
        currentChunkIndex = index;
        ChunkState state = loadCurrentChunk(requestPreview);
        
        // Optimization: Skip queueing full 25 MB neighbor-chunk preloads while dragging
        // the global scrollbar in preview mode to avoid saturating disk I/O and heap.
        if (!requestPreview) {
            final int targetNext = index + 1;
            final int targetPrev = index - 1;
            preloader.submit(() -> preloadChunk(targetNext));
            preloader.submit(() -> preloadChunk(targetPrev));
            preloadCache.keySet().removeIf(key -> Math.abs(key - currentChunkIndex) > 1);
        }
        
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
            return Files.readString(dirtyChunks.get(index).toPath(), getActiveCharset());
        } else if (currentFile != null && currentFile.exists()) {
            long[] boundaries = getChunkBoundaries(index);
            long bytesToRead = boundaries[1] - boundaries[0];
            if (bytesToRead > 0) {
                try (FileChannel channel = FileChannel.open(currentFile.toPath(), StandardOpenOption.READ)) {
                    ByteBuffer buffer = ByteBuffer.allocate((int) bytesToRead);
                    channel.position(boundaries[0]);
                    channel.read(buffer);
                    return new String(buffer.array(), getActiveCharset());
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

    public static boolean isMatchInColumnRange(String content, int matchStart, int matchEnd, int minCol, int maxCol) {
        if (minCol < 0 || maxCol < 0) return true; // Disabled
        
        int lineStart = content.lastIndexOf('\n', matchStart - 1) + 1;
        int startCol = matchStart - lineStart;
        
        int lineStartForEnd = content.lastIndexOf('\n', matchEnd - 1) + 1;
        int endCol = matchEnd - lineStartForEnd;
        
        return startCol >= minCol && endCol <= maxCol;
    }

    public int replaceAllGlobal(Pattern pattern, String replacement, int minCol, int maxCol, Consumer<Integer> progressPublisher, Supplier<Boolean> isCancelled) throws IOException {
        int totalMatches = 0;
        int virtualTotalChunks = getTotalChunks();

        for (int i = 0; i < virtualTotalChunks; i++) {
            if (isCancelled != null && isCancelled.get()) {
                return totalMatches;
            }

            String chunkContent = getChunkContent(i);
            Matcher m = pattern.matcher(chunkContent);
            
            StringBuffer sb = new StringBuffer(); 
            int chunkMatches = 0;
            
            while (m.find()) {
                if (isCancelled != null && isCancelled.get()) {
                    return totalMatches;
                }
                if (isMatchInColumnRange(chunkContent, m.start(), m.end(), minCol, maxCol)) {
                    m.appendReplacement(sb, replacement);
                    chunkMatches++;
                }
            }
            m.appendTail(sb);
            
            if (chunkMatches > 0) {
                totalMatches += chunkMatches;
                File tempFile = File.createTempFile("bearit_chunk_" + i + "_", ".tmp");
                tempFile.deleteOnExit();
                Files.writeString(tempFile.toPath(), sb.toString(), getActiveCharset());
                dirtyChunks.put(i, tempFile);
            }
            
            if (progressPublisher != null) {
                int percent = (int) (((i + 1) * 100.0) / virtualTotalChunks);
                progressPublisher.accept(percent);
            }
        }

        updateFileMetrics();
        return totalMatches;
    }

    public boolean formatGlobal(String formatType, java.util.function.Consumer<Integer> progressPublisher, java.util.function.Supplier<Boolean> isCancelled) throws IOException {
        int virtualTotalChunks = getTotalChunks();
        StreamFormatter.FormatState state = new StreamFormatter.FormatState();
        boolean changed = false;

        for (int i = 0; i < virtualTotalChunks; i++) {
            if (isCancelled != null && isCancelled.get()) {
                return changed; 
            }

            String chunkContent = getChunkContent(i);
            String formattedChunk;
            if ("json".equalsIgnoreCase(formatType)) {
                formattedChunk = StreamFormatter.formatJsonChunk(chunkContent, state);
            } else if ("xml".equalsIgnoreCase(formatType) || "html".equalsIgnoreCase(formatType)) {
                formattedChunk = StreamFormatter.formatXmlChunk(chunkContent, state);
            } else {
                formattedChunk = chunkContent;
            }

            if (!formattedChunk.equals(chunkContent)) {
                File tempFile = File.createTempFile("bearit_chunk_" + i + "_", ".tmp");
                tempFile.deleteOnExit();
                Files.writeString(tempFile.toPath(), formattedChunk, getActiveCharset());
                dirtyChunks.put(i, tempFile);
                changed = true;
            }
            
            if (progressPublisher != null) {
                int percent = (int) (((i + 1) * 100.0) / virtualTotalChunks);
                progressPublisher.accept(percent);
            }
        }

        if (changed) {
            updateFileMetrics();
        }
        return changed;
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
            return new ChunkState("", 1, 0, index, 1, false, false, "New file.", "Untitled", false);
        }

        String content = "";
        long absoluteStartOffset = 0; 
        
        if (dirtyChunks.containsKey(index)) {
            content = Files.readString(dirtyChunks.get(index).toPath(), getActiveCharset());
            isPreview = false; 
            absoluteStartOffset = getChunkBoundaries(index)[0];
        } else if (currentFile != null && currentFile.exists()) {
            // Optimization: Serve scrollbar previews directly from previewCache when available,
            // and use cached or approximate chunk boundaries during preview reads to avoid
            // blocking on 500 KB newline boundary scans for unindexed chunks.
            String cachedPreview = isPreview ? previewCache.get(index) : null;
            if (cachedPreview != null) {
                content = cachedPreview;
                long[] cachedBounds = boundaryCache.get(index);
                absoluteStartOffset = (cachedBounds != null) ? cachedBounds[0] : ((long) index * CHUNK_SIZE);
            } else {
                long[] boundaries;
                if (isPreview) {
                    long[] cachedBounds = boundaryCache.get(index);
                    if (cachedBounds != null) {
                        boundaries = cachedBounds;
                    } else {
                        long fileLen = currentFile.length();
                        long approxStart = Math.min((long) index * CHUNK_SIZE, fileLen);
                        long approxEnd = Math.min(approxStart + CHUNK_SIZE, fileLen);
                        boundaries = new long[]{approxStart, approxEnd};
                    }
                } else {
                    boundaries = getChunkBoundaries(index);
                }
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
                        content = new String(buffer.array(), getActiveCharset());
                        
                        if (isPreview && bytesToRead == PREVIEW_SIZE) {
                            content += "\n\n... [Scrolling Preview: Release scrollbar to render full 25MB chunk] ...";
                        }
                        if (isPreview) {
                            previewCache.put(index, content);
                        }
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
        return getChunkBoundaries(index, null);
    }

    // Optimization: Accepts an optional open RandomAccessFile handle and reuses the previous
    // chunk's end boundary (prevBounds[1]) as the current chunk's start offset, eliminating
    // redundant file opens and duplicate 500 KB boundary scans during sequential indexing.
    private long[] getChunkBoundaries(int index, java.io.RandomAccessFile existingRaf) throws IOException {
        long[] cached = boundaryCache.get(index);
        if (cached != null) return cached;
        if (currentFile == null) return new long[]{0, 0};

        long fileLen = currentFile.length();
        long start = (long) index * CHUNK_SIZE; 
        long end = Math.min(start + CHUNK_SIZE, fileLen);

        // ONLY scan for newlines if we are in Text Mode
        if (!isBinaryMode) {
            final long MAX_SCAN_DISTANCE = 500 * 1024; // 500 KB Failsafe
            long[] prevBounds = (index > 0) ? boundaryCache.get(index - 1) : null;
            if (existingRaf != null) {
                if (prevBounds != null) {
                    start = prevBounds[1];
                } else if (start > 0 && start < fileLen) {
                    start = fastFindNewline(existingRaf, start, Math.min(start + MAX_SCAN_DISTANCE, fileLen));
                }
                if (end < fileLen) {
                    end = fastFindNewline(existingRaf, end, Math.min(end + MAX_SCAN_DISTANCE, fileLen));
                }
            } else {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(currentFile, "r")) {
                    if (prevBounds != null) {
                        start = prevBounds[1];
                    } else if (start > 0 && start < fileLen) {
                        start = fastFindNewline(raf, start, Math.min(start + MAX_SCAN_DISTANCE, fileLen));
                    }
                    if (end < fileLen) {
                        end = fastFindNewline(raf, end, Math.min(end + MAX_SCAN_DISTANCE, fileLen));
                    }
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
        if (destFile.exists() && !destFile.canWrite()) throw new IOException("Failed to write the file: " + destFile.getAbsolutePath());
        
        commitCurrentChunk(currentText);

        Path destPath = destFile.toPath();
        Path tempPath = destPath.resolveSibling(destPath.getFileName() + ".tmp");
        int virtualTotalChunks = getTotalChunks();

        // CAPTURE EXISTING PERMISSIONS (Before Saving)
        Set<PosixFilePermission> originalPerms = null;
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (!isWindows && destFile.exists()) {
            try {
                originalPerms = Files.getPosixFilePermissions(destPath);
            } catch (Exception e) {
                // Silently ignore if the file system doesn't support POSIX (e.g., a FAT32 USB drive)
            }
        }

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

        // RESTORE EXISTING PERMISSIONS (After Saving)
        if (originalPerms != null && destFile.exists()) {
            try {
                Files.setPosixFilePermissions(destPath, originalPerms);
            } catch (Exception e) {
                System.err.println("Could not restore file permissions: " + e.getMessage());
            }
        }

        this.currentFile = destFile;
        this.pendingSaveAsFile = null;

        clearDirtyChunks();
        clearIndexCaches();
        updateFileMetrics();
        if (!isBinaryMode && "N/A".equals(detectedLineEndings)) {
            reanalyzeLineEndings();
        }
        return loadCurrentChunk(false); 
    }

    private void clearDirtyChunks() {
        dirtyChunks.values().forEach(File::delete);
        dirtyChunks.clear();
    }

    public void exportUnsavedContent(String currentText, File destFile) throws IOException {
        int virtualTotalChunks = getTotalChunks();
        try (FileChannel destChannel = FileChannel.open(destFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             FileChannel srcChannel = (currentFile != null && Files.exists(currentFile.toPath())) ? FileChannel.open(currentFile.toPath(), StandardOpenOption.READ) : null) {
            
            for (int i = 0; i < virtualTotalChunks; i++) {
                if (i == currentChunkIndex) {
                    byte[] bytes = currentText.getBytes(getActiveCharset());
                    destChannel.write(ByteBuffer.wrap(bytes));
                } else if (dirtyChunks.containsKey(i)) {
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

    public File getCurrentFile() {
        return currentFile;
    }

    public boolean isReadOnly() {
        return (currentFile != null && currentFile.exists() && !currentFile.canWrite());
    }
}