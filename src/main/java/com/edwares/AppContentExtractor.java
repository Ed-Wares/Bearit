package com.edwares;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * A utility class for extracting internal application content folder "app-content/" from this jar file to 
 * the same directory where the jar is located on the hard drive. This allows us to package default
 * config files and other resources inside the JAR, and have them automatically extracted on first run
 */

public class AppContentExtractor {

    private static final String RESOURCE_FOLDER = "app-content/";

    public static File getAppContentDir() {
        String userHome = System.getProperty("user.home");
        // Create a hidden directory for your app: ~/.bearit/
        return new File(new File(userHome, ".bearit"), RESOURCE_FOLDER);
    }

    public static void extractIfPresent() {
        try {
            // Get the physical path of the running application
            File jarPath = new File(AppContentExtractor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            
            // Safety Check: Only execute if we are actually running from a compiled .jar file
            if (!jarPath.isFile() || !jarPath.getName().toLowerCase().endsWith(".jar")) {
                System.out.println("Not running from a JAR. Skipping internal resource extraction.");
                return;
            }
            File targetDirectory = getAppContentDir().getParentFile();
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs(); // Safely creates the directory if it doesn't exist
            }
            boolean extractedAnything = false;

            // Open the JAR file to read its internal manifest/entries
            try (JarFile jar = new JarFile(jarPath)) {
                Enumeration<JarEntry> entries = jar.entries();

                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();

                    // Check if the entry lives inside our target folder
                    if (entryName.startsWith(RESOURCE_FOLDER)) {
                        File destFile = new File(targetDirectory, entryName);

                        if (entry.isDirectory()) {
                            // Recreate the directory structure
                            destFile.mkdirs();
                        } else {
                            // Ensure the parent folder exists (JAR entries aren't strictly ordered)
                            destFile.getParentFile().mkdirs();

                            // SAFETY: Only extract if the file doesn't already exist on the hard drive.
                            // This prevents Bearit from overwriting user-modified configs every time it boots!
                            if (!destFile.exists()) {
                                try (InputStream is = jar.getInputStream(entry)) {
                                    Files.copy(is, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                    extractedAnything = true;
                                }
                            }
                        }
                    }
                }
            }

            if (extractedAnything) {
                System.out.println("Successfully extracted '" + RESOURCE_FOLDER + "' to " + targetDirectory.getAbsolutePath());
            }

        } catch (Exception e) {
            System.err.println("Failed to extract internal app-content: " + e.getMessage());
            e.printStackTrace();
        }
    }
}