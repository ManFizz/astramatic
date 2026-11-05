#!/usr/bin/env bash
# Сценарий эмулятора: навести взгляд, нажать, взяться за уголок и ручку (VR-03, VR-06, VR-07, VR-08, VR-18).
# Перед запуском: tools/emu.sh start, сборка debug и tools/emu.sh install. Запускать из корня проекта.
#   tools/emu-gaze.sh [папка для снимков]
set -u

emu="tools/emu.sh"
shots="${1:-}"
[ -z "$shots" ] || mkdir -p "$shots"

failures=0

state() { "$emu" state; }

# Значение поля из строки состояния.
field() { state | tr ' ' '\n' | sed -n "s/^$1=//p"; }

shot() { [ -z "$shots" ] || "$emu" shot "$shots/$1.png"; }

ok() { echo "ok    $1"; }

fail() {
    echo "FAIL  $1" >&2
    failures=$((failures + 1))
}

# Дождаться поля в состоянии: состояние меняется по кадрам, поэтому опрашиваем. Предел 5 с.
expect() { # что проверяем, поле, значение
    local began value
    began=$(date +%s)
    while :; do
        value=$(field "$2")
        if [ "$value" = "$3" ]; then
            ok "$1 ($2=$value)"
            return 0
        fi
        if [ $(($(date +%s) - began)) -ge 5 ]; then
            fail "$1: ждали $2=$3, получили $2=$value"
            return 1
        fi
    done
}

# То же для числа: значение поля попадает в диапазон.
expect_range() { # что проверяем, поле, от, до
    local began value
    began=$(date +%s)
    while :; do
        value=$(field "$2")
        if awk -v v="$value" -v a="$3" -v b="$4" 'BEGIN { v += 0; exit !(v >= a && v <= b) }'; then
            ok "$1 ($2=$value)"
            return 0
        fi
        if [ $(($(date +%s) - began)) -ge 5 ]; then
            fail "$1: ждали $2 от $3 до $4, получили $2=$value"
            return 1
        fi
    done
}

# Навести на кнопку и дождаться, пока взгляд на неё придёт: нажатие должно срабатывать и при неподвижном взгляде.
aim_button() { # x, y
    "$emu" aim "$1" "$2" >/dev/null
    expect_range "взгляд на кнопке" gaze "$(($1 - 1))" "$(($1 + 1))"
}

# Настройки живут до выключения эмулятора, а сценарий ждёт меню обычного размера.
"$emu" reset >/dev/null
"$emu" vr >/dev/null || { echo "Плеер не открылся" >&2; exit 2; }

# Трекинг устаканивается: панель появляется не сразу.
expect "панель появилась" visible true
expect "трекинг устоялся" settling false
"$emu" angle 0 0 >/dev/null
expect "открыта главная" tab Home

echo "--- Навести на кнопку и нажать (VR-03)"
aim_button 110 752
shot 1-hover-settings
"$emu" tap
expect "кнопка сработала, открыты настройки" tab Settings
shot 2-settings

aim_button 90 48
"$emu" tap
expect "«← Главная» вернула на главную" tab Home

echo "--- Уголки (VR-06)"
"$emu" aim 640 400 >/dev/null
expect "в середине меню уголка нет" handle None
"$emu" aim -30 -30 >/dev/null
expect "левый верхний" handle TopLeft
shot 3-corner-top-left
"$emu" aim 1310 -30 >/dev/null
expect "правый верхний" handle TopRight
"$emu" aim -30 830 >/dev/null
expect "левый нижний" handle BottomLeft
"$emu" aim 1310 830 >/dev/null
expect "правый нижний" handle BottomRight
"$emu" aim 1200 30 >/dev/null
expect "кнопка у края меню не перехвачена уголком" handle None
expect_range "взгляд попадает в меню" gaze 1190 1210

echo "--- Растягивание за уголок (VR-07)"
"$emu" aim 1310 -30 >/dev/null
expect "взгляд на уголке" handle TopRight
"$emu" press
expect "уголок взят" active TopRight
"$emu" aim 1500 -130 >/dev/null
expect_range "меню выросло" size 1.15 1.4
shot 4-grown
"$emu" release
expect "уголок отпущен" active None
expect_range "размер записан" savedSize 1.15 1.4
expect "меню не закрылось" visible true

echo "--- Размер упирается в пределы (VR-07)"
"$emu" aim 1310 -30 >/dev/null
expect "взгляд снова на уголке" handle TopRight
"$emu" press
expect "уголок взят снова" active TopRight
"$emu" aim 4000 -3000 >/dev/null
expect_range "не больше 180%" size 1.799 1.801
"$emu" aim 700 380 >/dev/null
expect_range "не меньше 60%" size 0.599 0.601
"$emu" release
expect "уголок отпущен" active None
expect_range "записан минимум" savedSize 0.599 0.601

echo "--- Перетаскивание за ручку (VR-08)"
"$emu" aim 640 846 >/dev/null
expect "взгляд на ручке" handle Move
"$emu" press
expect "ручка взята" active Move
yaw_before=$(field panelYaw)
"$emu" angle 40 0 >/dev/null
expect_range "меню поехало за взглядом" panelYaw 39 41
shot 5-moved
"$emu" release
expect "ручка отпущена" active None
"$emu" angle 0 0 >/dev/null
expect_range "меню осталось там, где отпустили" panelYaw 39 41
echo "      было panelYaw=$yaw_before"

echo "--- Нажатие мимо панели (VR-18)"
"$emu" angle 0 0 >/dev/null
expect "взгляд мимо" gaze null
"$emu" tap
expect "без видео панель не закрылась" visible true
expect_range "и встала перед взглядом" panelYaw -1 1

"$emu" free >/dev/null
"$emu" errors

if [ "$failures" -gt 0 ]; then
    echo "Не прошло: $failures" >&2
    exit 1
fi
echo "Всё прошло"
