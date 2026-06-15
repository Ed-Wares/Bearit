package com.edwares;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;

public class ThemedScrollBarUI extends BasicScrollBarUI {

    String currentTheme = "Light";

    public ThemedScrollBarUI(String themeName) {
        this.currentTheme = themeName;
    }
    /**
     * The UIManager strictly requires this static method to exist 
     * so it knows how to instantiate your custom UI globally.
     */
    public static ComponentUI createUI(JComponent c) {
        // Fallback to reading the global properties if created via UIManager
        String currentTheme = BearitProperties.getInstance().getTheme();
        return new ThemedScrollBarUI(currentTheme);
    }

    @Override
    protected void configureScrollBarColors() {
        // --- The critical color injection ---
        if ("Dark".equals(currentTheme)) {
            trackColor = new Color(43, 43, 43); // Matches the dark text editor background
            thumbColor = new Color(85, 85, 85); // Lighter gray so it pops against the dark track
        } else {
            trackColor = new Color(245, 245, 245); // Matches the light text editor background
            thumbColor = new Color(180, 180, 180); // Darker gray so it pops against the light track
        }
        
        // Swing tries to draw a chunky 3D bevel around the thumb by default. 
        // Setting these shadow colors to match the thumbColor flattens it out for a modern look!
        thumbDarkShadowColor = thumbColor;
        thumbHighlightColor = thumbColor;
        thumbLightShadowColor = thumbColor;
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return createCustomArrowButton(orientation);
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return createCustomArrowButton(orientation);
    }

    public void applyTheme(String theme) {
        this.currentTheme = theme;
    }

    /**
     * Builds a geometric arrow button using your custom theme colors.
     */
    private JButton createCustomArrowButton(int orientation) {
        // Grab the background color of your theme
        boolean isDark = "Dark".equals(currentTheme);
        Color bg = isDark ? new Color(43, 43, 43) : new Color(245, 245, 245);
        Color arrowColor = isDark ? new Color(169, 183, 198) : Color.DARK_GRAY;
        // BasicArrowButton(orientation, background, shadow, darkShadow (the arrow), highlight)
        BasicArrowButton button = new BasicArrowButton(orientation, bg, bg, arrowColor, bg);
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setFocusPainted(false);
        return button;
    }

    /**
     * Modify the minimum size of the scroll thumb to prevent it from becoming too small
     */
    @Override
    protected Dimension getMinimumThumbSize() {
        // Grab the default dimensions calculated by the OS/Swing
        Dimension dim = super.getMinimumThumbSize();
        
        // Enforce an 40px minimum based on the scrollbar's orientation
        final int min_size = 40;
        if (scrollbar.getOrientation() == JScrollBar.VERTICAL) {
            return new Dimension(dim.width, Math.max(dim.height, min_size));
        } else {
            return new Dimension(Math.max(dim.width, min_size), dim.height);
        }
    }    
}