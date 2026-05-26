package com.edwares;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;

public class BearitApp {
    public static void main(String[] args) {

        ShortcutUtil.ensureShortcutExists();
        CommandLineParser cli = new CommandLineParser(args);

        // Handle Console-Only Actions
        if (cli.isShowHelp()) {
            cli.printHelp();
            return;
        }

        if (cli.getGenerateSizeGb() != null) {
            try {
                LargeFileManager.generateTestFile(cli.getGenerateSizeGb());
            } catch (IOException e) {
                System.err.println("Error generating file: " + e.getMessage());
            }
            return; 
        }

        // Handle UI Launch
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fallback gracefully
        }

        SwingUtilities.invokeLater(() -> {
            TextEditorFrame editor = new TextEditorFrame();
            
            // If a file was provided via command line, load it immediately
            if (cli.getFileToOpen() != null) {
                editor.loadInitialFile(cli.getFileToOpen());
            }
            
            editor.setVisible(true);
        });
    }
}