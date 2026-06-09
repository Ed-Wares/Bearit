package com.edwares;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;

public class BearitApp {
    public static void main(String[] args) {

        ShortcutUtil.ensureShortcutExists();
        AppContentExtractor.extractIfPresent();
        CommandLineParser cli = new CommandLineParser(args);

        // --- Handle Console-Only Actions ---
        // These can run multiple times in the background without triggering single-instance locks
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

        // --- Handle Single Instance Logic for the GUI ---
        // We only want one GUI window open at a time.
        boolean isPrimaryInstance = SingleInstanceManager.lockOrPassArguments(args, fileToOpen -> {
            // THIS CALLBACK RUNS ON THE PRIMARY INSTANCE WHEN A SECONDARY INSTANCE SENDS A FILE
            
            // Assuming TextEditorFrame acts as a Singleton, or you can retrieve the active frame
            BearitFrame mainFrame = BearitFrame.getInstance(); 
            
            if (mainFrame != null) {
                // Route the incoming file to your existing load method
                mainFrame.loadInitialFile(fileToOpen);
                
                // Force the existing window to the front to alert the user
                mainFrame.setExtendedState(JFrame.NORMAL);
                mainFrame.toFront();
                mainFrame.repaint();
            }
        });

        // If we failed to get the lock, we successfully passed the file to the primary instance.
        // We must exit now to prevent a second GUI from booting.
        if (!isPrimaryInstance) {
            System.out.println("Another instance is running. Passing arguments and exiting.");
            System.exit(0);
        }

        // --- Handle Primary UI Launch ---
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fallback gracefully
        }

        SwingUtilities.invokeLater(() -> {
            BearitFrame editor = new BearitFrame();
            
            // If a file was provided via command line on the FIRST boot, load it
            if (cli.getFileToOpen() != null) {
                editor.loadInitialFile(cli.getFileToOpen());
            }
            
            editor.setVisible(true);
        });
    }
}