#!/bin/bash
# script to update the debian package with an updated file (replace jar in opt/bearit/lib/app)

#original_deb=bearit_*.deb
original_deb=$(find . -maxdepth 1 -name "bearit_*.deb" ! -name "*custom*" -printf "%T@ %p\n" | sort -n | tail -n 1 | cut -d' ' -f2-)
filename_noext=$(basename "$original_deb" .deb)
new_deb=$filename_noext-custom.deb

echo "original package: $original_deb"
echo "creating custom package: $new_deb"
rm -f $new_deb
mkdir tmp
cd tmp
ar p ../$original_deb data.tar.zst | tar --zstd -x

#edit contents of data.tar.zst
custom_jar=$(find opt/bearit/lib/app -maxdepth 1 -name "bearit*.jar" -printf "%T@ %p\n" | sort -n | tail -n 1 | cut -d' ' -f2-)
echo "updating jar: $custom_jar"
jar -uf $custom_jar -C ../custom-tools/ bearit.properties ../custom-tools/app-content/
#cp -v ../$custom_jar opt/bearit/lib/app

tar --zstd -cf data.tar.zst *[!z]
cp -v ../$original_deb ../$new_deb
ar r ../$new_deb data.tar.zst
cd ..
rm -fr ./tmp
echo "succesfully updated package: $new_deb"
