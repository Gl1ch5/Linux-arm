# Образ KDE Plasma Mobile

Образ `plasma-mobile` лежит в `images/plasma-mobile/`. Это Debian 13 (trixie) с Plasma Mobile 6.3
и мобильной оболочкой. Композитор `kwin_wayland` работает вложенно в X-сервер Termux:X11
(`DISPLAY=:0`), а оболочка Plasma Mobile запускается внутри него.

Метаданные: `session_type: wayland-nested`, `launch_command: /usr/local/bin/la-plasma-mobile-session`,
`libc: glibc`, `base_image: debian:trixie-slim`.

## Почему Debian 13, а не Arch

В trixie есть готовые arm64-пакеты Plasma Mobile. Проверено по индексу `main/binary-arm64`
зеркала Debian: `plasma-mobile` 6.3.6-3, `plasma-mobile-tweaks` 6.3.6-3, `plasma-settings`,
`kwin-wayland` 6.3.6-1, `kclock`, `kweather`, `qmlkonsole`, `angelfish`, `maliit-keyboard`.
Пакета `index-fm` в trixie нет, поэтому в образ он не входит.

Arch Linux ARM для этого образа не нужен, так что Weston и патченный Mesa не требуются:
GPU идёт через Mesa из базового образа, как в `debian-gnome`.

## Что в образе

| Часть | Пакет | Замечание |
|---|---|---|
| Оболочка Plasma Mobile, `plasma_session`, `plasma-mobile-envmanager` | `plasma-mobile` | тянет плазму, `kwin-wayland`, `plasma-nm`, `plasma-pa` |
| Жесты и сенсорные компоненты | `plasma-mobile-tweaks` | рекомендуется пакетом `plasma-mobile`, ставится явно |
| Настройки | `plasma-settings` | |
| Часы, погода, терминал | `kclock`, `kweather`, `qmlkonsole` | Kirigami |
| Браузер | `angelfish` | тянет QtWebEngine, см. ниже |
| Экранная клавиатура | `maliit-keyboard` | входной метод Wayland |
| Тема | `breeze`, `breeze-wallpaper`, `kf6-breeze-icon-theme` | обои `Next` с тёмными вариантами |
| Звук | `pulseaudio` | см. ниже |

Установка идёт с `--no-install-recommends`, поэтому не ставятся Bluetooth, датчики, Akonadi, Baloo,
KDE Connect и Discover.

Тёмная тема задаётся в `/etc/xdg/kdeglobals`: `ColorScheme=BreezeDark`, `Icons/Theme=breeze-dark`.
Look and feel `org.kde.breeze.mobile` (его задаёт `plasma-mobile-envmanager`) берёт обои `Next`.

## Как запускается

1. `session.sh` приложения поднимает системную шину и запускает `dbus-run-session -- sh -c "$LA_LAUNCH"`.
2. `la-plasma-mobile-session` работает в два этапа.
   - **Этап 1, вне KWin.** Проверяет `DISPLAY`, ждёт сокет `/tmp/.X11-unix/X0` и читает размер корневого
     окна через `xwininfo`. Размер ждёт, пока он перестанет меняться: Termux:X11 сначала сообщает
     запасной размер. Затем запускает
     `kwin_wayland --x11-display :0 --width W --height H --scale 2.5 --no-lockscreen --no-global-shortcuts --no-kactivities --inputmethod maliit-keyboard --exit-with-session <этот скрипт>`.
   - **Этап 2, внутри KWin.** KWin запускает этот же скрипт с `LA_PLASMA_IN_KWIN=1` и `WAYLAND_DISPLAY`.
     Скрипт выставляет переменные из `startplasmamobile` (`PLASMA_PLATFORM=phone:handset`,
     `PLASMA_DEFAULT_SHELL=org.kde.plasma.mobileshell`, `QT_QUICK_CONTROLS_MOBILE=true`, `QT_QPA_PLATFORM=wayland`
     и др.), запускает `plasma-mobile-envmanager --apply-settings` и затем `plasma_session`.
3. Когда `plasma_session` завершается, завершается и `kwin_wayland`. Код возврата становится кодом сессии.

Все строки скрипта начинаются с `[plasma-mobile]` и попадают в лог сессии приложения.

Масштаб по умолчанию 2.5: логический размер для панели 1272x2800 около 509x1120. Переменная
`LA_PLASMA_SCALE` меняет его, если интерфейс выходит слишком мелким или крупным.

