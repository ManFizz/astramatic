# AstraMatic

VR-плеер для Cardboard на Android и приложение-компаньон для ПК.

- `androidApp` — плеер: видео с телефона, общих папок Windows (SMB), AstraMatic на ПК и Darkframe;
  интерфейс внутри VR, пульты и геймпады, режим 2D.
- `desktopApp` — AstraMatic для ПК: раздаёт папки с видео телефону, пульт, обзор Darkframe.
- `shared` — общее: форматы видео, протокол телефон ⇄ ПК.
- `third_party/cardboard` — исходники Cardboard SDK; приложение берёт готовый `androidApp/libs/cardboard-sdk.aar`.

## Сборка

| Что | Команда | Результат |
|---|---|---|
| Телефон, для разработки | ▶ Run в Android Studio или `gradlew :androidApp:assembleDebug` | «AstraMatic Dev», id `com.manfis.astramatic.debug` |
| Телефон, для себя | `gradlew :androidApp:assembleRelease` | «AstraMatic», `androidApp/build/outputs/apk/release/androidApp-release.apk` |
| ПК, запустить | `gradlew :desktopApp:run` | окно AstraMatic |
| ПК, установщик | `gradlew :desktopApp:packageMsi` | `.msi` в `desktopApp/build/compose/binaries` |
| Cardboard SDK из исходников | `gradlew -Pastramatic.buildCardboard=true :cardboard:publishAar` | обновлённый `cardboard-sdk.aar` |

Отладочная и release-версии ставятся на телефон рядом, настройки у каждой свои.

Release подписывается ключом из `%USERPROFILE%\.android-keys\` (`astramatic-release.jks` и
`astramatic.properties` с паролем). Их нет в репозитории — **сохраните копию**: без этого ключа
обновить установленную release-версию не получится, только удалить и поставить заново.
Если файлов нет, release соберётся без подписи.

## Документы

- [docs/product.md](docs/product.md) — что умеет приложение и что дальше.
- [docs/scenarios/](docs/scenarios/README.md) — сценарии поведения и открытые вопросы.
- [docs/testing.md](docs/testing.md) — проверка: `gradlew verifyAll`, скриншоты, эмулятор.

## Настройки на ПК

`%USERPROFILE%\.astramatic\desktop.json` — папки, ключ подключения телефонов, доступ к Darkframe.
