package com.edwares;

import com.edwares.DialogUtil;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class DialogThemeTest {

    // Component References for the Helper Method
    private JTextField textField;
    private JLabel label;
    private JCheckBox checkBox;
    private JSpinner spinner;
    private JComboBox<String> comboStatic;
    private JComboBox<String> comboEditable;
    private JToolBar toolBar;
    private JToolBar.Separator toolSeparator;
    private JButton toolBtn;
    private JTabbedPane tabbedPane;
    private JPanel innerTabPanel;
    private JScrollPane scrollPane;
    private TitledBorder titledBorder;
    private JMenuBar menuBar;
    private JMenu fileMenu;
    private JMenuItem openItem;
    private JCheckBoxMenuItem wrapItem;
    private JRadioButtonMenuItem hexItem;
    private JSeparator menuSeparator;
    private JPopupMenu popup;

    @Test
    public void testBidirectionalThemeToggling() {
        JPanel rootContainer = buildFrankensteinUI();

        // ==========================================
        // PHASE 1: ASSERT DARK THEME
        // ==========================================
        
        // 1. Update the centralized palette
        DialogUtil.updateTheme("Dark");
        
        // 2. Run the newly simplified sweeper
        DialogUtil.sweepComponents(rootContainer);

        // 3. Verify against the Dark Theme expected palette
        verifyColors("DARK THEME", 
            new Color(50, 50, 50),     // bg
            new Color(200, 200, 200),  // fg
            new Color(40, 40, 40),     // inputBg
            new Color(85, 85, 85),     // buttonBg
            new Color(75, 75, 75),     // menuBg
            new Color(75, 75, 75)      // toolbarBg
        );


        // ==========================================
        // PHASE 2: ASSERT LIGHT THEME REVERSION
        // ==========================================
        
        // 1. Swap the centralized palette back to Light
        DialogUtil.updateTheme("Light");
        
        // 2. Re-sweep the exact same UI container
        DialogUtil.sweepComponents(rootContainer);

        // 3. Verify against the Light Theme expected palette
        verifyColors("LIGHT THEME", 
            new Color(240, 240, 240),  // bg
            Color.BLACK,               // fg
            Color.WHITE,               // inputBg
            new Color(225, 225, 225),  // buttonBg
            new Color(245, 245, 245),  // menuBg
            new Color(240, 240, 240)   // toolbarBg
        );
    }

    private JPanel buildFrankensteinUI() {
        JPanel rootContainer = new JPanel();

        // --- Menus ---
        menuBar = new JMenuBar();
        fileMenu = new JMenu("File");
        openItem = new JMenuItem("Open");
        wrapItem = new JCheckBoxMenuItem("Word Wrap");
        hexItem = new JRadioButtonMenuItem("Hex Mode");
        menuSeparator = new JSeparator();
        
        fileMenu.add(openItem);
        fileMenu.add(menuSeparator);
        fileMenu.add(wrapItem);
        fileMenu.add(hexItem);
        menuBar.add(fileMenu);
        rootContainer.add(menuBar);

        // Force creation of the popup menu so it exists for testing
        popup = fileMenu.getPopupMenu();

        // --- Toolbars ---
        toolBar = new JToolBar();
        toolBtn = new JButton("Save");
        toolSeparator = new JToolBar.Separator();
        toolBar.add(toolBtn);
        toolBar.add(toolSeparator);
        rootContainer.add(toolBar);

        // --- Deeply Nested Containers ---
        tabbedPane = new JTabbedPane();
        innerTabPanel = new JPanel();
        
        scrollPane = new JScrollPane();
        JTextArea textArea = new JTextArea("Dummy Text");
        scrollPane.setViewportView(textArea);
        
        innerTabPanel.add(scrollPane);
        tabbedPane.addTab("Tab 1", innerTabPanel);
        rootContainer.add(tabbedPane);

        // --- Form Inputs & Controls ---
        JPanel formPanel = new JPanel();
        titledBorder = BorderFactory.createTitledBorder("Settings");
        formPanel.setBorder(titledBorder);
        
        textField = new JTextField("Input");
        label = new JLabel("Status");
        checkBox = new JCheckBox("Enabled");
        
        comboEditable = new JComboBox<>(new String[]{"Item 1"});
        comboEditable.setEditable(true);
        
        comboStatic = new JComboBox<>(new String[]{"Item A"});
        
        spinner = new JSpinner(new SpinnerNumberModel(1, 1, 10, 1));

        formPanel.add(textField);
        formPanel.add(label);
        formPanel.add(checkBox);
        formPanel.add(comboEditable);
        formPanel.add(comboStatic);
        formPanel.add(spinner);
        rootContainer.add(formPanel);

        return rootContainer;
    }

    private void verifyColors(String phase, Color bg, Color fg, Color inputBg, Color buttonBg, Color menuBg, Color toolbarBg) {
        String p = "[" + phase + "] ";

        // --- Basic Form Inputs ---
        assertEquals(inputBg, textField.getBackground(), p + "JTextField Background");
        assertEquals(fg, textField.getForeground(), p + "JTextField Foreground");
        
        assertEquals(fg, label.getForeground(), p + "JLabel Foreground");
        assertFalse(label.isOpaque(), p + "JLabel should be transparent");

        assertEquals(fg, checkBox.getForeground(), p + "JCheckBox Foreground");
        assertFalse(checkBox.isContentAreaFilled(), p + "JCheckBox area should be transparent");

        // --- Spinners ---
        assertEquals(inputBg, spinner.getBackground(), p + "JSpinner Outer Background");
        Component spinnerEditor = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
        assertEquals(inputBg, spinnerEditor.getBackground(), p + "JSpinner Internal TextField Background");
        assertEquals(fg, spinnerEditor.getForeground(), p + "JSpinner Internal TextField Foreground");

        // --- Combo Boxes ---
        assertEquals(inputBg, comboStatic.getBackground(), p + "Static JComboBox Background");
        
        assertEquals(inputBg, comboEditable.getBackground(), p + "Editable JComboBox Outer Background");
        Component comboEditor = comboEditable.getEditor().getEditorComponent();
        assertEquals(inputBg, comboEditor.getBackground(), p + "Editable JComboBox Internal TextField Background");
        assertEquals(fg, comboEditor.getForeground(), p + "Editable JComboBox Internal TextField Foreground");

        // --- Toolbars & Buttons ---
        assertEquals(toolbarBg, toolBar.getBackground(), p + "JToolBar Background");
        assertEquals(toolbarBg, toolSeparator.getBackground(), p + "JToolBar.Separator Background");
        assertEquals(fg, toolSeparator.getForeground(), p + "JToolBar.Separator Foreground line");
        
        assertFalse(toolBtn.isContentAreaFilled(), p + "Toolbar button should be transparent to blend in");
        assertEquals(fg, toolBtn.getForeground(), p + "Toolbar button text color");

        // --- Containers & Viewports ---
        assertEquals(bg, tabbedPane.getBackground(), p + "JTabbedPane Background");
        assertEquals(bg, innerTabPanel.getBackground(), p + "Nested JPanel Background");
        
        assertEquals(bg, scrollPane.getBackground(), p + "JScrollPane Background");
        assertEquals(bg, scrollPane.getViewport().getBackground(), p + "JScrollPane Viewport Background");
        
        assertEquals(fg, titledBorder.getTitleColor(), p + "TitledBorder Text Color");

        // --- Menus ---
        assertEquals(menuBg, menuBar.getBackground(), p + "JMenuBar Background");
        assertEquals(menuBg, fileMenu.getBackground(), p + "JMenu Background");
        assertEquals(menuBg, openItem.getBackground(), p + "JMenuItem Background");
        assertEquals(menuBg, wrapItem.getBackground(), p + "JCheckBoxMenuItem Background");
        assertEquals(menuBg, hexItem.getBackground(), p + "JRadioButtonMenuItem Background");
        
        assertEquals(menuBg, menuSeparator.getBackground(), p + "Menu JSeparator Background");
        assertEquals(bg, menuSeparator.getForeground(), p + "Menu JSeparator Foreground");

        assertEquals(menuBg, popup.getBackground(), p + "Hidden JPopupMenu Background");
    }
}