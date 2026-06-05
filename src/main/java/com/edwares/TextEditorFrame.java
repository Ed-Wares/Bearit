package com.edwares;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TextEditorFrame extends JFrame {
    private final JTabbedPane tabbedPane;
    private final JFileChooser fileChooser;
    
    // Safety lock to prevent infinite recursion during tab creation/deletion
    private boolean isUpdatingTabs = false; 
    
    // UI Elements that need their selected state synchronized 
    private JToggleButton btnWordWrap;
    private JCheckBoxMenuItem wrapMenuItem;
    private JCheckBoxMenuItem whitespaceMenuItem;
    private JCheckBoxMenuItem eolMenuItem;
    private JRadioButtonMenuItem lightThemeItem;
    private JRadioButtonMenuItem darkThemeItem;

    public TextEditorFrame() {
        BearitProperties props = BearitProperties.getInstance();
        
        setTitle("Bearit Text Editor");
        // The screen size is the working area of the screen, exluding taskbars and docks
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(ge.getDefaultScreenDevice().getDefaultConfiguration());
        Rectangle screenSize = ge.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        int screenWidth = screenSize.width - screenInsets.left - screenInsets.right;
        int screenHeight = screenSize.height - screenInsets.top - screenInsets.bottom;
        if (props.getFrameWidth() > screenWidth) {
            props.setFrameWidth(screenWidth);
        }
        if (props.getFrameHeight() > screenHeight) {
            props.setFrameHeight(screenHeight);
        }
        // Load and set the application icon ---
        try {
            java.net.URL iconURL = getClass().getResource("/BearFace.png");
            if (iconURL != null) {
                ImageIcon icon = new ImageIcon(iconURL);
                setIconImage(icon.getImage());
            } else {
                System.err.println("Icon resource not found in classpath.");
            }
        } catch (Exception e) {
            System.err.println("Failed to load application icon: " + e.getMessage());
        }
        setSize(props.getFrameWidth(), props.getFrameHeight());
        setLocationRelativeTo(null);
        
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (checkAllTabsBeforeExit()) {
                    props.setFrameWidth(getWidth());
                    props.setFrameHeight(getHeight());
                    // Save Session ---
                    List<String> openFiles = new ArrayList<>();
                    for (int i = 0; i < tabbedPane.getTabCount() - 1; i++) {
                        Component c = tabbedPane.getComponentAt(i);
                        if (c instanceof AdvancedTextEditorPanel) {
                            File f = ((AdvancedTextEditorPanel) c).getActiveFile();
                            if (f != null) openFiles.add(f.getAbsolutePath());
                        }
                    }
                    // Pass the current tab index to the session saver
                    BearitProperties.getInstance().saveSession(openFiles, tabbedPane.getSelectedIndex());
                    System.exit(0);
                }
            }
        });

        // --- Drag and Drop File Support for Frame and Blank Tabs ---
        TransferHandler fileDropHandler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    support.setDropAction(TransferHandler.COPY); 
                    return true;
                }
                return false;
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    
                    // Open each dropped file in a new tab
                    for (File file : files) {
                        if (file.exists() && !file.isDirectory()) {
                            openFileInTab(file);
                        }
                    }
                    return true;
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(TextEditorFrame.this, "Failed to open dropped files: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    return false;
                }
            }
        };
        setTransferHandler(fileDropHandler);

        try {
            java.net.URL iconURL = getClass().getResource("/bear.png");
            if (iconURL != null) {
                ImageIcon icon = new ImageIcon(iconURL);
                setIconImage(icon.getImage());
            }
        } catch (Exception e) {}

        fileChooser = new JFileChooser();
        tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT); // --- Forces Mac to align tabs to the left
        tabbedPane.setTransferHandler(fileDropHandler); 

        // Add the permanent "+" dummy tab
        tabbedPane.addTab("+", new JPanel());

        //  Global Tab Right-Click Listener ---
        tabbedPane.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { showPopup(e); }
            public void mouseReleased(MouseEvent e) { showPopup(e); }
            private void showPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int index = tabbedPane.indexAtLocation(e.getX(), e.getY());
                    if (index >= 0 && index < tabbedPane.getTabCount() - 1) { // exclude '+' tab
                        Component comp = tabbedPane.getComponentAt(index);
                        if (comp instanceof AdvancedTextEditorPanel) {
                            createAndShowTabMenu(tabbedPane, e.getX(), e.getY(), index, (AdvancedTextEditorPanel) comp);
                        }
                    }
                }
            }
        });

        tabbedPane.addChangeListener(e -> {
            if (isUpdatingTabs) return; // Ignore events while we are building/destroying tabs
            
            int selected = tabbedPane.getSelectedIndex();
            if (selected == tabbedPane.getTabCount() - 1) {
                // Decouple the tab creation from the selection event to prevent UI freezes
                SwingUtilities.invokeLater(() -> addNewTab(null));
            } else {
                updateFrameTitle();
            }
        });

        add(createToolBar(), BorderLayout.NORTH);
        add(tabbedPane, BorderLayout.CENTER);
        setJMenuBar(createMenuBar());

        // Load previous sessions ---
        List<String> sessionFiles = BearitProperties.getInstance().getSession();
        if (!sessionFiles.isEmpty()) {
            for (String path : sessionFiles) {
                File f = new File(path);
                if (f.exists()) {
                    addNewTab(f);
                }
            }
            
        }
        // Ensure at least one tab is open if session was empty or broken
        if (tabbedPane.getTabCount() == 1) addNewTab(null);

        // Restore the previously active tab index, ensuring it's within bounds
        int savedIndex = BearitProperties.getInstance().getSessionActiveIndex();
        if (savedIndex >= 0 && savedIndex < tabbedPane.getTabCount()) {
            tabbedPane.setSelectedIndex(savedIndex);
        }
    }

    // --- Tab Management ---

    private void updateTabHeader(AdvancedTextEditorPanel editor, JLabel lblTitle) {
        String title = editor.getCurrentTitle();
        if (editor.hasUnsavedChanges()) {
            title += "*";
        }
        lblTitle.setText(title + " ");
        if (getActiveEditor() == editor) {
            updateFrameTitle();
        }
    }

    private void addNewTab(File file) {
        isUpdatingTabs = true; // Engage lock
        try {
            AdvancedTextEditorPanel editor = new AdvancedTextEditorPanel();
            BearitProperties props = BearitProperties.getInstance();
            editor.setFont(new Font(props.getFontName(), Font.PLAIN, props.getFontSize()));

            int insertIndex = Math.max(0, tabbedPane.getTabCount() - 1);
            tabbedPane.insertTab("Untitled", null, editor, null, insertIndex);

            // Custom Tab Header with Close Button
            JPanel tabHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            tabHeader.setOpaque(false);
            JLabel lblTitle = new JLabel("Untitled ");
            JButton btnClose = new JButton("x");
            btnClose.setMargin(new Insets(0, 2, 0, 2));
            btnClose.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            btnClose.setFocusable(false);
            btnClose.setContentAreaFilled(false);
            
            btnClose.addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent evt) { btnClose.setForeground(Color.RED); }
                public void mouseExited(MouseEvent evt) { btnClose.setForeground(UIManager.getColor("Button.foreground")); }
            });
            
            btnClose.addActionListener(e -> closeTab(editor));

            tabHeader.add(lblTitle);
            tabHeader.add(btnClose);
            tabbedPane.setTabComponentAt(insertIndex, tabHeader);

            // --- Component-Level Tab Right-Click Listener & Left-Click Selector ---
            MouseAdapter tabMouseListener = new MouseAdapter() {
                public void mousePressed(MouseEvent e) { handleMouse(e); }
                public void mouseReleased(MouseEvent e) { handleMouse(e); }
                
                private void handleMouse(MouseEvent e) {
                    int tabIdx = tabbedPane.indexOfComponent(editor);
                    if (tabIdx == -1) return;

                    if (e.isPopupTrigger()) {
                        // Handle right-click context menu
                        createAndShowTabMenu(e.getComponent(), e.getX(), e.getY(), tabIdx, editor);
                    } else if (SwingUtilities.isLeftMouseButton(e) && e.getID() == MouseEvent.MOUSE_PRESSED) {
                        // Handle left-click to physically switch the tab
                        if (tabbedPane.getSelectedIndex() != tabIdx) {
                            tabbedPane.setSelectedIndex(tabIdx);
                        }
                    }
                }
            };
            
            tabHeader.addMouseListener(tabMouseListener);
            lblTitle.addMouseListener(tabMouseListener);

            editor.addPropertyChangeListener("editorTitle", evt -> updateTabHeader(editor, lblTitle));
            editor.addPropertyChangeListener("unsavedChanges", evt -> updateTabHeader(editor, lblTitle));
            
            // --- Hook up the custom drop event from the inner text editor ---
            editor.addPropertyChangeListener("filesDropped", evt -> {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) evt.getNewValue();
                for (File f : files) {
                    if (f.exists() && !f.isDirectory()) {
                        openFileInTab(f);
                    }
                }
            });

            if (file != null) {
                editor.loadFile(file);
                BearitProperties.getInstance().addRecentFile(file.getAbsolutePath());
            } else {
                editor.createNewDocument();
            }

            tabbedPane.setSelectedComponent(editor);
        } finally {
            isUpdatingTabs = false; // Disengage lock
            updateFrameTitle();
        }
    }

    // --- Context Menu Logic ---
    private void createAndShowTabMenu(Component invoker, int x, int y, int tabIndex, AdvancedTextEditorPanel targetEditor) {
        JPopupMenu popup = new JPopupMenu();
        
        JMenuItem newTabItem = new JMenuItem("New Tab");
        newTabItem.addActionListener(evt -> performNew());
        
        JMenuItem closeTabItem = new JMenuItem("Close Tab");
        closeTabItem.addActionListener(evt -> closeTab(targetEditor));
        
        JMenuItem closeOtherTabsItem = new JMenuItem("Close Other Tabs");
        closeOtherTabsItem.addActionListener(evt -> {
            List<AdvancedTextEditorPanel> toClose = new ArrayList<>();
            for (int i = 0; i < tabbedPane.getTabCount() - 1; i++) {
                Component c = tabbedPane.getComponentAt(i);
                if (c instanceof AdvancedTextEditorPanel && c != targetEditor) {
                    toClose.add((AdvancedTextEditorPanel) c);
                }
            }
            for (AdvancedTextEditorPanel editor : toClose) {
                closeTab(editor);
            }
        });
        
        JMenuItem copyFilenameItem = new JMenuItem("Copy Filename");
        copyFilenameItem.addActionListener(evt -> {
            File file = targetEditor.getActiveFile();
            String name = file != null ? file.getName() : "Untitled";
            StringSelection selection = new StringSelection(name);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, selection);
        });

        // Copy Full Filename (Absolute Path) ---
        JMenuItem copyFullFilenameItem = new JMenuItem("Copy Full Filename");
        File targetFile = targetEditor.getActiveFile();
        if (targetFile == null || !targetFile.exists()) {
            // Disable option if the file hasn't been saved to disk yet
            copyFullFilenameItem.setEnabled(false);
        } else {
            copyFullFilenameItem.addActionListener(evt -> {
                StringSelection selection = new StringSelection(targetFile.getAbsolutePath());
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(selection, selection);
            });
        }
        
        JMenuItem showExplorerItem = new JMenuItem("Show in File Explorer");
        if (targetFile == null || !targetFile.exists()) {
            showExplorerItem.setEnabled(false);
        } else {
            showExplorerItem.addActionListener(evt -> {
                try {
                    if (Desktop.isDesktopSupported()) {
                        if (Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                            Desktop.getDesktop().browseFileDirectory(targetFile);
                        } else if (Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                            Desktop.getDesktop().open(targetFile.getParentFile());
                        }
                    } else {
                        JOptionPane.showMessageDialog(TextEditorFrame.this, "Desktop integration is not supported on this platform.", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(TextEditorFrame.this, "Could not open file explorer: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            });
        }
        
        popup.add(newTabItem);
        popup.addSeparator();
        popup.add(closeTabItem);
        popup.add(closeOtherTabsItem);
        popup.addSeparator();
        popup.add(copyFilenameItem);
        popup.add(copyFullFilenameItem);
        popup.add(showExplorerItem);
        
        popup.show(invoker, x, y);
    }

    private void closeTab(AdvancedTextEditorPanel editor) {
        if (editor.hasUnsavedChanges()) {
            tabbedPane.setSelectedComponent(editor); 
            int opt = JOptionPane.showConfirmDialog(this, 
                "Save changes to " + editor.getCurrentTitle() + "?", 
                "Unsaved Changes", 
                JOptionPane.YES_NO_CANCEL_OPTION);
            
            if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) {
                return; // Abort closing
            }
            if (opt == JOptionPane.YES_OPTION) {
                if (!performSaveFor(editor, true)) {
                    // If it failed to save, abort the exit
                    JOptionPane.showMessageDialog(this, "Could not save " + editor.getCurrentTitle() + ". Aborting.");
                    return ; 
                }
            }
        }
        
        isUpdatingTabs = true; // Engage lock
        try {
            tabbedPane.remove(editor);
            
            // If all functional tabs are closed (only the "+" remains), open a fresh one
            if (tabbedPane.getTabCount() == 1) {
                SwingUtilities.invokeLater(() -> addNewTab(null));
            } 
            // PREVENT "+" FOCUS: If Swing auto-selected the "+" tab, shift focus left
            else if (tabbedPane.getSelectedIndex() == tabbedPane.getTabCount() - 1) {
                tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 2);
            }
        } finally {
            isUpdatingTabs = false; // Disengage lock
            updateFrameTitle();
        }
    }

    private boolean checkAllTabsBeforeExit() {
        for (int i = 0; i < tabbedPane.getTabCount() - 1; i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof AdvancedTextEditorPanel) {
                AdvancedTextEditorPanel editor = (AdvancedTextEditorPanel) c;
                // If it's dirty, we MUST resolve it
                if (editor.hasUnsavedChanges()) {
                    tabbedPane.setSelectedComponent(editor);
                    int opt = JOptionPane.showConfirmDialog(this, 
                        "Save changes to " + editor.getCurrentTitle() + "?", 
                        "Unsaved Changes", 
                        JOptionPane.YES_NO_CANCEL_OPTION);
                    
                    if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) {
                        return false; 
                    }
                    if (opt == JOptionPane.YES_OPTION) { // Attempt save
                        // Use synchronous save for shutdown ---
                        if (!performSaveFor(editor, true)) {
                            // If it failed to save, abort the exit
                            JOptionPane.showMessageDialog(this, "Could not save " + editor.getCurrentTitle() + ". Aborting exit.");
                            return false; 
                        }
                    }
                }
            }
        }
        return true;
    }

    private AdvancedTextEditorPanel getActiveEditor() {
        Component c = tabbedPane.getSelectedComponent();
        if (c instanceof AdvancedTextEditorPanel) {
            return (AdvancedTextEditorPanel) c;
        }
        return null; 
    }

    private void updateFrameTitle() {
        AdvancedTextEditorPanel active = getActiveEditor();
        if (active != null) {
            String title = active.getCurrentTitle();
            if (active.hasUnsavedChanges()) {
                title += "*";
            }
            setTitle("Bearit Text Editor - " + title);
        } else {
            setTitle("Bearit Text Editor");
        }
    }

    // --- File Operations ---

    public void loadInitialFile(File file) {
        openFileInTab(file);
    }

    private void openFileInTab(File file) {
        AdvancedTextEditorPanel active = getActiveEditor();
        if (active != null && !active.hasUnsavedChanges() && !active.hasActiveFile()) {
            active.loadFile(file);
            BearitProperties.getInstance().addRecentFile(file.getAbsolutePath());
        } else {
            addNewTab(file);
        }
    }

    private void performNew() {
        addNewTab(null);
    }

    private void performOpen() {
        int option = fileChooser.showOpenDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            openFileInTab(fileChooser.getSelectedFile());
        }
    }

    private void performSave() {
        AdvancedTextEditorPanel active = getActiveEditor();
        if (active != null) {
            performSaveFor(active, false);
        }
    }

    private void performSaveAs() {
        AdvancedTextEditorPanel active = getActiveEditor();
        if (active != null) {
            int option = fileChooser.showSaveDialog(this);
            if (option == JFileChooser.APPROVE_OPTION) {
                File selected = fileChooser.getSelectedFile();
                active.saveAsFile(selected);
                BearitProperties.getInstance().addRecentFile(selected.getAbsolutePath());
            }
        }
    }
    
    // Save All Logic ---
    private void performSaveAll() {
        for (int i = 0; i < tabbedPane.getTabCount() - 1; i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof AdvancedTextEditorPanel) {
                AdvancedTextEditorPanel editor = (AdvancedTextEditorPanel) c;
                if (editor.hasUnsavedChanges()) {
                    tabbedPane.setSelectedComponent(editor); // Bring to front so user sees which tab is saving
                    performSaveFor(editor, false);
                }
            }
        }
    }

    private boolean performSaveFor(AdvancedTextEditorPanel editor, boolean saveAsynchronously) {
        boolean saveResult = true;
        if (!editor.hasActiveFile()) {
            int option = fileChooser.showSaveDialog(this);
            if (option == JFileChooser.APPROVE_OPTION) {
                File selected = fileChooser.getSelectedFile();
                if (saveAsynchronously) {
                    saveResult = editor.saveAsSynchronously(selected);
                } else {
                    editor.saveAsFile(selected);
                }
                BearitProperties.getInstance().addRecentFile(selected.getAbsolutePath());
                return saveResult;
            }
            return false;
        } else {
            if (saveAsynchronously) {
                saveResult = editor.saveAsSynchronously(editor.getActiveFile());
            } else {
                editor.saveCurrentFile();
            }
            return saveResult;
        }
    }
    
    // --- Word Wrap Operation ---

    private void toggleGlobalWordWrap(boolean enableWrap) {
        BearitProperties.getInstance().setWordWrap(enableWrap);
        
        // Push the setting dynamically into all actively opened file tabs
        for (int i = 0; i < tabbedPane.getTabCount() - 1; i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof AdvancedTextEditorPanel) {
                ((AdvancedTextEditorPanel) c).setWordWrap(enableWrap);
            }
        }
    }
    
    private void setGlobalTheme(String theme) {
        BearitProperties.getInstance().setTheme(theme);
        for (int i = 0; i < tabbedPane.getTabCount() - 1; i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof AdvancedTextEditorPanel) {
                ((AdvancedTextEditorPanel) c).applyTheme(theme);
            }
        }
    }
    
    private void setGlobalWhitespace(boolean showWhitespace) {
        BearitProperties.getInstance().setShowWhitespace(showWhitespace);
        for (int i = 0; i < tabbedPane.getTabCount() - 1; i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof AdvancedTextEditorPanel) {
                ((AdvancedTextEditorPanel) c).setShowWhitespace(showWhitespace);
            }
        }
    }
    
    private void setGlobalEol(boolean showEol) {
        BearitProperties.getInstance().setShowEol(showEol);
        for (int i = 0; i < tabbedPane.getTabCount() - 1; i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof AdvancedTextEditorPanel) {
                ((AdvancedTextEditorPanel) c).setShowEol(showEol);
            }
        }
    }

    private void performGenerateTestFile() {
        String input = JOptionPane.showInputDialog(this, 
                "Enter target test file size in Gigabytes (e.g., 1.5):", 
                "Generate Test File", 
                JOptionPane.QUESTION_MESSAGE);
                
        if (input != null && !input.trim().isEmpty()) {
            try {
                double gbSize = Double.parseDouble(input.trim());
                if (gbSize <= 0) throw new NumberFormatException("Size must be positive.");

                fileChooser.setDialogTitle("Select Destination for Test File");
                fileChooser.setSelectedFile(new File(String.format(java.util.Locale.US, "bearit_test_file_%.2fGB.txt", gbSize)));
                
                if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    File destFile = fileChooser.getSelectedFile();
                    
                    JDialog progressDialog = new JDialog(this, "Generating File", true);
                    JPanel panel = new JPanel(new BorderLayout(10, 10));
                    panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
                    
                    JLabel lblStatus = new JLabel(String.format(java.util.Locale.US, "0.00 GB / %.2f GB (0%%)", gbSize));
                    lblStatus.setHorizontalAlignment(SwingConstants.CENTER);
                    panel.add(lblStatus, BorderLayout.NORTH);
                    
                    JProgressBar progressBar = new JProgressBar(0, 100);
                    progressBar.setStringPainted(true);
                    panel.add(progressBar, BorderLayout.CENTER);
                    
                    JButton btnCancel = new JButton("Cancel");
                    JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
                    bottomPanel.add(btnCancel);
                    panel.add(bottomPanel, BorderLayout.SOUTH);
                    
                    progressDialog.add(panel);
                    progressDialog.pack();
                    progressDialog.setLocationRelativeTo(this);
                    progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

                    SwingWorker<File, long[]> worker = new SwingWorker<File, long[]>() {
                        @Override
                        protected File doInBackground() throws Exception {
                            LargeFileManager.generateTestFile(destFile, gbSize, (written, total) -> {
                                publish(new long[]{written, total});
                            });
                            return destFile;
                        }

                        @Override
                        protected void process(List<long[]> chunks) {
                            long[] latest = chunks.get(chunks.size() - 1);
                            long written = latest[0];
                            long total = latest[1];
                            
                            int percent = (int) ((written * 100) / total);
                            double writtenGb = written / (1024.0 * 1024.0 * 1024.0);
                            double totalGb = total / (1024.0 * 1024.0 * 1024.0);
                            
                            progressBar.setValue(percent);
                            lblStatus.setText(String.format(java.util.Locale.US, "%.2f GB / %.2f GB (%d%%)", writtenGb, totalGb, percent));
                        }

                        @Override
                        protected void done() {
                            progressDialog.dispose(); 
                            try {
                                File result = get(); 
                                if (isCancelled()) {
                                    destFile.delete(); 
                                    return;
                                }
                                if (result.exists()) {
                                    JOptionPane.showMessageDialog(TextEditorFrame.this, 
                                        "Successfully generated test file:\n" + result.getAbsolutePath(), 
                                        "Generation Complete", JOptionPane.INFORMATION_MESSAGE);
                                    openFileInTab(result);
                                }
                            } catch (Exception ex) {
                                destFile.delete(); 
                                JOptionPane.showMessageDialog(TextEditorFrame.this, 
                                    "Failed to generate test file.\nError Details: " + ex.getMessage(), 
                                    "Generation Error", JOptionPane.ERROR_MESSAGE);
                            }
                        }
                    };
                    
                    btnCancel.addActionListener(e -> {
                        worker.cancel(true);
                        progressDialog.dispose();
                    });

                    worker.execute();
                    progressDialog.setVisible(true); 
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Please enter a valid positive number for the GB size.", "Invalid Input", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // --- Tool Execution Output Redirection ---
    private void executeCustomTool(String rawCommand) {
        // --- Resolve the %f (Current File) variable ---
        AdvancedTextEditorPanel activeEditor = getActiveEditor();
        File activeFile = activeEditor != null ? activeEditor.getActiveFile() : null;

        if (rawCommand.contains("%f") && activeFile == null) {
            JOptionPane.showMessageDialog(this, "This tool requires a saved file to use '%f'. Please save the current tab to disk first.", "Tool Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String command = rawCommand;
        if (command.contains("%f") && activeFile != null) {
            command = command.replace("%f", activeFile.getAbsolutePath());
        }

        // --- Resolve the %rp (Running Path) variable ---
        if (command.contains("%rp")) {
            String rp;
            try {
                rp = new File(TextEditorFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
                if (rp == null) rp = System.getProperty("user.dir");
            } catch (Exception ex) {
                rp = System.getProperty("user.dir");
            }
            command = command.replace("%rp", rp);
        }

        final String finalCommand = command;

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                publish(">> Executing: " + finalCommand + "\n");
                
                ProcessBuilder pb = new ProcessBuilder();
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    pb.command("cmd.exe", "/c", finalCommand);
                } else {
                    pb.command("bash", "-c", finalCommand);
                }
                
                pb.redirectErrorStream(true); 
                Process process = pb.start();
                
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        publish(line + "\n");
                    }
                }
                
                process.waitFor();
                publish(">> Execution Finished. Exit code: " + process.exitValue() + "\n\n");
                return null;
            }
            
            @Override
            protected void process(java.util.List<String> chunks) {
                StringBuilder sb = new StringBuilder();
                for (String s : chunks) {
                    sb.append(s);
                }
                appendToolOutput(sb.toString());
            }
            
            @Override
            protected void done() {
                try {
                    get(); 
                } catch (Exception ex) {
                    appendToolOutput("[EXECUTION FAILED] " + ex.getMessage() + "\n\n");
                }
            }
        }.execute();
    }
    
    private void appendToolOutput(String text) {
        AdvancedTextEditorPanel outputPanel = null;
        
        // Find existing output tab
        for (int i = 0; i < tabbedPane.getTabCount() - 1; i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof AdvancedTextEditorPanel) {
                AdvancedTextEditorPanel p = (AdvancedTextEditorPanel) c;
                if ("Tool Output".equals(p.getCurrentTitle())) {
                    outputPanel = p;
                    if (tabbedPane.getSelectedIndex() != i) {
                        tabbedPane.setSelectedIndex(i);
                    }
                    break;
                }
            }
        }
        
        // Create if missing
        if (outputPanel == null) {
            performNew(); 
            outputPanel = getActiveEditor();
            outputPanel.setCustomTitle("Tool Output");
            outputPanel.setTransient(true); // Prevent Tool tab from prompting for save
        }
        
        outputPanel.appendText(text);
    }

    // --- UI Setup ---
    // Creates the main toolbar with icons, tooltips, and action listeners
    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setMargin(new Insets(2, 5, 2, 5));

        // --- File Operations ---
        JButton btnNew = createIconButton("insert_drive_file.png", "New", "Create a new document tab", e -> performNew());
        JButton btnOpen = createIconButton("folder_open.png", "Open", "Open an existing file", e -> performOpen());
        JButton btnSave = createIconButton("save.png", "Save", "Save current tab", e -> performSave());
        JButton btnSaveAs = createIconButton("save_as.png", "Save As", "Save As...", e -> performSaveAs());
        
        // --- Edit Operations ---
        JButton btnUndo = createIconButton("undo.png", "Undo", "Undo last edit", e -> { if (getActiveEditor() != null) getActiveEditor().undo(); });
        JButton btnRedo = createIconButton("redo.png", "Redo", "Redo last edit", e -> { if (getActiveEditor() != null) getActiveEditor().redo(); });
        
        JButton btnCut = createIconButton("content_cut.png", "Cut", "Cut selected text", e -> { if (getActiveEditor() != null) getActiveEditor().cut(); });
        JButton btnCopy = createIconButton("content_copy.png", "Copy", "Copy selected text", e -> { if (getActiveEditor() != null) getActiveEditor().copy(); });
        JButton btnPaste = createIconButton("content_paste.png", "Paste", "Paste text from clipboard", e -> { if (getActiveEditor() != null) getActiveEditor().paste(); });
        
        // --- Search / Navigate ---
        JButton btnSearch = createIconButton("search.png", "Search", "Search and Replace across full file", e -> { if (getActiveEditor() != null) getActiveEditor().showSearchDialog(); });
        JButton btnGoto = createIconButton("location_on.png", "Go To", "Jump to specific line number", e -> { if (getActiveEditor() != null) getActiveEditor().showGotoLineDialog(); });

        // --- Toggle Buttons ---
        btnWordWrap = createIconToggleButton("wrap_text.png", "Wrap", "Toggle global Word Wrap mode", null);
        boolean currentWrapState = BearitProperties.getInstance().isWordWrap();
        btnWordWrap.setSelected(currentWrapState);

        // Synchronize Toolbar and Menu Checkbox visually
        ActionListener wrapToggleAction = e -> {
            boolean isChecked = ((AbstractButton) e.getSource()).isSelected();
            btnWordWrap.setSelected(isChecked);
            if (wrapMenuItem != null) wrapMenuItem.setSelected(isChecked);
            toggleGlobalWordWrap(isChecked);
        };
        btnWordWrap.addActionListener(wrapToggleAction);

        // --- Assembly ---
        toolBar.add(btnNew);
        toolBar.add(btnOpen);
        toolBar.add(btnSave);
        toolBar.add(btnSaveAs);
        toolBar.addSeparator();
        toolBar.add(btnUndo);
        toolBar.add(btnRedo);
        toolBar.addSeparator();
        toolBar.add(btnCut);
        toolBar.add(btnCopy);
        toolBar.add(btnPaste);
        toolBar.addSeparator();
        toolBar.add(btnSearch);
        toolBar.add(btnGoto);
        toolBar.addSeparator();
        toolBar.add(btnWordWrap);

        // --- Custom Tools Implementation ---
        BearitProperties props = BearitProperties.getInstance();
        boolean hasCustomTools = false;
        
        for (int i = 0; i < 8; i++) {
            String command = props.getCustomToolCommand(i);
            if (command != null && !command.trim().isEmpty()) {
                if (!hasCustomTools) {
                    toolBar.addSeparator();
                    hasCustomTools = true;
                }
                
                String iconProp = props.getCustomToolIcon(i);
                String name = props.getCustomToolName(i);
                
                // Fallback to "build.png" if the properties file is missing a defined icon
                String finalIconName = (iconProp != null && !iconProp.trim().isEmpty()) ? iconProp.trim() : "build.png";
                if (!finalIconName.endsWith(".png")) {
                    finalIconName += ".png";
                }
                
                final String toolCommand = command; // Must be effectively final for lambda
                JButton customBtn = createIconButton(finalIconName, name, "Executes: " + toolCommand, e -> executeCustomTool(toolCommand));
                
                toolBar.add(customBtn);
            }
        }

        return toolBar;
    }

    /**
     * Helper to load an icon, style a JButton, and safely fallback to text if missing.
     */
    private JButton createIconButton(String iconName, String fallbackText, String tooltip, ActionListener action) {
        JButton button = new JButton();
        button.setToolTipText(tooltip);
        if (action != null) {
            button.addActionListener(action);
        }
        
        java.net.URL iconUrl = getClass().getResource("/icons/" + iconName);
        if (iconUrl != null) {
            button.setIcon(new ImageIcon(iconUrl));
            button.setBorderPainted(false);
            button.setFocusPainted(false);
            button.setContentAreaFilled(false);
        } else {
            // Fallback for missing icons
            button.setText(fallbackText);
        }
        
        return button;
    }

    /**
     * Helper to load an icon, style a JToggleButton, and safely fallback to text if missing.
     */
    private JToggleButton createIconToggleButton(String iconName, String fallbackText, String tooltip, ActionListener action) {
        JToggleButton button = new JToggleButton();
        button.setToolTipText(tooltip);
        if (action != null) {
            button.addActionListener(action);
        }
        
        java.net.URL iconUrl = getClass().getResource("/icons/" + iconName);
        if (iconUrl != null) {
            button.setIcon(new ImageIcon(iconUrl));
            button.setBorderPainted(false);
            button.setFocusPainted(false);
            button.setContentAreaFilled(false);
        } else {
            // Fallback for missing icons
            button.setText(fallbackText);
        }
        
        return button;
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        BearitProperties props = BearitProperties.getInstance();

        // --- File Menu ---
        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem = new JMenuItem("New Tab");
        JMenuItem openItem = new JMenuItem("Open...");
        
        // --- Open Recent Submenu ---
        JMenu recentMenu = new JMenu("Open Recent");
        recentMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                recentMenu.removeAll();
                List<String> recents = BearitProperties.getInstance().getRecentFiles();
                if (recents.isEmpty()) {
                    JMenuItem emptyItem = new JMenuItem("No recent files");
                    emptyItem.setEnabled(false);
                    recentMenu.add(emptyItem);
                } else {
                    for (String path : recents) {
                        JMenuItem pathItem = new JMenuItem(path);
                        pathItem.addActionListener(evt -> {
                            File f = new File(path);
                            if (f.exists()) {
                                openFileInTab(f);
                            } else {
                                JOptionPane.showMessageDialog(TextEditorFrame.this, 
                                    "File not found: " + path, "Error", JOptionPane.ERROR_MESSAGE);
                            }
                        });
                        recentMenu.add(pathItem);
                    }
                }
            }
            @Override public void menuDeselected(MenuEvent e) {}
            @Override public void menuCanceled(MenuEvent e) {}
        });

        JMenuItem saveItem = new JMenuItem("Save");
        JMenuItem saveAsItem = new JMenuItem("Save As...");
        JMenuItem saveAllItem = new JMenuItem("Save All"); 
        JMenuItem exitItem = new JMenuItem("Exit");

        newItem.addActionListener(e -> performNew());
        openItem.addActionListener(e -> performOpen());
        saveItem.addActionListener(e -> performSave());
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        saveAsItem.addActionListener(e -> performSaveAs());
        saveAsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        
        // Add listener and shortcut to the new Save All menu item
        saveAllItem.addActionListener(e -> performSaveAll());
        
        exitItem.addActionListener(e -> {
            if (checkAllTabsBeforeExit()) {
                System.exit(0);
            }
        });

        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(recentMenu);
        fileMenu.addSeparator();
        fileMenu.add(saveItem);
        fileMenu.add(saveAsItem);
        fileMenu.add(saveAllItem); 
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        // --- Edit Menu ---
        JMenu editMenu = new JMenu("Edit");
        JMenuItem undoItem = new JMenuItem("Undo");
        JMenuItem redoItem = new JMenuItem("Redo");
        JMenuItem cutItem = new JMenuItem("Cut");
        JMenuItem copyItem = new JMenuItem("Copy");
        JMenuItem pasteItem = new JMenuItem("Paste");
        JMenuItem searchItem = new JMenuItem("Search & Replace...");
        JMenuItem gotoItem = new JMenuItem("Go To Line...");

        // Map accelerators to show standard system keyboard shortcuts in the menu UI
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK));
        searchItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        gotoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK));

        undoItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().undo(); });
        redoItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().redo(); });
        cutItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().cut(); });
        copyItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().copy(); });
        pasteItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().paste(); });
        searchItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().showSearchDialog(); });
        gotoItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().showGotoLineDialog(); });

        editMenu.add(undoItem);
        editMenu.add(redoItem);
        editMenu.addSeparator();
        editMenu.add(cutItem);
        editMenu.add(copyItem);
        editMenu.add(pasteItem);
        editMenu.addSeparator();
        editMenu.add(searchItem);
        editMenu.add(gotoItem);

        // --- View Menu ---
        JMenu viewMenu = new JMenu("View");
        
        JMenu themeMenu = new JMenu("Theme");
        ButtonGroup themeGroup = new ButtonGroup();
        lightThemeItem = new JRadioButtonMenuItem("Light");
        darkThemeItem = new JRadioButtonMenuItem("Dark");
        themeGroup.add(lightThemeItem);
        themeGroup.add(darkThemeItem);
        
        if ("Dark".equals(props.getTheme())) { darkThemeItem.setSelected(true); } 
        else { lightThemeItem.setSelected(true); }

        lightThemeItem.addActionListener(e -> setGlobalTheme("Light"));
        darkThemeItem.addActionListener(e -> setGlobalTheme("Dark"));
        
        themeMenu.add(lightThemeItem);
        themeMenu.add(darkThemeItem);
        
        JMenuItem incFontItem = new JMenuItem("Increase Font");
        JMenuItem decFontItem = new JMenuItem("Decrease Font");
        incFontItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK)); // Ctrl + = (+)
        decFontItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK));  // Ctrl + -
        
        incFontItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().adjustFontSize(2); });
        decFontItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().adjustFontSize(-2); });
        
        wrapMenuItem = new JCheckBoxMenuItem("Word Wrap");
        wrapMenuItem.setSelected(props.isWordWrap());
        wrapMenuItem.addActionListener(e -> {
            boolean isChecked = wrapMenuItem.isSelected();
            if (btnWordWrap != null) btnWordWrap.setSelected(isChecked);
            toggleGlobalWordWrap(isChecked);
        });
        
        whitespaceMenuItem = new JCheckBoxMenuItem("Show White Space Symbols");
        whitespaceMenuItem.setSelected(props.isShowWhitespace());
        whitespaceMenuItem.addActionListener(e -> setGlobalWhitespace(whitespaceMenuItem.isSelected()));
        
        eolMenuItem = new JCheckBoxMenuItem("Show End of Line Symbols");
        eolMenuItem.setSelected(props.isShowEol());
        eolMenuItem.addActionListener(e -> setGlobalEol(eolMenuItem.isSelected()));

        viewMenu.add(themeMenu);
        viewMenu.addSeparator();
        viewMenu.add(incFontItem);
        viewMenu.add(decFontItem);
        viewMenu.addSeparator();
        viewMenu.add(wrapMenuItem);
        viewMenu.addSeparator();
        viewMenu.add(whitespaceMenuItem);
        viewMenu.add(eolMenuItem);

        // --- Tools Menu ---
        JMenu toolsMenu = new JMenu("Tools");
        boolean hasMenuTools = false;
        
        for (int i = 0; i < 8; i++) {
            String command = props.getCustomToolCommand(i);
            if (command != null && !command.trim().isEmpty()) {
                String icon = props.getCustomToolIcon(i);
                String name = props.getCustomToolName(i);
                String buttonText = (icon != null && !icon.isEmpty() ? icon + " " : "") + name;
                
                JMenuItem toolItem = new JMenuItem(buttonText);
                final String toolCommand = command;
                toolItem.addActionListener(e -> executeCustomTool(toolCommand));
                toolsMenu.add(toolItem);
                hasMenuTools = true;
            }
        }
        
        if (!hasMenuTools) {
            JMenuItem emptyItem = new JMenuItem("No Custom Tools Configured");
            emptyItem.setEnabled(false);
            toolsMenu.add(emptyItem);
        }

        // --- Help Menu ---
        JMenu helpMenu = new JMenu("Help");

        JMenuItem mnuInstallContextMenu = new JMenuItem("Install OS Context Menu");
        mnuInstallContextMenu.setToolTipText("Add 'Edit with Bearit' to your system's right-click menu.");
        mnuInstallContextMenu.addActionListener(e -> ContextMenuInstaller.install(this));

        JMenuItem mnuUninstallContextMenu = new JMenuItem("Remove OS Context Menu");
        mnuUninstallContextMenu.setToolTipText("Remove 'Edit with Bearit' from your system's right-click menu.");
        mnuUninstallContextMenu.addActionListener(e -> ContextMenuInstaller.uninstall(this));

        
        JMenuItem generateItem = new JMenuItem("Generate Test File...");
        generateItem.addActionListener(e -> performGenerateTestFile());
        JMenuItem aboutItem = new JMenuItem("About Bearit...");
        aboutItem.addActionListener(e -> showAboutDialog());

        helpMenu.add(mnuInstallContextMenu);
        helpMenu.add(mnuUninstallContextMenu);
        helpMenu.addSeparator();
        helpMenu.add(generateItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);
        menuBar.add(toolsMenu); 
        menuBar.add(helpMenu);

        return menuBar;
    }

    private void showAboutDialog() {
        String appVersion = BearitApp.class.getPackage().getImplementationVersion(); // get version from pom.xml use <addDefaultImplementationEntries>true</addDefaultImplementationEntries>
        String aboutMessage = "Bearit Text Editor\n"
                + "Version: " + appVersion + "\n\n"
                + "Bearit is a high-performance Java 21 text editor designed specifically to handle massive file sizes, helping your system bear the heavy memory load.\n\n"
                + "By Ed Jakubowski  EdWaresApp@gmail.com\n";
                
        JOptionPane.showMessageDialog(this, 
                aboutMessage, 
                "About Bearit", 
                JOptionPane.INFORMATION_MESSAGE);
    }
}