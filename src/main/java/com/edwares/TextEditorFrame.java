package com.edwares;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;

public class TextEditorFrame extends JFrame {
    private final AdvancedTextEditorPanel editorPanel;
    private final JFileChooser fileChooser;

    public TextEditorFrame() {
        BearitProperties props = BearitProperties.getInstance();
        
        // The screen size is the working area of the screen, exluding taskbars and docks
        int xPos = 50;
        int yPos = 50;
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

        setTitle("Bearit Text Editor - Untitled");
        setSize(props.getFrameWidth(), props.getFrameHeight());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Save window dimensions automatically upon closing
        addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent evt) {
                props.setFrameWidth(getWidth());
                props.setFrameHeight(getHeight());
            }
        });

        // Instantiate the core reusable editor component
        editorPanel = new AdvancedTextEditorPanel();
        editorPanel.setFont(new Font(props.getFontName(), Font.PLAIN, props.getFontSize()));
        fileChooser = new JFileChooser();

        // Listen for document title changes from the editor panel to update the window frame
        editorPanel.addPropertyChangeListener("editorTitle", evt -> {
            setTitle("Bearit Text Editor - " + evt.getNewValue());
        });

        // Setup the UI Layout
        add(createToolBar(), BorderLayout.NORTH);
        add(editorPanel, BorderLayout.CENTER);
        setJMenuBar(createMenuBar());
    }

    /**
     * Called by the application bootstrapper if a file is passed via command-line arguments.
     */
    public void loadInitialFile(File file) {
        editorPanel.loadFile(file);
    }

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

        // Tooltip text for better UX
        btnNew.setToolTipText("Create a new document");
        btnOpen.setToolTipText("Open an existing file");
        btnSave.setToolTipText("Save current changes");
        
        btnUndo.setToolTipText("Undo last edit in current chunk");
        btnRedo.setToolTipText("Redo last edit in current chunk");
        
        btnCut.setToolTipText("Cut selected text");
        btnCopy.setToolTipText("Copy selected text");
        btnPaste.setToolTipText("Paste text from clipboard");
        
        btnSearch.setToolTipText("Search and Replace across full file");
        btnGoto.setToolTipText("Jump to specific line number");

        // Action routing
        btnNew.addActionListener(e -> performNew());
        btnOpen.addActionListener(e -> performOpen());
        btnSave.addActionListener(e -> performSave());
        btnSaveAs.addActionListener(e -> performSaveAs());
        
        btnUndo.addActionListener(e -> editorPanel.undo());
        btnRedo.addActionListener(e -> editorPanel.redo());
        
        btnCut.addActionListener(e -> editorPanel.cut());
        btnCopy.addActionListener(e -> editorPanel.copy());
        btnPaste.addActionListener(e -> editorPanel.paste());
        
        btnSearch.addActionListener(e -> editorPanel.showSearchDialog());
        btnGoto.addActionListener(e -> editorPanel.showGotoLineDialog());

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

        return toolBar;
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // --- File Menu ---
        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem = new JMenuItem("New");
        JMenuItem openItem = new JMenuItem("Open...");
        JMenuItem saveItem = new JMenuItem("Save");
        JMenuItem saveAsItem = new JMenuItem("Save As...");
        JMenuItem exitItem = new JMenuItem("Exit");

        newItem.addActionListener(e -> performNew());
        openItem.addActionListener(e -> performOpen());
        saveItem.addActionListener(e -> performSave());
        saveAsItem.addActionListener(e -> performSaveAs());
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(newItem);
        fileMenu.add(openItem);
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

        undoItem.addActionListener(e -> editorPanel.undo());
        redoItem.addActionListener(e -> editorPanel.redo());
        cutItem.addActionListener(e -> editorPanel.cut());
        copyItem.addActionListener(e -> editorPanel.copy());
        pasteItem.addActionListener(e -> editorPanel.paste());
        searchItem.addActionListener(e -> editorPanel.showSearchDialog());
        gotoItem.addActionListener(e -> editorPanel.showGotoLineDialog());

        editMenu.add(undoItem);
        editMenu.add(redoItem);
        editMenu.addSeparator();
        editMenu.add(cutItem);
        editMenu.add(copyItem);
        editMenu.add(pasteItem);
        editMenu.addSeparator();
        editMenu.add(searchItem);
        editMenu.add(gotoItem);

        // --- Help Menu ---
        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About Bearit...");
        aboutItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(aboutItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(helpMenu);

        return menuBar;
    }

    // --- Action Handlers ---

    private void performNew() {
        editorPanel.createNewDocument();
    }

    private void performOpen() {
        int option = fileChooser.showOpenDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            editorPanel.loadFile(fileChooser.getSelectedFile());
            BearitProperties.getInstance().addRecentFile(fileChooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void performSave() {
        // Automatically route to "Save As" if the file hasn't been saved to disk yet
        if (!editorPanel.hasActiveFile()) {
            performSaveAs();
        } else {
            editorPanel.saveCurrentFile();
        }
    }

    private void performSaveAs() {
        int option = fileChooser.showSaveDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            editorPanel.saveAsFile(fileChooser.getSelectedFile());
        }
    }

    private void showAboutDialog() {
        String appVersion = BearitApp.class.getPackage().getImplementationVersion(); // get version from pom.xml use <addDefaultImplementationEntries>true</addDefaultImplementationEntries>
        String aboutMessage = "Bearit Text Editor\n"
                + "Version: 1.0\n\n"
                + "A high-performance Java 21 text editor designed specifically "
                + "for handling massive file sizes with extreme memory efficiency.\n\n"
                + "By Ed Jakubowski  EdWaresApp@gmail.com\n";
                
        JOptionPane.showMessageDialog(this, 
                aboutMessage, 
                "About Bearit", 
                JOptionPane.INFORMATION_MESSAGE);
    }
}