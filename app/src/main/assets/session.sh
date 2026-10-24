#!/bin/sh
# Desktop session wrapper. Written by the Linux ARM app into the guest on every start
# (/usr/local/lib/linux-arm/session.sh); local edits are overwritten.
# Environment from the app: DISPLAY, LA_LAUNCH, LA_DISTRO, GPU variables.

log() { echo "[session] $*"; }

log "distro=$LA_DISTRO display=$DISPLAY launch: $LA_LAUNCH"
log "VK_ICD_FILENAMES=${VK_ICD_FILENAMES:-<unset>} MESA_LOADER_DRIVER_OVERRIDE=${MESA_LOADER_DRIVER_OVERRIDE:-<unset>} LD_LIBRARY_PATH=${LD_LIBRARY_PATH:-<unset>}"

mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
mkdir -p /tmp/.ICE-unix /tmp/.X11-unix && chmod 1777 /tmp/.ICE-unix /tmp 2>/dev/null

if [ ! -s /etc/machine-id ]; then
    if command -v dbus-uuidgen >/dev/null 2>&1; then
        dbus-uuidgen --ensure=/etc/machine-id
    else
        tr -d '-' < /proc/sys/kernel/random/uuid > /etc/machine-id
    fi
fi

# A system bus keeps a number of desktop components quiet; failures are harmless.
if command -v dbus-daemon >/dev/null 2>&1; then
    mkdir -p /run/dbus
    rm -f /run/dbus/pid /run/dbus/system_bus_socket
    dbus-daemon --system --fork >/dev/null 2>&1 && log "system dbus started" || log "system dbus failed (ignored)"
fi

if command -v dbus-run-session >/dev/null 2>&1; then
    exec dbus-run-session -- sh -c "$LA_LAUNCH"
else
    exec sh -c "$LA_LAUNCH"
fi
