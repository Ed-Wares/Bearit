package com.edwares;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class BearitProperties {
    public static final String PROPERTIES_FILENAME = "bearit.properties";
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
    private boolean wordWrap = false; 
    
    // --- NEW: View Settings ---
    private String theme = "Light"; // "Light" or "Dark"
    private boolean showWhitespace = false;
    private boolean showEol = false;
    
    private final String[] customToolCommands = new String[8];
    private final String[] customToolIcons = new String[8];
    private final String[] customToolNames = new String[8]; 

    private BearitProperties() {
        props = new Properties();
        propertiesFile = getPropertiesFile();
        
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

    public File getPropertiesFile() {
        try {
            String userHome = System.getProperty("user.home"); // Gets "C:\Users\Username" on Windows or "/home/username" on Linux
            // Create a hidden directory for your app: ~/.bearit/
            File appDir = new File(userHome, ".bearit");
            if (!appDir.exists()) {
                appDir.mkdirs(); // Safely creates the directory if it doesn't exist
            }
            // Return the safe path: ~/.bearit/bearit.properties
            return new File(appDir, PROPERTIES_FILENAME);
        } catch (Exception e) {
            // Ignore and fallback to current working directory
        }
        return new File(PROPERTIES_FILENAME);
    }

    public void load() {
        boolean createPropertiesFile = false;
        if (!propertiesFile.exists()) {
            createPropertiesFile = true;
            // Load the default properties bundled INSIDE the JAR if no external file exists, and then save it to create the external file for future edits
            try (java.io.InputStream in = getClass().getResourceAsStream("/" + PROPERTIES_FILENAME)) {
                if (in != null) {
                    System.out.println("No external properties file found. Loading defaults from internal resource.");
                    props.load(in);
                }
            } catch (java.io.IOException e) {
                System.err.println("Default internal properties not found.");
            }            
        }
        else {
            try (InputStream in = new FileInputStream(propertiesFile)) {
                System.out.println("Loading properties from file: " + propertiesFile.getAbsolutePath());
                props.load(in);
            } catch (IOException e) {
                System.err.println("Failed to load properties: " + e.getMessage());
            }     
        }
        
        try {
            frameWidth = Integer.parseInt(props.getProperty("frame.width", "950"));
            frameHeight = Integer.parseInt(props.getProperty("frame.height", "700"));
            checkForUpdates = Boolean.parseBoolean(props.getProperty("updates.check", "true"));
            fontName = props.getProperty("font.name", "Monospaced");
            fontSize = Integer.parseInt(props.getProperty("font.size", "14"));
            wordWrap = Boolean.parseBoolean(props.getProperty("word.wrap", "false"));
            theme = props.getProperty("theme", "Light");
            showWhitespace = Boolean.parseBoolean(props.getProperty("show.whitespace", "false"));
            showEol = Boolean.parseBoolean(props.getProperty("show.eol", "false"));

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
        } catch (NumberFormatException e) {
            System.err.println("Failed to load properties: " + e.getMessage());
        }
        if (createPropertiesFile) {
            System.out.println("Creating new properties file with default settings at: " + propertiesFile.getAbsolutePath());
            save();
        }
    }

    public void save() {
        props.setProperty("frame.width", String.valueOf(frameWidth));
        props.setProperty("frame.height", String.valueOf(frameHeight));
        props.setProperty("updates.check", String.valueOf(checkForUpdates));
        props.setProperty("font.name", fontName);
        props.setProperty("font.size", String.valueOf(fontSize));
        props.setProperty("word.wrap", String.valueOf(wordWrap));
        props.setProperty("theme", theme);
        props.setProperty("show.whitespace", String.valueOf(showWhitespace));
        props.setProperty("show.eol", String.valueOf(showEol));

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

    // methods to handle saving and loading the session state (the list of open file paths)
    public void saveSession(List<String> filePaths, int activeIndex) {
        // Clear old session
        for (int i = 0; i < 20; i++) { props.remove("session.file." + i); }
        
        // Save new session
        for (int i = 0; i < filePaths.size(); i++) {
            props.setProperty("session.file." + i, filePaths.get(i));
        }
        props.setProperty("session.count", String.valueOf(filePaths.size()));
        props.setProperty("session.active.index", String.valueOf(activeIndex));
        save();
    }
    
    public int getSessionActiveIndex() {
        return Integer.parseInt(props.getProperty("session.active.index", "0"));
    }

    public List<String> getSession() {
        List<String> session = new ArrayList<>();
        int count = Integer.parseInt(props.getProperty("session.count", "0"));
        for (int i = 0; i < count; i++) {
            String path = props.getProperty("session.file." + i);
            if (path != null) session.add(path);
        }
        return session;
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
    public boolean isWordWrap() { return wordWrap; }
    public void setWordWrap(boolean wordWrap) { this.wordWrap = wordWrap; save(); }
    
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; save(); }
    public boolean isShowWhitespace() { return showWhitespace; }
    public void setShowWhitespace(boolean showWhitespace) { this.showWhitespace = showWhitespace; save(); }
    public boolean isShowEol() { return showEol; }
    public void setShowEol(boolean showEol) { this.showEol = showEol; save(); }

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

    public int getMaxLineLength() {
        try {
            return Integer.parseInt(props.getProperty("max.line.length", "20000"));
        } catch (NumberFormatException e) {
            return 20000;
        }
    }

    public void setMaxLineLength(int length) {
        props.setProperty("max.line.length", String.valueOf(length));
    }

    public java.util.List<String> getSearchHistory() {
        return getHistoryList("search.history");
    }

    public void addSearchHistory(String term) {
        addHistoryItem("search.history", term, 15);
    }

    public java.util.List<String> getReplaceHistory() {
        return getHistoryList("replace.history");
    }

    public void addReplaceHistory(String term) {
        addHistoryItem("replace.history", term, 15);
    }

    private java.util.List<String> getHistoryList(String key) {
        java.util.List<String> list = new java.util.ArrayList<>();
        String raw = props.getProperty(key, "");
        if (!raw.isEmpty()) {
            String[] items = raw.split(":::");
            for (String item : items) {
                if (!item.isEmpty()) list.add(item);
            }
        }
        return list;
    }

    private void addHistoryItem(String key, String term, int max) {
        java.util.List<String> list = getHistoryList(key);
        list.remove(term); // Remove if it already exists to move it to the top
        list.add(0, term);
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
        props.setProperty(key, String.join(":::", list));
        // Be sure to call your property save method here (e.g., save() or saveProperties())
    }    

    public boolean isAutoFocusToolOutput() {
        // Default to false so existing behavior remains the same for users
        return Boolean.parseBoolean(props.getProperty("autoFocusToolOutput", "false"));
    }

    public void setAutoFocusToolOutput(boolean focus) {
        props.setProperty("autoFocusToolOutput", String.valueOf(focus));
        save(); // Call your existing method to write to disk
    }

    // Generic getter and setter for any additional properties you want to manage
    public void setProperty(String key, String value) {
        props.setProperty(key, value);
        save();
    }

    public String getProperty(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    // Removes a property completely from the file and saves the change
    public void removeProperty(String key) {
        if (props.containsKey(key)) {
            props.remove(key);
            save();
        }
    }
}