## Экономия процессов

Android убивает приложения с больше чем 32 дочерними процессами. Поэтому:

- не ставятся и не запускаются Baloo, Akonadi, KDE Connect и Discover (их нет в зависимостях);
- `baloofilerc` выключает индексацию на случай, если какой-то пакет всё же потянет Baloo;
- `xembedsniproxy` и `gmenudbusmenuproxy` скрыты через `/etc/xdg/autostart/*.desktop` с `Hidden=true`;
- Xwayland не запускается (`--xwayland` не передаётся): приложения Plasma Mobile работают через Wayland;
- `--no-lockscreen`, `--no-global-shortcuts`, `--no-kactivities` отключают лишние службы KWin;
- `NO_AT_BRIDGE=1`, `QT_ACCESSIBILITY=0` и `GTK_A11Y=none` не дают поднимать AT-SPI.

Ожидаемо около десяти процессов: `kwin_wayland`, `plasma_session`, `ksmserver`, `kcminit`, `krunner`,
`plasmashell`, `kded6`, `kglobalacceld`, шины D-Bus и PulseAudio. Служба `kactivitymanagerd` и
NetworkManager (через `plasma-nm`) запускаются по требованию.

## Известные ограничения

- **Angelfish** тянет QtWebEngine. При открытии он запускает несколько вспомогательных процессов
  (рендерер, GPU, сетевой процесс). Если лимит процессов уже близок, не открывайте его.
- **Нет systemd и logind.** Plasma обычно управляет сессией через systemd user units. Здесь сессия
  идёт через `plasma_session` напрямую, без юнитов. Блокировка экрана отключена, засыпание экрана
  не управляется.
- **Нет Xwayland.** Приложения, которым нужен X11, не запустятся. Это осознанный выбор ради
  процессов; если понадобится, добавьте `--xwayland` в `kwin_wayland`.
- **Звука нет через PipeWire.** Используется PulseAudio, как и в других образах. Мост к Android-аудио
  работает через `pulse.pa` из `session.sh`.
- **Образ большой.** Пакет `plasma-workspace` с зависимостями, Qt и QtWebEngine занимает заметно
  больше места, чем `debian-xfce`. Точный размер появится в `images.json` после сборки.

## Что проверить при первой сборке в CI

Эти пункты не проверялись вживую: сборка идёт только на arm64-раннере, и сессия не запускалась на телефоне.

1. `kwin_wayland` принимает все опции из `la-plasma-mobile-session`. Имена проверены по строкам
   бинарника `kwin_wayland` 6.3.6 (`x11-display`, `width`, `height`, `scale`, `no-lockscreen`,
   `no-global-shortcuts`, `no-kactivities`, `inputmethod`, `exit-with-session`). Неизвестная опция
   будет видна в логе. Самый важный вопрос: принимает ли `--exit-with-session` команду как значение
   (так сделано в скрипте). Если нет, нужно передавать её позиционным аргументом.
2. KWin в оконном режиме над X11 находит GL-контекст через `glxinfo` (Turnip+zink). Строка `OpenGL renderer`
   в логе покажет, что реально работает.
3. `plasma_session` поднимает `plasmashell` с оболочкой `org.kde.plasma.mobileshell` и без systemd.
4. `plasma-mobile-envmanager` видит сессию как Plasma Mobile. Он проверяет переменные `PLASMA_PLATFORM` и
   `PLASMA_DEFAULT_SHELL`, которые задаёт скрипт.
5. `maliit-keyboard` подключается к KWin через `--inputmethod`. Desktop-файл входного метода
   `com.github.maliit.keyboard.desktop` взят из конфигурации envmanager.
6. Масштаб 2.5 принимается как дробное значение. Если KWin требует целое, скрипт упадёт с ошибкой
   разбора опции; тогда поставьте `LA_PLASMA_SCALE=2` или `3`.

## Логи для отправки при сбое

Во вкладке **«Логи → Поделиться»** приложения есть `session-<id>.log`. Выбрать строки можно так:

```sh
grep -E '^\[plasma-mobile\]|^\[session\]|kwin|plasma' session-<id>.log
```

Внутри гостя полезен `glxinfo -B`. Журнала systemd нет, поэтому вывод `kwin_wayland` и
`plasma_session` смотрите в том же логе сессии.
