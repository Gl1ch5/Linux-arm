# GNOME в Linux ARM: исследование и выбранный подход

Этап 4 из README. Образ: `images/debian-gnome/`. Этот документ фиксирует, что проверено
по исходникам и документации, что не проверено, и какие компромиссы приняты. Сборка
образа и запуск на телефоне ещё не делались: образ собирается в CI на arm64-раннере.

## Коротко

- **Берём GNOME 48 из Debian 13 (trixie)**, а не GNOME 49. В GNOME 49 X11-сессия выключена
  по умолчанию, и `gnome-shell --nested` удалён, так что запуск «внутри окна» для проверки
  больше не работает.
- **Сессия:** `gnome-session` с X11-сессией на `DISPLAY=:0` (Termux:X11). Если она падает,
  запасной путь: `gnome-shell --x11 --replace` с настройками, запущенными вручную.
- **Полного GNOME без systemd и logind на этом этапе не гарантирую.** Сессия может стартовать,
  но часть функций работает с ограничениями: нет блокировки экрана, нет засыпания по
  простою, нет управления питанием через logind. Это ожидаемо, но не проверено на устройстве.
- **Нested GNOME под Weston невозможен** в GNOME 48 и 49 (см. ниже). Вариант (c) из задачи
  отброшен.

## Что выяснено

### GNOME 49 убирает X11-сессию и nested-режим

