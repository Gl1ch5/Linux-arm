#!/bin/bash
# Runs inside an arm64 debian:trixie-slim container; the result is exported as rootfs.
# Plasma Mobile from Debian 13 (trixie): plasma-mobile 6.3 with kwin_wayland, nested in the X11
# server of Termux:X11 (see la-plasma-mobile-session next to this file).
#
# Not packaged in trixie, so left out: index-fm. Angelfish pulls in QtWebEngine, which starts
# several helper processes when it opens; it is kept, but do not open it on a phone with a
# tight process limit.
set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive

# Keep the image small: no docs, man pages or recommends.
cat > /etc/dpkg/dpkg.cfg.d/01-nodoc <<'CFG'
path-exclude /usr/share/doc/*
path-include /usr/share/doc/*/copyright
path-exclude /usr/share/man/*
path-exclude /usr/share/info/*
path-exclude /usr/share/lintian/*
CFG
echo 'APT::Install-Recommends "false"; APT::Install-Suggests "false";' > /etc/apt/apt.conf.d/99norecommends

# Recommends are off, so the phone does not get the Bluetooth, sensor, Akonadi, Baloo,
# KDE Connect or Discover services. pulseaudio is listed on purpose: plasma-pa accepts
# pipewire-pulse or pulseaudio, and naming pulseaudio keeps PipeWire out of the image.
apt-get update
apt-get install -y --no-install-recommends \
    plasma-mobile plasma-mobile-tweaks plasma-settings kwin-wayland \
    kclock kweather qmlkonsole angelfish maliit-keyboard \
    breeze breeze-cursor-theme kf6-breeze-icon-theme breeze-wallpaper \
    dbus dbus-x11 polkitd xwayland \
    fonts-noto-core fonts-noto-color-emoji fonts-dejavu-core \
    locales sudo nano less curl wget ca-certificates procps psmisc bash-completion \
    x11-utils x11-xserver-utils xkb-data xauth \
    libgl1-mesa-dri libglx-mesa0 libegl-mesa0 libgbm1 libvulkan1 \
    mesa-utils vulkan-tools \
    pulseaudio pulseaudio-utils

# Locales: English and Russian.
sed -i -e 's/^# *\(en_US.UTF-8\)/\1/' -e 's/^# *\(ru_RU.UTF-8\)/\1/' /etc/locale.gen
locale-gen
echo 'LANG=ru_RU.UTF-8' > /etc/default/locale

# Session launcher (see la-plasma-mobile-session next to this file).
install -m 755 /la-build/image/la-plasma-mobile-session /usr/local/bin/la-plasma-mobile-session

# Breeze dark for the whole system: the mobile look and feel of plasma-mobile reads the colour
# scheme from here, and its wallpaper is Next, which ships dark variants.
mkdir -p /etc/xdg
cat > /etc/xdg/kdeglobals <<'KDEG'
[General]
ColorScheme=BreezeDark

[Icons]
Theme=breeze-dark
KDEG

# Background services that a phone does not need. Baloo indexing is off in case a Kirigami
# app pulls in the Baloo daemon. The tray proxy and the global-menu proxy are hidden from
# autostart; Plasma Mobile has neither a tray menu bar nor a global menu.
cat > /etc/xdg/baloofilerc <<'BALOO'
[Basic Settings]
Indexing-Enabled=false
BALOO
mkdir -p /etc/xdg/autostart
for name in xembedsniproxy gmenudbusmenuproxy; do
    cat > "/etc/xdg/autostart/$name.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=$name
Hidden=true
DESK
done

bash /la-build/common/cleanup.sh
