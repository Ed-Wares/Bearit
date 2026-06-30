package com.edwares;

import javax.swing.*;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
     * @param remoteCommandHandler A function to call to handle commands
     * @return true if this is the primary instance (keep loading the app), false if it passed args to an existing instance (exit now).
     */
    public static boolean lockOrPassArguments(String[] args, Consumer<String[]> remoteCommandHandler) {
        try {
            serverSocket = new ServerSocket(PORT, 10, InetAddress.getLoopbackAddress());

            Thread listenerThread = new Thread(() -> {
                while (true) {
                    try (Socket client = serverSocket.accept();
                         ObjectInputStream in = new ObjectInputStream(client.getInputStream())) {
                        
                        // Receive the full args array from the secondary instance
                        String[] remoteArgs = (String[]) in.readObject();
                        if (remoteArgs != null && remoteArgs.length > 0) {
                            SwingUtilities.invokeLater(() -> remoteCommandHandler.accept(remoteArgs));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
            listenerThread.setDaemon(true);
            listenerThread.start();
            return true;

        } catch (BindException e) {
            // Port is already in use. We are a SECONDARY instance.
            sendArgsToPrimaryInstance(args);
            return false;

        } catch (Exception e) {
            e.printStackTrace();
            return true; 
        }
    }

    private static void sendArgsToPrimaryInstance(String[] args) {
        if (args == null || args.length == 0) return;

        // Pre-process arguments: convert relative file paths to absolute paths 
        String[] processedArgs = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            
            // If it doesn't start with a hyphen, and isn't the value part of a command flag (-g, -s, etc.)
            boolean isValueArg = (i > 0 && (args[i-1].equals("-g") || args[i-1].equals("-gb") || args[i-1].equals("-s") || args[i-1].equals("-f")));
            
            if (!arg.startsWith("-") && !isValueArg) {
                processedArgs[i] = new File(arg).getAbsolutePath();
            } else {
                processedArgs[i] = arg;
            }
        }

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
            
            out.writeObject(processedArgs);
            out.flush();
            
        } catch (Exception e) {
            System.err.println("Failed to send arguments to primary instance: " + e.getMessage());
        }
    }
}