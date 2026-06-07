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

        add(hexEditor, BorderLayout.CENTER);
        loadCurrentChunkFromBearit();
        
        // --- Sync the initial cursor position to the ASCII side ---
        int rawCaret = hiddenTextEditor.getRawCaretPosition();
        hexEditor.setSelectedByteOffset(rawCaret);
    }

    /**
     * Reverts LargeFileManager back to text mode when toggling off.
     */
    public void cleanupAndRevert() {
        hiddenTextEditor.getFileManager().setBinaryMode(false);
    }

    public AdvancedTextEditorPanel getHiddenTextEditor() {
        return hiddenTextEditor;
    }

    private void loadCurrentChunkFromBearit() {
        try {
            LargeFileManager fm = hiddenTextEditor.getFileManager();
            fm.setBinaryMode(true); // Bypass string encoding
            
            // Note: You must expose loadedChunkIndex via a getter in AdvancedTextEditorPanel!
            currentLoadedChunk = hiddenTextEditor.getLoadedChunkIndex(); 
            
            byte[] rawBytes = fm.getChunkBytes(currentLoadedChunk);
            long absoluteOffset = fm.getChunkBoundaries(currentLoadedChunk)[0];
            
            hexEditor.loadData(rawBytes, absoluteOffset);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
            }
            
            LargeFileManager fm = hiddenTextEditor.getFileManager();
            
            // Temporarily switch to text mode to properly read the new bytes as UTF-8 strings
            fm.setBinaryMode(false);
            try {
                String updatedText = fm.getChunkContent(currentLoadedChunk);
                hiddenTextEditor.forceSetText(updatedText);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // Restore binary mode so the Hex Editor continues functioning
                fm.setBinaryMode(true); 
            }
        } finally {
            // Unlock when finished so future saves/closes work correctly
            isSyncing = false; 
        }
    }

    public int getHexSelectedByteOffset() {
        return hexEditor.getSelectedByteOffset();
    }

}