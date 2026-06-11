package com.edwares;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public class DialogUtil {

    private static volatile String theme = "Light";// Default theme, will be updated dynamically by BearitFrame

    /**
     * Displays a fully themed confirmation dialog (Yes/No/Cancel).
     */
    public static int showConfirmDialog(Component parent, Object message, String title, int optionType) {
        JOptionPane pane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, optionType);
        JDialog dialog = pane.createDialog(parent, title);
        
        themeDialog(dialog);
        dialog.setVisible(true);
        dialog.dispose();

        Object selectedValue = pane.getValue();
        if (selectedValue == null) return JOptionPane.CLOSED_OPTION;
        if (selectedValue instanceof Integer) return (Integer) selectedValue;
        
        return JOptionPane.CLOSED_OPTION;
    }

    /**
     * Displays a fully themed input dialog.
     */
    public static String showInputDialog(Component parent, Object message, String title) {
        JOptionPane pane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        pane.setWantsInput(true);
        JDialog dialog = pane.createDialog(parent, title);
        
        themeDialog(dialog);
        dialog.setVisible(true);
        dialog.dispose();

        Object value = pane.getInputValue();
        if (value != null && value != JOptionPane.UNINITIALIZED_VALUE) {
            return value.toString();
        }
        return null;
    }

    /**
     * Displays a fully themed message alert dialog.
     */
    public static void showMessageDialog(Component parent, Object message, String title, int messageType) {
        JOptionPane pane = new JOptionPane(message, messageType, JOptionPane.DEFAULT_OPTION);
        JDialog dialog = pane.createDialog(parent, title);
        
        themeDialog(dialog);
        dialog.setVisible(true);
        dialog.dispose();
    }

    /**
     * Opens the native OS file explorer to select a file for reading.
     * Returns null if the user cancels.
     */
    public static File showOpenFileDialog(Frame parentFrame, String title) {
        FileDialog fileDialog = new FileDialog(parentFrame, title, FileDialog.LOAD);
        fileDialog.setVisible(true);
        
        String directory = fileDialog.getDirectory();
        String filename = fileDialog.getFile();
        
        if (directory != null && filename != null) {
            return new File(directory, filename);
        }
        return null;
    }

    /**
     * Opens the native OS file explorer to choose a save destination.
     * Returns null if the user cancels.
     */
    public static File showSaveFileDialog(Frame parentFrame, String title) {
        FileDialog fileDialog = new FileDialog(parentFrame, title, FileDialog.SAVE);
        fileDialog.setVisible(true);
        
        String directory = fileDialog.getDirectory();
        String filename = fileDialog.getFile();
        
        if (directory != null && filename != null) {
            return new File(directory, filename);
        }
        return null;
    }

    // ==========================================
    // Internal Theming Engine
    // ==========================================

    public static void themeDialog(JDialog dialog) {
        // Automatically grab current app settings dynamically
        boolean isDark = "Dark".equals(theme);

        // Color Palette definitions matching the main frame
        Color bg = isDark ? new Color(50, 50, 50) : new Color(240, 240, 240);
        Color fg = isDark ? new Color(200, 200, 200) : Color.BLACK;
        Color borderColor = isDark ? new Color(100, 100, 100) : new Color(200, 200, 200);
        Color inputBg = isDark ? new Color(40, 40, 40) : Color.WHITE;
        Color buttonBg = isDark ? new Color(85, 85, 85) : new Color(225, 225, 225);

        dialog.setBackground(bg);
        sweepComponents(dialog, bg, fg, borderColor, inputBg, buttonBg);
    }

    private static void sweepComponents(Container container, Color bg, Color fg, Color borderColor, Color inputBg, Color buttonBg) {
        for (Component c : container.getComponents()) {
            
            // --- If it's a panel, theme it AND dive inside it ---
            if (c instanceof JPanel) {
                c.setBackground(bg);
                c.setForeground(fg);
                ((JPanel) c).setOpaque(true);
                sweepComponents((Container) c, bg, fg, borderColor, inputBg, buttonBg);
            }
            // --- Text Fields ---
            else if (c instanceof JTextField) {
                c.setBackground(inputBg);
                c.setForeground(fg);
                // --- Strip the native Linux GTK renderer off the text field ---
                ((JTextField) c).setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
                ((JTextField) c).setCaretColor(fg);
                ((JTextField) c).setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderColor, 1),
                        BorderFactory.createEmptyBorder(2, 5, 2, 5) 
                ));
            }
            // --- Buttons ---
            else if (c instanceof JButton) {
                c.setBackground(buttonBg);
                c.setForeground(fg);
                ((JComponent) c).setOpaque(true);
                ((JButton) c).setUI(new javax.swing.plaf.basic.BasicButtonUI());
                ((JButton) c).setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderColor, 1),
                        BorderFactory.createEmptyBorder(4, 10, 4, 10)
                ));
            }
            // --- Combo Boxes (Drop-downs) ---
            else if (c instanceof JComboBox) {
                JComboBox<?> combo = (JComboBox<?>) c;
                combo.setBackground(inputBg);
                combo.setForeground(fg);
                combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI());
                combo.setBorder(BorderFactory.createLineBorder(borderColor, 1));

                // --- Theme the hidden text field if it is editable ---
                if (combo.isEditable()) {
                    Component editor = combo.getEditor().getEditorComponent();
                    editor.setBackground(inputBg);
                    editor.setForeground(fg);
                    // Ensure the typing cursor is visible in dark mode
                    if (editor instanceof JTextField) {
                        // --- Strip the native renderer off the hidden internal text field! ---
                        ((JTextField) editor).setUI(new javax.swing.plaf.basic.BasicTextFieldUI());

                        ((JTextField) editor).setCaretColor(fg);
                        ((JTextField) editor).setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
                    }
                }
                // --- Theme the drop-down list items ---
                combo.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        Component renderer = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                        
                        // Theme the highlighted item vs the standard items
                        if (isSelected) {
                            renderer.setBackground(buttonBg); // Use button color for the hover highlight
                            renderer.setForeground(fg);
                        } else {
                            renderer.setBackground(inputBg);
                            renderer.setForeground(fg);
                        }
                        return renderer;
                    }
                });
            }
            // --- Labels and Checkboxes ---
            else if (c instanceof JLabel || c instanceof JCheckBox) {
                c.setForeground(fg);
                ((JComponent) c).setOpaque(false); 
                
                if (c instanceof JCheckBox) {
                    ((JCheckBox) c).setContentAreaFilled(false);
                }
            }
            // --- Catch ALL other containers (JOptionPane, Box, JPanel) ---
            else if (c instanceof Container) {
                c.setBackground(bg);
                c.setForeground(fg);
                
                // JComponent provides the setOpaque method
                if (c instanceof JComponent) {
                    ((JComponent) c).setOpaque(true);
                }
                
                // Recursively dig down into this container to find more inputs/buttons
                sweepComponents((Container) c, bg, fg, borderColor, inputBg, buttonBg);
            }            
        }
    }

    public static void themePopupMenu(JPopupMenu popupMenu) {
        if (popupMenu == null) return;
        
        boolean isDark = "Dark".equals(theme);
        
        // Define the exact same colors used in your BearitFrame top menus
        Color bg = isDark ? new Color(50, 50, 50) : new Color(240, 240, 240);
        Color fg = isDark ? new Color(200, 200, 200) : Color.BLACK;
        Color menuBg = isDark ? new Color(75, 75, 75) : new Color(245, 245, 245);
        Color borderColor = isDark ? new Color(100, 100, 100) : new Color(200, 200, 200);

        // Frame the main popup box with the exact same border as the top menus
        popupMenu.setBackground(menuBg);
        popupMenu.setForeground(fg);
        popupMenu.setOpaque(true);
        popupMenu.setBorder(BorderFactory.createLineBorder(borderColor, 1));

        // Loop through all the menu items (Copy, Paste, etc.)
        for (Component c : popupMenu.getComponents()) {
            c.setBackground(menuBg);
            c.setForeground(fg);
            ((JComponent) c).setOpaque(true);

            // --- Catch JMenu BEFORE JMenuItem ---
            if (c instanceof JMenu) {
                // Apply the correct UI that knows how to handle submenus
                ((JMenu) c).setUI(new javax.swing.plaf.basic.BasicMenuUI());
                // Recursively theme the hidden drop-down box attached to this submenu
                themePopupMenu(((JMenu) c).getPopupMenu());
            } else if (c instanceof JMenuItem) {
                // Strip the native OS renderer off the items
                ((JMenuItem) c).setUI(new javax.swing.plaf.basic.BasicMenuItemUI());
            } else if (c instanceof JSeparator) {
                // Style the separators to match
                c.setForeground(bg); // The drawn line
                c.setBackground(menuBg); // The padding around the line
            }
        }
    }    

    public static void updateTheme(String newTheme) {
        theme = newTheme;
    }
}