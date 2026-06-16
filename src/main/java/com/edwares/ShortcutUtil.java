package com.edwares;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public class ShortcutUtil {

    // Windows Constants
    public static final String WIN_SHORTCUT_NAME = "Bearit.lnk";
    public static final String WIN_BAT_NAME = "bearit.bat";
    public static final String WIN_ICON_NAME = "Bearit.ico";

    // Linux Constants
    public static final String LINUX_SHORTCUT_NAME = "Bearit.desktop";
    public static final String LINUX_ICON_NAME = "Bearit.png"; // Reusing the PNG from your BearitFrame

    public static void ensureShortcutExists() {
        try {
            File jarFile = new File(ShortcutUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File parentDir = jarFile.getParentFile();
            
            String osName = System.getProperty("os.name").toLowerCase();

            if (osName.contains("win")) {
                File shortcutFile = new File(parentDir, WIN_SHORTCUT_NAME);
                File batFile = new File(parentDir, WIN_BAT_NAME);
                File iconFile = new File(parentDir, WIN_ICON_NAME);

                if (!iconFile.exists() && parentDir.canWrite()) {
                    extractIconIfMissing(iconFile, WIN_ICON_NAME);
                }
                if (!shortcutFile.exists() && parentDir.canWrite()) {
                    createWindowsShortcut(shortcutFile, jarFile, iconFile);
                }
                if (!batFile.exists() && parentDir.canWrite()) {
                    createWindowsBatFile(jarFile);
                }
            } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
                File shortcutFile = new File(parentDir, LINUX_SHORTCUT_NAME);
                File iconFile = new File(parentDir, LINUX_ICON_NAME);

                if (!iconFile.exists() && parentDir.canWrite()) {
                    extractIconIfMissing(iconFile, LINUX_ICON_NAME);
                }
                if (!shortcutFile.exists() && parentDir.canWrite()) {
                    createLinuxShortcut(shortcutFile, jarFile, iconFile);
                }
            }
        } catch (URISyntaxException e) {
            System.err.println("Could not resolve application path.");
        }
    }

    private static void extractIconIfMissing(File iconFile, String resourceName) {
        try (InputStream is = ShortcutUtil.class.getResourceAsStream("/" + resourceName)) {
            if (is != null) {
                Files.copy(is, iconFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                System.err.println(resourceName + " not found in JAR resources.");
            }
        } catch (IOException e) {
            System.err.println("Failed to extract icon: " + e.getMessage());
        }
    }

    private static void createLinuxShortcut(File desktopFile, File jarFile, File iconFile) {
        String jarPath = jarFile.getAbsolutePath();
        String iconPath = iconFile.getAbsolutePath();
        
        // Generate the standard Linux .desktop INI format
        String desktopContents = "[Desktop Entry]\n" +
                "Version=1.0\n" +
                "Type=Application\n" +
                "Name=Bearit\n" +
                "Comment=Bearit Text Editor\n" +
                "Exec=java -jar \"" + jarPath + "\" %F\n" +
                "Icon=" + iconPath + "\n" +
                "Terminal=false\n" +
                "Categories=Utility;TextEditor;\n";
        
        try {
            Files.writeString(desktopFile.toPath(), desktopContents);
            
            // Critical for Linux: The shortcut must be marked as an executable file 
            // before the OS launcher will trust it and display the icon!
            desktopFile.setExecutable(true, false); 
        } catch (IOException e) {
            System.err.println("Failed to create Linux shortcut: " + e.getMessage());
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
            "$s.IconLocation = '%s'; " + 
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

    private static void createWindowsBatFile(File jarFile) {
        // --- Generate a CLI Batch Script for Portable Mode ---
        // This allows the user to just type 'bearit' and perfectly preserves their terminal's working directory.
        try {
            File appDir = jarFile.getParentFile();
            File batFile = new File(appDir, "bearit.bat");
            String javaHome = System.getProperty("java.home");
            String javawPath = new File(javaHome, "bin" + File.separator + "javaw.exe").getAbsolutePath();
            
            // The %* dynamically passes any and all file paths/arguments straight into your JAR
            String batContent = "@echo off\r\nstart \"\" \"" + javawPath + "\" -jar \"" + jarFile.getAbsolutePath() + "\" %*";
            Files.write(batFile.toPath(), batContent.getBytes());
        } catch (Exception e) {
            System.err.println("Failed to create batch file: " + e.getMessage());
        }
    }
}