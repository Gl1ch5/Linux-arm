#!/bin/bash
# Runs inside an arm64 debian:trixie-slim container; the result is exported as rootfs.
# Phosh 0.46 and phoc 0.46 (trixie). The phone shell runs nested in phoc on the X11 display;
# phoc uses the wlroots X11 backend, see la-phosh-session next to this file.
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

apt-get update
# Not installed on purpose: gnome-software, gnome-online-accounts (goa daemon),
# evolution-data-server (daemon), at-spi2-core (NO_AT_BRIDGE=1), xwayland (phoc runs without it).
# Libraries of goa and evolution come in as dependencies, but their daemons do not.
apt-get install -y --no-install-recommends \
    phoc phosh phosh-mobile-settings gnome-session-bin gnome-session-common gnome-settings-daemon \
    squeekboard gnome-console nautilus gnome-calculator gnome-text-editor epiphany-browser \
    gnome-backgrounds librsvg2-common gsettings-desktop-schemas dconf-cli gvfs \
    dbus dbus-x11 polkitd \
    adwaita-icon-theme fonts-noto-ui-core fonts-noto-color-emoji \
    locales sudo nano less curl wget ca-certificates procps psmisc bash-completion \
    x11-utils x11-xserver-utils xkb-data xauth bubblewrap \
    libgl1-mesa-dri libglx-mesa0 libegl-mesa0 libgbm1 libvulkan1 \
    mesa-utils vulkan-tools \
    libdrm2 libexpat1 libwayland-client0 libwayland-server0 libx11-xcb1 libxcb-dri3-0 libxcb-present0 \
    libxcb-randr0 libxcb-shm0 libxcb-sync1 libxcb-xfixes0 libxcb-glx0 libxshmfence1 libxxf86vm1 libxrandr2 libzstd1 \
    pulseaudio pulseaudio-utils

# Locales: English and Russian.
sed -i -e 's/^# *\(en_US.UTF-8\)/\1/' -e 's/^# *\(ru_RU.UTF-8\)/\1/' /etc/locale.gen
locale-gen
echo 'LANG=ru_RU.UTF-8' > /etc/default/locale

# Tracker (localsearch) comes with nautilus. Its indexer is off on a phone: no autostart,
# no D-Bus activation, no binaries. Nautilus search then finds nothing.
rm -f /etc/xdg/autostart/localsearch-3.desktop \
    /usr/share/dbus-1/services/org.freedesktop.LocalSearch3*.service \
    /usr/share/dbus-1/services/org.freedesktop.Tracker3*.service \
    /usr/share/localsearch3/miners/org.freedesktop.Tracker3.Miner.Files.service \
    /usr/libexec/localsearch-3 /usr/libexec/localsearch-control-3 \
    /usr/libexec/localsearch-extractor-3 /usr/libexec/localsearch-writeback-3

# The calendar helper of phosh reads evolution-data-server, which is not installed.
rm -f /usr/share/dbus-1/services/mobi.phosh.Shell.CalendarServer.service /usr/libexec/phosh-calendar-server

# Session launcher (see la-phosh-session next to this file).
install -m 755 /la-build/image/la-phosh-session /usr/local/bin/la-phosh-session

# System-wide defaults for a phone: dark theme, wallpaper, favorites, no screen lock or sleep.
# Read by dconf from the "local" system database; user settings still override them.
mkdir -p /etc/dconf/profile /etc/dconf/db/local.d/locks
printf '%s\n' 'user-db:user' 'system-db:local' > /etc/dconf/profile/user
cat > /etc/dconf/db/local.d/00-linux-arm <<'DCONF'
[org/gnome/desktop/interface]
color-scheme='prefer-dark'
gtk-theme='Adwaita'
icon-theme='Adwaita'
accent-color='blue'
font-name='Noto Sans 11'
document-font-name='Noto Sans 11'

[org/gnome/desktop/background]
picture-uri='file:///usr/share/backgrounds/gnome/blobs-d.svg'
picture-uri-dark='file:///usr/share/backgrounds/gnome/blobs-d.svg'
picture-options='zoom'

[org/gnome/desktop/session]
idle-delay=uint32 0

[org/gnome/desktop/screensaver]
lock-enabled=false
idle-activation-enabled=false

[org/gnome/desktop/lockdown]
disable-lock-screen=true

[org/gnome/settings-daemon/plugins/power]
sleep-inactive-ac-type='nothing'
sleep-inactive-battery-type='nothing'
idle-dim=false

[mobi/phosh/shell]
favorites=['org.gnome.Console.desktop', 'org.gnome.Epiphany.desktop', 'org.gnome.Nautilus.desktop', 'org.gnome.TextEditor.desktop', 'org.gnome.Calculator.desktop', 'mobi.phosh.MobileSettings.desktop']
require-unlock=false
DCONF
dconf update

# Launchers in the phosh app grid.
cat > /usr/share/applications/la-vkcube.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=vkcube (тест Vulkan)
Exec=vkcube
Icon=applications-games
Categories=Development;
DESK
cat > /usr/share/applications/la-gpu-info.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=Информация о GPU
Exec=kgx -- sh -c "vulkaninfo --summary; glxinfo -B; exec sh"
Icon=utilities-terminal
Categories=System;
DESK

bash /la-build/common/cleanup.sh
