#!/bin/bash
# Runs inside an arm64 debian:trixie-slim container; the result is exported as rootfs.
# Touch session on X11: Openbox, tint2, rofi and Onboard. Lomiri was checked and not used,
# see docs/lomiri.md (its app launcher needs the systemd user manager, which proot lacks).
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
    openbox tint2 rofi onboard feh \
    lxterminal pcmanfm mousepad \
    gnome-backgrounds papirus-icon-theme adwaita-icon-theme \
    dbus dbus-x11 libglib2.0-bin x11-utils x11-xserver-utils xkb-data xauth \
    fonts-noto-core fonts-noto-color-emoji \
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

# GTK 3 defaults: dark Adwaita, Papirus icons, larger text, no animations.
mkdir -p /etc/gtk-3.0
cat > /etc/gtk-3.0/settings.ini <<'INI'
[Settings]
gtk-theme-name=Adwaita
gtk-application-prefer-dark-theme=true
gtk-icon-theme-name=Papirus-Dark
gtk-font-name=Noto Sans 12
gtk-cursor-theme-size=40
gtk-enable-animations=false
INI

# Onboard: phone layout in the dark theme. Schema overrides change the defaults only.
cat > /usr/share/glib-2.0/schemas/90-la-touch.gschema.override <<'GS'
[org.onboard]
layout='Phone'
theme='Nightshade'
GS
glib-compile-schemas /usr/share/glib-2.0/schemas

# Shared configuration of the session, read by la-touch-session.
install -d /usr/local/share/la-touch
cat > /usr/local/share/la-touch/Xresources <<'XRES'
! DPI for a 1272x2800 portrait phone screen: 10-12 pt text is about 30-35 px.
Xft.dpi: 200
Xft.antialias: 1
Xft.hinting: 1
Xft.rgba: rgb
Xcursor.size: 40
XRES

cat > /usr/local/share/la-touch/openbox-rc.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc" xmlns:xi="http://www.w3.org/2001/XInclude">
  <resistance>
    <strength>0</strength>
    <screen_edge_strength>0</screen_edge_strength>
  </resistance>
  <focus>
    <focusNew>yes</focusNew>
    <followMouse>no</followMouse>
    <focusLast>yes</focusLast>
    <underMouse>no</underMouse>
    <focusDelay>0</focusDelay>
    <raiseOnFocus>yes</raiseOnFocus>
  </focus>
  <placement>
    <policy>Smart</policy>
    <center>yes</center>
  </placement>
  <theme>
    <name>Clearlooks</name>
    <titleLayout>NLIMC</titleLayout>
    <keepBorder>no</keepBorder>
    <animateIconify>no</animateIconify>
    <font place="ActiveWindow">
      <name>Noto Sans</name>
      <size>13</size>
      <weight>Bold</weight>
      <slant>Normal</slant>
    </font>
    <font place="InactiveWindow">
      <name>Noto Sans</name>
      <size>13</size>
      <weight>Normal</weight>
      <slant>Normal</slant>
    </font>
    <font place="MenuHeader">
      <name>Noto Sans</name>
      <size>13</size>
      <weight>Bold</weight>
      <slant>Normal</slant>
    </font>
    <font place="MenuItem">
      <name>Noto Sans</name>
      <size>14</size>
      <weight>Normal</weight>
      <slant>Normal</slant>
    </font>
  </theme>
  <desktops>
    <number>1</number>
    <firstdesk>1</firstdesk>
    <names>
      <name>Main</name>
    </names>
    <popupTime>0</popupTime>
  </desktops>
  <resize>
    <drawContents>yes</drawContents>
    <popupShow>Never</popupShow>
  </resize>
  <applications>
    <!-- Phone screen: every normal window fills the screen between the bar and the keyboard. -->
    <application type="normal">
      <decor>no</decor>
      <maximized>yes</maximized>
    </application>
    <application type="dialog">
      <decor>yes</decor>
    </application>
    <!-- Onboard stays above the apps and never takes focus from them. -->
    <application class="Onboard">
      <decor>no</decor>
      <focus>no</focus>
      <layer>above</layer>
      <skip_taskbar>yes</skip_taskbar>
      <skip_pager>yes</skip_pager>
    </application>
  </applications>
</openbox_config>
XML

cat > /usr/local/share/la-touch/tint2rc <<'TINT'
# Bottom bar for a touch phone: four big launchers and the clock.

# Background 1: the bar itself.
rounded = 0
border_width = 0
background_color = #111418 96
border_color = #111418 0
background_color_hover = #111418 96
border_color_hover = #111418 0
background_color_pressed = #111418 96
border_color_pressed = #111418 0

# Background 2: highlight behind a pressed launcher.
rounded = 24
border_width = 0
background_color = #1F2630 0
border_color = #1F2630 0
background_color_hover = #1F2630 0
border_color_hover = #1F2630 0
background_color_pressed = #2A3442 100
border_color_pressed = #2A3442 0

