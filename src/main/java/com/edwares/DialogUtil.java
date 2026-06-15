package com.edwares;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;

import java.awt.*;
import java.io.File;
import java.io.FilenameFilter;

public class DialogUtil {

    // ==========================================
    //  State Management & Centralized Palette
    // ==========================================

    private static volatile String theme = "Light";

    /**
     * Shared theme color palette
     */
    private static class ThemePalette {
        static boolean isDark;
        static Color bg, fg, borderColor, inputBg, buttonBg, menuBg, toolbarBg;

        static { update("Light"); } // Initialize default colors on load

        static void update(String currentTheme) {
            isDark = "Dark".equals(currentTheme);
            bg = isDark ? new Color(50, 50, 50) : new Color(240, 240, 240);
            fg = isDark ? new Color(200, 200, 200) : Color.BLACK;
            borderColor = isDark ? new Color(100, 100, 100) : new Color(200, 200, 200);
            inputBg = isDark ? new Color(40, 40, 40) : Color.WHITE;
            buttonBg = isDark ? new Color(85, 85, 85) : new Color(225, 225, 225);
            menuBg = isDark ? new Color(75, 75, 75) : new Color(245, 245, 245);
            toolbarBg = isDark ? new Color(75, 75, 75) : new Color(240, 240, 240);
        }
    }

    public static void updateTheme(String newTheme) {
        theme = newTheme;
        ThemePalette.update(theme);
        
    }

    public static String getTheme() {
        return theme;
    }

