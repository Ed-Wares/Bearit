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
                //JOptionPane.showMessageDialog(parent, "Unsupported operating system.", "Install Failed", JOptionPane.ERROR_MESSAGE);
                DialogUtil.showMessageDialog(parent, "Unsupported operating system.", "Install Failed", JOptionPane.ERROR_MESSAGE);
            }
            //DefaultEditorManager.promptAndSetDefault(null); // Prompt the user for default app
        } catch (Exception e) {
            //JOptionPane.showMessageDialog(parent, "Failed to install context menu: \n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            DialogUtil.showMessageDialog(parent, "Failed to install context menu: \n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    public static void uninstall(Component parent) {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            String jarPath = getRunningJarPath();
            if (os.contains("win")) {
                uninstallForWindows(parent, jarPath);
            } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                uninstallForUbuntu(parent);
            } else if (os.contains("mac")) {
                showMacNotice(parent);
            } else {
                //JOptionPane.showMessageDialog(parent, "Unsupported operating system.", "Uninstall Failed", JOptionPane.ERROR_MESSAGE);
                DialogUtil.showMessageDialog(parent, "Unsupported operating system.", "Uninstall Failed", JOptionPane.ERROR_MESSAGE);
            }
            // Clean up the default associations
            DefaultEditorManager.restorePreviousDefault();
        } catch (Exception e) {
            //JOptionPane.showMessageDialog(parent, "Failed to uninstall context menu: \n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            DialogUtil.showMessageDialog(parent, "Failed to uninstall context menu: \n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
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

        File jarFile = new File(jarPath);
        File appDir = jarFile.getParentFile();
        
        // Navigate up one more level to check for the native jpackage launcher
        File installDir = appDir != null ? appDir.getParentFile() : null;
        File nativeExe = installDir != null ? new File(installDir, "bearit.exe") : null;

        String command;
        String iconPath;
        String targetPathDir; // The directory we will add to the PATH

        if (nativeExe != null && nativeExe.exists()) {
            // --- JPACKAGE NATIVE INSTALL DETECTED ---
            // Route directly to the native Windows executable.
            // The "%1" passes the right-clicked file path directly to your main args!
            command = String.format("\"%s\" \"%%1\"", nativeExe.getAbsolutePath());
            // Jpackage executables embed your custom .ico directly inside the .exe file, 
            // so Windows can extract the registry icon straight from the launcher itself.
            iconPath = nativeExe.getAbsolutePath();
            targetPathDir = installDir.getAbsolutePath();
            
        } else {
            // --- PORTABLE / JAR EXECUTION DETECTED ---
            // Fallback to the existing ShortcutUtil approach
            ShortcutUtil.ensureShortcutExists();
            File shortcutFile = new File(appDir, ShortcutUtil.WIN_SHORTCUT_NAME);
            File iconFile = new File(appDir, ShortcutUtil.WIN_ICON_NAME);
            // Use cmd.exe to launch the .lnk file safely
            command = String.format("cmd.exe /c start \"\" \"%s\" \"%%1\"", shortcutFile.getAbsolutePath());
            iconPath = iconFile.getAbsolutePath();
            targetPathDir = appDir.getAbsolutePath();
        }

        // Cleanly escape the paths so the Windows Registry string parser doesn't break
        String escapedIconPath = iconPath.replace("\"", "\\\"");
        String escapedCommand = command.replace("\"", "\\\"");

        String regAddMenu = "reg add \"HKCU\\Software\\Classes\\*\\shell\\Bearit\" /ve /d \"Edit with Bearit\" /f";
        String regAddIcon = String.format("reg add \"HKCU\\Software\\Classes\\*\\shell\\Bearit\" /v Icon /t REG_SZ /d \"%s\" /f", escapedIconPath);
        String regAddCmd = String.format("reg add \"HKCU\\Software\\Classes\\*\\shell\\Bearit\\command\" /ve /d \"%s\" /f", escapedCommand);

        Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", regAddMenu}).waitFor();
        Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", regAddIcon}).waitFor();
        Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", regAddCmd}).waitFor();

        // Prompt the user for the PATH integration
        int addPathChoice = JOptionPane.showConfirmDialog(parent,
                "Context menu installed successfully!\nRight-click any file in Windows Explorer to see 'Edit with Bearit'\n\n" +
                "Would you also like to add Bearit to your system PATH\nso you can launch it from the command line?",
                "Add to PATH?", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (addPathChoice == JOptionPane.YES_OPTION) {
            // PowerShell script: Checks if the path exists before appending it to prevent duplicates
            String psScript = String.format(
                    "$p = [Environment]::GetEnvironmentVariable('Path', 'User'); " +
                    "if ($p -notmatch [regex]::Escape('%s')) { " +
                    "[Environment]::SetEnvironmentVariable('Path', $p + ';%s', 'User') }",
                    targetPathDir, targetPathDir);

            Runtime.getRuntime().exec(new String[]{"powershell.exe", "-Command", psScript}).waitFor();
            
            DialogUtil.showMessageDialog(parent, 
                    "Successfully added to PATH!\nPlease restart any open command prompts to use the 'bearit' command.", 
                    "Success", JOptionPane.INFORMATION_MESSAGE);
        } else {
            // Fallback success message if they only wanted the context menu
            DialogUtil.showMessageDialog(parent, "Setup complete.", "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static void uninstallForWindows(Component parent, String jarPath) throws Exception {
        // Detect the execution path so we know exactly what folder to rip out of the PATH variable
        File jarFile = new File(jarPath);
        File appDir = jarFile.getParentFile();
        File installDir = appDir != null ? appDir.getParentFile() : null;
        File nativeExe = installDir != null ? new File(installDir, "bearit.exe") : null;

        String targetPathDir = (nativeExe != null && nativeExe.exists()) 
                                ? installDir.getAbsolutePath() 
                                : appDir.getAbsolutePath();

        // Remove the Context Menu Registry Keys
        String regDelete = "reg delete \"HKCU\\Software\\Classes\\*\\shell\\Bearit\" /f";
        Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", regDelete}).waitFor();

        // Prompt the user to clean up the PATH integration
        int removePathChoice = JOptionPane.showConfirmDialog(parent,
                "Context menu removed successfully.\n\nWould you also like to remove Bearit from your system PATH?",
                "Remove from PATH?", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (removePathChoice == JOptionPane.YES_OPTION) {
            // PowerShell script: Splits the PATH array, filters out the Bearit directory, and joins it back together
            String psScript = String.format(
                    "$p = [Environment]::GetEnvironmentVariable('Path', 'User'); " +
                    "$newP = ($p -split ';' | Where-Object { $_ -ne '%s' -and $_ -ne '' }) -join ';'; " +
                    "[Environment]::SetEnvironmentVariable('Path', $newP, 'User')",
                    targetPathDir);

            Runtime.getRuntime().exec(new String[]{"powershell.exe", "-Command", psScript}).waitFor();
            
            DialogUtil.showMessageDialog(parent, 
                    "Successfully removed from PATH!\nPlease restart any open command prompts.", 
                    "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // --- Ubuntu/Linux Implementation ---

    private static void installForUbuntu(Component parent, String javaPath, String jarPath) throws Exception {
        String userHome = System.getProperty("user.home");
        Path nautilusScriptsDir = Paths.get(userHome, ".local", "share", "nautilus", "scripts");
        Path cajaScriptsDir = Paths.get(userHome, ".config", "caja", "scripts");
        
        // --- Detect Native Installation vs Portable/Jar Execution ---
        String scriptContent = "";
        Path symlinkPath = Paths.get("/usr/local/bin/bearit");
        
        if (Files.exists(symlinkPath)) {
            // The .deb package is installed. Route through the native system alias.
            // Using the absolute path ensures it works even if the file manager's environment PATH is restricted.
            scriptContent = "#!/bin/bash\n" +
                            "/usr/local/bin/bearit \"$1\"\n";
        } else {
            // No symlink found. Fall back to calling the JVM and Jar directly.
            scriptContent = "#!/bin/bash\n" +
                            "\"" + javaPath + "\" -jar \"" + jarPath + "\" \"$1\"\n";
        }

        boolean installed = false;

        // Install for Nautilus
        if (!Files.exists(nautilusScriptsDir)) {
            Files.createDirectories(nautilusScriptsDir);
        }
        Path nautilusScriptPath = nautilusScriptsDir.resolve("Edit with Bearit");
        Files.write(nautilusScriptPath, scriptContent.getBytes());
        setExecutablePermissions(nautilusScriptPath);
        installed = true;

        // Install for Caja
        if (!Files.exists(cajaScriptsDir)) {
            Files.createDirectories(cajaScriptsDir);
        }
        Path cajaScriptPath = cajaScriptsDir.resolve("Edit with Bearit");
        Files.write(cajaScriptPath, scriptContent.getBytes());
        setExecutablePermissions(cajaScriptPath);
        installed = true;

        if (installed) {
            //JOptionPane.showMessageDialog(parent, "Context menu script installed successfully!\nRight-click any file in Nautilus or Caja -> Scripts -> Edit with Bearit.", "Success", JOptionPane.INFORMATION_MESSAGE);
            DialogUtil.showMessageDialog(parent, "Context menu script installed successfully!\nRight-click any file in Nautilus or Caja -> Scripts -> Edit with Bearit.", "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static void uninstallForUbuntu(Component parent) throws Exception {
        String userHome = System.getProperty("user.home");
        Path nautilusScriptPath = Paths.get(userHome, ".local", "share", "nautilus", "scripts", "Edit with Bearit");
        Path cajaScriptPath = Paths.get(userHome, ".config", "caja", "scripts", "Edit with Bearit");
        
        boolean removedNautilus = false;
        boolean removedCaja = false;

        if (Files.exists(nautilusScriptPath)) {
            Files.delete(nautilusScriptPath);
            removedNautilus = true;
        }

        if (Files.exists(cajaScriptPath)) {
            Files.delete(cajaScriptPath);
            removedCaja = true;
        }

        if (removedNautilus || removedCaja) {
            //JOptionPane.showMessageDialog(parent, "Context menu script removed successfully from Linux file managers.", "Success", JOptionPane.INFORMATION_MESSAGE);
            DialogUtil.showMessageDialog(parent, "Context menu script removed successfully from Linux file managers.", "Success", JOptionPane.INFORMATION_MESSAGE);
        } else {
            //JOptionPane.showMessageDialog(parent, "Context menu scripts were not found. They may have already been removed.", "Notice", JOptionPane.INFORMATION_MESSAGE);
            DialogUtil.showMessageDialog(parent, "Context menu scripts were not found. They may have already been removed.", "Notice", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static void setExecutablePermissions(Path scriptPath) throws Exception {
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        perms.add(PosixFilePermission.GROUP_READ);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        perms.add(PosixFilePermission.OTHERS_READ);
        perms.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(scriptPath, perms);
    }

    // --- macOS Notice ---

    private static void showMacNotice(Component parent) {
        String message = "macOS restricts automatic context menu modifications for security reasons.\n\n" +
                         "To add Bearit to your right-click menu, you must either:\n" +
                         "1. Package Bearit as a native macOS .app bundle using a tool like 'jpackage'.\n" +
                         "2. Create an Automator 'Quick Action' that passes the file input to your java -jar command.";
        
        //JOptionPane.showMessageDialog(parent, message, "macOS Notice", JOptionPane.INFORMATION_MESSAGE);
        DialogUtil.showMessageDialog(parent, message, "macOS Notice", JOptionPane.INFORMATION_MESSAGE);
    }
}