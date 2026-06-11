package com.edwares;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URISyntaxException;

public class DefaultEditorManager {

    private static final String PREV_DEFAULT_KEY = "previous_default_editor";
    private static final String BEARIT_DESKTOP_FILE = "Bearit.desktop"; // From your Linux installer

    public static void promptAndSetDefault(JFrame parentFrame) {
        String os = System.getProperty("os.name").toLowerCase();

        // macOS explicitly blocks programmatic default app changes.
        if (os.contains("mac")) {
            // JOptionPane.showMessageDialog(parentFrame,
            //     "macOS security prevents automatic default app changes.\n\n" +
            //     "To set Bearit as default:\n" +
            //     "1. Right-click any .txt file and select 'Get Info'\n" +
            //     "2. Change 'Open with' to Bearit\n" +
            //     "3. Click 'Change All...'",
            //     "macOS Security Restriction", JOptionPane.INFORMATION_MESSAGE);
            DialogUtil.showMessageDialog(parentFrame,
                "macOS security prevents automatic default app changes.\n\n" +
                "To set Bearit as default:\n" +
                "1. Right-click any .txt file and select 'Get Info'\n" +
                "2. Change 'Open with' to Bearit\n" +
                "3. Click 'Change All...'",
                "macOS Security Restriction", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        //int choice = JOptionPane.showConfirmDialog(parentFrame, "Would you like to set Bearit as your default text editor?", "Set Default Editor", JOptionPane.YES_NO_OPTION);
        int choice = DialogUtil.showConfirmDialog(parentFrame, "Would you like to set Bearit as your default text editor?", "Set Default Editor", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) {
            if (os.contains("linux") || os.contains("nix")) {
                setLinuxDefault();
            } else if (os.contains("win")) {
                //setWindowsDefault();
            }
        }
    }

    public static void restorePreviousDefault() {
        String os = System.getProperty("os.name").toLowerCase();
        String previousDefault = BearitProperties.getInstance().getProperty(PREV_DEFAULT_KEY, null);

        if (previousDefault != null && !previousDefault.isEmpty()) {
            if (os.contains("linux") || os.contains("nix")) {
                executeCommand("xdg-mime", "default", previousDefault, "text/plain");
            } else if (os.contains("win")) {
                // Restore the original Windows Registry key
                executeCommand("reg", "add", "HKCU\\Software\\Classes\\.txt", "/ve", "/d", previousDefault, "/f");
            }
            // Clear the saved property
            BearitProperties.getInstance().setProperty(PREV_DEFAULT_KEY, "");
        }
    }

    private static void setLinuxDefault() {
        try {
            // Query the current default editor for standard text files
            String currentDefault = executeCommandAndRead("xdg-mime", "query", "default", "text/plain");
            
            if (currentDefault != null && !currentDefault.trim().isEmpty() && !currentDefault.contains(BEARIT_DESKTOP_FILE)) {
                // Save it to BearitProperties
                BearitProperties.getInstance().setProperty(PREV_DEFAULT_KEY, currentDefault.trim());
            }

            // Set Bearit as the new default
            executeCommand("xdg-mime", "default", BEARIT_DESKTOP_FILE, "text/plain");

        } catch (Exception e) {
            System.err.println("Failed to set Linux default editor: " + e.getMessage());
        }
    }

    // Windows default setting is more complex and may require additional permissions or user interaction, so it's currently commented out.
    private static void setWindowsDefault() {
        try {
            // Query the registry for the current .txt handler
            String currentDefault = executeCommandAndRead("reg", "query", "HKCU\\Software\\Classes\\.txt", "/ve");
            
            // Extract the actual value from the registry output string
            String extractedValue = parseWindowsRegistryOutput(currentDefault);

            if (extractedValue != null && !extractedValue.contains("Bearit")) {
                BearitProperties.getInstance().setProperty(PREV_DEFAULT_KEY, extractedValue);
            }

            String jarPath = getRunningJarPath();
            String currentExePath = ProcessHandle.current().info().command().orElse(null);
            // Construct the command string: "C:\Actual\Path\Bearit.exe" "%1"
            // or construct the command string: "javaw.exe" -jar "C:\Actual\Path\Bearit.jar" "%1"
            String commandString = "\"" + currentExePath + "\" \"-jar " + jarPath + "\" \"%1\"";
            //JOptionPane.showMessageDialog(null, "commandString: " + commandString);
            // Set Bearit as the default text handler
            // Note: Windows 11 may still intercept this and show a "How do you want to open this file?" prompt
            executeCommand("reg", "add", "HKCU\\Software\\Classes\\.txt", "/ve", "/d", "Bearit.txt", "/f");
            executeCommand("reg", "add", "HKCU\\Software\\Classes\\Bearit.txt\\shell\\open\\command", "/ve", "/d", commandString, "/f");

        } catch (Exception e) {
            System.err.println("Failed to set Windows default editor: " + e.getMessage());
        }
    }

    // --- Helper Methods for Process Execution ---
    
    private static String getRunningJarPath() throws URISyntaxException {
        String path = ContextMenuInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        if (System.getProperty("os.name").toLowerCase().contains("win") && path.startsWith("/")) {
            path = path.substring(1);
        }
        return new File(path).getAbsolutePath();
    }

    private static void executeCommand(String... command) {
        try {
            new ProcessBuilder(command).start().waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String executeCommandAndRead(String... command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = new ProcessBuilder(command).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return output.toString();
    }

    private static String parseWindowsRegistryOutput(String output) {
        if (output == null || output.isEmpty()) return null;
        // Windows reg query output looks like: "    (Default)    REG_SZ    txtfile"
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.contains("REG_SZ")) {
                String[] parts = line.split("REG_SZ");
                if (parts.length > 1) {
                    return parts[1].trim();
                }
            }
        }
        return null;
    }
}