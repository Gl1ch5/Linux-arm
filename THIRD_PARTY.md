# Сторонние компоненты и лицензии

Приложение распространяется под **GNU GPL v3.0** (см. [LICENSE](LICENSE)): в него встроен
X-сервер Termux:X11, который сам распространяется под GPL-3.0.

| Компонент | Где используется | Лицензия | Источник |
|---|---|---|---|
| Termux:X11 (модуль `lorie`, X-сервер на базе Xorg) | встроенный X11-сервер и экран | GPL-3.0 (+ лицензии X.Org/MIT для xserver и библиотек) | https://github.com/termux/termux-x11 (git-подмодуль `third_party/termux-x11`, закреплённый коммит) |
| PRoot (форк Termux) | запуск дистрибутивов без root | GPL-2.0-or-later | https://github.com/termux/proot (коммит в `native/proot/build.sh`) |
| talloc | статически в proot | LGPL-3.0-or-later | https://talloc.samba.org |
| Mesa (Turnip, Zink) | драйверы GPU, собираются в CI | MIT и др. (см. `docs/license.rst` в Mesa; копия лежит в каждом архиве драйвера) | https://gitlab.freedesktop.org/mesa/mesa |
| omarchy-android | референс архитектуры; патчи будут переиспользованы на этапе 3 | MIT | https://github.com/BlackFireAlex/omarchy-android |
| mesa-for-android-container | референс сборки Turnip под KGSL | MIT (как Mesa) | https://github.com/lfdevs/mesa-for-android-container |
| Jetpack Compose, AndroidX | интерфейс | Apache-2.0 | https://developer.android.com/jetpack |
| OkHttp | загрузки | Apache-2.0 | https://square.github.io/okhttp/ |
| Coil | превью образов | Apache-2.0 | https://coil-kt.github.io/coil/ |
| Apache Commons Compress | распаковка tar | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| zstd-jni | распаковка zstd | BSD-2-Clause | https://github.com/luben/zstd-jni |
| kotlinx.serialization, kotlinx.coroutines | — | Apache-2.0 | https://github.com/Kotlin |

Образы дистрибутивов состоят из пакетов соответствующих дистрибутивов (Debian и др.);
их лицензии лежат внутри образов (`/usr/share/doc/*/copyright`, `/usr/share/licenses`).

Исходный код GPL-компонентов, входящих в APK, доступен в этом репозитории (подмодули и
скрипты сборки с закреплёнными версиями).
