# Lomiri (оболочка Ubuntu Touch) на Debian 13: исследование

Цель: мобильный образ с оболочкой Lomiri на trixie (arm64), запущенной вложенно на `DISPLAY=:0`
(Termux:X11). Итог: **Lomiri не собран**, потому что оболочка не может запускать приложения
без менеджера пользовательских служб systemd, а в proot его нет. Вместо неё сделан образ
`debian-touch` (`images/debian-touch/`): сенсорная X11-сессия с Openbox, нижней панелью,
сеткой приложений и экранной клавиатурой Onboard.

Сборка образов и запуск на телефоне здесь не проверялись: образ собирается в CI.

## Что проверено

Версии и зависимости взяты из индекса `trixie` для arm64 (`Packages`) и из исходных
`.deb`-пакетов на deb.debian.org.

| Пакет | Версия в trixie | Заметка |
|---|---|---|
| `lomiri` | 0.5.0-2 | оболочка; зависит от `lomiri-app-launch`, `qtmir`, `qml-module-lomiri-components` |
| `lomiri-desktop-session` | 0.3-11 | скрипт `lomiri-session` и юнит `lomiri.service` (Type=notify) |
| `lomiri-app-launch` | 0.1.12-1 | запуск приложений; тянет `zeitgeist-core` |
| `qtmir` | 0.8.0~git20250407 | Qt-платформа Mir, Qt 5 |
| `mir-graphics-drivers-desktop` | 2.20.2-2 | платформа `server-x11.so.23` (`mir:x11`), пакет `mir-platform-graphics-x` |
| `lomiri-terminal-app` | 2.0.5-2 | |
| `lomiri-system-settings` | 1.3.1-6 | |
| `lomiri-filemanager-app` | 1.1.4+dfsg-2 | |
| `morph-browser` | 1.1.2+dfsg-3 | тянет Qt WebEngine |
| `lomiri-calculator-app` | 4.1.0-1 | |
| `lomiri-clock-app` | 4.1.1-2 | |
| `lomiri-keyboard` | 1.0.3-1 | экранная клавиатура Lomiri (Maliit) |
| `lomiri-wallpapers` | 20.04.0-4 | обои |

Пакетов `lomiri-ui-toolkit`, `lomiri-action-api` и `miral` как отдельных бинарных пакетов в trixie
нет. Их библиотеки входят в `liblomiritoolkit*`, `liblomiri-action-qt*` и `libmiral7`.

## Почему Lomiri не запускается в proot

`lomiri-session` (из `lomiri-desktop-session`) ставит переменные окружения и делает
`exec lomiri --mode=full-shell`. Сама оболочка при этом может стартовать. Проблема в запуске
приложений:

- `liblomiri-app-launch.so.0.0.0` (пакет `liblomiri-app-launch0` 0.1.12-1) запускает каждое
  приложение через **systemd user manager**: в строках библиотеки есть `org.freedesktop.systemd1.Manager`,
  `Creating a new SystemD for`, `Asking systemd to start task for` и `Unable to subscribe to SystemD`.
- Скрипт `lomiri-session` вызывает `systemctl --user` и `dbus-update-activation-environment --systemd`.
- В proot нет PID 1 и нет `systemd --user`. Значит, плитка приложения в оболочке не откроет ничего.

Заменить systemd здесь можно только подменой D-Bus-интерфейса `org.freedesktop.systemd1`
(с `StartTransientUnit` и отслеживанием PID и cgroup). Это нестабильно, нельзя проверить без
устройства и не даёт нормальной оболочки, поэтому такой путь не выбран.

Прочие риски (не проверены, но уже достаточно, чтобы не делать образ на текущем этапе):

- Mir X11-платформа использует EGL/Mesa на X11. Termux:X11 не предоставляет DRI3, поэтому
  аппаратная графика может не работать (этот пункт по условиям задачи).
- Стек большой: Qt 5, Mir 2.20, qtmir, Qt WebEngine в браузере. По размеру и числу процессов он
  тяжёлый для телефона; замеров нет.
- `lomiri-app-launch` тянет `zeitgeist-core` (журнал событий). Его нельзя убрать без поломки
  зависимостей, и он добавляет процессы.

