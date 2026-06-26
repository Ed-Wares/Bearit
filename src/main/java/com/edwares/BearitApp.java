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
                FileGenUtil.generateTestFile(cli.getGenerateSizeGb());
            } catch (IOException e) {
                System.err.println("Error generating file: " + e.getMessage());
            }
            return; 
        }

        // --- Handle Single Instance Logic for the GUI ---
        // We only want one GUI window open at a time.
        boolean isPrimaryInstance = SingleInstanceManager.lockOrPassArguments(args, remoteArgs -> {
            BearitFrame mainFrame = BearitFrame.getInstance(); 
            if (mainFrame != null) {
                // Route the raw String array into the frame
                mainFrame.processRemoteCommands(remoteArgs);
                
                mainFrame.setExtendedState(JFrame.NORMAL);
                mainFrame.toFront();
                mainFrame.repaint();
            }
        });

        // If we failed to get the lock, we successfully passed the file to the primary instance.
        // We must exit now to prevent a second GUI from booting.
        if (!isPrimaryInstance) {
            System.out.println("Commands routed to existing instance. Exiting.");
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
            // Process the startup args through the exact same logic pipeline!
            editor.processRemoteCommands(args);
            editor.executeStartupCommand(); // --- Trigger the startup command ---
            editor.setVisible(true);
        });
    }
}