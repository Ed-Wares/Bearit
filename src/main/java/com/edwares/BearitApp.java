package com.edwares;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class BearitApp {
    public static void main(String[] args) {
        // Use the system look and feel for a native OS presentation
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fallback gracefully to default Swing look and feel if system fails
        }

        SwingUtilities.invokeLater(() -> {
            TextEditorFrame editor = new TextEditorFrame();
            editor.setVisible(true);
        });
    }
}