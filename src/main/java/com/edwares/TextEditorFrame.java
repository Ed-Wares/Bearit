package com.edwares;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public class TextEditorFrame extends JFrame {
    private final AdvancedTextEditorPanel editorPanel;
    private final JFileChooser fileChooser;

    public TextEditorFrame() {
        setTitle("Bearit Text Editor - Untitled");
        setSize(950, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Instantiate the core reusable editor component
        editorPanel = new AdvancedTextEditorPanel();
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
        JButton btnCut = new JButton("✂ Cut");
        JButton btnCopy = new JButton("📋 Copy");
        JButton btnPaste = new JButton("📝 Paste");

        // Tooltip text for better UX
        btnNew.setToolTipText("Create a new document");
        btnOpen.setToolTipText("Open an existing file");
        btnSave.setToolTipText("Save current changes");
        btnCut.setToolTipText("Cut selected text");
        btnCopy.setToolTipText("Copy selected text");
        btnPaste.setToolTipText("Paste text from clipboard");

        // Action routing
        btnNew.addActionListener(e -> performNew());
        btnOpen.addActionListener(e -> performOpen());
        btnSave.addActionListener(e -> performSave());
        btnSaveAs.addActionListener(e -> performSaveAs());
        btnCut.addActionListener(e -> editorPanel.cut());
        btnCopy.addActionListener(e -> editorPanel.copy());
        btnPaste.addActionListener(e -> editorPanel.paste());

        toolBar.add(btnNew);
        toolBar.add(btnOpen);
        toolBar.add(btnSave);
        toolBar.add(btnSaveAs);
        toolBar.addSeparator();
        toolBar.add(btnCut);
        toolBar.add(btnCopy);
        toolBar.add(btnPaste);

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
        JMenuItem cutItem = new JMenuItem("Cut");
        JMenuItem copyItem = new JMenuItem("Copy");
        JMenuItem pasteItem = new JMenuItem("Paste");

        cutItem.addActionListener(e -> editorPanel.cut());
        copyItem.addActionListener(e -> editorPanel.copy());
        pasteItem.addActionListener(e -> editorPanel.paste());

        editMenu.add(cutItem);
        editMenu.add(copyItem);
        editMenu.add(pasteItem);

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
        String aboutMessage = "Bearit Text Editor\n"
                + "Version: 1.0\n\n"
                + "A high-performance Java 21 text editor designed specifically "
                + "for handling massive file sizes with extreme memory efficiency.\n\n"
                + "Package: com.edwares";
                
        JOptionPane.showMessageDialog(this, 
                aboutMessage, 
                "About Bearit", 
                JOptionPane.INFORMATION_MESSAGE);
    }
}