- По [заметкам разработчика GNOME 49](https://release.gnome.org/49/developers/) поддержка
  выделенной X11-сессии выключена по умолчанию. Это касается gnome-session, gdm, mutter и
  gnome-shell. X11 можно включить при сборке, но там же сказано, что дистрибутивам стоит
  учитывать, что в будущих версиях это может стать невозможно. X11-приложения при этом
  продолжают работать через XWayland.
- Nested-режим `gnome-shell --nested` заменён на **Mutter Development Kit** (`--devkit`),
  который требует `mutter-devkit` и PipeWire. По
  [обсуждению на Discourse](https://discourse.gnome.org/t/gnome-49-nested-sessions-no-longer-possible/30987)
  начиная с 49.beta1 опция `--nested` удалена, потому что nested-режим зависел от X11-бэкенда.
  Замена: `dbus-run-session -- gnome-shell --devkit`.
- Полное удаление X11 описано как шаг GNOME 50 (источники: [heise](https://www.heise.de/en/news/GNOME-49-retires-X11-10437523.html),
  [Alatiera](https://blogs.gnome.org/alatiera/2025/06/08/the-x11-session-removal/)).
- **Противоречие:** [It's FOSS](https://itsfoss.com/news/x11-is-back-in-gnome/) пишет, что в
  релиз-кандидате GNOME 49 вернули X11 в GDM. Это касается экрана входа, а не сессии
  пользователя, и мы GDM не используем. Вопрос в сессии остаётся открытым, поэтому выбран
  GNOME 48.

### Debian 13 (trixie) содержит GNOME 48

Проверено по пакетам trixie:

| Пакет | Версия | Что важно |
|---|---|---|
| `gnome-shell` | 48.7-0+deb13u2 | man-страница: `--x11`, `-r/--replace`, `--nested` (есть) |
| `gnome-session-xsession` | 48.0-1+deb13u1 | ставит `/usr/share/xsessions/gnome-xorg.desktop` и `gnome.desktop` |
| `gnome-session-bin` | 48.0-1+deb13u1 | бинарник `gnome-session` |
| `gnome-settings-daemon` | 48.1-1 | зависит от `libsystemd0` (библиотека), но не от работающего systemd |
| `dconf-cli` | 0.40.0-5 | инструмент `dconf update` для системной базы настроек |
| `gnome-console` | 48.0.1-2 | терминал (`kgx`) |

Источники: [gnome-shell (man)](https://manpages.debian.org/trixie/gnome-shell/gnome-shell.1.en.html),
[gnome-session-xsession](https://packages.debian.org/trixie/gnome-session-xsession),
[gnome-session-bin](https://packages.debian.org/trixie/gnome-session-bin),
[gnome-settings-daemon](https://packages.debian.org/trixie/gnome-settings-daemon),
[dconf-cli](https://packages.debian.org/trixie/dconf-cli),
[gnome-console](https://packages.debian.org/trixie/gnome-console).

Точное содержимое `gnome-xorg.desktop` и `gnome-xorg.session` **не проверено**: Debian sources
и upstream-файлы в этой среде не открылись. Поэтому launcher выбирает имя сессии по
наличию файла и пишет в лог, что нашёл.

### systemd и logind: что реально требуется

- В исходниках gnome-shell 48 ([js/misc/loginManager.js](https://gitlab.gnome.org/GNOME/gnome-shell/-/raw/gnome-48/js/misc/loginManager.js))
  выбор менеджера входа такой: если есть `/run/systemd/seats`, используется `LoginManagerSystemd`,
  иначе `LoginManagerDummy`. Значит, gnome-shell **не падает** без logind, а переходит на заглушку.
  Заглушка не умеет блокировать экран, усыплять и т. п.
- В исходниках gnome-session 48 ([main.c](https://gitlab.gnome.org/GNOME/gnome-session/-/raw/gnome-48/gnome-session/main.c))
  нет отдельного флага `--builtin`: при неудаче запуска systemd-цели используется запасной путь
  без systemd. Флаг `--builtin` из старых man-страниц я для GNOME 48 не подтвердил, поэтому
  launcher его не использует.
- GNOME 49 это меняет. По [заметке Adrian Vovk](https://blogs.gnome.org/adrianvovk/2025/06/10/gnome-systemd-dependencies/)
  gnome-session убирает встроенный менеджер сервисов, и дистрибутивам без systemd придётся
  самим предоставить менеджер сервисов, замену session leader и помощника `gnome-session-ctl`.
  Для GDM нужен userdb. Это ещё одна причина держаться GNOME 48. Сам автор отмечает, что
  GNOME 48 получает исправления до выхода GNOME 50, а совместимость GNOME 49 с gnome-session 48
  не проверена. Подробнее о зависимостях: [LWN](https://lwn.net/Articles/1025560/).

### Что делают пользователи proot и Termux

- [gnome-in-termux](https://github.com/sabamdarif/gnome-in-termux) и руководства
  [uDroid](https://udroid-rc.gitbook.io/udroid-wiki/udroid-landing/setting-up-gui/termux-x11) и
  [LinuxDroidMaster](https://github.com/LinuxDroidMaster/Termux-Desktops/blob/main/Documentation/proot/debian_proot.md)
  делают одно и то же: отключают файлы `*login1*` в `/usr` и запускают `gnome-shell --x11` на
  Termux:X11 с `DISPLAY=:0`.
- В одном из отзывов подделка `systemctl` для запуска сервисов GNOME роняла gnome-shell.
- Эти руководства старые (1–4 года), и я не проверил, что они работают на GNOME 48.
- Про GNOME без systemd на других дистрибутивах: [Gentoo wiki](https://wiki.gentoo.org/wiki/GNOME/GNOME_without_systemd/Gentoo),
  [dantrell/gentoo-project-gnome-without-systemd](https://github.com/dantrell/gentoo-project-gnome-without-systemd).
  Там используют elogind. В текущем образе elogind не ставится: по исходникам gnome-shell
  заглушка уже есть, и я не проверил, нужен ли elogind на практике. Если заглушка не справится,
  это первое, что стоит добавить.

## Варианты

| Вариант | Итог | Почему |
|---|---|---|
| (a) GNOME 48 X11 из trixie через `gnome-session` | **выбран** | Пакеты есть, gnome-shell не требует logind, есть запасной путь |
| (b) `gnome-shell --x11` + gsd вручную | **запасной путь** в launcher | Работает без gnome-session, но без части сервисов сессии |
| (c) nested GNOME под Weston | **отброшен** | `--nested` в GNOME 48 зависит от X11-бэкенда, в 49 удалён, Wayland-вложенного бэкенда не нашёл |
| (d) GNOME Flashback (metacity + gnome-panel) | резерв | Наличие пакетов в trixie не проверял |

Вариант (c) в деталях: в GNOME 48 вложенный режим и есть X11-бэкенд, то есть под Weston
всё равно нужен X-сервер. Значит, Weston добавляет слой без выгоды. Для Termux:X11 вариант
(a) и (b) уже и есть прямой X11-клиент.

## Что будет работать и что нет (ожидаемо, не проверено на устройстве)

Ожидается:
- сессия GNOME 48 на X11 с панелью, обзором (Activities), Nautilus, Console, Text Editor,
  Settings (Control Center), Adwaita, шрифтами;
- приложения GTK запускаются на X11 (`GDK_BACKEND=x11`);
- тесты GPU из меню приложений: vkcube, glxgears, GPU info (`vulkaninfo --summary`, `glxinfo -B`).

Не будет или будет с ограничениями:
- **Блокировки экрана нет.** Заглушка logind не блокирует сессию, и мы отключаем lock через dconf.
- **Засыпания по простою нет.** `idle-delay=0` и `sleep-*=nothing` в dconf. Power-плагин
  gnome-settings-daemon без logind и UPower не работает.
- **Portals и screencast нет.** `xdg-desktop-portal-gnome` не ставится: он тянет стек PipeWire и
  не даст захват экрана на X11 без лишних зависимостей. Приложения, которым нужны portals,
  могут не работать.
- **GDM, пользователи, Initial Setup нет.** Сессия стартует сразу.
- **Сети через NetworkManager нет.** Сеть на Android идёт через приложение, не через NM.
- **Wayland нет.** Вся графика идёт через X11 (Termux:X11 + XWayland не используются).
- **Панель Control Center «Пользователи» и «Питание» покажут ошибки**, так как опираются на
  logind и UPower.
- **Производительность:** Zink поверх Turnip должен давать GL, но это не проверено. Если
  Zink не работает, GNOME пойдёт на llvmpipe и будет очень медленным.
- **Память:** gnome-shell и gnome-settings-daemon потребляют заметную память; замеров нет.

Компромиссы, которые приняты сознательно:
- dconf-настройки системные (`/etc/dconf/db/local.d/00-linux-arm`): блокировка выключена,
  анимации выключены, масштаб текста 1.25. Пользователь может переопределить их в своём профиле.
- В меню приложений добавлены три ярлыка (vkcube, glxgears, GPU info) вместо ярлыков на рабочем
  столе, потому что GNOME не показывает `~/Desktop` без расширения.

## Как устроен образ

- `setup.sh`: пакеты GNOME 48 (без рекомендаций), runtime-библиотеки Mesa и X11 как в
  `debian-xfce`, локали en_US и ru_RU, dconf, ярлыки, затем `cleanup.sh`.
- `la-gnome-session` (ставится в `/usr/local/bin`):
  1. выставляет переменные XDG и `GDK_BACKEND=x11`;
  2. ждёт сокет X-сервера до 30 секунд;
  3. проверяет расширения X (Composite, DAMAGE, DRI3, Present, RANDR, XFIXES, SYNC и др.)
     через `xdpyinfo`, и пишет отсутствующие;
  4. пишет OpenGL renderer из `glxinfo -B`;
  5. выбирает `gnome-xorg` или `gnome` по наличию файла сессии;
  6. запускает `gnome-session`. При коде 0 выходит. При ошибке запускает запасной путь:
     `gnome-settings-daemon --replace`, `at-spi-bus-launcher`, `gnome-shell --x11 --replace`;
  7. если gnome-shell падает, повторяет с `LIBGL_ALWAYS_SOFTWARE=1` и пишет понятное сообщение.
- Все строки лога начинаются с `[gnome]`. Они попадают в лог сессии приложения.
- `LA_GNOME_DEBUG=1` включает `gnome-session --debug` (лог очень большой).

## Что проверить на устройстве

Сборка: CI (`build-images.yml`) с `debian-gnome`. Затем установка образа и запуск.
Пришлите из вкладки «Логи → Поделиться» файл `session-<id>.log` и, если сессия не стартует,
также `xserver.log` и `logcat.log`. Ключевые строки:

1. `[gnome] packages:` — версии gnome-session, gnome-shell, gnome-settings-daemon. Должны быть 48.x.
2. `[gnome] logind:` и `[gnome] systemd:` — ожидаются `absent` и `not running`.
3. `[gnome] X extensions:` или `WARNING: X extensions not reported` — **главная проверка**: если
   Composite или DRI3/Present отсутствуют, mutter не запустится.
4. `[gnome] OpenGL renderer:` — должен быть Zink/Turnip, не llvmpipe. Для llvmpipe будет предупреждение.
5. `[gnome] gnome-session session:` и список файлов `[gnome]   ...` — какая сессия выбрана
   и есть ли `gnome-xorg.session`.
6. `gnome-session exited with code N` — если N не 0, смотрите строки перед ним: ошибки
   `Failed to start`, `systemd`, `org.gnome.`, `dbus`.
7. `falling back to gnome-shell without gnome-session` — значит, сработал запасной путь.
   Ищите строки `gnome-shell` с `GLX`, `Failed to create` или `Cogl`.
8. `gnome-shell did not start` — сессия не поднялась; приложите весь лог.

Функциональные проверки на устройстве: появилась ли панель, работает ли касание (XInput2),
открывается ли Settings, запускаются ли vkcube и glxgears, нет ли зависания при закрытии окна.

## Источники

- [GNOME 49 developer notes](https://release.gnome.org/49/developers/)
- [Discourse: GNOME 49 nested sessions no longer possible](https://discourse.gnome.org/t/gnome-49-nested-sessions-no-longer-possible/30987)
- [Adrian Vovk: Introducing stronger dependencies on systemd](https://blogs.gnome.org/adrianvovk/2025/06/10/gnome-systemd-dependencies/)
- [LWN: GNOME deepens systemd dependencies](https://lwn.net/Articles/1025560/)
- [heise: GNOME 49 retires X11](https://www.heise.de/en/news/GNOME-49-retires-X11-10437523.html)
- [Alatiera: An update on the X11 GNOME Session Removal](https://blogs.gnome.org/alatiera/2025/06/08/the-x11-session-removal/)
- [It's FOSS: X11 is back in GNOME 49 (conflicting report)](https://itsfoss.com/news/x11-is-back-in-gnome/)
- [gnome-shell 48 loginManager.js](https://gitlab.gnome.org/GNOME/gnome-shell/-/raw/gnome-48/js/misc/loginManager.js)
- [gnome-session 48 main.c](https://gitlab.gnome.org/GNOME/gnome-session/-/raw/gnome-48/gnome-session/main.c)
- [Debian manpage: gnome-shell 48.7 (trixie)](https://manpages.debian.org/trixie/gnome-shell/gnome-shell.1.en.html)
- [Debian: gnome-session-xsession (trixie)](https://packages.debian.org/trixie/gnome-session-xsession)
- [Debian: gnome-session-bin (trixie)](https://packages.debian.org/trixie/gnome-session-bin)
- [Debian: gnome-settings-daemon (trixie)](https://packages.debian.org/trixie/gnome-settings-daemon)
- [Debian: dconf-cli (trixie)](https://packages.debian.org/trixie/dconf-cli)
- [Debian: gnome-console (trixie)](https://packages.debian.org/trixie/gnome-console)
- [gnome-in-termux](https://github.com/sabamdarif/gnome-in-termux)
- [uDroid: Termux:X11 setup](https://udroid-rc.gitbook.io/udroid-wiki/udroid-landing/setting-up-gui/termux-x11)
- [LinuxDroidMaster: Debian proot](https://github.com/LinuxDroidMaster/Termux-Desktops/blob/main/Documentation/proot/debian_proot.md)
- [Gentoo wiki: GNOME without systemd](https://wiki.gentoo.org/wiki/GNOME/GNOME_without_systemd/Gentoo)
- [dantrell/gentoo-project-gnome-without-systemd](https://github.com/dantrell/gentoo-project-gnome-without-systemd)