# Panel: full width, docked at the bottom, reserves its strut so maximized windows stop above it.
panel_monitor = all
panel_position = bottom center horizontal
panel_size = 100% 160
panel_margin = 0 0
panel_padding = 16 0 16
panel_background_id = 1
panel_dock = 1
panel_layer = top
panel_items = LC

# Launchers: app grid, keyboard, terminal, file manager.
launcher_padding = 36 14 0
launcher_background_id = 0
launcher_icon_background_id = 2
launcher_icon_theme = Papirus-Dark
launcher_icon_size = 96
launcher_item_app = /usr/local/share/applications/la-apps.desktop
launcher_item_app = /usr/local/share/applications/la-keyboard.desktop
launcher_item_app = /usr/share/applications/lxterminal.desktop
launcher_item_app = /usr/share/applications/pcmanfm.desktop

# Clock on the right.
time1_format = %H:%M
time1_font = Noto Sans Bold 26
clock_font_color = #E6EAF0 100
clock_background_id = 0
clock_padding = 24 0
TINT

cat > /usr/local/share/la-touch/launcher.rasi <<'RASI'
/* Full-screen app grid for rofi (drun mode). Dark, big targets for touch. */
configuration {
    modi: "drun";
    show-icons: true;
    drun-display-format: "{name}";
    font: "Noto Sans 14";
}

* {
    bg:     #0E1116F2;
    panel:  #1A202A;
    fg:     #E6EAF0;
    dim:    #8B95A5;
    accent: #4C8BF5;
    background-color: transparent;
    text-color: @fg;
}

window {
    width: 100%;
    height: 100%;
    background-color: @bg;
    border: 0;
    padding: 0;
}

mainbox {
    padding: 56px 40px;
    spacing: 36px;
    children: [ inputbar, listview ];
}

inputbar {
    background-color: @panel;
    border-radius: 28px;
    padding: 28px 36px;
    spacing: 16px;
    children: [ prompt, entry ];
}

prompt {
    text-color: @accent;
}

entry {
    placeholder: "Поиск";
    placeholder-color: @dim;
}

listview {
    columns: 4;
    lines: 5;
    spacing: 28px;
    fixed-height: true;
    scrollbar: false;
}

element {
    orientation: vertical;
    padding: 24px 12px;
    spacing: 16px;
    border-radius: 28px;
}

element selected.normal {
    background-color: @panel;
}

element-icon {
    size: 112px;
}

element-text {
    horizontal-align: 0.5;
    font: "Noto Sans 13";
}
RASI

# Session launcher (see la-touch-session next to this file).
install -m 755 /la-build/image/la-touch-session /usr/local/bin/la-touch-session

# Keyboard toggle and app grid, both used from the bottom bar.

cat > /usr/local/bin/la-touch-keyboard <<'SH'
#!/bin/sh
# Shows or hides the Onboard keyboard. The first call starts Onboard; later calls toggle its
# window over the session bus (org.onboard.Onboard.Keyboard.ToggleVisible).
if gdbus call --session --dest org.onboard.Onboard \
    --object-path /org/onboard/Onboard/Keyboard \
    --method org.onboard.Onboard.Keyboard.ToggleVisible >/dev/null 2>&1; then
    exit 0
fi
exec onboard
SH
cat > /usr/local/bin/la-touch-apps <<'SH'
#!/bin/sh
# App grid for the bottom bar: rofi in drun mode with the touch theme.
exec rofi -show drun -theme /usr/local/share/la-touch/launcher.rasi
SH
chmod 755 /usr/local/bin/la-touch-keyboard /usr/local/bin/la-touch-apps

# App grid entries. The bar's grid and keyboard entries are hidden (NoDisplay); the tests stay visible.
mkdir -p /usr/local/share/applications
cat > /usr/local/share/applications/la-apps.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=Приложения
Exec=/usr/local/bin/la-touch-apps
Icon=view-grid
NoDisplay=true
DESK
cat > /usr/local/share/applications/la-keyboard.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=Клавиатура
Exec=/usr/local/bin/la-touch-keyboard
Icon=input-keyboard
NoDisplay=true
DESK
cat > /usr/local/share/applications/la-vkcube.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=vkcube (тест Vulkan)
Exec=vkcube
Icon=applications-games
Categories=Development;
DESK
cat > /usr/local/share/applications/la-glxgears.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=glxgears (тест OpenGL)
Exec=sh -c "vblank_mode=0 glxgears -info"
Icon=applications-games
Categories=Development;
DESK
cat > /usr/local/share/applications/la-gpu-info.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=Информация о GPU
Exec=lxterminal -e sh -c "vulkaninfo --summary; glxinfo -B; exec sh"
Icon=utilities-terminal
Categories=System;
DESK

bash /la-build/common/cleanup.sh
