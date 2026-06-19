package com.edwares;

import java.io.File;

public class CommandLineParser {
    private boolean showHelp = false;
    private Double generateSizeGb = null;
    private File fileToOpen = null;
    
    private boolean hexModeOn = false;
    private boolean textModeOn = false;
    private String selectRange = null;

    public CommandLineParser(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            // Removed -h from the help check
            if ("-?".equals(arg) || "--help".equals(arg)) {
                showHelp = true;
            } else if ("-h".equalsIgnoreCase(arg)) {
                hexModeOn = true;
            } else if ("-t".equalsIgnoreCase(arg)) {
                textModeOn = true;
            } else if ("-s".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    selectRange = args[++i];
                } else {
                    System.err.println("Error: -s requires a range argument (start;end).");
                    showHelp = true;
                }
                // --- Text file generation ---
            } else if ("-g".equals(arg)) {
                if (i + 1 < args.length) {
                    try {
                        generateSizeGb = Double.parseDouble(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println("Error: Invalid size provided for -g. Must be a numeric value.");
                        showHelp = true;
                    }
                } else {
                    System.err.println("Error: -g requires a size argument in GB.");
                    showHelp = true;
                }
                // --- Binary file generation ---
            } else if ("-gb".equals(arg) && i + 1 < args.length) {
                try {
                    double size = Double.parseDouble(args[i + 1]);
                    FileGenUtil.generateBinaryTestFile(size);
                    System.exit(0);
                } catch (Exception e) {
                    System.err.println("Error generating binary file: " + e.getMessage());
                    System.exit(1);
                }                
            } else {
                // If it doesn't match an option flag, treat it as the target file
                if (fileToOpen == null) {
                    fileToOpen = new File(arg);
                } else {
                    System.err.println("Warning: Multiple files specified. Only attempting to open the first one: " + fileToOpen.getName());
                }
            }
        }
    }

    public boolean isShowHelp() { return showHelp; }
    public Double getGenerateSizeGb() { return generateSizeGb; }
    public File getFileToOpen() { return fileToOpen; }
    
    // Getters for the new commands
    public boolean isHexModeOn() { return hexModeOn; }
    public boolean isTextModeOn() { return textModeOn; }
    public String getSelectRange() { return selectRange; }

    public void printHelp() {
        System.out.println("Bearit Text Editor");
        System.out.println("Usage: java -jar bearit-1.0-SNAPSHOT.jar [OPTIONS] [FILE]");
        System.out.println("");
        System.out.println("Options:");
        System.out.println("  -?                        Show this help message and exit.");
        System.out.println("  -h                        Activate Hex Editor Mode for the active tab.");
        System.out.println("  -t                        Activate Text Editor Mode for the active tab.");
        System.out.println("  -s <start,end>            Select text/bytes in the active tab (e.g., -s 1024,2048).");
        System.out.println("  -g <GENERATE_SIZE_GB>     Generates a test text file.");
        System.out.println("  -gb <GENERATE_SIZE_GB>    Generates a test binary file.");
        System.out.println("");
        System.out.println("Examples:");
        System.out.println("  java -jar bearit-1.0-SNAPSHOT.jar");
        System.out.println("  java -jar bearit-1.0-SNAPSHOT.jar large_application.log");
        System.out.println("  java -jar bearit-1.0-SNAPSHOT.jar -g 50");        
    }
}