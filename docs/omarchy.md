# Образ Omarchy (Arch Linux ARM + Hyprland)

Образ `omarchy` лежит в `images/omarchy/`. Это Arch Linux ARM с Omarchy и Hyprland. Композитор
работает вложенно: Hyprland — Wayland-клиент Weston, а Weston работает внутри X-сервера
Termux:X11 (`DISPLAY=:0`). Ускорение идёт через KGSL: Mesa из проекта omarchy-android собрана
с драйвером Freedreno для ядра Adreno (`/dev/kgsl-3d0`).

Метаданные: `session_type: wayland-nested`, `launch_command: /usr/local/bin/la-omarchy-session`,
`libc: glibc`.

## Что в образе

| Часть | Откуда | Где в образе |
|---|---|---|
| База Arch Linux ARM | `danhunsaker/archlinuxarm`, зафиксирован digest `sha256:37260b35…` (тег 20260517) | `base_image` в `image.json` |
| Rootfs с Arch Linux ARM, Omarchy, Quickshell, Chromium, foot, Nautilus, PipeWire | релиз [omarchy-android v0.1.1](https://github.com/BlackFireAlex/omarchy-android/releases/tag/v0.1.1) | корень образа |
| Hyprland и Aquamarine (патченные сборки для Android/KGSL) | тот же релиз | `/opt/omarchy-android/hyprland`, `/opt/omarchy-android/aquamarine` |
| Mesa (Freedreno на KGSL, GBM, EGL) | тот же релиз | `/opt/omarchy-android/mesa/root/usr` |
| Weston (x11 backend, kiosk-shell) и xwininfo | Arch Linux ARM, ставятся `pacman` в `setup.sh` | `/usr/bin/weston`, `/usr/bin/xwininfo` |
| Запускающий скрипт | этот репозиторий | `/usr/local/bin/la-omarchy-session` |

Закреплённый релиз:

- URL: `https://github.com/BlackFireAlex/omarchy-android/releases/download/v0.1.1/omarchy-android-aarch64-0.1.1.bundle.tar`
- sha256: `7e9f1cd67533bc0d3988b5cb3831aef52f1527dd90391d9d868ed9345021cdb2`

Значения взяты из `manifest/release.lock` проекта omarchy-android. `setup.sh` проверяет
эту сумму до распаковки. Сумма покрывает весь архив, включая `rootfs.tar.xz`.

Размер: распакованный rootfs из релиза занимает около 3.7 ГиБ (по листингу архива), плюс Weston.
Сжатый образ будет меньше; точный размер появится в `images.json` после сборки. Во время сборки
Docker-экспорт занимает ещё столько же места. Сборочному раннеру нужно не меньше 10 ГБ свободного
места.

## Как запускается

1. `session.sh` приложения поднимает системную шину и запускает `dbus-run-session -- sh -c "$LA_LAUNCH"`.
2. `la-omarchy-session`:
   - проверяет `DISPLAY` и доступность `/dev/kgsl-3d0`;
   - снимает переменные Turnip и Zink, которые выставляет приложение (`LD_LIBRARY_PATH`,
     `LIBGL_DRIVERS_PATH`, `__EGL_VENDOR_LIBRARY_FILENAMES`, `GALLIUM_DRIVER`,
     `MESA_LOADER_DRIVER_OVERRIDE`, `VK_ICD_FILENAMES`, `TU_DEBUG` и др.);
   - ставит Mesa из образа: `MESA_LOADER_DRIVER_OVERRIDE=kgsl`,
     `VK_ICD_FILENAMES=/opt/omarchy-android/mesa/freedreno_icd.json`;
   - задаёт `XDG_RUNTIME_DIR=/tmp/runtime-root`, `HOME=/home/omarchy`, переменные Omarchy
     (`OMARCHY_PROOT=1`, `OMARCHY_OUTPUT_WIDTH/HEIGHT`, `OMARCHY_SCALE`, `OMARCHY_REFRESH_MHZ`);
   - читает размер корневого окна X и ждёт, пока он перестанет меняться;
   - запускает Weston: `weston -B x11 --renderer=gl --shell=kiosk --socket=wayland-omarchy`
     с размером окна и `--scale=1`. Если `gl` не запустился, пробует `pixman`;
   - запускает Hyprland с `WAYLAND_DISPLAY=wayland-omarchy` и без `DISPLAY` и конфигом
     `~/.config/hypr/hyprland.lua`;
   - при выходе Hyprland останавливает Weston. Код возврата Hyprland становится кодом сессии.

Все строки скрипта начинаются с `[omarchy]` и попадают в лог сессии приложения.

## Что взято из omarchy-android

Образ использует проект [BlackFireAlex/omarchy-android](https://github.com/BlackFireAlex/omarchy-android)
под лицензией MIT. Из него взяты:

- rootfs и `/opt/omarchy-android/*` (Hyprland, Aquamarine, Mesa), то есть бинарные сборки
  из релиза v0.1.1;
- порядок запуска и набор переменных окружения из `runtime/host/omarchy-android-start`
  (Weston → Hyprland, KGSL, флаги `AQ_*` и `HYPRLAND_*`, переменные `OMARCHY_*`, которые читает
  `monitors.lua`).

Лицензии компонентов лежат в образе: `/usr/share/omarchy/LICENSE` и `/usr/share/licenses/`.
Сам Omarchy распространяется под MIT (basecamp/omarchy). Hyprland и Aquamarine — BSD-3-Clause,
Weston — MIT, Mesa — MIT и смешанные лицензии (см. `license.rst` в пакете).

## Отличия от схемы omarchy-android

В omarchy-android Weston и часть компонентов работают на Termux-хосте. В этом образе всё работает
внутри гостя:

- **Weston стоковый, из Arch.** Патченный `x11-backend.so` из релиза собран под Bionic (Termux),
  в glibc-гостье он не запускается. Поэтому точный тайминг XRandR и `WESTON_X11_REFRESH_MHZ` не
  действуют. Частота по умолчанию 60 Гц (`OMARCHY_REFRESH_MHZ=60000`).
- **Weston на KGSL, а не на zink.** На хосте omarchy-android Weston использует zink поверх
  Turnip. Здесь он использует общую Mesa образа (`gl`). Если GL-инициализация не удалась,
  включается `pixman`, а Hyprland переходит на путь через общую память. Это медленнее, и в логе
  выводится предупреждение.
- **Нет Termux-хоста:** нет virgl, нет `omarchy-process-guard`, нет `omarchy-x11-keyboard`,
  нет моста звука через PulseAudio/AAudio. **Звука нет:** PipeWire из образа не запускается.
- **D-Bus** — шина, которую поднимает `session.sh`. Ограниченная конфигурация
  `proot-session-bus.conf` из omarchy-android не используется.
- **Пользователь** — root внутри proot (так работает приложение), а не `omarchy` с uid 1000.
  Конфигурация Omarchy лежит в `/home/omarchy`, поэтому скрипт ставит `HOME=/home/omarchy`.
- **Выбор драйвера Turnip** в приложении для этого образа не используется: сессия всегда
  берёт Mesa из образа.

## Известные ограничения

- Нет systemd и PID 1. `uwsm-app` работает в режиме `OMARCHY_PROOT=1`: он просто запускает
  приложение, без user-сервисов systemd.
- Chromium запускается с `--no-sandbox` (это задано в `~/.config/chromium-flags.conf`), потому
  что внутри proot песочница не работает. Открывайте в нём только доверенные сайты.
- Программного fallback нет. Если `/dev/kgsl-3d0` недоступен, сессия сразу завершается с
  ошибкой. Hyprland требует EGL, а программный рендер (llvmpipe) в этом образе не настроен и не проверялся.
- Частота и масштаб задаются переменными `OMARCHY_REFRESH_MHZ` и `OMARCHY_SCALE`. Масштаб
  по умолчанию 2, как в omarchy-android. Раскладка по умолчанию `us`.
- Образ не собирает Mesa, Hyprland или Aquamarine. Все они берутся из закреплённого релиза.
  Чтобы обновить образ, поменяйте `BUNDLE_URL`, `BUNDLE_SHA256` и `BUNDLE_VERSION` в `setup.sh`.

## Что проверить при первой сборке в CI

Эти пункты не проверялись вживую: сборка идёт только на arm64-раннере.

1. `pacman -S weston` не должен обновить библиотеки, от которых зависят Hyprland и Aquamarine.
   Сборка падает, если `ldd` находит ненайденные библиотеки.
2. В Arch Linux ARM `kiosk-shell.so` и `x11-backend.so` должны лежать в `/usr/lib`. Иначе
   `setup.sh` остановится с сообщением об отсутствующем модуле.
3. Weston с `--renderer=gl` на X11 должен работать через Freedreno/KGSL. Если нет, сессия
   перейдёт на `pixman`. Это видно по предупреждению в логе.
4. `pacman -Sy` должен синхронизировать все репозитории из `/etc/pacman.conf` релиза (включая
   `[aur]`, который там объявлен). Если какой-то репозиторий недоступен, сборка падает на этом шаге.
5. Общий размер образа и место на раннере.

## Логи для отправки при сбое

Во вкладке **«Логи → Поделиться»** приложения есть `session-<id>.log` со всем выводом сессии.
Если сессия не запустилась, отправьте весь zip. Если нужно выбрать строки вручную:

```sh
grep -E '^\[omarchy\]|^\[session\]|weston|Hyprland' session-<id>.log
```

Внутри гостя дополнительно полезны:

- `ls -la /tmp/runtime-root /tmp/runtime-root/hypr` (обычно `hyprland.log` лежит в
  `/tmp/runtime-root/hypr/<instance>/`);
- `ls -l /dev/kgsl-3d0`.

Расшифровка строк:

| Строка в логе | Что значит |
|---|---|
| `[omarchy] ERROR: DISPLAY is not set` | X-сервер Termux:X11 не запустился |
| `[omarchy] ERROR: /dev/kgsl-3d0 is not readable and writable` | Нет доступа к GPU, проверьте, что `/dev` передан в гостя |
| `[omarchy] ERROR: missing ...: the rootfs is incomplete` | Образ неполный, его нужно переустановить |
| `[omarchy] ERROR: weston is not installed` | Шаг установки Weston в `setup.sh` не сработал |
| `[omarchy] X root geometry: ...` / `did not answer within 30 s` | X-сервер не ответил, размер взят по умолчанию 1280x800 |
| `[omarchy] Weston exited during startup (renderer=gl)` | Weston не запустился на GL; дальше пробуется `pixman` |
| `[omarchy] WARNING: Weston runs with renderer=pixman` | Работа на CPU-пути: медленнее, но сессия идёт |
| `[omarchy] ERROR: Weston did not start with any renderer` | Нужны строки `weston` выше в том же логе |
| `[omarchy] Hyprland exited with status N` | Смотрите `hyprland.log` в `/tmp/runtime-root/hypr/` |
