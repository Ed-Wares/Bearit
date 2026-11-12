#!/bin/bash
# This script builds the macOS package for the Bearit application using jpackage.
set -eo pipefail

mvn clean package

jar_file=$(find target/ -maxdepth 1 -name "bearit*.jar" -exec stat -f "%N" {} + )
inputs_app_version=$(echo "$jar_file" | sed -E 's/.*-([0-9]+\.[0-9]+\.[0-9]+)\.jar/\1/')

echo building mac package for $jar_file ...
echo app version is $inputs_app_version

mkdir -p distribution_payload/macos/
rm -f "distribution_payload/macos/bearit-$inputs_app_version.dmg"

mkdir -p macos_resources
cat << 'EOF' > macos_resources/Info.plist
<?xml version="1.0" ?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
 <dict>
  <key>LSMinimumSystemVersion</key>
  <string>10.11</string>
  <key>CFBundleDevelopmentRegion</key>
  <string>English</string>
  <key>CFBundleAllowMixedLocalizations</key>
  <true/>
  <key>CFBundleExecutable</key>
  <string>DEPLOY_LAUNCHER_NAME</string>
  <key>CFBundleIconFile</key>
  <string>DEPLOY_ICON_FILE</string>
  <key>CFBundleIdentifier</key>
  <string>DEPLOY_BUNDLE_IDENTIFIER</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>DEPLOY_BUNDLE_NAME</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>DEPLOY_BUNDLE_SHORT_VERSION</string>
  <key>CFBundleSignature</key>
  <string>????</string>
  <key>LSApplicationCategoryType</key>
  <string>DEPLOY_APP_CATEGORY</string>
  <key>CFBundleVersion</key>
  <string>DEPLOY_BUNDLE_CFBUNDLE_VERSION</string>
  <key>NSHumanReadableCopyright</key>
  <string>DEPLOY_BUNDLE_COPYRIGHT</string>
  <key>CFBundleDocumentTypes</key>
  <array>
   <dict>
    <key>CFBundleTypeName</key>
    <string>Text Document</string>
    <key>CFBundleTypeRole</key>
    <string>Editor</string>
    <key>LSHandlerRank</key>
    <string>Default</string>
    <key>CFBundleTypeIconFile</key>
    <string>DEPLOY_ICON_FILE</string>
    <key>LSItemContentTypes</key>
    <array>
     <string>public.plain-text</string>
     <string>public.utf8-plain-text</string>
     <string>public.text</string>
     <string>public.source-code</string>
     <string>public.script</string>
     <string>public.shell-script</string>
     <string>public.xml</string>
     <string>public.json</string>
     <string>public.yaml</string>
     <string>public.comma-separated-values-text</string>
     <string>public.log</string>
     <string>com.apple.log</string>
     <string>com.apple.property-list</string>
    </array>
   </dict>
   <dict>
    <key>CFBundleTypeName</key>
    <string>All Files</string>
    <key>CFBundleTypeRole</key>
    <string>Editor</string>
    <key>LSHandlerRank</key>
    <string>Alternate</string>
    <key>CFBundleTypeIconFile</key>
    <string>DEPLOY_ICON_FILE</string>
    <key>LSItemContentTypes</key>
    <array>
     <string>public.data</string>
     <string>public.content</string>
     <string>public.item</string>
    </array>
   </dict>
  </array>
  <key>NSHighResolutionCapable</key>
  <string>true</string>
  <key>NSMicrophoneUsageDescription</key>
  <string>The application DEPLOY_LAUNCHER_NAME is requesting access to the microphone.</string>
 </dict>
</plist>
EOF

jpackage --type dmg --input target/ --main-jar bearit-$inputs_app_version.jar --main-class com.edwares.BearitApp --name bearit --app-version $inputs_app_version --dest distribution_payload/macos/ --icon src/main/resources/Bearit.icns --vendor "EdWares" --resource-dir macos_resources/

rm -rf macos_resources

echo "mac package built at distribution_payload/macos/bearit-$inputs_app_version.dmg"
