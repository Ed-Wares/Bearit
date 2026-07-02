package com.edwares;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.font.TextAttribute;
import java.io.File;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BearitFrame extends JFrame {
    // Singleton instance reference for global access (e.g., from static contexts)
    private static BearitFrame instance;
    

    private final JTabbedPane tabbedPane;
    private final JFileChooser fileChooser;
    
    // Safety lock to prevent infinite recursion during tab creation/deletion
    private boolean isUpdatingTabs = false; 
    
    // UI Elements that need their selected state synchronized 
    private JToggleButton btnWordWrap;
    private JCheckBoxMenuItem wrapMenuItem;
    private JCheckBoxMenuItem hexMenuItem;
    private JCheckBoxMenuItem whitespaceMenuItem;
    private JCheckBoxMenuItem eolMenuItem;
    private JRadioButtonMenuItem lightThemeItem;
    private JRadioButtonMenuItem darkThemeItem;

    private JToggleButton btnToggleHex;

    public static BearitFrame getInstance() {
        return instance;
    }

    public BearitFrame() {
        instance = this;
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
            java.net.URL iconURL = getClass().getResource("/Bearit.png");
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
                onClosingEvent(e);
            }
        });

        // --- Drag and Drop File Support for Frame and Blank Tabs ---
        TransferHandler fileDropHandler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                // --- Ignore clipboard pastes at the frame level so text can be pasted normally ---
                if (!support.isDrop()) {
                    return false;
                }

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
                    //JOptionPane.showMessageDialog(BearitFrame.this, "Failed to open dropped files: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    DialogUtil.showMessageDialog(BearitFrame.this, "Failed to open dropped files: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
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
        // --- Force a spacious default size for Linux file dialogs ---
        //fileChooser.setPreferredSize(new Dimension(800, 550));

        tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT); // --- Forces Mac to align tabs to the left
        tabbedPane.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI()); // --- Fixes the weird Mac "centered" tab behavior
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
            // Use your existing lock variable if you have one to prevent loops
            if (isUpdatingTabs) return; 
            
            int idx = tabbedPane.getSelectedIndex();
            if (idx == -1) return;

            // --- Safely handle the "+" tab ---
            if (idx == tabbedPane.getTabCount() - 1) {
                SwingUtilities.invokeLater(() -> addNewTab(null));
                return; // Stop execution here so we don't try to sync the dummy tab!
            }

            Component c = tabbedPane.getComponentAt(idx);
            boolean isHex = (c instanceof BearitTextHexWrapper);
            syncHexToggles(isHex);
            updateFrameTitle();
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

        // Force the UI to adapt to the saved theme immediately on launch
        applyTheme(BearitProperties.getInstance().getTheme());
    }

    private void onClosingEvent(WindowEvent e) {
        BearitProperties props = BearitProperties.getInstance();
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
                if (c instanceof BearitTextHexWrapper) {
                    File f = ((BearitTextHexWrapper) c).getHiddenTextEditor().getActiveFile();
                    if (f != null) openFiles.add(f.getAbsolutePath());
                }
            }
            // Pass the current tab index to the session saver
            props.saveSession(openFiles, tabbedPane.getSelectedIndex());
            System.exit(0);
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
            JLabel lblTitle = new JLabel("Untitled");
            JPanel tabHeader = ThemedTabbedPaneUI.insertNewTabWithClose(tabbedPane, lblTitle, editor, e -> closeTab(editor));
            // --- Component-Level Tab Right-Click Listener & Left-Click Selector ---
            MouseAdapter tabMouseListener = new MouseAdapter() {
                public void mousePressed(MouseEvent e) { handleMouse(e); }
                public void mouseReleased(MouseEvent e) { handleMouse(e); }
                
                private void handleMouse(MouseEvent e) {
                    // --- Safely find the tab index even if it is wrapped in Hex Mode ---
                    int tabIdx = -1;
                    for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                        Component c = tabbedPane.getComponentAt(i);
                        if (c == editor || (c instanceof BearitTextHexWrapper && ((BearitTextHexWrapper) c).getHiddenTextEditor() == editor)) {
                            tabIdx = i;
                            break;
                        }
                    }

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
            //lblTitle.addMouseListener(tabMouseListener);

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
            // Listen for scroll-wheel font changes from the text editor
            editor.setOnFontChangeListener(newFont -> {
                updateGlobalFont(newFont.getFamily(), newFont.getSize());
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
            // --- Immediately sweep the new tab with our custom theme interceptors! ---
            applyTheme(BearitProperties.getInstance().getTheme());
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
                        //JOptionPane.showMessageDialog(BearitFrame.this, "Desktop integration is not supported on this platform.", "Error", JOptionPane.ERROR_MESSAGE);
                        DialogUtil.showMessageDialog(BearitFrame.this, "Desktop integration is not supported on this platform.", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) {
                    //JOptionPane.showMessageDialog(BearitFrame.this, "Could not open file explorer: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    DialogUtil.showMessageDialog(BearitFrame.this, "Could not open file explorer: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
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
        
        DialogUtil.themePopupMenu(popup);
        popup.show(invoker, x, y);
    }

    private void closeTab(AdvancedTextEditorPanel editor) {
        // --- Find the exact index of this editor (it might be wrapped in Hex Mode) ---
        int targetIdx = -1;
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c == editor || (c instanceof BearitTextHexWrapper && ((BearitTextHexWrapper) c).getHiddenTextEditor() == editor)) {
                targetIdx = i;
                break;
            }
        }

        if (targetIdx == -1) return; // Safety fallback in case the tab is already gone

        if (editor.hasUnsavedChanges()) {
            // Select by index so the TabbedPane doesn't crash if it's a Hex Wrapper
            tabbedPane.setSelectedIndex(targetIdx); 
            
            //int opt = JOptionPane.showConfirmDialog(this, "Save changes to " + editor.getCurrentTitle() + "?", "Unsaved Changes", JOptionPane.YES_NO_CANCEL_OPTION);
            int opt = DialogUtil.showConfirmDialog(this, "Save changes to " + editor.getCurrentTitle() + "?", "Unsaved Changes", JOptionPane.YES_NO_CANCEL_OPTION);
            
            if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) {
                return; // Abort closing
            }
            if (opt == JOptionPane.YES_OPTION) {
                if (!performSaveFor(editor, true)) {
                    // If it failed to save, abort the exit
                    //JOptionPane.showMessageDialog(this, "Could not save " + editor.getCurrentTitle() + ". Aborting.");
                    DialogUtil.showMessageDialog(this, "Could not save " + editor.getCurrentTitle() + ". Aborting.", "Save Failed", JOptionPane.ERROR_MESSAGE);
                    return ; 
                }
            }
        }
        
        isUpdatingTabs = true; // Engage lock
        try {
            // Remove by index to guarantee the wrapper OR the editor gets completely removed
            tabbedPane.remove(targetIdx);
            
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
                    //int opt = JOptionPane.showConfirmDialog(this, "Save changes to " + editor.getCurrentTitle() + "?", "Unsaved Changes", JOptionPane.YES_NO_CANCEL_OPTION);
                    int opt = DialogUtil.showConfirmDialog(this, "Save changes to " + editor.getCurrentTitle() + "?", "Unsaved Changes", JOptionPane.YES_NO_CANCEL_OPTION);
                    
                    if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) {
                        return false; 
                    }
                    if (opt == JOptionPane.YES_OPTION) { // Attempt save
                        // Use synchronous save for shutdown ---
                        if (!performSaveFor(editor, true)) {
                            // If it failed to save, abort the exit
                            //JOptionPane.showMessageDialog(this, "Could not save " + editor.getCurrentTitle() + ". Aborting exit.");
                            DialogUtil.showMessageDialog(this, "Could not save " + editor.getCurrentTitle() + ". Aborting exit.", "Save Failed", JOptionPane.ERROR_MESSAGE);
                            return false; 
                        }
                    }
                }
            }
        }
        return true;
    }

    private Component getActiveTabComponent() {
        int idx = tabbedPane.getSelectedIndex();
        if (idx == -1) return null;
        return tabbedPane.getComponentAt(idx);
    }

    private AdvancedTextEditorPanel getActiveEditor() {
        Component c = getActiveTabComponent();
        if (c instanceof AdvancedTextEditorPanel) {
            return (AdvancedTextEditorPanel) c;
        } else if (c instanceof BearitTextHexWrapper) {
            return ((BearitTextHexWrapper) c).getHiddenTextEditor();
        }
        return null;
    }

    private void updateFrameTitle() {
        AdvancedTextEditorPanel activeEditor = getActiveEditor(); 
        
        if (activeEditor != null) {
            String displayPath;
            File activeFile = activeEditor.getActiveFile();
            
            // If the file exists on the hard drive, grab its full absolute path
            if (activeFile != null) {
                displayPath = activeFile.getAbsolutePath();
            } else {
                // Otherwise, fallback to the default short name (e.g., "Untitled")
                displayPath = activeEditor.getCurrentTitle(); 
            }
            
            // It is best practice to keep the unsaved asterisk indicator on the main window too!
            if (activeEditor.hasUnsavedChanges()) {
                displayPath = "*" + displayPath;
            }
            
            setTitle(displayPath + " - Bearit Text Editor");
        } else {
            // Fallback for when all tabs are completely closed
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
        //if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
        File selected = DialogUtil.showOpenFileDialog(this, "Open File");    
        if (selected != null) {
            openFileInTab(selected);//fileChooser.getSelectedFile());
        }
    }

    private void performReload() {
        AdvancedTextEditorPanel editor = getActiveEditor();
        if (editor != null && editor.hasActiveFile()) {
            
            // Safety check to prevent accidental data loss
            if (editor.hasUnsavedChanges()) {
                //int result = JOptionPane.showConfirmDialog(this, "You have unsaved changes. Reloading will discard them.\nAre you sure you want to reload?", "Confirm Reload", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                int result = DialogUtil.showConfirmDialog(this, "You have unsaved changes. Reloading will discard them.\nAre you sure you want to reload?", "Confirm Reload", JOptionPane.YES_NO_OPTION);

                if (result != JOptionPane.YES_OPTION) {
                    return; // User canceled the reload
                }
            }
            
            // Reload the file from disk
            editor.loadFile(editor.getActiveFile());
        }
    }

    private void performSave() {
        AdvancedTextEditorPanel active = getActiveEditor();
        Component c = getActiveTabComponent();
        if (c instanceof BearitTextHexWrapper) {
            BearitTextHexWrapper activeHex = (BearitTextHexWrapper) c;
            activeHex.syncToHiddenEditor(false); // check if the hex view has changed since last sync
        }
        if (active != null) {
            performSaveFor(active, false);
        }
    }

    private void performSaveAs() {
        AdvancedTextEditorPanel active = getActiveEditor();
        Component c = getActiveTabComponent();
        if (c instanceof BearitTextHexWrapper) {
            BearitTextHexWrapper activeHex = (BearitTextHexWrapper) c;
            activeHex.syncToHiddenEditor(false); // check if the hex view has changed since last sync
        }
        if (active != null) {
            //if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = DialogUtil.showSaveFileDialog(this, "Save File As");
            if (selected != null) {                
                //File selected = fileChooser.getSelectedFile();
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
        // --- Manually trigger the hex sync ONLY when saving! ---
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof BearitTextHexWrapper && ((BearitTextHexWrapper) c).getHiddenTextEditor() == editor) {
                ((BearitTextHexWrapper) c).syncToHiddenEditor(false);
                break;
            }
        }

        boolean saveResult = true;
        if (!editor.hasActiveFile()) {
            //if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = DialogUtil.showSaveFileDialog(this, "Save File");
            if (selected != null) {
                //File selected = fileChooser.getSelectedFile();
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
        // --- Build the Custom Input Panel ---
        JPanel inputPanel = new JPanel(new BorderLayout(0, 10));
        inputPanel.setOpaque(false);
        
        JPanel sizePanel = new JPanel(new BorderLayout(5, 5));
        sizePanel.setOpaque(false);
        sizePanel.add(new JLabel("Enter target test file size in Gigabytes (e.g., 1.5):"), BorderLayout.NORTH);
        JTextField txtSize = new JTextField(10);
        txtSize.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                // The first invokeLater waits for the dialog to be drawn
                SwingUtilities.invokeLater(() -> {
                    // The second invokeLater waits for JOptionPane to assign its default focus,
                    // and then immediately steals it back for the text field!
                    SwingUtilities.invokeLater(() -> {
                        txtSize.requestFocusInWindow();
                    });
                });
            }
            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {}
            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent event) {}
        });
        sizePanel.add(txtSize, BorderLayout.CENTER);

        
        JCheckBox chkNoNewLines = new JCheckBox("Do not add new lines to generated file");
        chkNoNewLines.setToolTipText("Generates a single continuous line. Excellent for testing horizontal scroll performance.");
        chkNoNewLines.setOpaque(false);
        
        inputPanel.add(sizePanel, BorderLayout.NORTH);
        inputPanel.add(chkNoNewLines, BorderLayout.CENTER);
        //String input = JOptionPane.showInputDialog(this, "Enter target test file size in Gigabytes (e.g., 1.5):", "Generate Test File", JOptionPane.QUESTION_MESSAGE);
        int result = DialogUtil.showConfirmDialog(this, inputPanel, "Generate Test File", JOptionPane.OK_CANCEL_OPTION);
        boolean preventNewLines = chkNoNewLines.isSelected();
        String input = txtSize.getText();
        
        if (result == JOptionPane.OK_OPTION && input != null && !input.trim().isEmpty()) {
            try {
                double gbSize = Double.parseDouble(input.trim());
                if (gbSize <= 0) throw new NumberFormatException("Size must be positive.");

                //fileChooser.setDialogTitle("Select Destination for Test File");
                //fileChooser.setSelectedFile(new File(String.format(java.util.Locale.US, "bearit_test_file_%.2fGB.txt", gbSize)));
                //if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File destFile = DialogUtil.showSaveFileDialog(this, "Select Destination for Test File", String.format(java.util.Locale.US, "bearit_test_file_%.2fGB.txt", gbSize));
                if (destFile != null) {

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
                            FileGenUtil.generateTestFile(destFile, gbSize, preventNewLines, (written, total) -> {
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
                                    //JOptionPane.showMessageDialog(BearitFrame.this, "Successfully generated test file:\n" + result.getAbsolutePath(), "Generation Complete", JOptionPane.INFORMATION_MESSAGE);
                                    DialogUtil.showMessageDialog(BearitFrame.this, "Successfully generated test file:\n" + result.getAbsolutePath(), "Generation Complete", JOptionPane.INFORMATION_MESSAGE);
                                    openFileInTab(result);
                                }
                            } catch (Exception ex) {
                                destFile.delete(); 
                                //JOptionPane.showMessageDialog(BearitFrame.this, "Failed to generate test file.\nError Details: " + ex.getMessage(), "Generation Error", JOptionPane.ERROR_MESSAGE);
                                DialogUtil.showMessageDialog(BearitFrame.this, "Failed to generate test file.\nError Details: " + ex.getMessage(), "Generation Error", JOptionPane.ERROR_MESSAGE);
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
                //JOptionPane.showMessageDialog(this, "Please enter a valid positive number for the GB size.", "Invalid Input", JOptionPane.ERROR_MESSAGE);
                DialogUtil.showMessageDialog(this, "Please enter a valid positive number for the GB size.", "Invalid Input", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void toggleHexModeForCurrentTab(boolean enableHex) {
        int idx = tabbedPane.getSelectedIndex();
        if (idx == -1) return;

        Component current = tabbedPane.getComponentAt(idx);

        if (enableHex && current instanceof AdvancedTextEditorPanel) {
            AdvancedTextEditorPanel textPanel = (AdvancedTextEditorPanel) current;
            
            // --- Auto-apply text edits without prompting ---
            if (textPanel.hasUnsavedChanges()) {
                try {
                    // Automatically push text to memory cache without saving to the hard drive
                    textPanel.getFileManager().commitCurrentChunk(textPanel.getCommitText());
                    // The asterisk remains active so the user remembers to save eventually
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            BearitTextHexWrapper hexWrapper = new BearitTextHexWrapper(textPanel);
            // Apply the global theme to the hex editor before displaying it ---
            hexWrapper.getHexEditor().applyTheme(BearitProperties.getInstance().getTheme()); 
            // Inherit the font from the text editor
            hexWrapper.setFont(textPanel.getFont());
            hexWrapper.adjustFontSize(textPanel.getFont().getSize() - hexWrapper.getCurrentFontSize());
            // Listen for scroll-wheel font changes from the hex editor
            hexWrapper.setOnFontChangeListener(newFont -> {
                updateGlobalFont(newFont.getFamily(), newFont.getSize());
            });

            tabbedPane.setComponentAt(idx, hexWrapper);
            syncHexToggles(true);

        } else if (!enableHex && current instanceof BearitTextHexWrapper) {
            BearitTextHexWrapper hexWrapper = (BearitTextHexWrapper) current;
            
            // --- Capture the exact GLOBAL byte offset before reverting ---
            final long targetGlobalOffset = hexWrapper.getHexEditor().getGlobalSelectedByteOffset();
            
            hexWrapper.syncToHiddenEditor(true); 
            
            AdvancedTextEditorPanel restoredTextPanel = hexWrapper.getHiddenTextEditor();
            tabbedPane.setComponentAt(idx, restoredTextPanel);
            // Sync the text editor back to the hex editor's font size 
            restoredTextPanel.setFont(restoredTextPanel.getFont().deriveFont((float) hexWrapper.getCurrentFontSize()));
            restoredTextPanel.revalidate();
            restoredTextPanel.repaint();
            syncHexToggles(false);
            restoredTextPanel.focusEditor();
            restoredTextPanel.setGlobalSelection(targetGlobalOffset, targetGlobalOffset);
        }
    }

    /**
     * Executes an optional startup command, replacing variables like %rp and %acp.
     */
    public void executeStartupCommand() {
        // Fetch the property. Check System Properties first (e.g. -Dstartup-cmd="..."), 
        // then fallback to the BearitProperties singleton.
        String cmd = System.getProperty("startup-cmd");
        if (cmd == null || cmd.trim().isEmpty()) {
            cmd = BearitProperties.getInstance().getProperty("startup-cmd", "");
        }

        if (cmd == null || cmd.trim().isEmpty()) {
            return; // Nothing to execute
        }

        // Apply the variable replacements using the existing helper methods
        cmd = resolveRunningPath(cmd);  // Resolves %rp
        cmd = resolveAppContentPath(cmd); // Resolves %acp
        
        executeBackgroundProcess("startup-cmd", cmd);
    }

    /**
     * Executes an optional startup command, replacing variables like %rp and %acp.
     */
    public void executeInstallCommand() {
        // Fetch the property. Check System Properties first (e.g. -Dstartup-cmd="..."), 
        // then fallback to the BearitProperties singleton.
        String cmd = System.getProperty("install-cmd");
        if (cmd == null || cmd.trim().isEmpty()) {
            cmd = BearitProperties.getInstance().getProperty("install-cmd", "");
        }

        if (cmd == null || cmd.trim().isEmpty()) {
            return; // Nothing to execute
        }

        // remove it from the properties file so it only runs one time
        BearitProperties.getInstance().removeProperty("install-cmd");

        // Apply the variable replacements using the existing helper methods
        cmd = resolveRunningPath(cmd);  // Resolves %rp
        cmd = resolveAppContentPath(cmd); // Resolves %acp
        
        executeBackgroundProcess("install-cmd", cmd);
    }

    /**
     * Shared SwingWorker logic to execute shell commands safely in the background.
     */
    public void executeBackgroundProcess(String logPrefix, String finalCmd) {
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                try {
                    System.out.println("Executing " + logPrefix + ": " + finalCmd);
                    
                    ProcessBuilder pb;
                    // Route to the correct OS shell so standard commands (like echo, dir, ls) work natively
                    if (System.getProperty("os.name").toLowerCase().contains("win")) {
                        pb = new ProcessBuilder("cmd.exe", "/c", finalCmd);
                    } else {
                        pb = new ProcessBuilder("bash", "-c", finalCmd);
                    }
                    
                    // Merge errors and output into one stream
                    pb.redirectErrorStream(true); 
                    Process process = pb.start();
                    
                    // Read the output so the external process doesn't hang waiting for buffer clearance
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            System.out.println("[" + logPrefix + "] " + line);
                        }
                    }
                    process.waitFor();
                } catch (Exception e) {
                    System.err.println(logPrefix + " Failed: " + e.getMessage());
                }
                return null;
            }
        };
        worker.execute();
    }

    // --- Tool Execution Output Redirection ---
    private void executeCustomTool(String rawCommand) {
        // --- Resolve the %f (Current File) variable ---
        AdvancedTextEditorPanel activeEditor = getActiveEditor();
        File activeFile = activeEditor != null ? activeEditor.getActiveFile() : null;

        if (rawCommand.contains("%f") && activeFile == null) {
            //JOptionPane.showMessageDialog(this, "This tool requires a saved file to use '%f'. Please save the current tab to disk first.", "Tool Error", JOptionPane.ERROR_MESSAGE);
            DialogUtil.showMessageDialog(this, "This tool requires a saved file to use '%f'. Please save the current tab to disk first.", "Tool Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String command = rawCommand;
        if (command.contains("%f") && activeFile != null) {
            command = command.replace("%f", activeFile.getAbsolutePath());
        }

        command = resolveRunningPath(command);  //Resolve the %rp (Running Path) 
        command = resolveAppContentPath(command); // resolve %acp
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

    private String resolveRunningPath(String input) {
        if (input == null || !input.contains("%rp")) return input;
        
        String rp;
        try {
            rp = new File(BearitFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            if (rp == null) rp = System.getProperty("user.dir");
        } catch (Exception ex) {
            rp = System.getProperty("user.dir");
        }
        
        return input.replace("%rp", rp);
    }

    private String resolveAppContentPath(String input) {
        if (input == null || !input.contains("%acp")) return input;
        
        String acp;
        try {
            acp = AppContentExtractor.getAppContentDir().getAbsolutePath();
        } catch (Exception ex) {
            ex.printStackTrace();
            acp = "%acp";
        }
        return input.replace("%acp", acp);
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
                    // Only switch the active tab if the user has the setting enabled
                    if (tabbedPane.getSelectedIndex() != i && BearitProperties.getInstance().isAutoFocusToolOutput()) {
                        tabbedPane.setSelectedIndex(i); // tool output tab already exists, switch to it
                    }
                    break;
                }
            }
        }
        
        // Create if missing
        if (outputPanel == null) {
            int currentTabIndex = tabbedPane.getSelectedIndex();
            performNew(); 
            outputPanel = getActiveEditor();
            outputPanel.setCustomTitle("Tool Output");
            outputPanel.setTransient(true); // Prevent Tool tab from prompting for save
            if (!BearitProperties.getInstance().isAutoFocusToolOutput()) {
                tabbedPane.setSelectedIndex(currentTabIndex); // Switch back to original File tab if user doesn't want auto-focus
            }
        }
        
        outputPanel.appendText(text);
    }

    // --- UI Setup ---
    // Creates the main toolbar with icons, tooltips, and action listeners
    private JToolBar createToolBar() {
       // Create the toolbar and force it to ignore the native OS gradient
        JToolBar toolBar = new JToolBar() {
            @Override
            protected void paintComponent(Graphics g) {
                // Paint a flat, solid block of color using the current background theme
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                
                // Note: We intentionally DO NOT call super.paintComponent(g) 
                // because that is what triggers Ubuntu to draw its native gradient over our theme!
            }
        };
        // Highly recommended for modern flat themes: lock the toolbar in place
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
        JButton btnGoto = createIconButton("location_on.png", "Go To", "Jump to specific line number or position", e -> { if (getActiveEditor() != null) getActiveEditor().showGotoDialog(); });

        // --- Toggle Buttons ---
        btnWordWrap = createIconToggleButton("wrap_text.png", "Wrap", "Toggle global Word Wrap mode", null);
        boolean currentWrapState = BearitProperties.getInstance().isWordWrap();
        btnWordWrap.setSelected(currentWrapState);

        // Hex Mode Toggle (for current tab only)
        btnToggleHex = createIconToggleButton("h_mobiledata_badge.png", "Hex", "Toggle Hex Editor for current tab", null);
        btnToggleHex.addActionListener(e -> toggleHexModeForCurrentTab(btnToggleHex.isSelected()));        
        

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
        toolBar.add(btnToggleHex);


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
                // --- Resolve the %rp variable for the icon path ---
                finalIconName = resolveRunningPath(finalIconName);
                finalIconName = resolveAppContentPath(finalIconName); // resolve %acp
                if (!finalIconName.contains(".")) {
                    finalIconName += ".png";
                }
                
                final String toolCommand = command; // Must be effectively final for lambda
                JButton customBtn = createIconButton(finalIconName, name, name, e -> executeCustomTool(toolCommand));
                
                toolBar.add(customBtn);
            }
        }

        return toolBar;
    }

    /**
     * Helper to load an icon, style a JButton, safely fallback to text, and apply clean custom hover effects.
     */
    private JButton createIconButton(String iconName, String fallbackText, String tooltip, ActionListener action) {
        JButton button = new JButton() {
            boolean isHovered = false;

            {
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        if (isEnabled()) { isHovered = true; repaint(); }
                    }
                    @Override
                    public void mouseExited(MouseEvent e) {
                        isHovered = false; repaint();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                // Manually paint our clean hover effect BEFORE the button paints its icon
                if (isHovered && isEnabled()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    // Turn on anti-aliasing for smooth rounded corners
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    
                    // Semi-transparent gray
                    g2.setColor(new Color(150, 150, 150, 60)); 
                    
                    // Draw a subtle rounded rectangle 
                    g2.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 8, 8);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };

        button.setToolTipText(tooltip);
        if (action != null) {
            button.addActionListener(action);
        }
        
        URL iconUrl = getClass().getResource("/icons/" + iconName);
        if (iconUrl != null) {
            button.setIcon(new ImageIcon(iconUrl));
        } else {
            // Not in JAR. Check the local file system
            File iconFile = new File(iconName);
            if (iconFile.exists() && !iconFile.isDirectory()) {
                button.setIcon(new ImageIcon(iconFile.getAbsolutePath()));
            } else {
                // Not on hard drive either. Fallback strictly to text
                button.setText(fallbackText);
            }            
        }
        
        // Strip away ALL default OS borders and painting
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setContentAreaFilled(false); 
        button.setOpaque(false); // Essential to let the parent toolbar paint underneath it first
        
        return button;
    }

    /**
     * Helper to load an icon, style a JToggleButton, and safely render custom active/hover states.
     */
    private JToggleButton createIconToggleButton(String iconName, String fallbackText, String tooltip, ActionListener action) {
        JToggleButton button = new JToggleButton() {
            boolean isHovered = false;

            {
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        if (isEnabled()) { isHovered = true; repaint(); }
                    }
                    @Override
                    public void mouseExited(MouseEvent e) {
                        isHovered = false; repaint();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                // Manually paint active/hover states
                if ((isSelected() || isHovered) && isEnabled()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    
                    if (isSelected()) {
                        g2.setColor(new Color(150, 150, 150, 100)); // Darker when permanently toggled on
                    } else {
                        g2.setColor(new Color(150, 150, 150, 60));  // Lighter when just hovered
                    }
                    
                    g2.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 8, 8);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };

        button.setToolTipText(tooltip);
        if (action != null) {
            button.addActionListener(action);
        }
        
        java.net.URL iconUrl = getClass().getResource("/icons/" + iconName);
        if (iconUrl != null) {
            button.setIcon(new ImageIcon(iconUrl));
        } else {
            button.setText(fallbackText);
        }
        
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        
        return button;
    }

    private JMenuBar createMenuBar() {
        
        // --- Override the paint method to completely bypass native OS L&F L&F drawing ---
        JMenuBar menuBar = new JMenuBar() {
            @Override
            protected void paintComponent(Graphics g) {
                // We do NOT call super.paintComponent(g) here! 
                // We manually paint a solid rectangle using our exact theme background color.
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        menuBar.setOpaque(true);
        BearitProperties props = BearitProperties.getInstance();

        // --- File Menu ---
        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem = new JMenuItem("New Tab");
        newItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        JMenuItem openItem = new JMenuItem("Open...");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));

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
                                //JOptionPane.showMessageDialog(BearitFrame.this, "File not found: " + path, "Error", JOptionPane.ERROR_MESSAGE);
                                DialogUtil.showMessageDialog(BearitFrame.this, "File not found: " + path, "Error", JOptionPane.ERROR_MESSAGE);
                            }
                        });
                        recentMenu.add(pathItem);
                    }
                }
                // Theme these brand-new items before the OS paints them to the screen!
                DialogUtil.sweepComponents(recentMenu.getPopupMenu());
            }
            @Override public void menuDeselected(MenuEvent e) {}
            @Override public void menuCanceled(MenuEvent e) {}
        });

        JMenuItem mnuReload = new JMenuItem("Reload from Disk");
        mnuReload.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        mnuReload.setToolTipText("Discard unsaved changes and reload the file");
        mnuReload.addActionListener(e -> performReload());        
        
        // --- Print Option ---
        JMenuItem mnuPrint = new JMenuItem("Print...");
        mnuPrint.setToolTipText("Print the current file");
        mnuPrint.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK)); // Ctrl+P
        mnuPrint.addActionListener(e -> {
            if (getActiveEditor() != null) {
                getActiveEditor().printFile();
            }
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
        
        exitItem.addActionListener(e -> onClosingEvent(null));

        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(recentMenu);
        fileMenu.add(mnuReload);
        fileMenu.addSeparator();
        fileMenu.add(mnuPrint);
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
        JMenuItem selectAllItem = new JMenuItem("Select All");
        JMenuItem searchItem = new JMenuItem("Search & Replace...");
        JMenuItem gotoItem = new JMenuItem("Go To Line or Position...");

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
        selectAllItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().selectAll(); });

        searchItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().showSearchDialog(); });
        gotoItem.addActionListener(e -> { if (getActiveEditor() != null) getActiveEditor().showGotoDialog(); });

        // --- Convert Case Sub-Menu ---
        JMenu convertCaseMenu = new JMenu("Convert Case");
        JMenuItem mnuLowerCase = new JMenuItem("lower case");
        mnuLowerCase.addActionListener(e -> {
            if (getActiveEditor() != null) getActiveEditor().convertSelectionCase("LOWER");
        });
        JMenuItem mnuUpperCase = new JMenuItem("UPPER CASE");
        mnuUpperCase.addActionListener(e -> {
            if (getActiveEditor() != null) getActiveEditor().convertSelectionCase("UPPER");
        });
        JMenuItem mnuProperCase = new JMenuItem("Proper Case");
        mnuProperCase.addActionListener(e -> {
            if (getActiveEditor() != null) getActiveEditor().convertSelectionCase("PROPER");
        });
        
        convertCaseMenu.add(mnuLowerCase);
        convertCaseMenu.add(mnuUpperCase);
        convertCaseMenu.add(mnuProperCase);

        editMenu.add(undoItem);
        editMenu.add(redoItem);
        editMenu.addSeparator();
        editMenu.add(cutItem);
        editMenu.add(copyItem);
        editMenu.add(pasteItem);
        editMenu.add(selectAllItem);
        editMenu.addSeparator();
        editMenu.add(searchItem);
        editMenu.add(gotoItem);
        editMenu.addSeparator();
        editMenu.add(convertCaseMenu);

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

        lightThemeItem.addActionListener(e -> applyTheme("Light"));
        darkThemeItem.addActionListener(e -> applyTheme("Dark"));
        
        themeMenu.add(lightThemeItem);
        themeMenu.add(darkThemeItem);
        
        JMenuItem changeFontItem = new JMenuItem("Change Font...");
        changeFontItem.addActionListener(e -> showFontPickerDialog());

        JMenuItem incFontItem = new JMenuItem("Increase Font Size");
        JMenuItem decFontItem = new JMenuItem("Decrease Font Size");
        incFontItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK)); // Ctrl + = (+)
        decFontItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK));  // Ctrl + -
        
        incFontItem.addActionListener(e -> { 
            Component c = getActiveTabComponent();
            if (c instanceof BearitTextHexWrapper) {
                ((BearitTextHexWrapper) c).adjustFontSize(2);
            } else if (getActiveEditor() != null) {
                getActiveEditor().adjustFontSize(2); 
            }
        });
        
        decFontItem.addActionListener(e -> { 
            Component c = getActiveTabComponent();
            if (c instanceof BearitTextHexWrapper) {
                ((BearitTextHexWrapper) c).adjustFontSize(-2);
            } else if (getActiveEditor() != null) {
                getActiveEditor().adjustFontSize(-2); 
            }
        });
        
        wrapMenuItem = new JCheckBoxMenuItem("Word Wrap");
        wrapMenuItem.setSelected(props.isWordWrap());
        wrapMenuItem.addActionListener(e -> {
            boolean isChecked = wrapMenuItem.isSelected();
            if (btnWordWrap != null) btnWordWrap.setSelected(isChecked);
            toggleGlobalWordWrap(isChecked);
        });

        hexMenuItem = new JCheckBoxMenuItem("Hex Editor");
        hexMenuItem.addActionListener(e -> toggleHexModeForCurrentTab(hexMenuItem.isSelected()));
        
        whitespaceMenuItem = new JCheckBoxMenuItem("Show White Space Symbols");
        whitespaceMenuItem.setSelected(props.isShowWhitespace());
        whitespaceMenuItem.addActionListener(e -> setGlobalWhitespace(whitespaceMenuItem.isSelected()));
        
        eolMenuItem = new JCheckBoxMenuItem("Show End of Line Symbols");
        eolMenuItem.setSelected(props.isShowEol());
        eolMenuItem.addActionListener(e -> setGlobalEol(eolMenuItem.isSelected()));

        viewMenu.add(themeMenu);
        viewMenu.addSeparator();
        viewMenu.add(changeFontItem);
        viewMenu.add(incFontItem);
        viewMenu.add(decFontItem);
        viewMenu.addSeparator();
        viewMenu.add(wrapMenuItem);
        viewMenu.add(hexMenuItem);
        viewMenu.addSeparator();
        viewMenu.add(whitespaceMenuItem);
        viewMenu.add(eolMenuItem);

        // --- Tools Menu ---
        JMenu toolsMenu = new JMenu("Tools");

        JCheckBoxMenuItem chkFocusOutput = new JCheckBoxMenuItem("Auto-Focus Tool Output Tab");
        chkFocusOutput.setToolTipText("Switch to the output tab automatically when a tool runs");
        
        // Load initial state
        boolean currentFocusState = BearitProperties.getInstance().isAutoFocusToolOutput();
        chkFocusOutput.setSelected(currentFocusState);
        
        // Toggle state on click
        chkFocusOutput.addActionListener(e -> {
            boolean isChecked = chkFocusOutput.isSelected();
            BearitProperties.getInstance().setAutoFocusToolOutput(isChecked);
        });
        
        toolsMenu.add(chkFocusOutput);
        toolsMenu.addSeparator();

        boolean hasMenuTools = false;
        
        for (int i = 0; i < 8; i++) {
            String command = props.getCustomToolCommand(i);
            if (command != null && !command.trim().isEmpty()) {
                //String icon = props.getCustomToolIcon(i);
                String name = props.getCustomToolName(i);
                String buttonText = name;
                
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

    private void applyTheme(String themeName) {
        BearitProperties props = BearitProperties.getInstance();
        props.setTheme(themeName);
        DialogUtil.applyGlobalTheme(this, themeName);
        if (tabbedPane != null) {
            tabbedPane.setUI(new ThemedTabbedPaneUI(themeName));
        }
        // Update all open tabs
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof AdvancedTextEditorPanel) {
                ((AdvancedTextEditorPanel) c).applyTheme(themeName);
            } else if (c instanceof BearitTextHexWrapper) {
                BearitTextHexWrapper wrapper = (BearitTextHexWrapper) c;
                wrapper.getHiddenTextEditor().applyTheme(themeName);
                wrapper.getHexEditor().applyTheme(themeName);
            }
        }
    }

    // --- Helper to keep both UI toggles in perfect sync ---
    private void syncHexToggles(boolean isHexMode) {
        if (btnToggleHex != null && btnToggleHex.isSelected() != isHexMode) {
            btnToggleHex.setSelected(isHexMode);
        }
        if (hexMenuItem != null && hexMenuItem.isSelected() != isHexMode) {
            hexMenuItem.setSelected(isHexMode);
        }
    }

    /**
     * Creates a JLabel styled as a clickable web hyperlink without layout jumps.
     */
    private JLabel createHyperlink(String text, String url, float fontSize) {
        JLabel linkLabel = new JLabel(text);
        linkLabel.setFont(linkLabel.getFont().deriveFont(Font.PLAIN, fontSize));
        linkLabel.setForeground(new Color(45, 114, 217)); // Modern link blue
        linkLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        
        // Pre-calculate the underlined font
        Font originalFont = linkLabel.getFont();
        Map<TextAttribute, Object> attributes = new HashMap<>(originalFont.getAttributes());
        attributes.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        Font underlineFont = originalFont.deriveFont(attributes);
        
        linkLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                // Apply the native font underline
                linkLabel.setFont(underlineFont);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                // Revert to the normal font
                linkLabel.setFont(originalFont);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(new java.net.URI(url));
                    }
                } catch (Exception ex) {
                    System.err.println("Failed to open hyperlink: " + ex.getMessage());
                }
            }
        });
        
        return linkLabel;
    }

    private void showAboutDialog() {
        String appVersion = BearitApp.class.getPackage().getImplementationVersion(); // get version from pom.xml use <addDefaultImplementationEntries>true</addDefaultImplementationEntries>
        float bodyFontSize = 14f;
        float licenseFontSize = 12f;
        JDialog aboutDialog = new JDialog(this, "About Bearit", true);
        aboutDialog.setLayout(new BorderLayout(15, 15));
        aboutDialog.setResizable(false);
        
        // Center panel for text and links
        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setBorder(BorderFactory.createEmptyBorder(20, 25, 5, 25));
        
        JLabel lblName = new JLabel("Bearit Text Editor");
        lblName.setFont(lblName.getFont().deriveFont(Font.BOLD, 18f));
        lblName.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        JLabel lblVersion = new JLabel("Version: " + appVersion );
        Font bodyFont = lblName.getFont().deriveFont(Font.PLAIN, bodyFontSize);
        lblVersion.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblVersion.setFont(bodyFont);

        JLabel lblInfo = new JLabel("Bearit is a high-performance Java 21 text editor designed specifically to handle massive file sizes, helping your system bear the heavy memory load.");
        lblInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblInfo.setFont(bodyFont);

        JLabel lblAuthor = new JLabel("By Edward Jakubowski  EdWaresApp@gmail.com");
        lblAuthor.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblAuthor.setFont(bodyFont);

        // --- Create the clickable link using our helper ---
        JLabel lblWebsite = createHyperlink("Visit EdWares.com", "https://edwares.com", bodyFontSize);
        lblWebsite.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblWebsite.setFont(bodyFont);

        // --- Licensing Info (Wrapped in a horizontal FlowLayout) ---
        JPanel licensePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));

        JLabel lblLicensePre = new JLabel("Licensed under:");
        lblLicensePre.setForeground(Color.GRAY);
        lblLicensePre.setFont(lblLicensePre.getFont().deriveFont(licenseFontSize));
        lblLicensePre.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        JLabel lblLicenseLink = createHyperlink("Apache 2.0 License", "https://www.apache.org/licenses/LICENSE-2.0", licenseFontSize);
        lblLicenseLink.setFont(lblLicenseLink.getFont().deriveFont(licenseFontSize)); // Match the smaller text size
        lblLicenseLink.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        licensePanel.add(lblLicensePre);
        licensePanel.add(lblLicenseLink);
        
        // Add components with a little vertical spacing
        infoPanel.add(lblName);
        infoPanel.add(Box.createVerticalStrut(5));
        infoPanel.add(lblVersion);
        infoPanel.add(Box.createVerticalStrut(15));
        infoPanel.add(lblInfo);
        infoPanel.add(Box.createVerticalStrut(15));
        infoPanel.add(lblAuthor);
        infoPanel.add(Box.createVerticalStrut(15));
        infoPanel.add(lblWebsite);
        infoPanel.add(Box.createVerticalStrut(20)); // Larger gap before license
        infoPanel.add(licensePanel);
        infoPanel.add(Box.createVerticalStrut(25));
        infoPanel.add(createAboutDebugPanel());
        
        // Bottom panel for the close button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 15));
        JButton closeButton = new JButton(" Close ");
        closeButton.addActionListener(e -> aboutDialog.dispose());
        buttonPanel.add(closeButton);
        
        aboutDialog.add(infoPanel, BorderLayout.CENTER);
        aboutDialog.add(buttonPanel, BorderLayout.SOUTH);
        
        DialogUtil.themeDialog(aboutDialog); // Apply current theme to the dialog and all its children
        aboutDialog.pack();
        aboutDialog.setLocationRelativeTo(this); // Center on the main editor window
        aboutDialog.setVisible(true);
    }

    private JPanel createAboutDebugPanel() {
        // --- Debug Information Section for About ---

        // get version from pom.xml use <addDefaultImplementationEntries>true</addDefaultImplementationEntries>
        String appVersion = BearitApp.class.getPackage().getImplementationVersion(); 

        // Fetch OS Info
        String osInfo = System.getProperty("os.name") + " " + 
                        System.getProperty("os.version") + " (" + 
                        System.getProperty("os.arch") + ")";
        
        // Fetch Java Info
        String javaInfo = System.getProperty("java.version") + " (" + 
                          System.getProperty("java.vendor") + ")";
        
        // Fetch the active JAR/Executable Path dynamically
        String jarPath;
        try {
            jarPath = new File(com.edwares.BearitApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (Exception e) {
            jarPath = "Unable to resolve installation path";
        }

        // Fetch the Properties File Path
        String propsPath = BearitProperties.getInstance().getPropertiesFile().getAbsolutePath();

        Runtime rt = Runtime.getRuntime();
        // --- System RAM (Physical Memory) ---
        OperatingSystemMXBean osBean = (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        long totalRamGB = osBean.getTotalMemorySize() / (1024L * 1024L * 1024L);
        long freeRamGB = osBean.getFreeMemorySize() / (1024L * 1024L * 1024L);

        // --- CPU Cores ---
        int availableCores = rt.availableProcessors();
        
        // --- Max Java Heap Size (JVM Memory) ---
        long maxMemory = rt.maxMemory();
        String maxHeapStr = (maxMemory == Long.MAX_VALUE) ? "No Limit" : (maxMemory / (1024 * 1024)) + " MB";
        
        // --- Hard Drive Space ---
        File currentDrive = new File(".");
        String driveName = Paths.get(".").toAbsolutePath().getRoot().toString();
        long totalSpaceGB = currentDrive.getTotalSpace() / (1024L * 1024L * 1024L);
        long usableSpaceGB = currentDrive.getUsableSpace() / (1024L * 1024L * 1024L);

        // Consolidate the data into a single formatted string
        String fullDebugText =  "Bearit Version: " + appVersion + "\n" +
                                "OS: " + osInfo + "\n" +
                                "Java: " + javaInfo + "\n" +
                                "Install Path: " + jarPath + "\n" +
                                "Preferences: " + propsPath + "\n" +
                                "Max Java Heap: " + maxHeapStr + "\n" +
                                "System RAM: " + freeRamGB + " GB Free / " + totalRamGB + " GB Total\n" +
                                "CPU Cores: " + availableCores + "\n" +
                                "Drive Space (" + driveName + "): " + usableSpaceGB + " GB Free / " + totalSpaceGB + " GB Total\n" ;

        // ---  Debug Panel ---
        JPanel debugPanel = new JPanel(new BorderLayout(0, 10)); // Swapped to BorderLayout
        debugPanel.setBorder(BorderFactory.createTitledBorder("Debug Information"));
        debugPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Debug Information"),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));

        // Build the Text Area
        JTextArea debugTextArea = new JTextArea(fullDebugText);
        debugTextArea.setEditable(false);
        // Use the default label font so it matches the dialog natively
        debugTextArea.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN, 14f)); 
        
        // Wrap it in a scroll pane just in case paths get extremely long
        JScrollPane scrollPane = new JScrollPane(debugTextArea);
        scrollPane.setPreferredSize(new Dimension(200, 100));
        debugPanel.add(scrollPane, BorderLayout.CENTER);

        // --- Build the Copy Button ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        JButton copyBtn = new JButton("Copy to Clipboard");
        
        copyBtn.addActionListener(e -> {
            StringSelection stringSelection = new StringSelection(debugTextArea.getText());
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(stringSelection, null);
            
            // Give brief user feedback that it worked
            copyBtn.setText("Copied!");
            Timer timer = new Timer(1500, evt -> copyBtn.setText("Copy to Clipboard"));
            timer.setRepeats(false);
            timer.start();
        });
        
        buttonPanel.add(copyBtn);
        debugPanel.add(buttonPanel, BorderLayout.SOUTH);
        return debugPanel;
    }

    public void processRemoteCommands(String[] args) {
        CommandLineParser cli = new CommandLineParser(args);
        
        // Handle File Opening first
        if (cli.getFileToOpen() != null) {
            loadInitialFile(cli.getFileToOpen());
        }

        // Ensure we have an active tab to control
        if (tabbedPane == null || getActiveTabComponent() == null) {
            return;
        }

        //get active tab's editor component
        final Component editorComponent = getActiveTabComponent();
        AdvancedTextEditorPanel textEditor = null;
        BearitTextHexWrapper hexWrapper = null;
        if (editorComponent instanceof AdvancedTextEditorPanel) {
            textEditor = (AdvancedTextEditorPanel) editorComponent;
        } else if (editorComponent instanceof BearitTextHexWrapper) {
            hexWrapper = (BearitTextHexWrapper)editorComponent;
        }        

        // Handle Mode Toggling
        if (cli.isHexModeOn() && hexWrapper == null) {
            toggleHexModeForCurrentTab(true);
        } else if (cli.isTextModeOn() && hexWrapper != null) {
            toggleHexModeForCurrentTab(false);
        }

        // Handle Selection
        String range = cli.getSelectRange();
        if (range != null) {
            String[] parts = range.split(",");
            if (parts.length == 2) {
                try {
                    long start = Long.parseLong(parts[0].trim());
                    long end = Long.parseLong(parts[1].trim());
                    if (textEditor != null) textEditor.setGlobalSelection(start, end);
                    if (hexWrapper != null) hexWrapper.setGlobalSelection(start);
                    //wrapper.setGlobalSelection(start, end);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid selection format. Expected -s start;end");
                }
            }
        }

        // Handle Find Search term
        if (cli.getSearchTerm() != null && textEditor != null) {
            SwingUtilities.invokeLater(() -> {
                ((AdvancedTextEditorPanel) editorComponent).updateSearchHistory(cli.getSearchTerm());
                ((AdvancedTextEditorPanel) editorComponent).performFind(cli.getSearchTerm(), true);
                ((AdvancedTextEditorPanel) editorComponent).showSearchDialog();
            });
        }
    }

    /**
     * Broadcasts a font change to all open tabs and saves it to properties.
     */
    public void updateGlobalFont(String fontName, int fontSize) {
        BearitProperties props = BearitProperties.getInstance();
        props.setFontName(fontName);
        props.setFontSize(fontSize);
        
        Font newFont = new Font(fontName, Font.PLAIN, fontSize);
        
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof AdvancedTextEditorPanel) {
                ((AdvancedTextEditorPanel) c).setFont(newFont);
            } else if (c instanceof BearitTextHexWrapper) {
                ((BearitTextHexWrapper) c).setFont(newFont);
            }
        }
    }

    private void showFontPickerDialog() {
        BearitProperties props = BearitProperties.getInstance();
        String currentName = props.getFontName();
        int currentSize = props.getFontSize();

        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setOpaque(false);
        Font dialogFont = new Font(Font.DIALOG, Font.PLAIN, 14);
        // Font Family List
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] fontNames = ge.getAvailableFontFamilyNames();
        JList<String> fontList = new JList<>(fontNames);
        fontList.setFont(dialogFont);
        fontList.setSelectedValue(currentName, true);
        
        // Explicitly forces high-contrast foreground/background colors for both Light and Dark themes
        boolean isDark = "Dark".equals(props.getTheme());
        Color selBg = isDark ? new Color(85, 85, 85) : new Color(200, 220, 255); // Nice visible blue highlight for light mode
        Color selFg = isDark ? Color.WHITE : Color.BLACK;
        Color normalBg = isDark ? new Color(40, 40, 40) : Color.WHITE;
        Color normalFg = isDark ? new Color(200, 200, 200) : Color.BLACK;
        fontList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                c.setBackground(isSelected ? selBg : normalBg);
                c.setForeground(isSelected ? selFg : normalFg);
                return c;
            }
        });

        JScrollPane fontScroll = new JScrollPane(fontList);
        fontScroll.setPreferredSize(new Dimension(270, 200));

        // Font Size Spinner
        JSpinner sizeSpinner = new JSpinner(new SpinnerNumberModel(currentSize, 6, 80, 1));
        sizeSpinner.setFont(dialogFont);
        
        // Explicitly increase the width of the spinner, and lock its height to 30px
        sizeSpinner.setPreferredSize(new Dimension(80, 30));

        // --- Editable Preview Section ---
        JPanel previewPanel = new JPanel(new BorderLayout(3,3));
        previewPanel.setOpaque(false);
        
        JLabel previewLabel = new JLabel("Preview:");
        previewLabel.setFont(dialogFont);
        
        JTextField previewField = new JTextField("The quick brown fox jumps over the lazy dog");
        previewField.setHorizontalAlignment(JTextField.CENTER);
        previewField.setFont(new Font(currentName, Font.PLAIN, currentSize));
        // Give the preview a fixed height so the dialog doesn't bounce around as font sizes change
        previewField.setPreferredSize(new Dimension(300, 80)); 
        JPanel spacePreview = new JPanel();
        spacePreview.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 25));
        JPanel spacePreview2 = new JPanel();
        spacePreview2.setPreferredSize(new Dimension(1, 10));
        //spacePreview2.setBorder(BorderFactory.createEmptyBorder(0, 0, 1, 0));

        previewPanel.add(previewLabel, BorderLayout.NORTH);
        previewPanel.add(previewField, BorderLayout.CENTER);
        previewPanel.add(spacePreview, BorderLayout.EAST);
        previewPanel.add(spacePreview2, BorderLayout.SOUTH);

        // Listeners to update the editable preview in real-time
        fontList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && fontList.getSelectedValue() != null) {
                previewField.setFont(new Font(fontList.getSelectedValue(), Font.PLAIN, (Integer) sizeSpinner.getValue()));
            }
        });
        sizeSpinner.addChangeListener(e -> {
            if (fontList.getSelectedValue() != null) {
                previewField.setFont(new Font(fontList.getSelectedValue(), Font.PLAIN, (Integer) sizeSpinner.getValue()));
            }
        });

        // --- Layout Assembly ---
        JPanel topPanel = new JPanel(new BorderLayout(15, 15));
        TitledBorder border = BorderFactory.createTitledBorder("Font Selection:");
        border.setTitleFont(dialogFont);
        topPanel.setBorder(border);
        topPanel.setOpaque(false);
        topPanel.add(fontScroll, BorderLayout.CENTER);

        // Build the components for the right side
        JPanel rightPanel = new JPanel(new BorderLayout(5, 5));
        rightPanel.setOpaque(false);
        rightPanel.add(new JLabel("Size:"), BorderLayout.WEST);
        rightPanel.add(sizeSpinner, BorderLayout.CENTER);
        
        // The Anchor Wrapper
        // By putting 'rightPanel' in the NORTH slot of a new wrapper, 
        // it refuses to stretch vertically, absorbing the leftover space cleanly!
        JPanel rightWrapper = new JPanel(new BorderLayout());
        rightWrapper.setOpaque(false);
        rightWrapper.add(rightPanel, BorderLayout.NORTH);

        topPanel.add(rightWrapper, BorderLayout.EAST);
        panel.add(topPanel, BorderLayout.CENTER);
        JPanel spacePanel = new JPanel();
        spacePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 25));
        panel.add(spacePanel, BorderLayout.EAST);
        
        // Add the new Preview Panel to the bottom
        panel.add(previewPanel, BorderLayout.SOUTH);

        int result = DialogUtil.showConfirmDialog(this, panel, "Choose Font", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION && fontList.getSelectedValue() != null) {
            updateGlobalFont(fontList.getSelectedValue(), (Integer) sizeSpinner.getValue());
        }
    }
}