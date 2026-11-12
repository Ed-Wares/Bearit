package com.edwares;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class MacContextMenuInstallerTest {

    @Test
    public void testFindMacAppBundleFromJpackageJarPath(@TempDir Path tempDir) throws Exception {
        Path appBundle = tempDir.resolve("bearit.app");
        Path contentsDir = appBundle.resolve("Contents");
        Path appJarDir = contentsDir.resolve("app");
        Files.createDirectories(appJarDir);

        String infoPlist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n" +
                "<dict>\n" +
                "\t<key>CFBundleIdentifier</key>\n" +
                "\t<string>com.edwares</string>\n" +
                "</dict>\n" +
                "</plist>\n";
        Files.write(contentsDir.resolve("Info.plist"), infoPlist.getBytes(StandardCharsets.UTF_8));

        Path jarFile = appJarDir.resolve("bearit-1.2.1.jar");
        Files.write(jarFile, new byte[]{0});

        File detected = ContextMenuInstaller.findMacAppBundle(jarFile.toFile().getAbsolutePath());
        assertNotNull(detected, "Should detect .app bundle from jpackage jar path");
        assertEquals(appBundle.toFile().getCanonicalPath(), detected.getCanonicalPath());

        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            String bundleId = ContextMenuInstaller.extractMacBundleId(detected);
            assertEquals("com.edwares", bundleId);
        }
    }

    @Test
    public void testGeneratedMacWorkflowPlistsAreValid(@TempDir Path tempDir) throws Exception {
        String infoPlist = ContextMenuInstaller.buildMacWorkflowInfoPlist();
        String wflowDoc = ContextMenuInstaller.buildMacWorkflowDocument("for f in \"$@\"; do\n    open -a \"/Applications/bearit.app\" \"$f\"\ndone");

        assertTrue(infoPlist.contains("Edit with Bearit"));
        assertTrue(infoPlist.contains("com.apple.finder"));
        assertTrue(wflowDoc.contains("com.apple.RunShellScript"));
        assertTrue(wflowDoc.contains("open -a \"/Applications/bearit.app\""));

        Path infoFile = tempDir.resolve("Info.plist");
        Path wflowFile = tempDir.resolve("document.wflow");
        Files.write(infoFile, infoPlist.getBytes(StandardCharsets.UTF_8));
        Files.write(wflowFile, wflowDoc.getBytes(StandardCharsets.UTF_8));

        if (System.getProperty("os.name").toLowerCase().contains("mac") && new File("/usr/bin/plutil").exists()) {
            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
            Process p1 = new ProcessBuilder("/usr/bin/plutil", "-lint", infoFile.toString()).start();
            assertEquals(0, p1.waitFor(), "Workflow Info.plist should pass plutil -lint");

            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
            Process p2 = new ProcessBuilder("/usr/bin/plutil", "-lint", wflowFile.toString()).start();
            assertEquals(0, p2.waitFor(), "Workflow document.wflow should pass plutil -lint");
        }
    }

    @Test
    public void testMacTextUtisConfigured() {
        assertTrue(Arrays.asList(DefaultEditorManager.MAC_TEXT_UTIS).contains("public.plain-text"));
        assertTrue(Arrays.asList(DefaultEditorManager.MAC_TEXT_UTIS).contains("public.text"));
        assertTrue(Arrays.asList(DefaultEditorManager.MAC_TEXT_UTIS).contains("public.source-code"));
    }
}

