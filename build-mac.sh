#!/bin/bash
# This script builds the macOS package for the Bearit application using jpackage.

mvn clean package

jar_file=$(find target/ -maxdepth 1 -name "bearit*.jar" -exec stat -f "%N" {} + )
inputs_app_version=$(echo "$jar_file" | sed -E 's/.*-([0-9]+\.[0-9]+\.[0-9]+)\.jar/\1/')

echo building mac package for $jar_file ...
echo app version is $inputs_app_version

mkdir -p distribution_payload/macos/

jpackage --type dmg --input target/ --main-jar bearit-$inputs_app_version.jar --main-class com.edwares.BearitApp --name bearit --app-version $inputs_app_version --dest distribution_payload/macos/ --icon src/main/resources/Bearit.icns --vendor "EdWares"

echo "mac package built at distribution_payload/macos/bearit-$inputs_app_version.dmg"
