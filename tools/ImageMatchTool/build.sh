#!/bin/sh
# Builds ImageMatchTool.app into tools/ImageMatchTool/build with the system Swift compiler.
set -e
cd "$(dirname "$0")"
APP=build/ImageMatchTool.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
swiftc -O -o "$APP/Contents/MacOS/ImageMatchTool" Sources/*.swift
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>ImageMatchTool</string>
  <key>CFBundleIdentifier</key><string>com.coc.suncode.imagematchtool</string>
  <key>CFBundleName</key><string>ImageMatchTool</string>
  <key>CFBundleDisplayName</key><string>图片区块匹配工具</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>LSMinimumSystemVersion</key><string>12.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>CFBundleDocumentTypes</key><array><dict>
    <key>CFBundleTypeRole</key><string>Viewer</string>
    <key>LSItemContentTypes</key><array><string>public.image</string></array>
  </dict></array>
</dict></plist>
PLIST
echo "Built $APP"
