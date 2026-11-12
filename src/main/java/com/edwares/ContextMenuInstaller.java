package com.edwares;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

public class ContextMenuInstaller {

    private static final String LSREGISTER_PATH =
            "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister";
    private static final String PBS_PATH = "/System/Library/CoreServices/pbs";
    private static final String MAC_WORKFLOW_NAME = "Edit with Bearit.workflow";

    private static final String MAC_DOCUMENT_TYPES_XML =
            "<array>" +
            "<dict>" +
            "<key>CFBundleTypeName</key><string>Text Document</string>" +
            "<key>CFBundleTypeRole</key><string>Editor</string>" +
            "<key>LSHandlerRank</key><string>Default</string>" +
            "<key>CFBundleTypeIconFile</key><string>bearit.icns</string>" +
            "<key>LSItemContentTypes</key>" +
            "<array>" +
            "<string>public.plain-text</string>" +
            "<string>public.utf8-plain-text</string>" +
            "<string>public.text</string>" +
            "<string>public.source-code</string>" +
            "<string>public.script</string>" +
            "<string>public.shell-script</string>" +
            "<string>public.xml</string>" +
            "<string>public.json</string>" +
            "<string>public.yaml</string>" +
            "<string>public.comma-separated-values-text</string>" +
            "<string>public.log</string>" +
            "<string>com.apple.log</string>" +
            "<string>com.apple.property-list</string>" +
            "</array>" +
            "</dict>" +
            "<dict>" +
            "<key>CFBundleTypeName</key><string>All Files</string>" +
            "<key>CFBundleTypeRole</key><string>Editor</string>" +
            "<key>LSHandlerRank</key><string>Alternate</string>" +
            "<key>CFBundleTypeIconFile</key><string>bearit.icns</string>" +
            "<key>LSItemContentTypes</key>" +
            "<array>" +
            "<string>public.data</string>" +
            "<string>public.content</string>" +
            "<string>public.item</string>" +
            "</array>" +
            "</dict>" +
            "</array>";

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
                String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                installForMac(parent, javaPath, jarPath);
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
                uninstallForMac(parent, jarPath);
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
        Path symlinkPath = Paths.get("/usr/local/bin/bearit");
        boolean isNative = Files.exists(symlinkPath);

        // --- Resolve Absolute Icon Path ---
        String iconPath = "text-editor"; // Safe generic fallback
        if (isNative) {
            // jpackage usually places the native icon here
            File nativeIcon = new File("/opt/bearit/lib/bearit.png");
            if (nativeIcon.exists()) {
                iconPath = nativeIcon.getAbsolutePath();
            } else {
                // If the specific file name varies, fallback to the registered system name
                iconPath = "bearit"; 
            }
        } else {
            // Portable mode: Look for a bearit.png in the same folder as the jar
            File portableIcon = new File(new File(jarPath).getParentFile(), "bearit.png");
            if (portableIcon.exists()) {
                iconPath = portableIcon.getAbsolutePath();
            }
        }

        // --- Create the Execution Command ---
        // The '%F' is critical: It tells Linux to pass the exact file path(s) into your application natively.
        String execCommand = isNative
                ? "/usr/local/bin/bearit %F"
                : "\"" + javaPath + "\" -jar \"" + jarPath + "\" %F";


        // --- NATIVE MAIN MENU INTEGRATION (MIME TYPES) ---
        Path localAppsDir = Paths.get(userHome, ".local", "share", "applications");
        if (!Files.exists(localAppsDir)) {
            Files.createDirectories(localAppsDir);
        }

        // We generate a hidden .desktop file specifically to handle right-click events
        Path desktopFilePath = localAppsDir.resolve("bearit-opener.desktop");
        String desktopContent = "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=Bearit\n" +
                "Exec=" + execCommand + "\n" +
                "Icon=" + iconPath + "\n" + // Uses the absolute path or safe fallback
                "Terminal=false\n" +
                "NoDisplay=true\n" + // Hides this duplicate file from the main App Drawer
                "MimeType=text/plain;application/octet-stream;text/x-java;application/json;text/xml;\n";

        Files.write(desktopFilePath, desktopContent.getBytes());

