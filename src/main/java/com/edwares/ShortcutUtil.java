package com.edwares;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public class ShortcutUtil {

    public static final String SHORTCUT_NAME = "Bearit.lnk";
    public static final String ICON_NAME = "Bearit.ico";

    public static void ensureShortcutExists() {
        try {
            File jarFile = new File(ShortcutUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File parentDir = jarFile.getParentFile();
            File shortcutFile = new File(parentDir, SHORTCUT_NAME);
            File iconFile = new File(parentDir, ICON_NAME);

            // Ensure the icon exists on disk
            if (!iconFile.exists() && parentDir.canWrite()) {
                extractIconIfMissing(iconFile);
            }

            // Create shortcut if it doesn't exist
            if (!shortcutFile.exists() && parentDir.canWrite()) {
                createWindowsShortcut(shortcutFile, jarFile, iconFile);
            }
        } catch (URISyntaxException e) {
            System.err.println("Could not resolve application path.");
        }
    }

    private static void extractIconIfMissing(File iconFile) {
        try (InputStream is = ShortcutUtil.class.getResourceAsStream("/" + ICON_NAME)) {
            if (is != null) {
                Files.copy(is, iconFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                System.err.println(ICON_NAME + " not found in JAR resources.");
            }
        } catch (IOException e) {
            System.err.println("Failed to extract icon: " + e.getMessage());
        }
    }

    private static void createWindowsShortcut(File lnkFile, File jarFile, File iconFile) {
        // Use ProcessHandle to find the exact executable that launched this process
        Optional<String> command = ProcessHandle.current().info().command();

        // Ensure we are pointing to javaw.exe (GUI mode) rather than java.exe (console mode)
        String javaPath = command.orElse("javaw.exe").replace("java.exe", "javaw.exe");

        String jarPath = jarFile.getAbsolutePath();
        String lnkPath = lnkFile.getAbsolutePath();
        String iconPath = iconFile.getAbsolutePath();

        // PowerShell script to create the link and assign the icon
        String psScript = String.format(
            "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('%s'); " +
            "$s.TargetPath = '%s'; " +
            "$s.Arguments = '-jar \"%s\"'; " +
            "$s.Description = 'Bearit Text Editor'; " +
            "$s.WorkingDirectory = '%s'; " +
            "$s.IconLocation = '%s'; " + // Set the icon location
            "$s.Save()",
            lnkPath.replace("'", "''"), 
            javaPath.replace("'", "''"), 
            jarPath.replace("'", "''"), 
            jarFile.getParent().replace("'", "''"),
            iconPath.replace("'", "''")
        );

        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", psScript);
            pb.start();
        } catch (IOException e) {
            System.err.println("Failed to create shortcut: " + e.getMessage());
        }
    }
}