## Выбранный вариант: `debian-touch`

Идея: X11-сессия без systemd, рассчитанная на касания. Все компоненты есть в trixie arm64
(версии проверены по индексу):

| Роль | Пакет | Где в образе |
|---|---|---|
| Оконный менеджер (без рамок, окна развёрнуты) | `openbox` 3.6.1 | `/usr/local/share/la-touch/openbox-rc.xml` |
| Нижняя панель: сетка приложений, клавиатура, терминал, файлы, часы | `tint2` 17.0.1 | `/usr/local/share/la-touch/tint2rc` |
| Сетка приложений (полноэкранная, 4 колонки) | `rofi` 1.7.5 | `/usr/local/share/la-touch/launcher.rasi`, `/usr/local/bin/la-touch-apps` |
| Экранная клавиатура (раскладка Phone, тема Nightshade) | `onboard` 1.4.1 | `/usr/local/bin/la-touch-keyboard`, схема `90-la-touch.gschema.override` |
| Обои (тёмные, Adwaita) | `gnome-backgrounds` 48.2.1 (`adwaita-d.jpg`), `feh` 3.10.3 | `/usr/share/backgrounds/gnome/` |
| Терминал, файлы, текст | `lxterminal` 0.4.1, `pcmanfm` 1.4.0, `mousepad` 0.6.3 | |
| Иконки и шрифты | `papirus-icon-theme` (Papirus-Dark), `fonts-noto-core` | |

Файлы образа:

- `setup.sh`: пакеты, GTK 3 (тёмная тема, Papirus-Dark, шрифт 12 pt, без анимаций), схема Onboard,
  конфигурация сессии, ярлыки, затем `cleanup.sh`.
- `la-touch-session` (ставится в `/usr/local/bin`): переменные сессии (`NO_AT_BRIDGE=1`,
  `GTK_A11Y=none`, `GDK_BACKEND=x11`, `GIO_USE_VOLUME_MONITOR=unix`), ожидание X-сокета, `xrdb`
  (DPI 200, курсор 40), затем Openbox, обои и tint2. Завершается вместе с Openbox.
- `image.json`: `session_type: x11`, `launch_command: /usr/local/bin/la-touch-session`.

Клавиатура: в панели кнопка «Клавиатура» вызывает `la-touch-keyboard`. Первый вызов запускает
Onboard. Следующие переключают его окно через D-Bus (`org.onboard.Onboard.Keyboard.ToggleVisible`).
Автопоказ отключён, потому что он работает через AT-SPI, а `NO_AT_BRIDGE=1` его выключает.

Процессы: dbus-daemon сессии, openbox, tint2, при необходимости onboard и приложения.
Лишних служб нет.

## Что не проверено

- Имена ключей tint2 (`launcher_icon_background_id`, `clock_font_color` и др.) и правила Openbox
  (`class="Onboard"`) сверены по памяти документации, а не по исходникам. Если ключ не распознан,
  tint2 или Openbox выведут предупреждение в лог сессии.
- Масштаб: DPI 200 через `Xft.dpi` работает для Xft и Pango. Нужно проверить, что GTK 3 на X11
  тоже берёт его. Если нет, размер текста в GTK-приложениях станет мельче, и его придётся задать
  через `GDK_DPI_SCALE`.
- Раскладка Onboard `Phone` и тема `Nightshade` есть в `onboard-common` 1.4.1-10. Как они
  выглядят в окне 1272x2800, нужно проверить на телефоне.
- Размер образа: `gnome-backgrounds` ставит около 37 МБ ради двух обоев. Если это много,
  можно взять другой пакет обоев.

## Что проверить после сборки в CI

1. `tint2 -c` и `openbox --config-file` не пишут ошибок в лог `[touch]`.
2. Окна разворачиваются на весь экран между панелью и клавиатурой, и панель не перекрывается.
3. `la-touch-keyboard` показывает и прячет клавиатуру при повторных вызовах.
4. `la-touch-apps` открывает сетку приложений, и приложения из неё запускаются.
5. `vkcube` и `glxgears` работают (проверка GPU, как в остальных образах).