    // ==========================================
    //  Public Dialog API
    // ==========================================

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
        return showOpenFileDialog(parentFrame, title, null);
    }

    public static File showOpenFileDialog(Frame parentFrame, String title, String defaultFile) {
        FileDialog fileDialog = new FileDialog(parentFrame, title, FileDialog.LOAD);
        
        if (defaultFile != null && !defaultFile.isEmpty()) {
            fileDialog.setFile(defaultFile);
        }
        
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
        return showSaveFileDialog(parentFrame, title, null);
    }

    public static File showSaveFileDialog(Frame parentFrame, String title, String defaultFile) {
        FileDialog fileDialog = new FileDialog(parentFrame, title, FileDialog.SAVE);

        if (defaultFile != null && !defaultFile.isEmpty()) {
            fileDialog.setFile(defaultFile);
        }
        fileDialog.setVisible(true);
        
        String directory = fileDialog.getDirectory();
        String filename = fileDialog.getFile();
        
        if (directory != null && filename != null) {
            return new File(directory, filename);
        }
        return null;
    }

    // ==========================================
    //  Public Theme API
    // ==========================================

    public static void themeDialog(JDialog dialog) {
        dialog.setBackground(ThemePalette.bg);
        sweepComponents(dialog);
    }

    public static void applyGlobalTheme(Container container, String newTheme){
        updateTheme(newTheme); // Ensure palette is synced before applying

        // Local aliases for UIManager readability
        boolean isDark = ThemePalette.isDark;
        Color bg = ThemePalette.bg;
        Color fg = ThemePalette.fg;
        Color inputBg = ThemePalette.inputBg;
        Color buttonBg = ThemePalette.buttonBg;
        Color menuBg = ThemePalette.menuBg;
        Color toolbarBg = ThemePalette.toolbarBg;

        // Update UIManager for standard dialogs
        UIManager.put("OptionPane.background", bg);
        UIManager.put("Panel.background", bg);
        UIManager.put("OptionPane.messageForeground", fg);
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(0, 0, 0, 0));
        
        // Global Fallbacks for Dynamically Spawned Inputs (Like Hex Editor Grids)
        UIManager.put("ComboBox.background", inputBg);
        UIManager.put("ComboBox.foreground", fg);
        UIManager.put("ComboBox.selectionBackground", buttonBg);
        UIManager.put("ComboBox.selectionForeground", fg);
        
        UIManager.put("TextField.background", inputBg);
        UIManager.put("TextField.foreground", fg);
        UIManager.put("TextField.caretForeground", fg);
        UIManager.put("TextField.inactiveBackground", inputBg);
        
        // UBUNTU GTK FIX: Permanently strip native renderers from dynamically spawned inputs
        UIManager.put("TextFieldUI", "javax.swing.plaf.basic.BasicTextFieldUI");
        UIManager.put("SpinnerUI", "javax.swing.plaf.basic.BasicSpinnerUI");
        UIManager.put("ScrollBarUI", ThemedScrollBarUI.class.getName());

        // Theme-specific UI settings
        if (isDark) {
            UIManager.put("TabbedPane.background", toolbarBg);
            UIManager.put("TabbedPane.contentAreaColor", bg);
            UIManager.put("TabbedPane.shadow", new Color(30, 30, 30));         
            UIManager.put("TabbedPane.darkShadow", new Color(20, 20, 20));     
            UIManager.put("TabbedPane.light", new Color(42, 42, 42));          
            UIManager.put("TabbedPane.highlight", new Color(49, 49, 49));      
            UIManager.put("TabbedPane.selected", bg); 
            
            UIManager.put("MenuBar.background", menuBg);
            UIManager.put("Menu.background", menuBg);
            UIManager.put("MenuItem.background", menuBg);
            UIManager.put("PopupMenu.background", menuBg);
            
            UIManager.put("MenuItem.acceleratorForeground", fg);
            UIManager.put("Menu.acceleratorForeground", fg);
            UIManager.put("CheckBoxMenuItem.acceleratorForeground", fg);
            UIManager.put("RadioButtonMenuItem.acceleratorForeground", fg);
            
            UIManager.put("CheckBoxMenuItem.checkIcon", new ThemeCheckBoxIcon(fg));
            UIManager.put("RadioButtonMenuItem.checkIcon", new ThemeRadioIcon(fg));
        } else {
            UIManager.put("TabbedPane.background", UIManager.getColor("control"));
            UIManager.put("TabbedPane.contentAreaColor", UIManager.getColor("control"));
            UIManager.put("TabbedPane.shadow", UIManager.getColor("controlShadow"));
            UIManager.put("TabbedPane.darkShadow", UIManager.getColor("controlDkShadow"));
            UIManager.put("TabbedPane.light", UIManager.getColor("controlHighlight"));
            UIManager.put("TabbedPane.highlight", UIManager.getColor("controlLtHighlight"));
            UIManager.put("TabbedPane.selected", UIManager.getColor("control"));
            
            UIManager.put("MenuItem.acceleratorForeground", Color.BLACK);
            UIManager.put("Menu.acceleratorForeground", Color.BLACK);
            UIManager.put("CheckBoxMenuItem.acceleratorForeground", Color.BLACK);
            UIManager.put("RadioButtonMenuItem.acceleratorForeground", Color.BLACK);
            
            UIManager.put("CheckBoxMenuItem.checkIcon", new ThemeCheckBoxIcon(Color.BLACK));
            UIManager.put("RadioButtonMenuItem.checkIcon", new ThemeRadioIcon(Color.BLACK));            
        }

        // Explicitly target the JMenuBar to fill the trailing empty space
        if (container instanceof JFrame) {
            JFrame frame = (JFrame)container;
            if (frame.getJMenuBar() != null) {
                frame.getJMenuBar().setOpaque(true);
                frame.getJMenuBar().setBackground(menuBg);
                frame.getJMenuBar().setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, bg));
            }
            frame.getContentPane().setBackground(toolbarBg);
        }

        // Recursively theme the main window
        sweepComponents(container);
    }

    public static void themePopupMenu(JPopupMenu popupMenu) {
        if (popupMenu == null) return;
        
        popupMenu.setBackground(ThemePalette.menuBg);
        popupMenu.setForeground(ThemePalette.fg);
        popupMenu.setOpaque(true);
        popupMenu.setBorder(BorderFactory.createLineBorder(ThemePalette.borderColor, 1));

        for (Component c : popupMenu.getComponents()) {
            c.setBackground(ThemePalette.menuBg);
            c.setForeground(ThemePalette.fg);
            ((JComponent) c).setOpaque(true);

            if (c instanceof JMenu) {
                ((JMenu) c).setUI(new javax.swing.plaf.basic.BasicMenuUI() {
                    @Override
                    protected void paintMenuItem(Graphics g, JComponent c, Icon checkIcon, Icon arrowIcon, Color background, Color foreground, int defaultTextIconGap) {
                        Icon customArrow = new ThemeMenuArrowIcon(ThemePalette.fg);
                        super.paintMenuItem(g, c, checkIcon, customArrow, background, foreground, defaultTextIconGap);
                    }
                });
                themePopupMenu(((JMenu) c).getPopupMenu());
            } else if (c instanceof JMenuItem) {
                ((JMenuItem) c).setUI(new javax.swing.plaf.basic.BasicMenuItemUI());
            } else if (c instanceof JSeparator) {
                c.setForeground(ThemePalette.bg); 
                c.setBackground(ThemePalette.menuBg); 
            }
        }
    }    

    // ==========================================
    //  Internal Theming Engine (The Sweeper)
    // ==========================================

    public static void sweepComponents(Container container) {
        // Alias palette colors locally to keep component assignments extremely clean
        Color bg = ThemePalette.bg;
        Color fg = ThemePalette.fg;
        Color borderColor = ThemePalette.borderColor;
        Color inputBg = ThemePalette.inputBg;
        Color buttonBg = ThemePalette.buttonBg;
        Color menuBg = ThemePalette.menuBg;
        Color toolbarBg = ThemePalette.toolbarBg;
        boolean isDark = ThemePalette.isDark;

        for (Component c : container.getComponents()) {

            // --- Menus and Submenus ---
            if (c instanceof JMenu || c instanceof JMenuItem || c instanceof JMenuBar) {
                c.setBackground(menuBg);
                c.setForeground(fg);
                ((JComponent) c).setOpaque(true); 
                
                if (c instanceof JMenuBar) {
                    ((JMenuBar) c).setUI(new javax.swing.plaf.basic.BasicMenuBarUI());
                    sweepComponents((Container) c);                
                } else if (c instanceof JCheckBoxMenuItem) {
                    ((JCheckBoxMenuItem) c).setUI(new javax.swing.plaf.basic.BasicCheckBoxMenuItemUI());
                } else if (c instanceof JRadioButtonMenuItem) {
                    ((JRadioButtonMenuItem) c).setUI(new javax.swing.plaf.basic.BasicRadioButtonMenuItemUI());
                } else if (c instanceof JMenu) {
                    ((JMenu) c).setUI(new javax.swing.plaf.basic.BasicMenuUI() {
                        @Override
                        protected void paintMenuItem(Graphics g, JComponent c, Icon checkIcon, Icon arrowIcon, Color background, Color foreground, int defaultTextIconGap) {
                            Icon customArrow = (((JMenu) c).getParent() instanceof JPopupMenu) ? new ThemeMenuArrowIcon(fg) : null;
                            super.paintMenuItem(g, c, checkIcon, customArrow, background, foreground, defaultTextIconGap);
                        }
                    });     
                } else if (c instanceof JMenuItem) {
                    ((JMenuItem) c).setUI(new javax.swing.plaf.basic.BasicMenuItemUI());
                }
                
                if (c instanceof JMenu) {
                    JPopupMenu popup = ((JMenu) c).getPopupMenu();
                    if (popup != null) {
                        popup.setBackground(menuBg);
                        popup.setForeground(fg);
                        popup.setOpaque(true); 
                        popup.setBorder(BorderFactory.createLineBorder(fg, 1));
                        sweepComponents(popup);
                    }
                }
            } 
            // --- Toolbar Separators ---
            else if (c instanceof JToolBar.Separator) {
                c.setBackground(toolbarBg);
                c.setForeground(fg); 
                ((JComponent) c).setOpaque(true);
                ((JToolBar.Separator) c).setSeparatorSize(new Dimension(16, 24));
                ((JToolBar.Separator) c).setMaximumSize(new Dimension(16, Short.MAX_VALUE));                
                ((JToolBar.Separator) c).setUI(new javax.swing.plaf.basic.BasicToolBarSeparatorUI() {
                    @Override
                    public void paint(Graphics g, JComponent comp) {
                        g.setColor(toolbarBg);
                        g.fillRect(0, 0, comp.getWidth(), comp.getHeight());
                        g.setColor(fg); 
                        int middleX = comp.getWidth() / 2;
                        int padY = 3; 
                        g.drawLine(middleX, padY, middleX, comp.getHeight() - padY); 
                    }
                });
            } 
            // --- Standard Menu Separators ---
            else if (c instanceof JSeparator) {
                c.setBackground(menuBg);
                c.setForeground(bg); 
                ((JComponent) c).setOpaque(true);
            } 
            // --- Toolbars ---
            else if (c instanceof JToolBar) {
                c.setBackground(toolbarBg);
                c.setForeground(fg);
                ((JComponent) c).setOpaque(true);
                ((JToolBar) c).setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
                sweepComponents((Container) c);
            } 
            // --- TabbedPanes ---
            else if (c instanceof JTabbedPane) {
                c.setBackground(bg); 
                c.setForeground(fg);
                ((JComponent) c).setOpaque(true);
                sweepComponents((Container) c);
                
                JTabbedPane tp = (JTabbedPane)c;
                tp.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
                    @Override
                    protected void installDefaults() {
                        super.installDefaults();
                        if (isDark) {
                            lightHighlight = new Color(42, 42, 42); 
                            highlight = new Color(49, 49, 49);     
                            shadow = new Color(30, 30, 30);         
                            darkShadow = new Color(20, 20, 20);     
                        }
                    }
                });
                tp.setOpaque(false);
            } 
            // --- ScrollPanes ---
            else if (c instanceof JScrollPane) {
                c.setBackground(bg);
                c.setForeground(fg);
                ((JComponent) c).setOpaque(true);
                ((JScrollPane) c).setBorder(BorderFactory.createEmptyBorder());
                ((JScrollPane) c).getViewport().setBackground(bg);
                ((JScrollPane) c).getViewport().setForeground(fg);
                sweepComponents(((JScrollPane) c).getViewport());
            } 
            // --- Panels ---
            else if (c instanceof JPanel) {
                c.setBackground(bg);
                c.setForeground(fg);
                ((JPanel) c).setOpaque(true);
                themeBorder(((JPanel) c).getBorder(), fg);
                sweepComponents((Container) c);
            } 
            // --- Text Fields ---
            else if (c instanceof JTextField) {
                c.setBackground(inputBg);
                c.setForeground(fg);
                ((JTextField) c).setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
                ((JTextField) c).setCaretColor(fg);
                ((JTextField) c).setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderColor, 1),
                        BorderFactory.createEmptyBorder(2, 5, 2, 5) 
                ));
            } 
            // --- Buttons ---
            else if (c instanceof JButton) {
                JButton btn = (JButton) c;
                btn.setForeground(fg);
                
                if (btn.getText().equals("x")) {
                    btn.setContentAreaFilled(false);
                    btn.setOpaque(false);                
                } else if (btn.getParent() instanceof JToolBar) {
                    btn.setOpaque(false);
                    btn.setContentAreaFilled(false);
                    btn.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6)); 
                    
                    btn.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
                        @Override
                        public void paint(Graphics g, JComponent comp) {
                            AbstractButton b = (AbstractButton) comp;
                            ButtonModel model = b.getModel();
                            if (model.isPressed() || model.isArmed()) {
                                g.setColor(isDark ? new Color(95, 95, 95) : new Color(210, 210, 210));
                                g.fillRect(0, 0, b.getWidth(), b.getHeight());
                            } else if (model.isRollover()) {
                                g.setColor(isDark ? new Color(85, 85, 85) : new Color(225, 225, 225));
                                g.fillRect(0, 0, b.getWidth(), b.getHeight());
                            }
                            super.paint(g, comp);
                        }
                    });
                } else {
                    c.setBackground(buttonBg);
                    ((JComponent) c).setOpaque(true);
                    ((JButton) c).setUI(new javax.swing.plaf.basic.BasicButtonUI());
                    ((JButton) c).setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(borderColor, 1),
                            BorderFactory.createEmptyBorder(4, 10, 4, 10)
                    ));
                }
            } 
            // --- Spinners ---
            else if (c instanceof JSpinner) {
                c.setBackground(inputBg);
                c.setForeground(fg);
                ((JComponent) c).setOpaque(true);
                ((JSpinner) c).setBorder(BorderFactory.createLineBorder(borderColor, 1));

                Component editor = ((JSpinner) c).getEditor();
                if (editor instanceof JSpinner.DefaultEditor) {
                    JTextField spinnerField = ((JSpinner.DefaultEditor) editor).getTextField();
                    spinnerField.setBackground(inputBg);
                    spinnerField.setForeground(fg);
                    spinnerField.setCaretColor(fg);
                    spinnerField.setUI(new javax.swing.plaf.basic.BasicTextFieldUI()); 
                }
            }            
            // --- Combo Boxes (The Ubuntu Jujitsu) ---
            else if (c instanceof JComboBox) {
                JComboBox<?> combo = (JComboBox<?>) c;
                boolean wasOriginallyEditable = combo.isEditable();
                combo.setEditable(true); 

                combo.setBackground(inputBg);
                combo.setForeground(fg);
                ((JComponent) c).setOpaque(true); 
                
                combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
                    @Override
                    protected JButton createArrowButton() {
                        javax.swing.plaf.basic.BasicArrowButton arrow = new javax.swing.plaf.basic.BasicArrowButton(
                            javax.swing.SwingConstants.SOUTH, buttonBg, buttonBg, fg, buttonBg);
                        arrow.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, borderColor));
                        return arrow;
                    }
                });
                
                combo.setBorder(BorderFactory.createLineBorder(borderColor, 1));

                if (combo.getEditor() != null) {
                    Component editor = combo.getEditor().getEditorComponent();
                    if (editor != null && editor instanceof JTextField) {
                        JTextField tf = (JTextField) editor;
                        
                        tf.setUI(new javax.swing.plaf.basic.BasicTextFieldUI() {
                            @Override
                            protected void paintBackground(Graphics g) {
                                g.setColor(inputBg);
                                g.fillRect(0, 0, tf.getWidth(), tf.getHeight());
                            }
                        });
                        
                        tf.setBackground(inputBg);
                        tf.setForeground(fg);
                        tf.setCaretColor(fg);
                        tf.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
                        
                        if (!wasOriginallyEditable) {
                            tf.setEditable(false);
                            tf.setDisabledTextColor(fg); 
                            tf.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)); 
                            
                            if (tf.getClientProperty("UbuntuComboFix") == null) {
                                tf.putClientProperty("UbuntuComboFix", true);
                                tf.addMouseListener(new java.awt.event.MouseAdapter() {
                                    @Override
                                    public void mousePressed(java.awt.event.MouseEvent e) {
                                        if (combo.isEnabled()) {
                                            combo.setPopupVisible(!combo.isPopupVisible());
                                        }
                                    }
                                });
                            }
                        }
                    }
                }

                combo.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        JLabel renderer = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                        renderer.setOpaque(true);
                        if (isSelected) {
                            renderer.setBackground(buttonBg); 
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
            // --- Toggle Buttons ---
            else if (c instanceof JToggleButton) {
                c.setForeground(fg);
            } 
            // --- Catch ALL other containers ---
            else if (c instanceof Container) {
                c.setBackground(bg);
                c.setForeground(fg);
                
                if (c instanceof JComponent) {
                    ((JComponent) c).setOpaque(true);
                }
                
                sweepComponents((Container) c);
            }
        }
    }

    // ==========================================
    //  Custom Theme Icons
    // ==========================================

    /**
     * Custom Check Box Icon for JCheckBoxes
    */
    private static class ThemeCheckBoxIcon implements javax.swing.Icon {
        private final Color color;
        public ThemeCheckBoxIcon(Color color) { this.color = color; }
        
        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }
        
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            AbstractButton b = (AbstractButton) c;
            if (b.isSelected()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                
                g2.drawLine(x + 3, y + 8, x + 6, y + 12);
                g2.drawLine(x + 6, y + 12, x + 13, y + 4);
                g2.dispose();
            }
        }
    }

    /**
     * Custom Radio Button Icon for JRadioButtons
     */
    private static class ThemeRadioIcon implements javax.swing.Icon {
        private final Color color;
        public ThemeRadioIcon(Color color) { this.color = color; }
        
        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }
        
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            AbstractButton b = (AbstractButton) c;
            if (b.isSelected()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                
                g2.fillOval(x + 4, y + 4, 8, 8);
                g2.dispose();
            }
        }
    }

    /**
     * Custom Menu Arrow Icon for Menus
     */
    private static class ThemeMenuArrowIcon implements javax.swing.Icon {
        private final Color color;
        public ThemeMenuArrowIcon(Color color) { this.color = color; }
        
        @Override public int getIconWidth() { return 8; }
        @Override public int getIconHeight() { return 12; }
        
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            
            int[] xPts = {x, x + 4, x};
            int[] yPts = {y + 2, y + 6, y + 10};
            g2.fillPolygon(xPts, yPts, 3);
            
            g2.dispose();
        }
    }

    /**
     * Custom Themed Border for JPanel and other Swing Components
     */
    private static void themeBorder(Border border, Color fgColor) {
        if (border == null) return;
        
        if (border instanceof TitledBorder) {
            ((TitledBorder) border).setTitleColor(fgColor);
        } else if (border instanceof CompoundBorder) {
            CompoundBorder cb = (CompoundBorder) border;
            themeBorder(cb.getOutsideBorder(), fgColor);
            themeBorder(cb.getInsideBorder(), fgColor);
        }
    }    
}