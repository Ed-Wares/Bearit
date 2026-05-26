package com.edwares;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
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
                    System.exit(0);
                }
            }
        });

        try {
            java.net.URL iconURL = getClass().getResource("/bear.png");
            if (iconURL != null) {
                ImageIcon icon = new ImageIcon(iconURL);
                setIconImage(icon.getImage());
            }
        } catch (Exception e) {}

        fileChooser = new JFileChooser();
        tabbedPane = new JTabbedPane();

        // The "+" Tab routing logic with safety lock
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

        // Add the permanent "+" dummy tab
        tabbedPane.addTab("+", new JPanel());

        add(createToolBar(), BorderLayout.NORTH);
        add(tabbedPane, BorderLayout.CENTER);
        setJMenuBar(createMenuBar());

        // Launch with one empty tab
        //addNewTab(null);
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
            
            btnClose.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent evt) { btnClose.setForeground(Color.RED); }
                public void mouseExited(java.awt.event.MouseEvent evt) { btnClose.setForeground(UIManager.getColor("Button.foreground")); }
            });
            
            btnClose.addActionListener(e -> closeTab(editor));

            tabHeader.add(lblTitle);
            tabHeader.add(btnClose);
            tabbedPane.setTabComponentAt(insertIndex, tabHeader);

            editor.addPropertyChangeListener("editorTitle", evt -> updateTabHeader(editor, lblTitle));
            editor.addPropertyChangeListener("unsavedChanges", evt -> updateTabHeader(editor, lblTitle));

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
                boolean saved = performSaveFor(editor);
                if (!saved) return; // Abort if they cancelled the Save As dialog
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
                if (editor.hasUnsavedChanges()) {
                    tabbedPane.setSelectedComponent(editor);
                    int opt = JOptionPane.showConfirmDialog(this, 
                        "Save changes to " + editor.getCurrentTitle() + "?", 
                        "Unsaved Changes", 
                        JOptionPane.YES_NO_CANCEL_OPTION);
                    
                    if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) {
                        return false; 
                    }
                    if (opt == JOptionPane.YES_OPTION) {
                        if (!performSaveFor(editor)) return false; 
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
            performSaveFor(active);
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

    private boolean performSaveFor(AdvancedTextEditorPanel editor) {
        if (!editor.hasActiveFile()) {
            int option = fileChooser.showSaveDialog(this);
            if (option == JFileChooser.APPROVE_OPTION) {
                File selected = fileChooser.getSelectedFile();
                editor.saveAsFile(selected);
                BearitProperties.getInstance().addRecentFile(selected.getAbsolutePath());
                return true;
            }
            return false;
        } else {
            editor.saveCurrentFile();
            return true;
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

    // --- UI Setup ---

    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setMargin(new Insets(2, 5, 2, 5));

        JButton btnNew = new JButton("📄 New");
        JButton btnOpen = new JButton("📂 Open");
        JButton btnSave = new JButton("💾 Save");
        JButton btnSaveAs = new JButton("💾 Save As...");
        
        JButton btnUndo = new JButton("↩ Undo");
        JButton btnRedo = new JButton("↪ Redo");
        
        JButton btnCut = new JButton("✂ Cut");
        JButton btnCopy = new JButton("📋 Copy");
        JButton btnPaste = new JButton("📝 Paste");
        
        JButton btnSearch = new JButton("🔍 Search");
        JButton btnGoto = new JButton("📍 Go To Line");

        btnWordWrap = new JToggleButton("↩ Wrap");
        boolean currentWrapState = BearitProperties.getInstance().isWordWrap();
        btnWordWrap.setSelected(currentWrapState);

        btnNew.setToolTipText("Create a new document tab");
        btnOpen.setToolTipText("Open an existing file");
        btnSave.setToolTipText("Save current tab");
        btnUndo.setToolTipText("Undo last edit");
        btnRedo.setToolTipText("Redo last edit");
        btnCut.setToolTipText("Cut selected text");
        btnCopy.setToolTipText("Copy selected text");
        btnPaste.setToolTipText("Paste text from clipboard");
        btnSearch.setToolTipText("Search and Replace across full file");
        btnGoto.setToolTipText("Jump to specific line number");
        btnWordWrap.setToolTipText("Toggle global Word Wrap mode");

        btnNew.addActionListener(e -> performNew());
        btnOpen.addActionListener(e -> performOpen());
        btnSave.addActionListener(e -> performSave());
        btnSaveAs.addActionListener(e -> performSaveAs());
        
        btnUndo.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().undo(); });
        btnRedo.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().redo(); });
        btnCut.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().cut(); });
        btnCopy.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().copy(); });
        btnPaste.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().paste(); });
        btnSearch.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().showSearchDialog(); });
        btnGoto.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().showGotoLineDialog(); });

        // Synchronize Toolbar and Menu Checkbox visually
        ActionListener wrapToggleAction = e -> {
            boolean isChecked = ((AbstractButton) e.getSource()).isSelected();
            btnWordWrap.setSelected(isChecked);
            if (wrapMenuItem != null) wrapMenuItem.setSelected(isChecked);
            toggleGlobalWordWrap(isChecked);
        };
        btnWordWrap.addActionListener(wrapToggleAction);

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

        // Custom Tools Implementation
        BearitProperties props = BearitProperties.getInstance();
        boolean hasCustomTools = false;
        
        for (int i = 0; i < 8; i++) {
            String command = props.getCustomToolCommand(i);
            if (command != null && !command.trim().isEmpty()) {
                if (!hasCustomTools) {
                    toolBar.addSeparator();
                    hasCustomTools = true;
                }
                
                String icon = props.getCustomToolIcon(i);
                String name = props.getCustomToolName(i);
                
                String buttonText = (icon != null && !icon.isEmpty() ? icon + " " : "") + name;
                JButton customBtn = new JButton(buttonText);
                customBtn.setToolTipText("Executes: " + command);
                
                final String toolCommand = command;
                customBtn.addActionListener(e -> executeCustomTool(toolCommand));
                
                toolBar.add(customBtn);
            }
        }

        return toolBar;
    }

    private void executeCustomTool(String command) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                Runtime.getRuntime().exec(command);
                return null;
            }
            @Override
            protected void done() {
                try {
                    get(); // Throws exception if the background execution failed
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(TextEditorFrame.this, 
                        "Failed to execute custom tool command:\n" + ex.getMessage(), 
                        "Tool Execution Error", 
                        JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
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
        JMenuItem exitItem = new JMenuItem("Exit");

        newItem.addActionListener(e -> performNew());
        openItem.addActionListener(e -> performOpen());
        saveItem.addActionListener(e -> performSave());
        saveAsItem.addActionListener(e -> performSaveAs());
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

        // --- Help Menu ---
        JMenu helpMenu = new JMenu("Help");
        JMenuItem generateItem = new JMenuItem("Generate Test File...");
        generateItem.addActionListener(e -> performGenerateTestFile());
        JMenuItem aboutItem = new JMenuItem("About Bearit...");
        aboutItem.addActionListener(e -> showAboutDialog());
        
        helpMenu.add(generateItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);
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