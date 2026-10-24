#!/bin/bash
# Shared cleanup for all images, run inside the build container as the last step.
set -eux
if command -v apt-get >/dev/null; then
    apt-get clean
    rm -rf /var/lib/apt/lists/*
fi
if command -v pacman >/dev/null; then
    yes | pacman -Scc || true
fi
if command -v apk >/dev/null; then
    rm -rf /var/cache/apk/*
fi
# Locales other than English/Russian.
if [ -d /usr/share/locale ]; then
    find /usr/share/locale -mindepth 1 -maxdepth 1 -type d ! -name 'en*' ! -name 'ru*' ! -name 'locale.alias' -exec rm -rf {} +
fi
rm -rf /tmp/* /var/tmp/* /var/log/*.log /root/.cache /etc/machine-id
: > /etc/machine-id
