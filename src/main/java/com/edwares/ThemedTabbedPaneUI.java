package com.edwares;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;

public class ThemedTabbedPaneUI extends BasicTabbedPaneUI {

    String currentTheme = "Light";
    
    // Theme Palette
    private final Color activeBg;
    private final Color inactiveBg;
    private final Color activeFg;
    private final Color inactiveFg;
    private final Color highlightColor;

    private ChangeListener tabSelectionListener;
    private final int inactiveFontSize = 14;
    private Font baseFont;

    public ThemedTabbedPaneUI(String theme) {
        this.currentTheme = theme;
        if ("Dark".equals(currentTheme)) {
            activeBg = new Color(43, 43, 43);      // Flush with dark editor background
            inactiveBg = new Color(60, 63, 65);    // Darker, muted background for inactive tabs
            activeFg = new Color(187, 187, 187);   // Bright, crisp text for active tab
            inactiveFg = new Color(150, 150, 150); // Dimmed text for inactive tabs
            highlightColor = new Color(74, 136, 199); // IntelliJ-style blue highlight line
        } else {
            activeBg = new Color(245, 245, 245);   // Flush with light editor background
            inactiveBg = new Color(225, 225, 225); // Grayed out background for inactive tabs
            activeFg = new Color(0, 0, 0);         // Bold black text for active tab
            inactiveFg = new Color(90, 90, 90); // Dimmed gray text for inactive tabs
            highlightColor = new Color(74, 136, 199); // Blue highlight line
        }
        baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, inactiveFontSize);
    }

    public static ComponentUI createUI(JComponent c) {
        return new ThemedTabbedPaneUI(BearitProperties.getInstance().getTheme());
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        // Give the tabs modern padding so they aren't cramped
        tabInsets = new Insets(8, 16, 8, 16);
        selectedTabPadInsets = new Insets(0, 0, 0, 0);
        tabAreaInsets = new Insets(0, 0, 0, 0);
    }

    // --- Listen for tab changes natively ---
    @Override
    protected void installListeners() {
        super.installListeners();
        
        tabSelectionListener = e -> syncCustomTabHeaderStyles();
        tabPane.addChangeListener(tabSelectionListener);
        
        // Initial execution to format tabs cleanly on boot
        SwingUtilities.invokeLater(this::syncCustomTabHeaderStyles);
    }    

    @Override
    protected void uninstallListeners() {
        if (tabSelectionListener != null) {
            tabPane.removeChangeListener(tabSelectionListener);
        }
        super.uninstallListeners();
    }

    /**
     * Iterates through custom tab component headers to update font sizing, 
     * visibility, text foreground matching, and guarantees transparent backgrounds.
     */
    private void syncCustomTabHeaderStyles() {
        if (tabPane == null) return;
        
        int selectedIndex = tabPane.getSelectedIndex();
        Font selectedFont = baseFont.deriveFont(Font.PLAIN, baseFont.getSize() + 2f);
        Font unselectedFont = baseFont.deriveFont(Font.PLAIN, (float) baseFont.getSize());
        Font CloseNewFont = baseFont.deriveFont(Font.PLAIN, baseFont.getSize() + 4f);

        for (int i = 0; i < tabPane.getTabCount(); i++) {
            boolean isSelected = (i == selectedIndex);
            Component tabHeader = tabPane.getTabComponentAt(i);
            
            if (tabHeader instanceof Container) {
                Container container = (Container) tabHeader;
                
                // Guarantee the parent layout wrapper panel is transparent
                if (container instanceof JComponent) {
                    ((JComponent) container).setOpaque(false);
                }
                
                // Process interior components (Labels and close buttons)
                for (Component c : container.getComponents()) {
                    if (c instanceof JLabel) {
                        JLabel label = (JLabel) c;
                        label.setOpaque(false); // Enforce transparent label boundaries so background colors don't clash
                        // Skip the small "x" asset safely
                        // Dynamically scale fonts and drop foreground text visibility back if unselected
                        label.setFont(isSelected ? selectedFont : unselectedFont);
                        label.setForeground(isSelected ? activeFg : inactiveFg);
                        label.setBackground(isSelected ? activeBg : inactiveBg);
                    } else if (c instanceof JButton) { // close
                        JButton btn = (JButton) c;
                        btn.setOpaque(false); // Enforce transparent label boundaries so background colors don't clash
                        if (btn.getText() != null && btn.getText().trim().equalsIgnoreCase("x")) {
                            btn.setFont(CloseNewFont);
                            btn.setForeground(isSelected ? activeFg : inactiveFg);
                            btn.setBackground(isSelected ? activeBg : inactiveBg);
                        }
                    }
                }
            // --- Standard Tabs (Like our "+" Dummy Tab) ---
            } else {
                String title = tabPane.getTitleAt(i);
                if ("+".equals(title)) {
                    // Force the + button to always use the bold button font and active color
                    tabPane.setForegroundAt(i, activeFg);
                    tabPane.setFont(CloseNewFont);
                } else {
                    tabPane.setForegroundAt(i, isSelected ? activeFg : inactiveFg);
                }
            }
        }
    }    

    @Override
    protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
        // Drastically differentiate the active vs inactive background colors
        g.setColor(isSelected ? activeBg : inactiveBg);
        g.fillRect(x, y, w, h);
    }

    @Override
    protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
        if (isSelected) {
            // Draw a sleek 3px highlight line at the very top of the active tab
            g.setColor(highlightColor);
            g.fillRect(x, y, w, 3);
        } else {
            // Draw a subtle vertical separator line on the right edge of inactive tabs
            g.setColor("Dark".equals(currentTheme) ? new Color(50, 50, 50) : new Color(200, 200, 200));
            g.drawLine(x + w - 1, y + 4, x + w - 1, y + h - 4);
        }
    }

    @Override
    protected void paintText(Graphics g, int tabPlacement, Font font, FontMetrics metrics, int tabIndex, String title, Rectangle textRect, boolean isSelected) {
        // If this is the "+" tab, hijack the font rendering to match our buttonFont sizing
        if ("+".equals(title)) {
            Font buttonFont = font.deriveFont(Font.PLAIN, font.getSize() + 4f);
            g.setFont(buttonFont);
            metrics = g.getFontMetrics(buttonFont);
            g.setColor(activeFg);
        } else {
            // Fallback for non-custom fallback tab instances
            g.setFont(isSelected ? font.deriveFont(font.getSize() + 2f) : font);
            g.setColor(isSelected ? activeFg : inactiveFg);
        }
        int textY = textRect.y + metrics.getAscent() + ((textRect.height - metrics.getHeight()) / 2);
        g.drawString(title, textRect.x, textY);

    }

    @Override
    protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex, Rectangle iconRect, Rectangle textRect, boolean isSelected) {
        // Leave completely empty! This destroys the ugly dotted outline when a tab is clicked.
    }

    @Override
    protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
        // Leave completely empty! This prevents Swing from drawing a 3D box around the text editor.
        // Because there is no border, the active tab will seamlessly bleed right into the editor background.
    }
}