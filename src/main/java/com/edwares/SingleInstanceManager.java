package com.edwares;

import javax.swing.*;
import java.io.File;
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
            // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            serverSocket = new ServerSocket(PORT, 10, InetAddress.getLoopbackAddress());

            Thread listenerThread = new Thread(() -> {
                while (true) {
                    try (Socket client = serverSocket.accept();
                         java.io.DataInputStream in = new java.io.DataInputStream(client.getInputStream())) {
                        
                        int len = in.readInt();
                        if (len >= 0 && len < 1000) {
                            String[] remoteArgs = new String[len];
                            for (int i = 0; i < len; i++) {
                                remoteArgs[i] = in.readUTF();
                            }
                            if (remoteArgs.length > 0) {
                                SwingUtilities.invokeLater(() -> remoteCommandHandler.accept(remoteArgs));
                            }
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

        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), PORT);
             java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream())) {
            
            out.writeInt(processedArgs.length);
            for (String arg : processedArgs) {
                out.writeUTF(arg);
            }
            out.flush();
            
        } catch (Exception e) {
            System.err.println("Failed to send arguments to primary instance: " + e.getMessage());
        }
    }
}