        // Use the native xdg-mime tool to tell Linux that Bearit is the preferred editor for these files.
        // This instantly pushes Bearit to the very top of the right-click menu in Nautilus and Caja!
        String[] mimesToClaim = {
                "text/plain",               // Standard text files
                "application/octet-stream", // Unknown binaries (perfect for Hex Editing)
                "text/x-java",              // Java source files
                "application/json",         // JSON files
                "text/xml"                  // XML files
        };

        for (String mime : mimesToClaim) {
            Runtime.getRuntime().exec(new String[]{"xdg-mime", "default", "bearit-opener.desktop", mime}).waitFor();
        }

        // Force the desktop environments to immediately reload their right-click menus
        Runtime.getRuntime().exec(new String[]{"update-desktop-database", localAppsDir.toString()}).waitFor();


        // --- SCRIPTS FALLBACK (Keep this for files that aren't strictly text/binary) ---
        Path nautilusScriptsDir = Paths.get(userHome, ".local", "share", "nautilus", "scripts");
        Path cajaScriptsDir = Paths.get(userHome, ".config", "caja", "scripts");
        
        String scriptContent = isNative 
                ? "#!/bin/bash\n/usr/local/bin/bearit \"$1\"\n"
                : "#!/bin/bash\n\"" + javaPath + "\" -jar \"" + jarPath + "\" \"$1\"\n";

        boolean installedScripts = false;

        if (!Files.exists(nautilusScriptsDir)) Files.createDirectories(nautilusScriptsDir);
        Path nautilusScriptPath = nautilusScriptsDir.resolve("Edit with Bearit");
        Files.write(nautilusScriptPath, scriptContent.getBytes());
        setExecutablePermissions(nautilusScriptPath);
        installedScripts = true;

        if (!Files.exists(cajaScriptsDir)) Files.createDirectories(cajaScriptsDir);
        Path cajaScriptPath = cajaScriptsDir.resolve("Edit with Bearit");
        Files.write(cajaScriptPath, scriptContent.getBytes());
        setExecutablePermissions(cajaScriptPath);
        installedScripts = true;


