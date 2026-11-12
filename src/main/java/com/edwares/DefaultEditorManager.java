package com.edwares;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

public class DefaultEditorManager {

    private static final String PREV_DEFAULT_KEY = "previous_default_editor";
    private static final String BEARIT_DESKTOP_FILE = "Bearit.desktop"; // From your Linux installer
    public static final String DEFAULT_MAC_BUNDLE_ID = "com.edwares";
    public static final String FALLBACK_MAC_EDITOR = "com.apple.TextEdit";

    public static final String[] MAC_TEXT_UTIS = {
        "public.plain-text",
        "public.utf8-plain-text",
        "public.text",
        "public.source-code",
        "public.script",
        "public.shell-script",
        "public.json",
        "public.xml",
        "public.yaml",
        "public.comma-separated-values-text",
        "public.log",
        "com.apple.log"
    };

    public static void promptAndSetDefault(JFrame parentFrame) {
        String os = System.getProperty("os.name").toLowerCase();

        //int choice = JOptionPane.showConfirmDialog(parentFrame, "Would you like to set Bearit as your default text editor?", "Set Default Editor", JOptionPane.YES_NO_OPTION);
        int choice = DialogUtil.showConfirmDialog(parentFrame, "Would you like to set Bearit as your default text editor?", "Set Default Editor", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) {
            if (os.contains("linux") || os.contains("nix")) {
                setLinuxDefault();
            } else if (os.contains("mac")) {
                setMacDefault(DEFAULT_MAC_BUNDLE_ID);
            } else if (os.contains("win")) {
                //setWindowsDefault();
            }
        }
    }

    public static void restorePreviousDefault() {
        String os = System.getProperty("os.name").toLowerCase();
        String previousDefault = BearitProperties.getInstance().getProperty(PREV_DEFAULT_KEY, null);

        if (os.contains("mac")) {
            restoreMacDefault(previousDefault);
            BearitProperties.getInstance().setProperty(PREV_DEFAULT_KEY, "");
            return;
        }

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

    public static boolean setMacDefault(String bundleId) {
        if (bundleId == null || bundleId.trim().isEmpty()) {
            bundleId = DEFAULT_MAC_BUNDLE_ID;
        }
        String sanitizedBundleId = bundleId.trim().replace("\"", "").replace("'", "");

        try {
            // 1. Query current default role handlers for text UTIs before changing them
            String existingSaved = BearitProperties.getInstance().getProperty(PREV_DEFAULT_KEY, "");
            if (existingSaved == null || existingSaved.trim().isEmpty()) {
                StringBuilder queryScript = new StringBuilder();
                queryScript.append("ObjC.import('CoreFoundation'); ObjC.import('CoreServices'); ");
                queryScript.append("var utis = [").append(buildJsArray(MAC_TEXT_UTIS)).append("]; ");
                queryScript.append("var pairs = []; ");
                queryScript.append("for (var i = 0; i < utis.length; i++) { ");
                queryScript.append("  var ref = $.LSCopyDefaultRoleHandlerForContentType($(utis[i]), $.kLSRolesAll); ");
                queryScript.append("  var h = ref ? ObjC.unwrap(ObjC.castRefToObject(ref)) : null; ");
                queryScript.append("  if (h && h.toLowerCase() !== '").append(sanitizedBundleId.toLowerCase()).append("') { ");
                queryScript.append("    pairs.push(utis[i] + '=' + h); ");
                queryScript.append("  } ");
                queryScript.append("} ");
                queryScript.append("pairs.join(';');");

                String queried = executeCommandAndRead("/usr/bin/osascript", "-l", "JavaScript", "-e", queryScript.toString());
                if (queried != null && !queried.trim().isEmpty()) {
                    BearitProperties.getInstance().setProperty(PREV_DEFAULT_KEY, queried.trim());
                } else {
                    BearitProperties.getInstance().setProperty(PREV_DEFAULT_KEY, FALLBACK_MAC_EDITOR);
                }
            }

            // 2. Set Bearit as the default role handler for all text UTIs in LaunchServices
            StringBuilder setScript = new StringBuilder();
            setScript.append("ObjC.import('CoreServices'); ");
            setScript.append("var utis = [").append(buildJsArray(MAC_TEXT_UTIS)).append("]; ");
            setScript.append("var bid = $('").append(sanitizedBundleId).append("'); ");
            setScript.append("for (var i = 0; i < utis.length; i++) { ");
            setScript.append("  $.LSSetDefaultRoleHandlerForContentType($(utis[i]), $.kLSRolesAll, bid); ");
            setScript.append("  $.LSSetDefaultRoleHandlerForContentType($(utis[i]), $.kLSRolesEditor, bid); ");
            setScript.append("} ");
            setScript.append("'ok';");

            String result = executeCommandAndRead("/usr/bin/osascript", "-l", "JavaScript", "-e", setScript.toString());
            return result != null && result.trim().contains("ok");
        } catch (Exception e) {
            System.err.println("Failed to set macOS default editor: " + e.getMessage());
            return false;
        }
    }

    private static void restoreMacDefault(String previousDefault) {
        try {
            Map<String, String> utiToHandler = new HashMap<>();
            String defaultFallback = FALLBACK_MAC_EDITOR;

            if (previousDefault != null && !previousDefault.trim().isEmpty()) {
                String trimmed = previousDefault.trim();
                if (trimmed.contains("=")) {
                    String[] entries = trimmed.split(";");
                    for (String entry : entries) {
                        int eq = entry.indexOf('=');
                        if (eq > 0 && eq < entry.length() - 1) {
                            String uti = entry.substring(0, eq).trim();
                            String handler = entry.substring(eq + 1).trim();
                            if (!handler.isEmpty() && !handler.equalsIgnoreCase(DEFAULT_MAC_BUNDLE_ID)) {
                                utiToHandler.put(uti, handler);
                            }
                        }
                    }
                } else if (!trimmed.equalsIgnoreCase(DEFAULT_MAC_BUNDLE_ID)) {
                    defaultFallback = trimmed;
                }
            }

            StringBuilder restoreScript = new StringBuilder();
            restoreScript.append("ObjC.import('CoreServices'); ");
            for (String uti : MAC_TEXT_UTIS) {
                String handler = utiToHandler.getOrDefault(uti, defaultFallback)
                        .replace("\"", "").replace("'", "");
                String safeUti = uti.replace("\"", "").replace("'", "");
                restoreScript.append("$.LSSetDefaultRoleHandlerForContentType($('")
                        .append(safeUti).append("'), $.kLSRolesAll, $('")
                        .append(handler).append("')); ");
                restoreScript.append("$.LSSetDefaultRoleHandlerForContentType($('")
                        .append(safeUti).append("'), $.kLSRolesEditor, $('")
                        .append(handler).append("')); ");
            }
            executeCommand("/usr/bin/osascript", "-l", "JavaScript", "-e", restoreScript.toString());
        } catch (Exception e) {
            System.err.println("Failed to restore macOS default editor: " + e.getMessage());
        }
    }

    private static String buildJsArray(String[] items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("'").append(items[i]).append("'");
        }
        return sb.toString();
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
            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
            new ProcessBuilder(java.util.Arrays.asList(command)).start().waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String executeCommandAndRead(String... command) {
        StringBuilder output = new StringBuilder();
        try {
            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
            Process process = new ProcessBuilder(java.util.Arrays.asList(command)).start();
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