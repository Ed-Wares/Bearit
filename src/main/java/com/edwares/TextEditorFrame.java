package com.edwares;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class TextEditorFrame extends JFrame {
    private final JTextArea textArea;
    private final JFileChooser fileChooser;
    private File currentFile;

    public TextEditorFrame() {
        setTitle("Bearit Text Editor - Untitled");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Configure the text editing area
        textArea = new JTextArea();
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        // Wrap text area inside a scroll pane
        JScrollPane scrollPane = new JScrollPane(textArea);
        add(scrollPane, BorderLayout.CENTER);

        fileChooser = new JFileChooser();

        setupMenuBar();
    }

    private void setupMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // File Menu
        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem = new JMenuItem("New");
        JMenuItem openItem = new JMenuItem("Open...");
        JMenuItem saveItem = new JMenuItem("Save");
        JMenuItem saveAsItem = new JMenuItem("Save As...");
        JMenuItem exitItem = new JMenuItem("Exit");

        newItem.addActionListener(e -> performNew());
        openItem.addActionListener(e -> performOpen());
        saveItem.addActionListener(e -> performSave(false));
        saveAsItem.addActionListener(e -> performSave(true));
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.add(saveAsItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        // Edit Menu
        JMenu editMenu = new JMenu("Edit");
        JMenuItem cutItem = new JMenuItem("Cut");
        JMenuItem copyItem = new JMenuItem("Copy");
        JMenuItem pasteItem = new JMenuItem("Paste");

        cutItem.addActionListener(e -> textArea.cut());
        copyItem.addActionListener(e -> textArea.copy());
        pasteItem.addActionListener(e -> textArea.paste());

        editMenu.add(cutItem);
        editMenu.add(copyItem);
        editMenu.add(pasteItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        setJMenuBar(menuBar);
    }

    private void performNew() {
        textArea.setText("");
        currentFile = null;
        setTitle("Bearit Text Editor - Untitled");
    }

    private void performOpen() {
        int option = fileChooser.showOpenDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            currentFile = fileChooser.getSelectedFile();
            try {
                String content = Files.readString(Path.of(currentFile.getAbsolutePath()));
                textArea.setText(content);
                setTitle("Bearit Text Editor - " + currentFile.getName());
            } catch (IOException ex) {
                showError("Could not read file: " + ex.getMessage());
            }
        }
    }

    private void performSave(boolean saveAs) {
        if (saveAs || currentFile == null) {
            int option = fileChooser.showSaveDialog(this);
            if (option == JFileChooser.APPROVE_OPTION) {
                currentFile = fileChooser.getSelectedFile();
            } else {
                return; // User canceled the save operation
            }
        }

        try {
            Files.writeString(Path.of(currentFile.getAbsolutePath()), textArea.getText());
            setTitle("Bearit Text Editor - " + currentFile.getName());
        } catch (IOException ex) {
            showError("Could not save file: " + ex.getMessage());
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
}