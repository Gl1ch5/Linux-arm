# Phosh в Linux ARM: образ и выбранный подход

Образ: `images/debian-phosh/`. Сборка в CI и запуск на телефоне ещё не проверялись. Имена
пакетов и состав зависимостей проверены по индексу Debian 13 (trixie, arm64) и по содержимому
`.deb`-файлов.

## Коротко

- **Phosh 0.46 и phoc 0.46 из Debian 13.** phoc работает с бэкендом wlroots `x11` на
  `DISPLAY=:0` (Termux:X11). Так же устроен Omarchy: Hyprland вложен в Weston, который сам
  работает в окне X-сервера.
- **Сессия:** `gnome-session --session=phosh` внутри phoc. Оболочка, экранная клавиатура
  Squeekboard и gnome-settings-daemon стартуют автоматически.
- **Вид:** тема Adwaita dark, масштаб 3 (1272x2800 даёт около 424x933 логических пикселей),
  обои `blobs-d.svg` из gnome-backgrounds.
- **Процессов мало.** Нет Firefox (он поднимает много content-процессов), нет индексатора
  tracker, нет демонов evolution-data-server и goa, нет gnome-software.

## Стек

| Слой | Что | Откуда |
|---|---|---|
| X-сервер | Termux:X11, `DISPLAY=:0` | приложение |
| Композитор | phoc 0.46, wlroots 0.18, выход `X11-1` | пакет `phoc` |
| Сессия | gnome-session 48 со встроенным менеджером (без systemd), `phosh.session` | `gnome-session-bin` |
| Оболочка | phosh 0.46 (`mobi.phosh.Shell`) | пакет `phosh` |
| Клавиатура | Squeekboard 1.43 (автозапуск, `OnlyShowIn=Phosh`) | пакет `squeekboard` |
| Приложения | GNOME Console, Nautilus, Epiphany, Калькулятор, Текстовый редактор, настройки Phosh | пакеты `gnome-*`, `epiphany-browser`, `phosh-mobile-settings` |

## Что отключено и почему

- **Tracker (localsearch).** Nautilus тянет его через `tracker-extract`: в trixie пакета
  `localsearch` нет, это виртуальное имя. `setup.sh` после установки удаляет автозапуск,
  D-Bus-активацию и бинарники. Индексатора нет, поиск в Nautilus ничего не находит.
- **evolution-data-server, goa, gnome-software.** Их библиотеки приходят как зависимости
  (`libedataserver`, `libecal`, `libgoa`), но демоны и пакеты приложений не ставятся.
- **Календарный помощник Phosh** (`mobi.phosh.Shell.CalendarServer`) удалён: ему нужен
  evolution-data-server.
- **Доступность.** `at-spi2-core` не ставится, сессия задаёт `NO_AT_BRIDGE=1` и `GTK_A11Y=none`.
- **Монитор томов.** `GIO_USE_VOLUME_MONITOR=unix`, udisks-монитор не нужен.
- **Recommends выключены.** Не ставятся xwayland, phosh-plugins, phosh-mobile-tweaks и
  gnome-sushi. `feedbackd` приходит как зависимость `phosh-mobile-settings`, но без вызова
  не запускается.
- **systemd.** Ставится как провайдер logind для gnome-settings-daemon, как в образе
  `debian-gnome`. Не запускается, сессия работает без него.

## Сессия (`la-phosh-session`)

1. Ждёт X-сервер и стабильный размер корневого окна (`xwininfo`). Пишет
   `$XDG_RUNTIME_DIR/phoc.ini` с `mode` и `scale`. Статический `phoc.ini` пакета не подходит:
   размер окна Termux:X11 меняется.
2. Запускает `phoc -S -C phoc.ini -E "bash -lc 'exec gnome-session --disable-acceleration-check --session=phosh'"`.
3. Если phoc упал раньше чем за 20 с, повторяет с рендерером `pixman` (на CPU). GL-путь
   wlroots использует DRI3/Present, а Termux:X11 их может не давать.
4. Если bubblewrap не создаёт пространства имён (proot на Android), выставляет
   `WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1`, иначе Epiphany не откроет страницы. Песочница
   WebKit отключается для всей сессии, это записывается в лог.

Переменные: `LA_PHOSH_SCALE` (по умолчанию 3) и `LA_PHOSH_DEBUG=1` для отладочного вывода
gnome-session. Все строки лога начинаются с `[phosh]`.

## Масштаб, тема, настройки

- **Масштаб.** phoc задаёт `scale = 3`. Wayland-клиенты получают масштаб 3, текст Noto Sans 11.
  `text-scaling-factor` не меняется.
- **Тёмная тема.** `org.gnome.desktop.interface color-scheme=prefer-dark`, акцент `blue`.
- **Избранное.** `mobi.phosh.shell favorites`: Console, Epiphany, Nautilus, Text Editor,
  Калькулятор и настройки Phosh.
- **Без блокировки.** `lock-enabled=false`, `idle-delay=0`, `require-unlock=false`. Но при
  старте Phosh всё равно показывает экран блокировки и проверяет код через PAM. У root нет
  пароля, поэтому `/etc/pam.d/phosh` принимает любой код: введите любую цифру и нажмите
  «Unlock».
- Все значения заданы в `/etc/dconf/db/local.d/00-linux-arm`, пользователь может их менять.

## Что не проверено

- Запуск phoc с бэкендом x11 на Termux:X11. Не известно, есть ли у сервера Present и DRI3 для
  GL-пути. Если нет, сработает `pixman` (медленнее).
- Касания. Бэкенд wlroots x11 читает XInput2 (`libxcb-xinput` в зависимостях). Точность,
  жесты и длинные нажатия на устройстве не проверялись.
- Squeekboard: показ при фокусе поля и раскладка.
- Тёмная тема GTK3-приложений: опираемся на `color-scheme`, его поддержка зависит от версии GTK.
- Epiphany в proot: отключение песочницы описано выше, но сами страницы не открывались.
- Число процессов не измерялось. Оценка по составу сессии: десятки процессов, в пределах лимита
  Android в 32 дочерних процесса, если не открыто много вкладок Epiphany.

## Проверка сборки

- `apt-get -s install` по индексу trixie arm64 (без рекомендаций): 626 пакетов, ошибок
  резолвера нет. Проверка шла на хосте Ubuntu, поэтому базовый набор `debian:trixie-slim`
  приблизительный.
- `bash -n` и shellcheck: без предупреждений, только информационный SC1091 (как в
  `debian-gnome`).
