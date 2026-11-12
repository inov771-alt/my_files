# Мои файлы (Android)

Приложение появляется в системном меню «Поделиться» для файлов любых типов
(`ACTION_SEND` и `ACTION_SEND_MULTIPLE`) и загружает их на
`https://files.spkmd.ru/index.php?api=upload` (`multipart/form-data`, поля `files[]` и `dir`).

* Android 8.0+ (minSdk 26), compileSdk/targetSdk 35
* Kotlin 2.0.21, AGP 8.7.3, Gradle 8.10.2 (wrapper)
* Зависимостей нет — только Android SDK

## Как собрать APK

**Android Studio:** File → Open → папка проекта → Build → Build APK(s).
Файл: `app/build/outputs/apk/release/app-release.apk`.

**Командная строка** (нужны JDK 17+ и Android SDK, `ANDROID_HOME` или `local.properties` с `sdk.dir`):

    ./gradlew assembleRelease        # Windows: gradlew.bat assembleRelease

**GitHub:** загрузите проект в репозиторий — `.github/workflows/build-apk.yml`
соберёт APK, он будет в Actions → артефакт `MyFiles-apk`.

Release-сборка подписана debug-ключом, чтобы APK сразу устанавливался.
Для Google Play подставьте свой keystore в `app/build.gradle.kts`.

## Как устроено

| Файл | Назначение |
|---|---|
| `ShareReceiverActivity.kt` | экран приёма: прогресс, результат, ошибки, «Повторить» |
| `SharedFiles.kt` | разбор Intent, имя/MIME/размер через ContentResolver, нормализация имён |
| `Uploader.kt` | потоковый multipart-запрос и разбор ответа сервера |
| `MainActivity.kt` | подсказка при запуске иконки |

* **Большие файлы** — тело запроса стримится (`setFixedLengthStreamingMode`, либо chunked, если размер неизвестен),
  память не растёт: проверено на файле 300 МБ при куче 48 МБ.
* **Русские имена** — нормализация в NFC (имена с macOS/iOS), имя пишется сырым UTF-8 как в браузерах,
  убираются пути, управляющие символы, кавычки и ведущие точки; длина ≤ 200 байт с сохранением расширения.
* **Одинаковые имена.** Перед загрузкой приложение запрашивает `?api=list` и выбирает свободное имя:
  `файл.txt` → `файл(1).txt` → `файл(2).txt`… Если список получить не удалось, файл отправляется как есть,
  а при ответе «Такой файл уже существует.» (или HTTP 409) повторяется со следующим номером (до 20 раз).
  Файлы уходят по одному, поэтому ошибка одного не отменяет остальные, а «Повторить» шлёт только неудавшиеся.
* **Лимиты сервера.** Имя не длиннее 180 символов (иначе `index.php` отклоняет весь запрос). Размер файла ограничен
  настройками PHP/веб-сервера: `upload_max_filesize`, `post_max_size` (по умолчанию 2 и 8 МБ), `client_max_body_size` в nginx.
  Для больших файлов эти значения нужно увеличить на сервере.
* **Результат** — ответ сервера разбирается как JSON (`ok`, `failed`); любые отклонения показываются на экране.
