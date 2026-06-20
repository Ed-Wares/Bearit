package com.edwares;

import javax.swing.*;
import java.awt.*;

public class BearitTextHexWrapper extends JPanel {
    private AdvancedTextEditorPanel hiddenTextEditor;
    private HexEditorPanel hexEditor;
    private boolean isDirty = false;
    private int currentLoadedChunk = 0;
    private boolean isSyncing = false;

    public BearitTextHexWrapper(AdvancedTextEditorPanel textEditor) {
        this.hiddenTextEditor = textEditor;
        setLayout(new BorderLayout());

        hexEditor = new HexEditorPanel();
        
        hexEditor.setOnDataChangedListener(() -> {
            isDirty = true;
            hiddenTextEditor.setUnsavedChanges(true); 
        });
        // --- Wire up the chunk navigation ---
        // Update existing listeners to pass 'null' for the exact offset
        hexEditor.setOnPrevChunk((cursorAtBottom) -> navigateToChunk(currentLoadedChunk - 1, cursorAtBottom, null));
        hexEditor.setOnNextChunk((cursorAtBottom) -> navigateToChunk(currentLoadedChunk + 1, cursorAtBottom, null));
        hexEditor.setOnJumpToChunk(chunkIdx -> navigateToChunk(chunkIdx, false, null));

        // --- Wire up the Global Goto logic ---
        hexEditor.setOnJumpToGlobalAddress(globalAddr -> {
            LargeFileManager fm = hiddenTextEditor.getFileManager();
            try {
                for (int i = 0; i < fm.getTotalChunks(); i++) {
                    long[] bounds = fm.getChunkBoundaries(i);
                    // bounds[0] is inclusive, bounds[1] is exclusive
                    if (globalAddr >= bounds[0] && globalAddr < bounds[1]) {
                        navigateToChunk(i, false, globalAddr);
                        return;
                    }
                }
                //JOptionPane.showMessageDialog(this, "Address is outside the bounds of the file.");
                DialogUtil.showMessageDialog(this, "Address is outside the bounds of the file.", "Invalid Address", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        add(hexEditor, BorderLayout.CENTER);
        
        // --- Global Syncing Initialization (Replaces loadCurrentChunkFromBearit) ---
        long globalCaret = hiddenTextEditor.getGlobalCaretByteOffset();
        LargeFileManager fm = hiddenTextEditor.getFileManager();
        int targetChunk = 0;
        try {
            // Find exactly which strict 25MB Hex Chunk contains this byte
            for (int i = 0; i < fm.getTotalChunks(); i++) {
                long[] bounds = fm.getChunkBoundaries(i);
                if (globalCaret >= bounds[0] && globalCaret < bounds[1]) {
                    targetChunk = i;
                    break;
                }
            }
            currentLoadedChunk = targetChunk;
            byte[] rawBytes = fm.getChunkBytes(currentLoadedChunk);
            long offset = fm.getChunkBoundaries(currentLoadedChunk)[0];
            hexEditor.setFileSizeDateStatus(hiddenTextEditor.getFileInfoString(hiddenTextEditor.getActiveFile()));
            hexEditor.updateChunkStatus(currentLoadedChunk + 1, fm.getTotalChunks());
            hexEditor.loadData(rawBytes, offset);
            hexEditor.setSelectedByteOffset((int)(globalCaret - offset));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public AdvancedTextEditorPanel getHiddenTextEditor() {
        return hiddenTextEditor;
    }


    public boolean isDirty() {
        return isDirty;
    }

    public void applyHexEdits() {
        try {
            LargeFileManager fm = hiddenTextEditor.getFileManager();
            // Commit the raw bytes to the dirty chunks map so it stays in memory
            fm.commitCurrentChunkBytes(currentLoadedChunk, hexEditor.getModifiedData());
            isDirty = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void discardHexEdits() {
        isDirty = false;
        // Wipe the global dirty flag since the user explicitly aborted
        hiddenTextEditor.setUnsavedChanges(false); 
    }

    public void syncToHiddenEditor() {
        // --- RE-ENTRANCY LOCK: ---
        if (isSyncing) return; 
        isSyncing = true;
        
        try {
            if (isDirty) {
                applyHexEdits();
                try {
                    String updatedText = hiddenTextEditor.getFileManager().getChunkContent(currentLoadedChunk);
                    hiddenTextEditor.forceSetText(updatedText);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }            
        } finally {
            // Unlock when finished so future saves/closes work correctly
            isSyncing = false; 
        }
    }

    public int getHexSelectedByteOffset() {
        return hexEditor.getSelectedByteOffset();
    }

    public int getCurrentLoadedChunk() {
        return currentLoadedChunk;
    }

    // Add the boolean parameter to the method signature
    // --- Added 'exactGlobalOffset' to the parameters ---
    private void navigateToChunk(int newIndex, boolean cursorAtBottom, Long exactGlobalOffset) {
        LargeFileManager fm = hiddenTextEditor.getFileManager();
        if (newIndex < 0 || newIndex >= fm.getTotalChunks()) return;

        if (isDirty) {
            applyHexEdits(); 
        }

        hexEditor.setStatus("Loading chunk " + (newIndex + 1) + "...");
        hexEditor.setUIEnabled(false);

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            byte[] rawBytes;
            long offset;
            
            @Override
            protected Void doInBackground() throws Exception {
                rawBytes = fm.getChunkBytes(newIndex);
                offset = fm.getChunkBoundaries(newIndex)[0];
                return null;
            }
            
            @Override
            protected void done() {
                try {
                    currentLoadedChunk = newIndex;
                    hexEditor.loadData(rawBytes, offset);
                    hexEditor.updateChunkStatus(currentLoadedChunk + 1, fm.getTotalChunks());
                    hexEditor.setStatus("Ready");
                    
                    // --- NEW: Handle exact offset targeting ---
                    if (exactGlobalOffset != null) {
                        hexEditor.setSelectedByteOffset((int)(exactGlobalOffset - offset));
                    } else if (cursorAtBottom && rawBytes.length > 0) {
                        hexEditor.setSelectedByteOffset(rawBytes.length - 1);
                    } else {
                        hexEditor.setSelectedByteOffset(0); 
                    }
                    
                } catch (Exception e) {
                    hexEditor.setStatus("Error loading chunk");
                } finally {
                    hexEditor.setUIEnabled(true);
                }
            }
        };
        worker.execute();
    }

    public void setGlobalSelection(long globalStart) {
        try {
            String hexAddress = Long.toHexString(globalStart).toUpperCase();
            hexEditor.jumpToHexAddress(hexAddress);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public HexEditorPanel getHexEditor() {
        return hexEditor;
    }
}