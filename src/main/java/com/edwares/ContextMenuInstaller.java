package com.edwares;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

public class ContextMenuInstaller {

    public static void install(Component parent) {
        String os = System.getProperty("os.name").toLowerCase();
        
        try {
            String jarPath = getRunningJarPath();

            if (os.contains("win")) {
                installForWindows(parent, jarPath);
            } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                installForUbuntu(parent, javaPath, jarPath);
            } else if (os.contains("mac")) {
                showMacNotice(parent);
            } else {
                JOptionPane.showMessageDialog(parent, "Unsupported operating system.", "Install Failed", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parent, "Failed to install context menu: \n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    public static void uninstall(Component parent) {
        String os = System.getProperty("os.name").toLowerCase();
        
        try {
            if (os.contains("win")) {
                uninstallForWindows(parent);
            } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                uninstallForUbuntu(parent);
            } else if (os.contains("mac")) {
                showMacNotice(parent);
            } else {
                JOptionPane.showMessageDialog(parent, "Unsupported operating system.", "Uninstall Failed", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parent, "Failed to uninstall context menu: \n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private static String getRunningJarPath() throws URISyntaxException {
        String path = ContextMenuInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        if (System.getProperty("os.name").toLowerCase().contains("win") && path.startsWith("/")) {
            path = path.substring(1);
        }
        return new File(path).getAbsolutePath();
    }

    // --- Windows Implementation ---

    private static void installForWindows(Component parent, String jarPath) throws Exception {
        // Ensure the shortcut and icon are generated first using your utility
        ShortcutUtil.ensureShortcutExists();

        File jarFile = new File(jarPath);
        File parentDir = jarFile.getParentFile();
        File shortcutFile = new File(parentDir, ShortcutUtil.WIN_SHORTCUT_NAME);
        File iconFile = new File(parentDir, ShortcutUtil.WIN_ICON_NAME);

        // To safely execute a .lnk file from the Windows registry, we use 'cmd.exe /c start'
        // The empty quotes "" act as the window title parameter to 'start'
        String command = String.format("cmd.exe /c start \"\" \"%s\" \"%%1\"", shortcutFile.getAbsolutePath());
        
        String regAddMenu = "reg add \"HKCU\\Software\\Classes\\*\\shell\\Bearit\" /ve /d \"Edit with Bearit\" /f";
        String regAddIcon = String.format("reg add \"HKCU\\Software\\Classes\\*\\shell\\Bearit\" /v Icon /t REG_SZ /d \"%s\" /f", iconFile.getAbsolutePath().replace("\"", "\\\""));
        String regAddCmd = String.format("reg add \"HKCU\\Software\\Classes\\*\\shell\\Bearit\\command\" /ve /d \"%s\" /f", command.replace("\"", "\\\""));

        Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", regAddMenu}).waitFor();
        Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", regAddIcon}).waitFor();
        Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", regAddCmd}).waitFor();

        JOptionPane.showMessageDialog(parent, "Context menu installed successfully!\nRight-click any file in Windows Explorer to see 'Edit with Bearit'.", "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void uninstallForWindows(Component parent) throws Exception {
        String regDelete = "reg delete \"HKCU\\Software\\Classes\\*\\shell\\Bearit\" /f";
        Process p = Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", regDelete});
        p.waitFor();
        
        // Exit code 0 is success, 1 usually means the key didn't exist in the first place
        if (p.exitValue() == 0 || p.exitValue() == 1) {
            JOptionPane.showMessageDialog(parent, "Context menu removed successfully from Windows.", "Success", JOptionPane.INFORMATION_MESSAGE);
        } else {
            throw new Exception("Registry deletion failed with exit code: " + p.exitValue());
        }
    }

    // --- Ubuntu Implementation ---

    private static void installForUbuntu(Component parent, String javaPath, String jarPath) throws Exception {
        String userHome = System.getProperty("user.home");
        Path scriptsDir = Paths.get(userHome, ".local", "share", "nautilus", "scripts");
        
        if (!Files.exists(scriptsDir)) {
            Files.createDirectories(scriptsDir);
        }

        Path scriptPath = scriptsDir.resolve("Edit with Bearit");
        String scriptContent = "#!/bin/bash\n" +
                               "\"" + javaPath + "\" -jar \"" + jarPath + "\" \"$1\"\n";
        
        Files.write(scriptPath, scriptContent.getBytes());

        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        perms.add(PosixFilePermission.GROUP_READ);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        perms.add(PosixFilePermission.OTHERS_READ);
        perms.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(scriptPath, perms);

        JOptionPane.showMessageDialog(parent, "Context menu script installed successfully!\nRight-click any file in Nautilus -> Scripts -> Edit with Bearit.", "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void uninstallForUbuntu(Component parent) throws Exception {
        String userHome = System.getProperty("user.home");
        Path scriptPath = Paths.get(userHome, ".local", "share", "nautilus", "scripts", "Edit with Bearit");
        
        if (Files.exists(scriptPath)) {
            Files.delete(scriptPath);
            JOptionPane.showMessageDialog(parent, "Context menu script removed successfully from Nautilus.", "Success", JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(parent, "Context menu script was not found. It may have already been removed.", "Notice", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // --- macOS Notice ---

    private static void showMacNotice(Component parent) {
        String message = "macOS restricts automatic context menu modifications for security reasons.\n\n" +
                         "To add Bearit to your right-click menu, you must either:\n" +
                         "1. Package Bearit as a native macOS .app bundle using a tool like 'jpackage'.\n" +
                         "2. Create an Automator 'Quick Action' that passes the file input to your java -jar command.";
        
        JOptionPane.showMessageDialog(parent, message, "macOS Notice", JOptionPane.INFORMATION_MESSAGE);
    }
}