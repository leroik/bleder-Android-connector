# Компоненты, условия и приватность

## Прямые зависимости

| Компонент | Версия / лицензия | Источник |
|---|---|---|
| ARCore SDK для Android | 1.48.0; SDK и использование API регулируются условиями Google. Лицензия Apache 2.0 в репозитории примеров относится к применимому исходному коду SDK/примеров, а условия ARCore применяются отдельно | [SDK](https://github.com/google-ar/arcore-android-sdk/blob/main/LICENSE), [ARCore Terms](https://developers.google.com/ar/develop/terms), [Privacy](https://developers.google.com/ar/develop/privacy-requirements) |
| ZXing Core | 3.5.3; Apache-2.0 | [LICENSE](https://github.com/zxing/zxing/blob/master/LICENSE) |
| OkHttp | 4.12.0; Apache-2.0. Okio/Kotlin runtime входят в граф сборки | [LICENSE](https://github.com/square/okhttp/blob/master/LICENSE.txt) |
| Open3D | 0.19.0; MIT для проекта | [LICENSE](https://github.com/isl-org/Open3D/blob/v0.19.0/LICENSE), [3rdparty](https://github.com/isl-org/Open3D/tree/v0.19.0/3rdparty) |
| NumPy | 1.26.4; BSD-3-Clause, плюс лицензии нативного содержимого колеса | [LICENSE](https://github.com/numpy/numpy/blob/v1.26.4/LICENSE.txt) |
| Pillow и JPEG | 11.2.1; MIT-CMU/историческая Pillow License и условия компонентов конкретного бинарного колеса | [Pillow license](https://github.com/python-pillow/Pillow/blob/11.2.1/LICENSE), [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/LICENSE.md) |
| FastAPI / Uvicorn / websockets | 0.115.12 MIT / 0.34.3 BSD-3-Clause / 15.0.1 BSD-3-Clause | [FastAPI](https://github.com/fastapi/fastapi/blob/0.115.12/LICENSE), [Uvicorn](https://github.com/encode/uvicorn/blob/0.34.3/LICENSE.md), [websockets](https://github.com/python-websockets/websockets/blob/15.0.1/LICENSE) |
| qrcode | 8.2; BSD-3-Clause | [LICENSE](https://github.com/lincolnloop/python-qrcode/blob/main/LICENSE) |
| Blender-аддон Scanner | Исходники аддона и ZIP по GPL-3.0-or-later | [Blender FAQ](https://www.blender.org/support/faq/), `blender_addon/hybrid_scanner/LICENSE.txt` |

Windows ZIP содержит исходный код и запускающие файлы. Прямые зависимости перечислены в requirements; Windows lock фиксирует разрешённые версии, в том числе пакеты из графа Open3D. Сторонние бинарные Python-колёса устанавливаются через pip отдельно. Для коммерческой поставки соберите SBOM, сохраните лицензии и notices из фактически включаемых AAR/JAR/колёс. Особого внимания требуют нативные библиотеки Open3D, BLAS и компиляторные runtime NumPy, транзитивные SciPy-колёса, JPEG и zlib. Лицензии конкретных сборок и исключения нужно проверить перед перепаковкой. Лицензия верхнего уровня Open3D не заменяет аудит содержимого wheel. GPL/AGPL-код не добавлялся непосредственно в собственный движок.

Blender-аддон использует bpy/GPU и распространяется с исходниками и GPL-текстом. Движок работает самостоятельно с документированным H3S/1. Отдельный процесс помогает разделять компоненты технически, но лицензионную независимость проприетарной поставки и применимость условий Google нужно оценить по фактической дистрибуции. Публичное распространение предыдущего APK/ZIP регулируется их собственными условиями, ветка Scanner их не перелицензирует. Перед коммерческой подписью APK проверьте актуальные Google Play Target API, политику данных и требования к нативным библиотекам Android.

Приложение запрашивает CAMERA, объявляет INTERNET для LAN, отключает штатное резервирование приложения и хранит кадры в app-private каталоге. В приложении есть уведомление об использовании Google ARCore и ссылки на условия и конфиденциальность Google. При распространении включите их в собственные условия приложения и подготовьте политику приватности для записи помещения, хранения, экспорта и удаления. Облачные API ARCore не используются. Токен должен оставаться в доверенной сети и меняется при перезапуске. Для защищённого продукта нужны TLS и согласованная политика хранения.
