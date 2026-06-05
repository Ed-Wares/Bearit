#!/bin/bash

# This script downloads the latest Material Symbols (Outlined) from Google's official Symbol CDN

# Prerequisites
# Before running the script, you just need to ensure the SVG-to-PNG converter (rsvg-convert) is installed on your system. Run this in your terminal:
# sudo apt-get install librsvg2-bin

# Bearit resource directory
RESOURCE_DIR="src/main/resources/icons"
mkdir -p "$RESOURCE_DIR"

# Ensure rsvg-convert is installed for SVG -> PNG conversion
if ! command -v rsvg-convert &> /dev/null; then
    echo "Error: rsvg-convert is not installed."
    echo "Please install it by running: sudo apt-get install librsvg2-bin"
    exit 1
fi

# The complete list of new Material Symbols
ICONS=(
    "insert_drive_file" # New File
    "folder_open"       # Open File
    "save"              # Save
    "save_as"           # Save As (Now natively supported!)
    "undo"              # Undo
    "redo"              # Redo
    "content_cut"       # Cut
    "content_copy"      # Copy
    "content_paste"     # Paste
    "search"            # Find/Replace
    "location_on"       # Go To Line
    "wrap_text"         # Word Wrap Toggle
    "build"             # Default Custom Tool Icon
)

echo "Downloading new Material Symbols (Outlined) for Bearit..."

for ICON in "${ICONS[@]}"; do
    # Download the 24px SVG from Google's official Symbol CDN
    FILE_URL="https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/${ICON}/default/24px.svg" 
    SVG_FILE="${RESOURCE_DIR}/${ICON}.svg"
    PNG_FILE="${RESOURCE_DIR}/${ICON}.png"
    
    echo "Fetching: $ICON"
    
    # Download the SVG silently
    if curl -s -f -L "$FILE_URL" -o "$SVG_FILE"; then
        # Convert SVG to a 24x24 PNG (change -w and -h if you want larger icons)
        rsvg-convert -w 24 -h 24 "$SVG_FILE" -o "$PNG_FILE"
        
        # Remove the raw SVG so we don't bloat the compiled Java JAR
        rm "$SVG_FILE"
        
        echo "  -> [SUCCESS] Downloaded and converted to $PNG_FILE"
    else
        echo "  -> [ERROR] Failed to download $ICON (URL might be invalid)"
    fi
done

echo "Icon download and conversion complete! Check the $RESOURCE_DIR directory."