        // --- SUCCESS PROMPT ---
        if (installedScripts) {
            DialogUtil.showMessageDialog(parent, 
                    "Context menu installed successfully!\n\n" +
                    "• Bearit is now at the top of the main right-click menu for Text and Binary files.\n" +
                    "• For unassociated file types, Bearit is still available in the 'Scripts' menu.", 
                    "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static void uninstallForUbuntu(Component parent) throws Exception {
        String userHome = System.getProperty("user.home");
        boolean removedAnything = false;

        // Remove the MIME Type Desktop File
        Path desktopFilePath = Paths.get(userHome, ".local", "share", "applications", "bearit-opener.desktop");
        if (Files.exists(desktopFilePath)) {
            Files.delete(desktopFilePath);
            removedAnything = true;
            
            // Force the desktop environments to refresh and fallback to their default editors (e.g., Gedit)
            Path localAppsDir = Paths.get(userHome, ".local", "share", "applications");
            Runtime.getRuntime().exec(new String[]{"update-desktop-database", localAppsDir.toString()}).waitFor();
        }

        // Remove the Nautilus Script
        Path nautilusScriptPath = Paths.get(userHome, ".local", "share", "nautilus", "scripts", "Edit with Bearit");
        if (Files.exists(nautilusScriptPath)) {
            Files.delete(nautilusScriptPath);
            removedAnything = true;
        }

        // Remove the Caja Script
        Path cajaScriptPath = Paths.get(userHome, ".config", "caja", "scripts", "Edit with Bearit");
        if (Files.exists(cajaScriptPath)) {
            Files.delete(cajaScriptPath);
            removedAnything = true;
        }

        // 4. Success Prompt
        if (removedAnything) {
            DialogUtil.showMessageDialog(parent, 
                    "Context menu integrations removed successfully!\n\n" +
                    "Ubuntu has reverted back to its default text editor.", 
                    "Success", JOptionPane.INFORMATION_MESSAGE);
        } else {
            DialogUtil.showMessageDialog(parent, 
                    "No context menu integrations were found to remove.", 
                    "Info", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static void setExecutablePermissions(Path scriptPath) throws Exception {
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        perms.add(PosixFilePermission.GROUP_READ);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        Files.setPosixFilePermissions(scriptPath, perms);
    }

    // --- macOS Implementation ---

    private static void installForMac(Component parent, String javaPath, String jarPath) throws Exception {
        File appBundle = findMacAppBundle(jarPath);
        boolean registeredAppBundle = false;
        boolean defaultEditorSet = false;

        if (appBundle != null && appBundle.exists()) {
            String bundleId = extractMacBundleId(appBundle);
            registeredAppBundle = registerMacAppDocumentTypes(appBundle);
            defaultEditorSet = DefaultEditorManager.setMacDefault(bundleId);
        }

        // Install Finder Quick Action (~/Library/Services/Edit with Bearit.workflow)
        boolean quickActionInstalled = installMacQuickAction(appBundle, javaPath, jarPath);

        StringBuilder msg = new StringBuilder("Context menu installed successfully!\n\n");
        if (registeredAppBundle) {
            msg.append("• Control+Click any file in Finder and select 'Open With -> ")
               .append(appBundle.getName().replace(".app", ""))
               .append("' to open it in Bearit.\n");
        }
        if (quickActionInstalled) {
            msg.append("• 'Edit with Bearit' is also available under Finder's Control+Click -> Quick Actions menu.\n");
        }
        if (defaultEditorSet) {
            msg.append("• Bearit is now set as your default text editor when double-clicking text files in Finder.");
        } else if (appBundle == null) {
            msg.append("\nNote: Install the jpackage-built Bearit.app into /Applications to also register Bearit in 'Open With' and as the default text editor.");
        }

        DialogUtil.showMessageDialog(parent, msg.toString().trim(), "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void uninstallForMac(Component parent, String jarPath) throws Exception {
        boolean removedAnything = false;

        // 1. Remove the Finder Quick Action workflow
        String userHome = System.getProperty("user.home");
        Path workflowPath = Paths.get(userHome, "Library", "Services", MAC_WORKFLOW_NAME);
        if (Files.exists(workflowPath)) {
            deleteDirectoryRecursively(workflowPath);
            removedAnything = true;
            refreshMacServices();
        }

        // 2. Remove custom CFBundleDocumentTypes from the app bundle and refresh LaunchServices
        File appBundle = findMacAppBundle(jarPath);
        if (appBundle != null && appBundle.exists()) {
            if (unregisterMacAppDocumentTypes(appBundle)) {
                removedAnything = true;
            }
        }

        // 3. Check if a previous default editor was saved
        String prevDefault = BearitProperties.getInstance().getProperty("previous_default_editor", "");
        if (prevDefault != null && !prevDefault.isEmpty()) {
            removedAnything = true;
        }

        if (removedAnything) {
            DialogUtil.showMessageDialog(parent,
                    "Context menu integrations removed successfully!\n\n" +
                    "macOS has reverted back to its previous default text editor.",
                    "Success", JOptionPane.INFORMATION_MESSAGE);
        } else {
            DialogUtil.showMessageDialog(parent,
                    "No context menu integrations were found to remove.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    static File findMacAppBundle(String jarPath) {
        if (jarPath != null) {
            File current = new File(jarPath).getAbsoluteFile();
            while (current != null) {
                if (current.getName().toLowerCase().endsWith(".app")) {
                    File infoPlist = new File(current, "Contents/Info.plist");
                    if (infoPlist.exists()) {
                        return current;
                    }
                }
                current = current.getParentFile();
            }
        }

        String userHome = System.getProperty("user.home");
        File[] candidates = new File[]{
            new File("/Applications/bearit.app"),
            new File("/Applications/Bearit.app"),
            new File(userHome, "Applications/bearit.app"),
            new File(userHome, "Applications/Bearit.app")
        };

        for (File candidate : candidates) {
            if (candidate.exists() && new File(candidate, "Contents/Info.plist").exists()) {
                return candidate;
            }
        }
        return null;
    }

    static String extractMacBundleId(File appBundle) {
        File infoPlist = new File(appBundle, "Contents/Info.plist");
        if (infoPlist.exists()) {
            String output = runProcessAndRead("/usr/bin/plutil", "-extract", "CFBundleIdentifier", "raw", "-o", "-", infoPlist.getAbsolutePath());
            if (output != null && !output.trim().isEmpty()) {
                return output.trim();
            }
        }
        return DefaultEditorManager.DEFAULT_MAC_BUNDLE_ID;
    }

    private static boolean registerMacAppDocumentTypes(File appBundle) {
        File infoPlist = new File(appBundle, "Contents/Info.plist");
        if (!infoPlist.exists()) {
            return false;
        }

        try {
            // Check if CFBundleDocumentTypes already exists with All Files & Text Document
            String existingTypes = runProcessAndRead("/usr/bin/plutil", "-extract", "CFBundleDocumentTypes", "xml1", "-o", "-", infoPlist.getAbsolutePath());
            boolean needsPlistUpdate = existingTypes == null || !existingTypes.contains("public.plain-text") || !existingTypes.contains("public.item");

            if (needsPlistUpdate && infoPlist.canWrite()) {
                runProcess("/usr/bin/plutil", "-replace", "CFBundleDocumentTypes", "-xml", MAC_DOCUMENT_TYPES_XML, infoPlist.getAbsolutePath());
                resignMacAppBundleIfAdhoc(appBundle);
            }

            if (new File(LSREGISTER_PATH).exists()) {
                runProcess(LSREGISTER_PATH, "-f", appBundle.getAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            System.err.println("Failed to register macOS app document types: " + e.getMessage());
            return false;
        }
    }

    private static boolean unregisterMacAppDocumentTypes(File appBundle) {
        File infoPlist = new File(appBundle, "Contents/Info.plist");
        if (!infoPlist.exists() || !infoPlist.canWrite()) {
            return false;
        }

        try {
            String existingTypes = runProcessAndRead("/usr/bin/plutil", "-extract", "CFBundleDocumentTypes", "xml1", "-o", "-", infoPlist.getAbsolutePath());
            if (existingTypes != null && existingTypes.contains("<array>")) {
                runProcess("/usr/bin/plutil", "-remove", "CFBundleDocumentTypes", infoPlist.getAbsolutePath());
                resignMacAppBundleIfAdhoc(appBundle);
                if (new File(LSREGISTER_PATH).exists()) {
                    runProcess(LSREGISTER_PATH, "-f", appBundle.getAbsolutePath());
                }
                return true;
            }
        } catch (Exception e) {
            System.err.println("Failed to unregister macOS app document types: " + e.getMessage());
        }
        return false;
    }

    private static void resignMacAppBundleIfAdhoc(File appBundle) {
        try {
            String sigInfo = runProcessAndReadStderr("/usr/bin/codesign", "-dv", appBundle.getAbsolutePath());
            if (sigInfo != null && sigInfo.contains("Signature=adhoc")) {
                // Unlink and recreate the launcher executable inode before re-signing so an
                // actively running launcher process never has its mapped Mach-O vnode modified in-place.
                File macosDir = new File(appBundle, "Contents/MacOS");
                File[] binaries = macosDir.listFiles();
                if (binaries != null) {
                    for (File bin : binaries) {
                        if (bin.isFile()) {
                            Path binPath = bin.toPath();
                            Path tmpPath = macosDir.toPath().resolve(bin.getName() + ".tmp");
                            Files.copy(binPath, tmpPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            Files.move(tmpPath, binPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        }
                    }
                }
                runProcess("/usr/bin/codesign", "--force", "--sign", "-", appBundle.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("Failed to re-sign macOS app bundle: " + e.getMessage());
        }
    }

    static boolean installMacQuickAction(File appBundle, String javaPath, String jarPath) {
        try {
            String userHome = System.getProperty("user.home");
            Path contentsDir = Paths.get(userHome, "Library", "Services", MAC_WORKFLOW_NAME, "Contents");
            Files.createDirectories(contentsDir);

            String commandString;
            if (appBundle != null && appBundle.exists()) {
                String escapedApp = appBundle.getAbsolutePath().replace("\"", "\\\"");
                commandString = "for f in \"$@\"; do\n    open -a \"" + escapedApp + "\" \"$f\"\ndone";
            } else {
                String escapedJava = javaPath.replace("\"", "\\\"");
                String escapedJar = jarPath.replace("\"", "\\\"");
                commandString = "for f in \"$@\"; do\n    \"" + escapedJava + "\" -jar \"" + escapedJar + "\" \"$f\" &\ndone";
            }

            String infoPlistContent = buildMacWorkflowInfoPlist();
            String documentWflowContent = buildMacWorkflowDocument(commandString);

            Files.write(contentsDir.resolve("Info.plist"), infoPlistContent.getBytes(StandardCharsets.UTF_8));
            Files.write(contentsDir.resolve("document.wflow"), documentWflowContent.getBytes(StandardCharsets.UTF_8));

            refreshMacServices();
            return true;
        } catch (Exception e) {
            System.err.println("Failed to install macOS Finder Quick Action: " + e.getMessage());
            return false;
        }
    }

    static String buildMacWorkflowInfoPlist() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
               "<plist version=\"1.0\">\n" +
               "<dict>\n" +
               "\t<key>CFBundleDevelopmentRegion</key>\n" +
               "\t<string>en_US</string>\n" +
               "\t<key>CFBundleIdentifier</key>\n" +
               "\t<string>com.edwares.bearit.QuickAction</string>\n" +
               "\t<key>CFBundleName</key>\n" +
               "\t<string>Edit with Bearit</string>\n" +
               "\t<key>CFBundleShortVersionString</key>\n" +
               "\t<string>1.0</string>\n" +
               "\t<key>NSServices</key>\n" +
               "\t<array>\n" +
               "\t\t<dict>\n" +
               "\t\t\t<key>NSMenuItem</key>\n" +
               "\t\t\t<dict>\n" +
               "\t\t\t\t<key>default</key>\n" +
               "\t\t\t\t<string>Edit with Bearit</string>\n" +
               "\t\t\t</dict>\n" +
               "\t\t\t<key>NSMessage</key>\n" +
               "\t\t\t<string>runWorkflowAsService</string>\n" +
               "\t\t\t<key>NSRequiredContext</key>\n" +
               "\t\t\t<dict>\n" +
               "\t\t\t\t<key>NSApplicationIdentifier</key>\n" +
               "\t\t\t\t<string>com.apple.finder</string>\n" +
               "\t\t\t</dict>\n" +
               "\t\t\t<key>NSSendFileTypes</key>\n" +
               "\t\t\t<array>\n" +
               "\t\t\t\t<string>public.item</string>\n" +
               "\t\t\t\t<string>public.data</string>\n" +
               "\t\t\t\t<string>public.content</string>\n" +
               "\t\t\t</array>\n" +
               "\t\t</dict>\n" +
               "\t</array>\n" +
               "</dict>\n" +
               "</plist>\n";
    }

    static String buildMacWorkflowDocument(String shellCommand) {
        String escapedCommand = shellCommand
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
               "<plist version=\"1.0\">\n" +
               "<dict>\n" +
               "\t<key>AMApplicationBuild</key>\n" +
               "\t<string>523</string>\n" +
               "\t<key>AMApplicationVersion</key>\n" +
               "\t<string>2.10</string>\n" +
               "\t<key>AMDocumentVersion</key>\n" +
               "\t<string>2</string>\n" +
               "\t<key>actions</key>\n" +
               "\t<array>\n" +
               "\t\t<dict>\n" +
               "\t\t\t<key>action</key>\n" +
               "\t\t\t<dict>\n" +
               "\t\t\t\t<key>AMAccepts</key>\n" +
               "\t\t\t\t<dict>\n" +
               "\t\t\t\t\t<key>Container</key>\n" +
               "\t\t\t\t\t<string>List</string>\n" +
               "\t\t\t\t\t<key>Optional</key>\n" +
               "\t\t\t\t\t<true/>\n" +
               "\t\t\t\t\t<key>Types</key>\n" +
               "\t\t\t\t\t<array>\n" +
               "\t\t\t\t\t\t<string>com.apple.cocoa.string</string>\n" +
               "\t\t\t\t\t</array>\n" +
               "\t\t\t\t</dict>\n" +
               "\t\t\t\t<key>AMActionVersion</key>\n" +
               "\t\t\t\t<string>2.0.3</string>\n" +
               "\t\t\t\t<key>AMApplication</key>\n" +
               "\t\t\t\t<array>\n" +
               "\t\t\t\t\t<string>Automator</string>\n" +
               "\t\t\t\t</array>\n" +
               "\t\t\t\t<key>AMParameterProperties</key>\n" +
               "\t\t\t\t<dict>\n" +
               "\t\t\t\t\t<key>COMMAND_STRING</key>\n" +
               "\t\t\t\t\t<dict/>\n" +
               "\t\t\t\t\t<key>CheckedForUserDefaultShell</key>\n" +
               "\t\t\t\t\t<dict/>\n" +
               "\t\t\t\t\t<key>inputMethod</key>\n" +
               "\t\t\t\t\t<dict/>\n" +
               "\t\t\t\t\t<key>shell</key>\n" +
               "\t\t\t\t\t<dict/>\n" +
               "\t\t\t\t\t<key>source</key>\n" +
               "\t\t\t\t\t<dict/>\n" +
               "\t\t\t\t</dict>\n" +
               "\t\t\t\t<key>AMProvides</key>\n" +
               "\t\t\t\t<dict>\n" +
               "\t\t\t\t\t<key>Container</key>\n" +
               "\t\t\t\t\t<string>List</string>\n" +
               "\t\t\t\t\t<key>Types</key>\n" +
               "\t\t\t\t\t<array>\n" +
               "\t\t\t\t\t\t<string>com.apple.cocoa.string</string>\n" +
               "\t\t\t\t\t</array>\n" +
               "\t\t\t\t</dict>\n" +
               "\t\t\t\t<key>ActionBundlePath</key>\n" +
               "\t\t\t\t<string>/System/Library/Automator/Run Shell Script.action</string>\n" +
               "\t\t\t\t<key>ActionName</key>\n" +
               "\t\t\t\t<string>Run Shell Script</string>\n" +
               "\t\t\t\t<key>ActionParameters</key>\n" +
               "\t\t\t\t<dict>\n" +
               "\t\t\t\t\t<key>COMMAND_STRING</key>\n" +
               "\t\t\t\t\t<string>" + escapedCommand + "</string>\n" +
               "\t\t\t\t\t<key>CheckedForUserDefaultShell</key>\n" +
               "\t\t\t\t\t<true/>\n" +
               "\t\t\t\t\t<key>inputMethod</key>\n" +
               "\t\t\t\t\t<integer>1</integer>\n" +
               "\t\t\t\t\t<key>shell</key>\n" +
               "\t\t\t\t\t<string>/bin/sh</string>\n" +
               "\t\t\t\t\t<key>source</key>\n" +
               "\t\t\t\t\t<string></string>\n" +
               "\t\t\t\t</dict>\n" +
               "\t\t\t\t<key>BundleIdentifier</key>\n" +
               "\t\t\t\t<string>com.apple.RunShellScript</string>\n" +
               "\t\t\t\t<key>CFBundleVersion</key>\n" +
               "\t\t\t\t<string>2.0.3</string>\n" +
               "\t\t\t\t<key>CanShowSelectedItemsWhenRun</key>\n" +
               "\t\t\t\t<false/>\n" +
               "\t\t\t\t<key>CanShowWhenRun</key>\n" +
               "\t\t\t\t<true/>\n" +
               "\t\t\t\t<key>Category</key>\n" +
               "\t\t\t\t<array>\n" +
               "\t\t\t\t\t<string>AMCategoryUtilities</string>\n" +
               "\t\t\t\t</array>\n" +
               "\t\t\t\t<key>Class Name</key>\n" +
               "\t\t\t\t<string>RunShellScriptAction</string>\n" +
               "\t\t\t\t<key>InputUUID</key>\n" +
               "\t\t\t\t<string>A890A485-7D24-4A31-B53E-96B29C3258A0</string>\n" +
               "\t\t\t\t<key>Keywords</key>\n" +
               "\t\t\t\t<array>\n" +
               "\t\t\t\t\t<string>Shell</string>\n" +
               "\t\t\t\t\t<string>Script</string>\n" +
               "\t\t\t\t\t<string>Command</string>\n" +
               "\t\t\t\t\t<string>Run</string>\n" +
               "\t\t\t\t\t<string>Unix</string>\n" +
               "\t\t\t\t</array>\n" +
               "\t\t\t\t<key>OutputUUID</key>\n" +
               "\t\t\t\t<string>59C9C1F5-89A1-443A-A394-12F66E892D7B</string>\n" +
               "\t\t\t\t<key>UUID</key>\n" +
               "\t\t\t\t<string>2F1E0E70-50E3-4E0E-99BA-45DE9813F101</string>\n" +
               "\t\t\t\t<key>UnlocalizedApplications</key>\n" +
               "\t\t\t\t<array>\n" +
               "\t\t\t\t\t<string>Automator</string>\n" +
               "\t\t\t\t</array>\n" +
               "\t\t\t</dict>\n" +
               "\t\t</dict>\n" +
               "\t</array>\n" +
               "\t<key>connectors</key>\n" +
               "\t<dict/>\n" +
               "\t<key>workflowMetaData</key>\n" +
               "\t<dict>\n" +
               "\t\t<key>applicationBundleID</key>\n" +
               "\t\t<string>com.apple.finder</string>\n" +
               "\t\t<key>applicationBundleIDsByPath</key>\n" +
               "\t\t<dict>\n" +
               "\t\t\t<key>/System/Library/CoreServices/Finder.app</key>\n" +
               "\t\t\t<string>com.apple.finder</string>\n" +
               "\t\t</dict>\n" +
               "\t\t<key>applicationPath</key>\n" +
               "\t\t<string>/System/Library/CoreServices/Finder.app</string>\n" +
               "\t\t<key>applicationPaths</key>\n" +
               "\t\t<array>\n" +
               "\t\t\t<string>/System/Library/CoreServices/Finder.app</string>\n" +
               "\t\t</array>\n" +
               "\t\t<key>inputTypeIdentifier</key>\n" +
               "\t\t<string>com.apple.Automator.fileSystemObject</string>\n" +
               "\t\t<key>outputTypeIdentifier</key>\n" +
               "\t\t<string>com.apple.Automator.nothing</string>\n" +
               "\t\t<key>presentationMode</key>\n" +
               "\t\t<integer>15</integer>\n" +
               "\t\t<key>processesInput</key>\n" +
               "\t\t<integer>0</integer>\n" +
               "\t\t<key>serviceApplicationBundleID</key>\n" +
               "\t\t<string>com.apple.finder</string>\n" +
               "\t\t<key>serviceApplicationPath</key>\n" +
               "\t\t<string>/System/Library/CoreServices/Finder.app</string>\n" +
               "\t\t<key>serviceInputTypeIdentifier</key>\n" +
               "\t\t<string>com.apple.Automator.fileSystemObject</string>\n" +
               "\t\t<key>serviceOutputTypeIdentifier</key>\n" +
               "\t\t<string>com.apple.Automator.nothing</string>\n" +
               "\t\t<key>serviceProcessesInput</key>\n" +
               "\t\t<integer>0</integer>\n" +
               "\t\t<key>systemImageName</key>\n" +
               "\t\t<string>NSActionTemplate</string>\n" +
               "\t\t<key>useAutomaticInputType</key>\n" +
               "\t\t<integer>0</integer>\n" +
               "\t\t<key>workflowTypeIdentifier</key>\n" +
               "\t\t<string>com.apple.Automator.servicesMenu</string>\n" +
               "\t</dict>\n" +
               "</dict>\n" +
               "</plist>\n";
    }

    private static void refreshMacServices() {
        if (new File(PBS_PATH).exists()) {
            runProcess(PBS_PATH, "-flush");
            runProcess(PBS_PATH, "-update");
        }
    }

    private static void deleteDirectoryRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {}
                });
        }
    }

    private static int runProcess(String... command) {
        try {
            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
            Process p = new ProcessBuilder(java.util.Arrays.asList(command)).start();
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String runProcessAndRead(String... command) {
        try {
            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
            Process p = new ProcessBuilder(java.util.Arrays.asList(command)).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            int code = p.waitFor();
            return code == 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String runProcessAndReadStderr(String... command) {
        try {
            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
            ProcessBuilder pb = new ProcessBuilder(java.util.Arrays.asList(command));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}