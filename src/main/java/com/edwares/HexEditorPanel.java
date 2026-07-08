package com.edwares;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public class HexEditorPanel extends JPanel {
    private JTable hexTable;
    private HexTableModel tableModel;
    private byte[] dataBytes;
    private long baseAddressOffset;
    private boolean isDark = false;
    private int currentFontSize = 14; // Default starting size
    private Consumer<Font> fontChangeListener;

    
    // --- Global Scrolling UI ---
    private JScrollPane scrollPane;
    private JScrollBar globalVBar;
    private int totalChunks = 1;
    private int currentChunkIdx = 0;
    private boolean isUpdatingScroll = false;
    
    // Inspector & Status Panels (Stored for dynamic font scaling)
    private JPanel pnlInspectorWrapper;
    private JScrollPane inspectorScrollPane;
    private JPanel pnlInspector;
    private JPanel pnlStatusBar;

    // Inspector UI
    private JTextField lblAddress, lblChunkAddress, lblPosition, lbl8Bit, lbl16Bit, lbl32Bit, lbl64Bit;
    private JTextField lblFloat, lblBinary, lblUnix32, lblUnix64;
    private JTextField lblDosTime, lblWin32Time;
    private JTextField lblUtf8, lblUtf16, lblEbcdic;
    private JTextField txtGoto;
    private JComboBox<Integer> comboBytesPerRow;

    // --- Status Bar UI ---
    private JTextField lblStatus;
    private JTextField lblChunkFileStatus;
    private JTextField lblFontInfo;
    private String chunkStatus = "";
    private JProgressBar chunkLoadProgressBar; 
    private String fileSizeDateStatus = "";
    private Consumer<Boolean> onPrevChunk;
    private Consumer<Boolean> onNextChunk;  
    private Consumer<Integer> onJumpToChunk;
    private Consumer<Long> onJumpToGlobalAddress;    
    private Runnable onDataChanged;
    private Runnable onExitHexMode;
    public void setOnExitHexMode(Runnable listener) { this.onExitHexMode = listener; }

    public HexEditorPanel() {
        setLayout(new BorderLayout());

        // --- Table Setup ---
        tableModel = new HexTableModel(16);
        hexTable = new JTable(tableModel);
        hexTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, currentFontSize));
        // Load the font name and size straight from properties!
        hexTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        hexTable.setCellSelectionEnabled(true);
        hexTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        hexTable.getTableHeader().setReorderingAllowed(false);
        hexTable.setRowHeight(hexTable.getFontMetrics(hexTable.getFont()).getHeight() + 2);

        hexTable.setDefaultRenderer(String.class, new HexCellRenderer());

        
        // --- Custom Cell Editor for instant overwrite & auto-advance ---
        JTextField editField = new JTextField();
        editField.setHorizontalAlignment(JTextField.CENTER);
        editField.setBorder(BorderFactory.createEmptyBorder());
        
        // Prevent CTRL+H from acting as a backspace while actively typing a byte
        editField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "exitHexMode");
        editField.getActionMap().put("exitHexMode", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (hexTable.isEditing()) hexTable.getCellEditor().stopCellEditing();
                if (onExitHexMode != null) onExitHexMode.run();
            }
        });        

        DefaultCellEditor overwriteEditor = new DefaultCellEditor(editField) {
            private int currentRow;
            private int currentCol;
            private boolean isHexCol;
            private boolean isSettingUp = false; // Prevents premature auto-advance

            @Override
            public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
                isSettingUp = true;
                JTextField tf = (JTextField) super.getTableCellEditorComponent(table, value, isSelected, row, column);
                
                currentRow = row;
                currentCol = column;
                isHexCol = column <= tableModel.getBytesPerRow();
                
                tf.setText(""); // Instant wipe for overwrite
                isSettingUp = false; // Safe to listen to user typing now

                javax.swing.text.AbstractDocument doc = (javax.swing.text.AbstractDocument) tf.getDocument();
                doc.setDocumentFilter(new javax.swing.text.DocumentFilter() {
                    @Override
                    public void insertString(FilterBypass fb, int offset, String string, javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException {
                        replace(fb, offset, 0, string, attr);
                    }

                    @Override
                    public void replace(FilterBypass fb, int offset, int length, String text, javax.swing.text.AttributeSet attrs) throws javax.swing.text.BadLocationException {
                        int maxLength = isHexCol ? 2 : 1;
                        int currentLength = fb.getDocument().getLength();
                        int newLength = currentLength - length + (text != null ? text.length() : 0);

                        if (newLength <= maxLength) {
                            super.replace(fb, offset, length, text, attrs);
                        } else if (text != null) {
                            int allowed = maxLength - (currentLength - length);
                            if (allowed > 0) {
                                super.replace(fb, offset, length, text.substring(0, allowed), attrs);
                            }
                        }

                        // Only auto-advance if we aren't in the middle of setting up the cell
                        if (!isSettingUp && fb.getDocument().getLength() == maxLength) {
                            SwingUtilities.invokeLater(() -> {
                                if (table.isEditing()) {
                                    table.getCellEditor().stopCellEditing(); 
                                    advanceSelection(table);                
                                }
                            });
                        }
                    }
                });
                return tf;
            }

            private void advanceSelection(JTable table) {
                int bpr = tableModel.getBytesPerRow();
                int nextRow = currentRow;
                int nextCol = currentCol + 1;

                if (isHexCol) {
                    if (nextCol > bpr) { 
                        nextCol = 1;
                        nextRow++;
                    }
                } else {
                    if (nextCol > bpr * 2) { 
                        nextCol = bpr + 1;
                        nextRow++;
                    }
                }

                if (nextRow < table.getRowCount()) {
                    table.changeSelection(nextRow, nextCol, false, false);
                } else {
                    // --- Typing past the end loads the next chunk ---
                    if (onNextChunk != null && hexTable.isEnabled()) {
                        onNextChunk.accept(false); // Spawns cursor at the top of the new chunk
                    }
                }
            }
        };
        hexTable.setDefaultEditor(String.class, overwriteEditor);
        hexTable.setSurrendersFocusOnKeystroke(true);

        hexTable.getSelectionModel().addListSelectionListener(e -> updateInspector());
        hexTable.getColumnModel().getSelectionModel().addListSelectionListener(e -> updateInspector());

        // --- GLOBAL SCROLLBAR ARCHITECTURE ---
        scrollPane = new JScrollPane(hexTable);
        // Shrink the native scrollbar to 0x0. It remains active for mouse-wheels, but is invisible!
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(0, 0));

        globalVBar = new JScrollBar(JScrollBar.VERTICAL);
        globalVBar.setMinimum(0);

        JPanel centerContainer = new JPanel(new BorderLayout());
        centerContainer.add(scrollPane, BorderLayout.CENTER);
        centerContainer.add(globalVBar, BorderLayout.EAST);
        add(centerContainer, BorderLayout.CENTER);

        // Dragging the Global Scrollbar
        globalVBar.addAdjustmentListener(e -> {
            if (isUpdatingScroll || !hexTable.isEnabled()) return;

            int val = e.getValue();
            int targetChunk = val / 100000;
            targetChunk = Math.min(Math.max(targetChunk, 0), totalChunks - 1); 

            if (targetChunk != currentChunkIdx) {
                // ONLY load the new chunk when the user lets go of the mouse to prevent lag spam
                if (!e.getValueIsAdjusting() && onJumpToChunk != null) {
                    onJumpToChunk.accept(targetChunk);
                }
            } else {
                // Live scroll within the current chunk
                double localPercent = (val % 100000) / 100000.0;
                JViewport vp = scrollPane.getViewport();
                int maxScroll = hexTable.getPreferredSize().height - vp.getHeight();
                if (maxScroll > 0) {
                    vp.setViewPosition(new Point(0, (int)(localPercent * maxScroll)));
                }
            }
        });

        // Syncing the Global Scrollbar to the local viewport
        scrollPane.getViewport().addChangeListener(e -> {
            if (isUpdatingScroll || !hexTable.isEnabled()) return;
            JViewport vp = scrollPane.getViewport();
            int maxScroll = hexTable.getPreferredSize().height - vp.getHeight();
            double localPercent = maxScroll > 0 ? (double) vp.getViewPosition().y / maxScroll : 0;
            int newVal = (currentChunkIdx * 100000) + (int)(localPercent * 100000);

            isUpdatingScroll = true;
            globalVBar.setValue(newVal);
            isUpdatingScroll = false;
        });

        // Crossing chunk boundaries with the mouse wheel
        scrollPane.addMouseWheelListener(e -> {
            if (!hexTable.isEnabled()) return; 
            
            // --- Handle Font Zooming ---
            if (e.isControlDown()) {
                if (e.getWheelRotation() < 0) adjustFontSize(2);
                else adjustFontSize(-2);
                e.consume(); // Consume to stop native scrolling
                return;
            }

            JViewport vp = scrollPane.getViewport();
            int maxScroll = hexTable.getPreferredSize().height - vp.getHeight();
            int currentY = vp.getViewPosition().y;

            if (e.getWheelRotation() > 0 && currentY >= maxScroll - 2) {
                if (onNextChunk != null) onNextChunk.accept(false); 
            } else if (e.getWheelRotation() < 0 && currentY <= 2) {
                if (onPrevChunk != null) onPrevChunk.accept(true); 
            }
        });

        // --- Font Zoom Keyboard Shortcuts ---
        InputMap im = hexTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = hexTable.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), "zoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, InputEvent.CTRL_DOWN_MASK), "zoomInNumPad");
        am.put("zoomIn", new AbstractAction() { public void actionPerformed(ActionEvent e) { adjustFontSize(2); }});
        am.put("zoomInNumPad", new AbstractAction() { public void actionPerformed(ActionEvent e) { adjustFontSize(2); }});

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "zoomOut");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, InputEvent.CTRL_DOWN_MASK), "zoomOutNumPad");
        am.put("zoomOut", new AbstractAction() { public void actionPerformed(ActionEvent e) { adjustFontSize(-2); }});
        am.put("zoomOutNumPad", new AbstractAction() { public void actionPerformed(ActionEvent e) { adjustFontSize(-2); }});

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "exitHexMode"); // Disable default backspace behavior
        am.put("exitHexMode", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (hexTable.isEditing()) hexTable.getCellEditor().stopCellEditing();
                if (onExitHexMode != null) onExitHexMode.run();
            }
        });

        // --- Seamless Keyboard Arrow Navigation ---
        hexTable.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!hexTable.isEnabled()) return;
                
                int row = hexTable.getSelectedRow();
                int col = hexTable.getSelectedColumn();
                int maxRow = hexTable.getRowCount() - 1;
                
                if (e.getKeyCode() == KeyEvent.VK_DOWN && row == maxRow) {
                    if (onNextChunk != null) onNextChunk.accept(false);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_UP && row == 0) {
                    if (onPrevChunk != null) onPrevChunk.accept(true);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_RIGHT && row == maxRow && col == hexTable.getColumnCount() - 1) {
                    if (onNextChunk != null) onNextChunk.accept(false);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_LEFT && row == 0 && col == 1) {
                    if (onPrevChunk != null) onPrevChunk.accept(true);
                    e.consume();
                }
            }
        });

        add(createInspectorPanel(), BorderLayout.EAST);
        add(createStatusBar(), BorderLayout.SOUTH);
        updateColumnWidths();
    }

    // --- FONT METHODS ---
    public int getCurrentFontSize() {
        return currentFontSize;
    }

    @Override
    public void setFont(Font font) {
        super.setFont(font);
        // Guard against the super constructor calling this before the UI is built
        if (hexTable == null) return; 
        
        currentFontSize = font.getSize();
        hexTable.setFont(font);
        
        // Dynamically adjust row heights for the new font
        FontMetrics fm = hexTable.getFontMetrics(font);
        hexTable.setRowHeight(fm.getHeight() + 2);
        
        // Update the Font Status Label
        if (lblFontInfo != null) {
            lblFontInfo.setText(" | Font: " + font.getName() + " " + currentFontSize + "pt | ");
        }

        // Apply new font sizes directly to the inspector wrapper UI components
        if (pnlInspectorWrapper != null) DialogUtil.applyFontToContainer(pnlInspectorWrapper, font);
        if (pnlStatusBar != null) DialogUtil.applyFontToContainer(pnlStatusBar, DialogUtil.DEFAULT_STATUSLBL_FONT);

        revalidate();
        repaint();
        updateColumnWidths(); 
    }

    public void setOnFontChangeListener(Consumer<Font> listener) {
        this.fontChangeListener = listener;
    }

    public void adjustFontSize(int delta) {
        int newSize = Math.max(6, Math.min(80, currentFontSize + delta));
        if (newSize == currentFontSize) return;
        
        // Broadcast the event to whoever is listening
        if (fontChangeListener != null) {
            fontChangeListener.accept(new Font(hexTable.getFont().getFamily(), Font.PLAIN, newSize));
        }
    }

    private JPanel createStatusBar() {
        pnlStatusBar = new JPanel(new BorderLayout());
        pnlStatusBar.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        
        // Group the labels into a left-aligned container
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        
        lblChunkFileStatus = newLabelTextField(" Chunk 1 of 1 ");
        lblChunkFileStatus.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 15)); // Add spacing on the right
        
        lblStatus = newLabelTextField("Ready");

        chunkLoadProgressBar = new JProgressBar();
        chunkLoadProgressBar.setIndeterminate(true);
        chunkLoadProgressBar.setPreferredSize(new Dimension(100, 14));
        chunkLoadProgressBar.setVisible(false);
        
        // Add them to the flow layout in the desired order
        leftPanel.add(lblChunkFileStatus);
        leftPanel.add(lblStatus);
        leftPanel.add(chunkLoadProgressBar);
        
        pnlStatusBar.add(leftPanel, BorderLayout.WEST);

        // --- Add the Font Info Label to the Right Side ---
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        lblFontInfo = newLabelTextField(" | Font: " + this.getFont().getName() + " " + currentFontSize + "pt | ");
        rightPanel.add(lblFontInfo);
        
        pnlStatusBar.add(rightPanel, BorderLayout.EAST);
        
        return pnlStatusBar;
    }

    public void setStatus(String message) {
        lblStatus.setText(message);
    }

    public void showChunkLoadProgressBar(boolean visibleFlag) {
        chunkLoadProgressBar.setVisible(visibleFlag);
    }

    public void setOnPrevChunk(Consumer<Boolean> listener) { this.onPrevChunk = listener; }
    public void setOnNextChunk(Consumer<Boolean> listener) { this.onNextChunk = listener; }
    public void setOnJumpToChunk(Consumer<Integer> listener) { this.onJumpToChunk = listener; }

    public void updateChunkStatus(int currentIdx, int totalChunks) {
        updateChunkFileStatusLabel(String.format(" Chunk %d of %d ", currentIdx, totalChunks), fileSizeDateStatus);
        this.totalChunks = totalChunks;
        this.currentChunkIdx = currentIdx - 1;

        // Configure the global scrollbar mathematically
        isUpdatingScroll = true;
        globalVBar.setMaximum(totalChunks * 100000 + 10000);
        globalVBar.setVisibleAmount(10000);
        globalVBar.setBlockIncrement(100000);
        globalVBar.setUnitIncrement(2000);

        // Sync the thumb perfectly to where the local viewport is sitting
        JViewport vp = scrollPane.getViewport();
        int maxScroll = hexTable.getPreferredSize().height - vp.getHeight();
        double localPercent = maxScroll > 0 ? (double) vp.getViewPosition().y / maxScroll : 0;
        globalVBar.setValue((currentChunkIdx * 100000) + (int)(localPercent * 100000));
        isUpdatingScroll = false;
    }

    public void setChunkStatus(String chunkStatus) {
        this.chunkStatus = chunkStatus;
    }

    public void setFileSizeDateStatus(String fileSizeDateStatus) {
        this.fileSizeDateStatus = fileSizeDateStatus;
    }

    public void updateChunkFileStatusLabel(String chunkStatus, String fileStatus) {
        if (!fileStatus.isEmpty())
            lblChunkFileStatus.setText(chunkStatus + "  |  " + fileStatus); // chunk location and file size and modified date
        else {
            lblChunkFileStatus.setText(chunkStatus); // chunk location only    
        }
        // remember last status updates
        this.chunkStatus = chunkStatus;
        this.fileSizeDateStatus = fileStatus;
    }    

    public void setUIEnabled(boolean enabled) {
        hexTable.setEnabled(enabled);
        txtGoto.setEnabled(enabled);
        comboBytesPerRow.setEnabled(enabled);
        // We let updateChunkStatus handle the button states
    }

    /**
     * Feed data into the standalone component.
     */
    public void loadData(byte[] bytes, long absoluteOffset) {
        this.dataBytes = bytes;
        this.baseAddressOffset = absoluteOffset;
        tableModel.fireTableDataChanged();
        updateColumnWidths();
    }

    public byte[] getModifiedData() {
        return dataBytes;
    }

    public void setOnDataChangedListener(Runnable listener) {
        this.onDataChanged = listener;
    }

    private JPanel createInspectorPanel() {
        // --- Create a wrapper to hold the Titled Border so it doesn't scroll away ---
        pnlInspectorWrapper = new JPanel(new BorderLayout());
        pnlInspectorWrapper.setBorder(BorderFactory.createTitledBorder("Data Inspector"));

        // --- Create the inner content panel with GridBagLayout ---
        pnlInspector = new JPanel(new GridBagLayout());
        
        // ---> Add a 16px transparent right border to act as a buffer for the scrollbar! <---
        pnlInspector.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 16));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST; gbc.insets = new Insets(4, 5, 4, 5);

        // View Config
        pnlInspector.add(new JLabel("Bytes Per Row:"), gbc);
        gbc.gridx = 1;
        comboBytesPerRow = new JComboBox<>(new Integer[]{16, 32, 48});
        comboBytesPerRow.setPreferredSize(new Dimension(75, 26)); 
        comboBytesPerRow.addActionListener(e -> {
            tableModel.setBytesPerRow((Integer) comboBytesPerRow.getSelectedItem());
            tableModel.fireTableStructureChanged();
            updateColumnWidths();
        });
        pnlInspector.add(comboBytesPerRow, gbc);

        // Goto
        gbc.gridx = 0; gbc.gridy++;
        pnlInspector.add(new JLabel("Go to (Hex):"), gbc);
        gbc.gridx = 1;
        // --- Force Linux to stretch the text box horizontally ---
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        JPanel gotoPanel = new JPanel(new BorderLayout(2, 0));
        gotoPanel.setOpaque(false);
        txtGoto = new JTextField(8);
        JButton btnGo = new JButton("Go");
        btnGo.setMargin(new Insets(1, 4, 1, 4));
        btnGo.setFocusable(false); // Prevents it from stealing keyboard focus unnecessarily
        // Wire both the Enter key in the text box AND the button to the same action
        java.awt.event.ActionListener goAction = e -> jumpToHexAddress(txtGoto.getText().trim());
        txtGoto.addActionListener(goAction);
        btnGo.addActionListener(goAction);
        gotoPanel.add(txtGoto, BorderLayout.CENTER);
        gotoPanel.add(btnGo, BorderLayout.EAST);
        pnlInspector.add(new JLabel("  "), gbc);
        pnlInspector.add(gotoPanel, gbc);
        // --- Reset the constraints so the rest of the UI doesn't warp ---
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;

        gbc.gridx = 0; gbc.gridy++; gbc.gridwidth = 2;
        pnlInspector.add(new JSeparator(), gbc);
        gbc.gridwidth = 1;

        // Translations
        lblAddress = addInspectorRow("Global Address:", pnlInspector, ++gbc.gridy);
        lblChunkAddress = addInspectorRow("Chunk Address:", pnlInspector, ++gbc.gridy);
        lblPosition = addInspectorRow("Position:", pnlInspector, ++gbc.gridy);
        lbl8Bit = addInspectorRow("Int8:", pnlInspector, ++gbc.gridy);
        lbl16Bit = addInspectorRow("Int16 (LE):", pnlInspector, ++gbc.gridy);
        lbl32Bit = addInspectorRow("Int32 (LE):", pnlInspector, ++gbc.gridy);
        lbl64Bit = addInspectorRow("Int64 (LE):", pnlInspector, ++gbc.gridy);
        lblFloat = addInspectorRow("Float:", pnlInspector, ++gbc.gridy);
        lblBinary = addInspectorRow("Binary:", pnlInspector, ++gbc.gridy);
        lblUnix32 = addInspectorRow("Unix 32:", pnlInspector, ++gbc.gridy);
        lblUnix64 = addInspectorRow("Unix 64:", pnlInspector, ++gbc.gridy);
        lblWin32Time = addInspectorRow("Win32 Time:", pnlInspector, ++gbc.gridy);
        lblDosTime = addInspectorRow("MS-DOS Time:", pnlInspector, ++gbc.gridy);
        lblUtf8 = addInspectorRow("UTF-8:", pnlInspector, ++gbc.gridy);
        lblUtf16 = addInspectorRow("UTF-16:", pnlInspector, ++gbc.gridy);
        lblEbcdic = addInspectorRow("EBCDIC:", pnlInspector, ++gbc.gridy);
        
        gbc.gridy++; gbc.weighty = 1.0;
        pnlInspector.add(Box.createVerticalGlue(), gbc);

        // --- Wrap the inner panel in a JScrollPane ---
        inspectorScrollPane = new JScrollPane(pnlInspector);
        inspectorScrollPane.setBorder(null); // Eliminate borders inside the wrapper
        inspectorScrollPane.getVerticalScrollBar().setUnitIncrement(16); // Smooth scrolling
        inspectorScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inspectorScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        
        // Prevent gray background boxes
        inspectorScrollPane.setOpaque(false);
        inspectorScrollPane.getViewport().setOpaque(false);
        
        pnlInspectorWrapper.add(inspectorScrollPane, BorderLayout.CENTER);

        return pnlInspectorWrapper;
    }

    private JTextField addInspectorRow(String title, JPanel panel, int row) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = row; gbc.anchor = GridBagConstraints.WEST; gbc.insets = new Insets(2, 5, 2, 5);
        panel.add(new JLabel(title), gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        JTextField field = new JTextField("-");
        field.setEditable(false); 
        field.setBorder(null); 
        field.setOpaque(false);
        panel.add(field, gbc);
        
        return field;
    }

    private void updateColumnWidths() {
        if (hexTable.getColumnCount() == 0) return;
        
        // --- DYNAMIC WIDTH MATH ---
        // Measures the physical pixel dimensions of the current font to perfectly bound the cells
        FontMetrics fm = hexTable.getFontMetrics(hexTable.getFont());
        int charW = fm.charWidth('W'); // Uses a wide character as a safe bounding box
        
        hexTable.getColumnModel().getColumn(0).setPreferredWidth((charW * 8) + 20); 
        int bpr = tableModel.getBytesPerRow();
        for (int i = 1; i <= bpr; i++) hexTable.getColumnModel().getColumn(i).setPreferredWidth((charW * 2) + 12); 
        for (int i = bpr + 1; i <= bpr * 2; i++) hexTable.getColumnModel().getColumn(i).setPreferredWidth(charW + 8); 
    }

    public void jumpToHexAddress(String hexAddress) {
        try {
            if (hexAddress.startsWith("0x") || hexAddress.startsWith("0X")) hexAddress = hexAddress.substring(2);
            long targetGlobalOffset = Long.parseLong(hexAddress, 16);
            long localOffset = targetGlobalOffset - baseAddressOffset;
            
            if (localOffset >= 0 && localOffset < dataBytes.length) {
                // Address is currently in memory! Jump instantly.
                int row = (int) (localOffset / tableModel.getBytesPerRow());
                int col = (int) (localOffset % tableModel.getBytesPerRow()) + 1;
                hexTable.changeSelection(row, col, false, false);
            } else {
                // Address is in a different chunk. Ask the wrapper to fetch it!
                if (onJumpToGlobalAddress != null) {
                    onJumpToGlobalAddress.accept(targetGlobalOffset);
                }
            }
        } catch (NumberFormatException ex) {
            //JOptionPane.showMessageDialog(this, "Invalid Hex Address");
            DialogUtil.showMessageDialog(this, "Invalid Hex Address", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateInspector() {
        
        int localAddress = getSelectedByteOffset();
        if (localAddress == -1) {
            // Clear the inspector if nothing is selected
            lblAddress.setText("-");
            lblChunkAddress.setText("-");
            lblPosition.setText("-");
            lbl8Bit.setText("-");
            lbl16Bit.setText("-");
            lbl32Bit.setText("-");
            lbl64Bit.setText("-");
            lblFloat.setText("-");
            lblBinary.setText("-");
            lblUnix32.setText("-");
            lblUnix64.setText("-");
            lblWin32Time.setText("-");
            lblDosTime.setText("-");
            lblUtf8.setText("-");
            lblUtf16.setText("-");
            lblEbcdic.setText("-");
            return;
        }
        long globalAddress = localAddress == -1 ? baseAddressOffset : baseAddressOffset + localAddress;
        hexTable.repaint(); // Force repaint for cross-highlighting

        lblAddress.setText(String.format("0x%08X", globalAddress));
        lblChunkAddress.setText(String.format("0x%08X", localAddress));
        lblPosition.setText(String.format("%d", globalAddress));

        byte[] buf = new byte[8];
        int len = Math.min(8, dataBytes.length - localAddress);
        System.arraycopy(dataBytes, localAddress, buf, 0, len);
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

        lbl8Bit.setText(String.valueOf(buf[0]));
        lblBinary.setText(String.format("%8s", Integer.toBinaryString(buf[0] & 0xFF)).replace(' ', '0'));
        lbl16Bit.setText(len >= 2 ? String.valueOf(bb.getShort(0)) : "-");

        // UTF-8 is variable-length (1 to 4 bytes). We grab up to 4 bytes from the current cursor position and let Java's standard charset decoder figure out the bounds of the first character.
        int utf8Len = Math.min(4, dataBytes.length - localAddress);
        if (utf8Len > 0) {
            String utf8Raw = new String(dataBytes, localAddress, utf8Len, StandardCharsets.UTF_8);
            lblUtf8.setText(getSafeDisplayChar(utf8Raw));
        } else {
            lblUtf8.setText("N/A"); // End of file edge case
        }

        // --- UTF-16 Character ---
        if (utf8Len >= 2) {
            String utf16Raw = new String(dataBytes, localAddress, 2, StandardCharsets.UTF_16BE);
            lblUtf16.setText(getSafeDisplayChar(utf16Raw));
        } else {
            lblUtf16.setText("N/A"); // End of file edge case
        }

        // --- EBCDIC Character ---
        if (utf8Len >= 1) {
            String ebcdicRaw = new String(dataBytes, localAddress, 1, Charset.forName("Cp037"));
            lblEbcdic.setText(getSafeDisplayChar(ebcdicRaw));
        } else {
            lblEbcdic.setText("N/A"); // End of file edge case
        }      

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

        if (len >= 4) {
            int i32 = bb.getInt(0);
            lbl32Bit.setText(String.valueOf(i32));
            lblFloat.setText(String.valueOf(bb.getFloat(0)));
            
            try {
                lblUnix32.setText(dtf.format(Instant.ofEpochSecond(i32 & 0xFFFFFFFFL)));
            } catch (Exception e) {
                lblUnix32.setText("Out of Range");
            }
            
            // MS-DOS Datetime (32-bit: High Word Date, Low Word Time)
            int date = (i32 >> 16) & 0xFFFF;
            int time = i32 & 0xFFFF;
            int year = ((date >> 9) & 0x7F) + 1980;
            int month = (date >> 5) & 0x0F;
            int day = date & 0x1F;
            int hour = (time >> 11) & 0x1F;
            int minute = (time >> 5) & 0x3F;
            int second = (time & 0x1F) * 2;
            try {
                // Ensure basic logical bounds for DOS time to prevent weird string outputs
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31 && hour < 24 && minute < 60 && second < 60) {
                    lblDosTime.setText(String.format("%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second));
                } else {
                    lblDosTime.setText("Out of Range");
                }
            } catch (Exception e) { 
                lblDosTime.setText("Out of Range"); 
            }
        } else {
            lbl32Bit.setText("-"); lblFloat.setText("-"); lblUnix32.setText("-"); lblDosTime.setText("-");
        }

        if (len >= 8) {
            long l64 = bb.getLong(0);
            lbl64Bit.setText(String.valueOf(l64));
            
            try {
                lblUnix64.setText(dtf.format(Instant.ofEpochSecond(l64)));
            } catch (Exception e) {
                lblUnix64.setText("Out of Range");
            }
            
            // Win32 FileTime (100-ns intervals since Jan 1, 1601)
            try {
                long fileTimeEpochMilli = (l64 - 116444736000000000L) / 10000;
                lblWin32Time.setText(dtf.format(Instant.ofEpochMilli(fileTimeEpochMilli)));
            } catch (Exception e) {
                lblWin32Time.setText("Out of Range");
            }
            
        } else {
            lbl64Bit.setText("-"); lblUnix64.setText("-"); lblWin32Time.setText("-");
        }
    }

    private class HexTableModel extends AbstractTableModel {
        private int bytesPerRow;
        public HexTableModel(int bpr) { this.bytesPerRow = bpr; }
        public int getBytesPerRow() { return bytesPerRow; }
        public void setBytesPerRow(int bpr) { this.bytesPerRow = bpr; }

        @Override
        public int getRowCount() {
            return dataBytes == null ? 0 : (int) Math.ceil((double) dataBytes.length / bytesPerRow);
        }

        @Override
        public int getColumnCount() { return 1 + (bytesPerRow * 2); }

        @Override
        public String getColumnName(int col) {
            if (col == 0) return "Offset";
            if (col <= bytesPerRow) return String.format("%02X", col - 1);
            return ""; // Return blank instead of "Char" to prevent the "..." truncation
        }

        @Override
        public Class<?> getColumnClass(int colIndex) { return String.class; }
        @Override
        public boolean isCellEditable(int row, int col) { return col > 0; }

        @Override
        public Object getValueAt(int row, int col) {
            if (dataBytes == null) return "";
            if (col == 0) return String.format("%08X", baseAddressOffset + (row * bytesPerRow));
            
            int index = row * bytesPerRow + (col <= bytesPerRow ? (col - 1) : (col - bytesPerRow - 1));
            if (index >= dataBytes.length) return "";

            byte b = dataBytes[index];
            if (col <= bytesPerRow) return String.format("%02X", b);
            return (b >= 32 && b <= 126) ? String.valueOf((char) b) : ".";
        }

        @Override
        public void setValueAt(Object aValue, int row, int col) {
            int index = row * bytesPerRow + (col <= bytesPerRow ? (col - 1) : (col - bytesPerRow - 1));
            if (index >= dataBytes.length) return;

            try {
                // DO NOT trim here, otherwise space characters get deleted
                String input = aValue.toString(); 
                
                if (col <= bytesPerRow) {
                    // Only trim when parsing Hex
                    dataBytes[index] = (byte) Integer.parseInt(input.trim(), 16);
                } else if (!input.isEmpty()) {
                    dataBytes[index] = (byte) input.charAt(0);
                }
                
                if (onDataChanged != null) onDataChanged.run();
                fireTableRowsUpdated(row, row); 
            } catch (Exception e) {} 
        }
    }

    private class HexCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object val, boolean isSel, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, val, isSel, hasFocus, row, col);
            int bpr = tableModel.getBytesPerRow();
            
            if (!isSel) {
                int sCol = table.getSelectedColumn();
                int sRow = table.getSelectedRow();
                // Cross-highlighting logic
                if (sRow == row && ((col > 0 && col <= bpr && sCol == col + bpr) || (col > bpr && sCol == col - bpr))) {
                    c.setBackground(new Color(220, 235, 255)); 
                } else {
                    c.setBackground(table.getBackground());
                }
            }
            
            // --- Custom Borders for Dividers ---
            Border focusBorder = hasFocus ? UIManager.getBorder("Table.focusCellHighlightBorder") : BorderFactory.createEmptyBorder(0, 2, 0, 2);
            
            if (col == bpr) {
                // Draw a solid vertical gray line on the right side of the final hex column
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 2, Color.GRAY), focusBorder));
            } else if (col == 0) {
                // Draw a lighter line separating the Address column from the Hex data
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.LIGHT_GRAY), focusBorder));
            } else {
                setBorder(focusBorder);
            }

            // --- Colors and Alignment (Theme Aware) ---
            if (!isSel) {
                int sCol = table.getSelectedColumn();
                int sRow = table.getSelectedRow();
                // Cross-highlighting logic
                if (sRow == row && ((col > 0 && col <= bpr && sCol == col + bpr) || (col > bpr && sCol == col - bpr))) {
                    c.setBackground(isDark ? new Color(70, 90, 120) : new Color(220, 235, 255)); 
                } else {
                    c.setBackground(table.getBackground());
                }
            } else {
                // Ensure the selected cell text remains readable
                c.setBackground(isDark ? new Color(40, 60, 90) : table.getSelectionBackground());
                c.setForeground(isDark ? Color.WHITE : table.getSelectionForeground());
            }

            if (col == 0) {
                c.setBackground(isDark ? new Color(50, 50, 50) : new Color(240, 240, 240)); 
                c.setForeground(isDark ? new Color(150, 150, 150) : Color.GRAY);
                setHorizontalAlignment(RIGHT);
            } else {
                if (!isSel) c.setForeground(isDark ? new Color(200, 200, 200) : Color.BLACK);
                setHorizontalAlignment(CENTER);
            }
            
            return c;
        }
    }

    public int getSelectedByteOffset() {
        int row = hexTable.getSelectedRow();
        int col = hexTable.getSelectedColumn();
        if (row < 0 || col < 1 || dataBytes == null) return -1;

        int bpr = tableModel.getBytesPerRow();
        int byteOffset = col <= bpr ? (col - 1) : (col - bpr - 1);
        int index = row * bpr + byteOffset;
        
        return index < dataBytes.length ? index : -1;
    }

    public void setSelectedByteOffset(int offset) {
        if (dataBytes == null || offset < 0 || offset >= dataBytes.length) return;
        
        int bpr = tableModel.getBytesPerRow();
        int row = offset / bpr;
        
        // Calculate the column for the ASCII side: 
        // 1 (Address) + bpr (Hex cols) + the remainder offset
        int col = bpr + 1 + (offset % bpr); 
        
        hexTable.changeSelection(row, col, false, false);
    }

    public long getGlobalSelectedByteOffset() {
        int local = getSelectedByteOffset();
        return local == -1 ? baseAddressOffset : baseAddressOffset + local;
    }

    public long getBaseAddressOffset() {
        return baseAddressOffset;
    }

    public void setOnJumpToGlobalAddress(Consumer<Long> listener) { 
        this.onJumpToGlobalAddress = listener; 
    }

    public void applyTheme(String theme) {
        this.isDark = "Dark".equals(theme);
        Color headerBg = isDark ? new Color(35, 35, 35) : new Color(225, 225, 225); 

        Color bg = isDark ? new Color(50, 50, 50) : Color.WHITE;
        Color fg = isDark ? new Color(200, 200, 200) : Color.BLACK;

        hexTable.setBackground(bg);
        hexTable.setForeground(fg);
        scrollPane.getViewport().setBackground(bg);
        
        // --- Inject a custom renderer to bypass the native OS header painter ---
        javax.swing.table.JTableHeader header = hexTable.getTableHeader();
        header.setOpaque(true);
        header.setBackground(headerBg);
        header.setForeground(fg);
        
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                label.setBackground(headerBg);
                label.setForeground(fg);
                label.setHorizontalAlignment(SwingConstants.CENTER);
                
                // Add a subtle border to separate the columns cleanly
                Color borderColor = isDark ? new Color(60, 60, 60) : new Color(200, 200, 200);
                label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, borderColor));
                return label;
            }
        });

        //applyThemeToContainer(this, panelBg, fg);
        DialogUtil.sweepComponents(this);
        if (globalVBar != null) {
            globalVBar.setUI(new ThemedScrollBarUI(theme));
        }        
        if (inspectorScrollPane != null) {
            inspectorScrollPane.getVerticalScrollBar().setUI(new ThemedScrollBarUI(theme));
            inspectorScrollPane.getHorizontalScrollBar().setUI(new ThemedScrollBarUI(theme));
        }
        hexTable.repaint();
    }

    /**
     * Extracts the first character from a decoded string and ensures it is 
     * visually safe to display in a standard UI label.
     */
    private String getSafeDisplayChar(String decodedString) {
        if (decodedString == null || decodedString.isEmpty()) return ".";
        
        int codePoint = decodedString.codePointAt(0);
        
        // If it is a control character, format character, or unassigned, return a dot
        if (Character.isISOControl(codePoint) || 
            Character.getType(codePoint) == Character.FORMAT ||
            Character.getType(codePoint) == Character.UNASSIGNED) {
            return ".";
        }
        
        return new String(Character.toChars(codePoint));
    }

    // Creates a JTextField that you can highlight and copy text to the clipboard
    private JTextField newLabelTextField(String text) {
        JTextField newLbl = new JTextField(text) {
            @Override
            public void setText(String t) {
                super.setText(t);
                // Force layout recalculation when text changes
                revalidate(); 
                repaint();
                if (getParent() != null) {
                    getParent().revalidate();
                    getParent().repaint();
                }                
            }

            @Override
            public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                String currentText = getText();
                
                if (currentText != null) {
                    // Manually calculate the exact pixel width of the current string
                    java.awt.FontMetrics fm = getFontMetrics(getFont());
                    java.awt.Insets insets = getInsets();
                    
                    // Add the text width + left/right padding + a 4-pixel buffer
                    size.width = fm.stringWidth(currentText) + insets.left + insets.right + 4;
                }
                return size;
            }
        };

        //FLAG to prevent theme from adding borders
        newLbl.putClientProperty("isFlatLabel", true);
        // Make it read-only so the user can highlight and copy, but not type
        newLbl.setEditable(false);
        
        // Strip away the text box styling so it looks exactly like a flat label
        newLbl.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 5, 2, 5)); // Adds slight padding
        newLbl.setOpaque(false); // Makes the background transparent
        
        // Force it to use the exact same font and color as a standard JLabel
        newLbl.setFont(javax.swing.UIManager.getFont("Label.font"));
        newLbl.setForeground(javax.swing.UIManager.getColor("Label.foreground"));
        return newLbl;
    }
}