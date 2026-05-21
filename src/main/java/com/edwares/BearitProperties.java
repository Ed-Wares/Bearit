package com.edwares;

import java.io.*;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class BearitProperties {
    private static BearitProperties instance;
    private final File propertiesFile;
    private final Properties props;

    // --- Application Settings ---
    private List<String> recentFiles = new ArrayList<>();
    private int frameWidth = 950;
    private int frameHeight = 700;
    private boolean checkForUpdates = true;
    private String fontName = "Monospaced";
    private int fontSize = 14;
    private final String[] customToolCommands = new String[8];
    private final String[] customToolIcons = new String[8];
    private final String[] customToolNames = new String[8];

    private BearitProperties() {
        props = new Properties();
        propertiesFile = determinePropertiesFile();
        
        for (int i = 0; i < 8; i++) {
            customToolCommands[i] = "";
            customToolIcons[i] = "";
            customToolNames[i] = "Tool " + (i + 1);
        }
        
        load();
    }

    public static BearitProperties getInstance() {
        if (instance == null) {
            instance = new BearitProperties();
        }
        return instance;
    }

    private File determinePropertiesFile() {
        try {
            File jarPath = new File(BearitProperties.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (jarPath.isFile()) {
                return new File(jarPath.getParentFile(), "bearit.properties");
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            // Ignore and fallback to current working directory
        }
        return new File("bearit.properties");
    }

    public void load() {
        if (!propertiesFile.exists()) {
            save(); 
            return;
        }
        
        try (InputStream in = new FileInputStream(propertiesFile)) {
            props.load(in);
            
            frameWidth = Integer.parseInt(props.getProperty("frame.width", "950"));
            frameHeight = Integer.parseInt(props.getProperty("frame.height", "700"));
            checkForUpdates = Boolean.parseBoolean(props.getProperty("updates.check", "true"));
            fontName = props.getProperty("font.name", "Monospaced");
            fontSize = Integer.parseInt(props.getProperty("font.size", "14"));

            recentFiles.clear();
            for (int i = 1; i <= 10; i++) {
                String file = props.getProperty("recent." + i);
                if (file != null && !file.trim().isEmpty()) {
                    recentFiles.add(file.trim());
                }
            }

            for (int i = 0; i < 8; i++) {
                customToolCommands[i] = props.getProperty("tool." + (i + 1) + ".command", "");
                customToolIcons[i] = props.getProperty("tool." + (i + 1) + ".icon", "");
                customToolNames[i] = props.getProperty("tool." + (i + 1) + ".name", "Tool " + (i + 1));
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("Failed to load properties: " + e.getMessage());
        }
    }

    public void save() {
        props.setProperty("frame.width", String.valueOf(frameWidth));
        props.setProperty("frame.height", String.valueOf(frameHeight));
        props.setProperty("updates.check", String.valueOf(checkForUpdates));
        props.setProperty("font.name", fontName);
        props.setProperty("font.size", String.valueOf(fontSize));

        // Clear out old recent file keys
        for (int i = 1; i <= 10; i++) {
            props.remove("recent." + i);
        }
        for (int i = 0; i < recentFiles.size(); i++) {
            props.setProperty("recent." + (i + 1), recentFiles.get(i));
        }

        for (int i = 0; i < 8; i++) {
            props.setProperty("tool." + (i + 1) + ".command", customToolCommands[i] != null ? customToolCommands[i] : "");
            props.setProperty("tool." + (i + 1) + ".icon", customToolIcons[i] != null ? customToolIcons[i].replace("\\", "/") : "");
            props.setProperty("tool." + (i + 1) + ".name", customToolNames[i] != null ? customToolNames[i] : "Tool " + (i + 1));
        }

        try (OutputStream out = new FileOutputStream(propertiesFile)) {
            props.store(out, "Bearit Text Editor Configuration");
        } catch (IOException e) {
            System.err.println("Failed to save properties: " + e.getMessage());
        }
    }

    // --- Data Management Methods ---

    public void addRecentFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) return;
        
        // Remove if it already exists to move it to the top
        recentFiles.remove(filePath);
        recentFiles.add(0, filePath);
        
        // Trim to 10 items
        if (recentFiles.size() > 10) {
            recentFiles = new ArrayList<>(recentFiles.subList(0, 10));
        }
        save();
    }

    public void clearRecentFiles() {
        recentFiles.clear();
        save();
    }

    // --- Getters & Setters ---

    public List<String> getRecentFiles() { return new ArrayList<>(recentFiles); }
    
    public int getFrameWidth() { return frameWidth; }
    public void setFrameWidth(int frameWidth) { this.frameWidth = frameWidth; save(); }

    public int getFrameHeight() { return frameHeight; }
    public void setFrameHeight(int frameHeight) { this.frameHeight = frameHeight; save(); }

    public boolean isCheckForUpdates() { return checkForUpdates; }
    public void setCheckForUpdates(boolean checkForUpdates) { this.checkForUpdates = checkForUpdates; save(); }

    public String getFontName() { return fontName; }
    public void setFontName(String fontName) { this.fontName = fontName; save(); }

    public int getFontSize() { return fontSize; }
    public void setFontSize(int fontSize) { this.fontSize = fontSize; save(); }

    public String getCustomToolCommand(int index) {
        if (index >= 0 && index < 8) return customToolCommands[index];
        return "";
    }
    public void setCustomToolCommand(int index, String command) {
        if (index >= 0 && index < 8) { customToolCommands[index] = command; save(); }
    }

    public String getCustomToolIcon(int index) {
        if (index >= 0 && index < 8) return customToolIcons[index];
        return "";
    }
    public void setCustomToolIcon(int index, String iconPath) {
        if (index >= 0 && index < 8) { customToolIcons[index] = iconPath; save(); }
    }

    public String getCustomToolName(int index) {
        if (index >= 0 && index < 8) return customToolNames[index];
        return "";
    }
    public void setCustomToolName(int index, String name) {
        if (index >= 0 && index < 8) { customToolNames[index] = name; save(); }
    }
}