package com.edwares;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

public class SingleInstanceManager {

    // A specific, arbitrary port number for Bearit to communicate on.
    private static final int PORT = 28419;
    private static ServerSocket serverSocket;

    /**
     * Attempts to lock the instance port.
     * @param args The command line arguments passed to main()
     * @param openFileCallback A function to call when a new file is received (must be thread-safe/Swing-safe)
     * @return true if this is the primary instance (keep loading the app), false if it passed args to an existing instance (exit now).
     */
    public static boolean lockOrPassArguments(String[] args, Consumer<File> openFileCallback) {
        try {
            // Bind exclusively to localhost to avoid firewall prompts
            serverSocket = new ServerSocket(PORT, 10, InetAddress.getLoopbackAddress());

            // If we reach here, we are the PRIMARY instance. Start the background listener.
            Thread listenerThread = new Thread(() -> {
                while (true) {
                    try (Socket client = serverSocket.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
                        
                        String filePath;
                        while ((filePath = in.readLine()) != null) {
                            if (!filePath.trim().isEmpty()) {
                                File fileToOpen = new File(filePath);
                                // Ensure the UI update happens on the Swing Event Dispatch Thread
                                SwingUtilities.invokeLater(() -> openFileCallback.accept(fileToOpen));
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
            listenerThread.setDaemon(true);
            listenerThread.start();
            return true; // Tell main() to continue booting the GUI

        } catch (BindException e) {
            // Port is already in use. We are a SECONDARY instance.
            sendArgsToPrimaryInstance(args);
            return false; // Tell main() to abort booting

        } catch (Exception e) {
            // Fallback: If socket fails for a weird network reason, just boot normally.
            e.printStackTrace();
            return true; 
        }
    }

    private static void sendArgsToPrimaryInstance(String[] args) {
        if (args == null || args.length == 0) return;

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), PORT);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            for (String arg : args) {
                // Send the absolute path so the primary instance knows exactly where the file is,
                // regardless of what working directory this secondary instance was launched from.
                File file = new File(arg);
                out.println(file.getAbsolutePath());
            }
            
        } catch (Exception e) {
            System.err.println("Failed to send arguments to primary instance: " + e.getMessage());
        }
    }
}