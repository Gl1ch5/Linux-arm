#!/bin/bash
# Runs inside an arm64 debian:trixie-slim container; the result is exported as rootfs.
# GNOME 48 (trixie). GNOME 49 dropped the X11 session, so do not move this image to forky/sid.
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
apt-get install -y --no-install-recommends \
    gnome-session-bin gnome-session-common gnome-session-xsession gnome-shell \
    gnome-settings-daemon gnome-control-center nautilus gnome-console gnome-text-editor \
    gsettings-desktop-schemas dconf-cli gvfs \
    dbus dbus-x11 at-spi2-core polkitd \
    adwaita-icon-theme fonts-cantarell fonts-dejavu-core fonts-noto-color-emoji \
    locales sudo nano less curl wget ca-certificates procps psmisc bash-completion \
    x11-utils x11-xserver-utils xkb-data xauth \
    libgl1-mesa-dri libglx-mesa0 libegl-mesa0 libgbm1 libvulkan1 \
    mesa-utils vulkan-tools \
    libdrm2 libexpat1 libwayland-client0 libwayland-server0 libx11-xcb1 libxcb-dri3-0 libxcb-present0 \
    libxcb-randr0 libxcb-shm0 libxcb-sync1 libxcb-xfixes0 libxcb-glx0 libxshmfence1 libxxf86vm1 libxrandr2 libzstd1 \
    pulseaudio pulseaudio-utils

# Locales: English and Russian.
sed -i -e 's/^# *\(en_US.UTF-8\)/\1/' -e 's/^# *\(ru_RU.UTF-8\)/\1/' /etc/locale.gen
locale-gen
echo 'LANG=ru_RU.UTF-8' > /etc/default/locale

# Session launcher (see la-gnome-session next to this file).
install -m 755 /la-build/image/la-gnome-session /usr/local/bin/la-gnome-session

# System-wide GNOME defaults for a phone: no screen lock, no idle suspend, bigger text.
# Read by dconf from the "local" system database; user settings still override them.
mkdir -p /etc/dconf/profile /etc/dconf/db/local.d/locks
printf '%s\n' 'user-db:user' 'system-db:local' > /etc/dconf/profile/user
cat > /etc/dconf/db/local.d/00-linux-arm <<'DCONF'
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

[org/gnome/desktop/interface]
text-scaling-factor=1.25
enable-animations=false

[org/gnome/shell]
welcome-dialog-last-shown-version='4294967295'
DCONF
dconf update

# Launchers in the GNOME Activities overview.
cat > /usr/share/applications/la-vkcube.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=vkcube (тест Vulkan)
Exec=vkcube
Icon=applications-games
Categories=Development;
DESK
cat > /usr/share/applications/la-glxgears.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=glxgears (тест OpenGL)
Exec=sh -c "vblank_mode=0 glxgears -info"
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
