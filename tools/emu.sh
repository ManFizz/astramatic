#!/usr/bin/env bash
# Управление эмулятором для проверок без телефона. Запускать из Git Bash или любого bash, из корня проекта.
#
#   tools/emu.sh start            запустить без окна и дождаться загрузки (профиль не меняется)
#   tools/emu.sh install          поставить собранную debug-версию
#   tools/emu.sh vr               открыть плеер и положить телефон как в шлеме
#   tools/emu.sh shot имя.png     снять экран вместе с GL-картинкой
#   tools/emu.sh key BACK         нажать кнопку: BACK, DPAD_DOWN, DPAD_CENTER…
#   tools/emu.sh tap              нажать кнопку шлема (касание экрана)
#   tools/emu.sh reset            стереть данные приложения в эмуляторе: настройки, размер меню
#   tools/emu.sh look X Y СЕКУНД  поворот головы: X — влево-вправо, Y — вверх-вниз, рад/с. Тайминг приблизительный
#   tools/emu.sh aim PX PY        точно навести взгляд на точку панели, пиксели дисплея 1280×800; за краем — меньше 0 или больше размера
#   tools/emu.sh angle YAW PITCH  точно задать взгляд в градусах: YAW — влево, PITCH — вверх
#   tools/emu.sh free             вернуть взгляд трекингу
#   tools/emu.sh press            нажать кнопку шлема и держать
#   tools/emu.sh release          отпустить
#   tools/emu.sh state            состояние плеера одной строкой: панель, взгляд, уголок, размер меню
#   tools/emu.sh errors           падения приложения в журнале
#   tools/emu.sh stop             выключить эмулятор
set -u

AVD="${ASTRAMATIC_AVD:-Medium_Phone_API_37.0}"
PACKAGE=com.manfis.astramatic.debug
BOOT_LIMIT_S=240

# В Git Bash пути устройства не должны превращаться в пути Windows.
export MSYS_NO_PATHCONV=1

sdk="${ANDROID_HOME:-}"
if [ -z "$sdk" ] && [ -f local.properties ]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | sed 's/\\:/:/g; s/\\\\/\//g')
fi
[ -n "$sdk" ] || { echo "Не найден Android SDK: задайте ANDROID_HOME или sdk.dir в local.properties" >&2; exit 2; }
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"

a() { "$adb" -e "$@"; }

wait_for() { # описание, предел в секундах, команда-условие
    local what=$1 limit=$2 began
    shift 2
    began=$(date +%s)
    until "$@"; do
        if [ $(($(date +%s) - began)) -gt "$limit" ]; then
            echo "Не дождался: $what ($limit с)" >&2
            return 1
        fi
        sleep 1
    done
}

booted() { [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }
in_player() { a shell dumpsys activity activities | grep -q "topResumedActivity.*PlayerActivity"; }

# Отладочная команда плеера (DebugCommands, только debug-сборка). В ответ — состояние плеера.
command() {
    a shell am broadcast -a com.manfis.astramatic.debug.COMMAND -p "$PACKAGE" "$@" |
        tr -d '\r' | sed -n 's/.*data="\(.*\)"$/\1/p'
}

# Координаты кнопки главного экрана из дерева интерфейса; сам экран перестраивается по мере загрузки.
button_center() {
    a shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    a exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 |
        sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' |
        awk 'NF == 4 { print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}

tap_button() {
    # Текст кнопки, сколько секунд её ждать.
    local xy="" began
    began=$(date +%s)
    while [ -z "$xy" ]; do
        xy=$(button_center "$1")
        if [ -z "$xy" ] && [ $(($(date +%s) - began)) -ge "$2" ]; then
            return 1
        fi
    done
    # shellcheck disable=SC2086
    a shell input tap $xy
}

case "${1:-}" in
start)
    "$emulator" -avd "$AVD" -read-only -no-snapshot -no-audio -no-boot-anim -no-window -gpu host >/dev/null 2>&1 &
    wait_for "загрузка эмулятора" "$BOOT_LIMIT_S" booted && echo "Эмулятор загружен"
    ;;
install)
    a install -r -t androidApp/build/outputs/apk/debug/androidApp-debug.apk | tail -1
    ;;
vr)
    a shell am force-stop "$PACKAGE"
    a shell am start -n "$PACKAGE/com.manfis.astramatic.MainActivity" >/dev/null
    tap_button "Войти в VR" 20 || { echo "Нет кнопки «Войти в VR»" >&2; exit 1; }
    wait_for "плеер" 15 in_player || exit 1
    # Если профиль остался в 2D, переключаем: на этой кнопке подпись — режим, в который она ведёт.
    # Уже в VR кнопки в дереве интерфейса нет, поэтому одной попытки достаточно.
    sleep 1
    tap_button "VR" 0
    a emu sensor set acceleration 9.8:0:0 >/dev/null
    a emu sensor set gyroscope-uncalibrated 0:0:0 >/dev/null
    echo "Плеер открыт"
    ;;
shot)
    a exec-out screencap -p > "${2:?куда сохранить}"
    ;;
key)
    a shell input keyevent "KEYCODE_${2:?какую кнопку}"
    ;;
tap)
    a shell input tap 1000 500
    ;;
look)
    # Трекинг Cardboard читает именно некалиброванный гироскоп.
    a emu sensor set gyroscope-uncalibrated "${2:?X}:${3:?Y}:0" >/dev/null
    sleep "${4:?секунды}"
    a emu sensor set gyroscope-uncalibrated 0:0:0 >/dev/null
    ;;
reset)
    a shell pm clear "$PACKAGE"
    ;;
aim)
    command --es cmd aim --ef px "${2:?PX}" --ef py "${3:?PY}"
    ;;
angle)
    command --es cmd angle --ef yaw "${2:?YAW}" --ef pitch "${3:?PITCH}"
    ;;
free)
    command --es cmd free
    ;;
press)
    a shell input motionevent DOWN 1000 500 >/dev/null
    ;;
release)
    a shell input motionevent UP 1000 500 >/dev/null
    ;;
state)
    command --es cmd state
    ;;
errors)
    a logcat -d | grep -E "FATAL EXCEPTION|E AndroidRuntime|UnsatisfiedLinkError" || echo "Падений нет"
    ;;
stop)
    a emu kill
    ;;
*)
    sed -n '2,19p' "$0"
    exit 2
    ;;
esac
