package com.edwares;

import java.io.File;

import javax.swing.JOptionPane;

public class CommandLineParser {
    private boolean showHelp = false;
    private Double generateSizeGb = null;
    private File fileToOpen = null;
    
    private boolean hexModeOn = false;
    private boolean textModeOn = false;
    private String selectRange = null;
    private String searchTerm = null;

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
            }
            else if ("-f".equalsIgnoreCase(arg)) {
                if(i + 1 < args.length) {
                    searchTerm = args[++i];
                } else {
                    System.err.println("Error: -f requires argument (search_term).");
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
    public String getSearchTerm() { return searchTerm; }

    public void printHelp() {
    
        String helpText = "Bearit Text Editor\n" +
            "Usage: bearit [OPTIONS] [FILE]\n\n" +
            "Options:\n" +
            "  -?               Show this help message and exit.\n" +
            "  -h               Activate Hex Editor Mode.\n" +
            "  -t               Activate Text Editor Mode.\n" +
            "  -s <start,end>   Select text/bytes (e.g., -s 1024,2048).\n" +
            "  -f <search_term> Find search term.\n" +
            "  -g <SIZE_GB>     Generates a test text file.\n" +
            "  -gb <SIZE_GB>    Generates a test binary file.\n\n" +
            "Examples:\n" +
            "  bearit\n" +
            "  bearit large_application.log\n" +
            "  bearit -g 50\n";
        
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) { //jpackage exe doesn't print output in windows for desktop applications
            DialogUtil.showMessageDialog(null, helpText, "Command line help", JOptionPane.INFORMATION_MESSAGE);
        } else {
            System.out.print(helpText);
        }
    }
}