#!/bin/bash
# Runs inside an arm64 debian:trixie-slim container; the result is exported as rootfs.
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
apt-get install -y \
    xfce4-session xfwm4 xfce4-panel xfdesktop4 xfce4-settings xfconf thunar xfce4-terminal \
    xfce4-appfinder xfce4-notifyd mousepad ristretto \
    dbus dbus-x11 x11-xserver-utils x11-utils xkb-data xauth \
    adwaita-icon-theme fonts-dejavu-core fonts-noto-color-emoji \
    locales sudo nano less curl wget ca-certificates procps psmisc bash-completion \
    libgl1-mesa-dri libglx-mesa0 libegl-mesa0 libgbm1 libvulkan1 \
    mesa-utils vulkan-tools \
    libdrm2 libexpat1 libwayland-client0 libwayland-server0 libx11-xcb1 libxcb-dri3-0 libxcb-present0 \
    libxcb-randr0 libxcb-shm0 libxcb-sync1 libxcb-xfixes0 libxcb-glx0 libxshmfence1 libxxf86vm1 libxrandr2 libzstd1 \
    pulseaudio pulseaudio-utils

# Locales: English and Russian.
sed -i -e 's/^# *\(en_US.UTF-8\)/\1/' -e 's/^# *\(ru_RU.UTF-8\)/\1/' /etc/locale.gen
locale-gen
echo 'LANG=ru_RU.UTF-8' > /etc/default/locale

# XFCE defaults for a phone: no compositor (fewer GPU round trips), no screensaver.
mkdir -p /etc/xdg/xfce4/xfconf/xfce-perchannel-xml
cat > /etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="use_compositing" type="bool" value="false"/>
    <property name="theme" type="string" value="Default-xhdpi"/>
  </property>
</channel>
XML

# Phone scale: larger fonts (DPI), bigger panel, icons and cursor.
cat > /etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Xft" type="empty">
    <property name="DPI" type="int" value="168"/>
    <property name="Antialias" type="int" value="1"/>
    <property name="HintStyle" type="string" value="hintslight"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="CursorThemeSize" type="int" value="48"/>
  </property>
</channel>
XML
cat > /etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-desktop" version="1.0">
  <property name="desktop-icons" type="empty">
    <property name="icon-size" type="uint" value="64"/>
  </property>
</channel>
XML
# Bigger default panel (applied when the panel creates its first config).
if [ -f /etc/xdg/xfce4/panel/default.xml ]; then
    sed -i 's|name="size" type="uint" value="[0-9]*"|name="size" type="uint" value="48"|g; s|name="icon-size" type="uint" value="[0-9]*"|name="icon-size" type="uint" value="0"|g' /etc/xdg/xfce4/panel/default.xml
fi
echo 'Xcursor.size: 48' > /root/.Xresources

# Handy test launchers on the desktop.
mkdir -p /root/Desktop
cat > /root/Desktop/vkcube.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=vkcube (тест Vulkan)
Exec=vkcube
Icon=applications-games
DESK
cat > /root/Desktop/glxgears.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=glxgears (тест OpenGL)
Exec=xfce4-terminal --hold -e "sh -c 'glxinfo -B; vblank_mode=0 glxgears -info'"
Icon=applications-games
DESK
chmod +x /root/Desktop/*.desktop

bash /la-build/common/cleanup.sh
