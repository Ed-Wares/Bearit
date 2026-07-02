#!/bin/bash
# This script builds the Linux DEB package for the Bearit application using jpackage.

mvn clean package

jar_file=$(find target/ -maxdepth 1 -name "bearit*.jar" -printf "%T@ %p\n" | sort -n | tail -n 1 | cut -d' ' -f2-)
inputs_app_version=$(echo "$jar_file" | sed -E 's/.*-([0-9]+\.[0-9]+\.[0-9]+)\.jar/\1/')

echo building linux deb package for $jar_file ...

mkdir -p linux_resources

# Post-Install Script
cat << 'EOF' > linux_resources/postinst
#!/bin/sh
# Create the terminal alias
ln -sf /opt/bearit/bin/bearit /usr/local/bin/bearit

# Manually register the application with the GNOME App Drawer
# (jpackage usually names the internal file bearit-bearit.desktop)
if [ -f /opt/bearit/lib/bearit-bearit.desktop ]; then
    cp /opt/bearit/lib/bearit-bearit.desktop /usr/share/applications/bearit.desktop
    update-desktop-database /usr/share/applications/
fi
exit 0
EOF
chmod +x linux_resources/postinst

# Pre-Removal Script
cat << 'EOF' > linux_resources/prerm
#!/bin/sh
# Clean up the terminal alias
rm -f /usr/local/bin/bearit

# Clean up the GNOME App Drawer entry
rm -f /usr/share/applications/bearit.desktop
update-desktop-database /usr/share/applications/
exit 0
EOF
chmod +x linux_resources/prerm

echo running jpackage ...
jpackage --type deb \
--input target/ \
--main-jar bearit-$inputs_app_version.jar \
--main-class com.edwares.BearitApp \
--name bearit \
--app-version $inputs_app_version \
--dest distribution_payload/linux/ \
--icon src/main/resources/Bearit.png \
--vendor "EdWares" \
--resource-dir linux_resources/ \
--linux-menu-group "Utility;Development;"

echo "deb package built at distribution_payload/linux/bearit-$inputs_app_version.deb"
