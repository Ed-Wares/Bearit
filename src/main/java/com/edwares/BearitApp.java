package com.edwares;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BearitApp {
    private static final List<File> pendingOpenFiles = new ArrayList<>();
    private static boolean frameReady = false;

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

        // Register macOS / OS Desktop open-file handler early so files opened via Finder
        // (double-click or Control+Click -> Open With) are captured at launch and while running.
        registerOpenFileHandler();

        // --- Handle Single Instance Logic for the GUI ---
        // We only want one GUI window open at a time.
        boolean isPrimaryInstance = SingleInstanceManager.lockOrPassArguments(args, remoteArgs -> {
            BearitFrame mainFrame = BearitFrame.getInstance(); 
            if (mainFrame != null) {
                // Route the raw String array into the frame
                mainFrame.processRemoteCommands(remoteArgs);
                bringFrameToFront(mainFrame);
            }
        });

        // If we failed to get the lock, we successfully passed the file to the primary instance.
        // We must exit now to prevent a second GUI from booting.
        if (!isPrimaryInstance) {
            if (args.length == 0) {
                // On macOS, Finder may launch the binary with no CLI args and deliver the file via OpenFilesEvent
                List<File> macFiles;
                synchronized (pendingOpenFiles) {
                    if (pendingOpenFiles.isEmpty()) {
                        try {
                            pendingOpenFiles.wait(250);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    macFiles = new ArrayList<>(pendingOpenFiles);
                    pendingOpenFiles.clear();
                }
                for (File f : macFiles) {
                    if (f != null) {
                        SingleInstanceManager.lockOrPassArguments(new String[]{f.getAbsolutePath()}, r -> {});
                    }
                }
            }
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
            List<File> initialOpenFiles;
            synchronized (pendingOpenFiles) {
                initialOpenFiles = new ArrayList<>(pendingOpenFiles);
                pendingOpenFiles.clear();
                frameReady = true;
            }
            for (File f : initialOpenFiles) {
                if (f != null) {
                    editor.loadInitialFile(f);
                }
            }
            editor.executeInstallCommand(); // --- Trigger the one time install command ---
            editor.executeStartupCommand(); // --- Trigger the startup command ---
            editor.setVisible(true);
        });
    }

    private static void registerOpenFileHandler() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
                    desktop.setOpenFileHandler(event -> {
                        List<File> files = event.getFiles();
                        if (files == null || files.isEmpty()) {
                            return;
                        }
                        synchronized (pendingOpenFiles) {
                            if (!frameReady) {
                                pendingOpenFiles.addAll(files);
                                pendingOpenFiles.notifyAll();
                                return;
                            }
                        }
                        SwingUtilities.invokeLater(() -> {
                            BearitFrame mainFrame = BearitFrame.getInstance();
                            if (mainFrame != null) {
                                for (File f : files) {
                                    if (f != null) {
                                        mainFrame.loadInitialFile(f);
                                    }
                                }
                                bringFrameToFront(mainFrame);
                            }
                        });
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to register OS OpenFileHandler: " + e.getMessage());
        }
    }

    private static void bringFrameToFront(BearitFrame mainFrame) {
        // Get the window's exact current state
        int currentState = mainFrame.getExtendedState();

        // Check if it is currently minimized (ICONIFIED)
        if ((currentState & java.awt.Frame.ICONIFIED) != 0) {
            // Bitwise logic: Remove the ICONIFIED flag, but keep the MAXIMIZED flag intact!
            mainFrame.setExtendedState(currentState & ~java.awt.Frame.ICONIFIED);
        }

        // Bring to front and request focus safely
        mainFrame.setVisible(true);
        mainFrame.toFront();
        mainFrame.requestFocus();
        mainFrame.repaint();
    }
}