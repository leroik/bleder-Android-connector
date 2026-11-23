# H3S/1

QR и ручной код `h3s1:<локальный IPv4>:<port>:<token>`. Все HTTP/WS запросы передают `Authorization: Bearer <token>`. Токен генерируется при каждом запуске, срок действия 12 часов. Предыдущие QR после перезапуска становятся недействительны. Данный протокол предназначен для доверенной локальной сети с HTTP/WS без шифрования.

## HTTP

| Метод и путь | Назначение |
|---|---|
| GET /v1/status | Текущее состояние, очередь, число принятых/интегрированных кадров, тайминги |
| POST /v1/command | Команда JSON до 4 КиБ |
| POST /v1/sessions/{uuid}/frames | Бинарный пакет H3S1 и заголовок X-SHA256 |
| GET /v1/preview/points | Версия, session, точки [x,y,z,r,g,b], camera path [x,y,z] |
| GET /v1/preview/mesh | Версия, session, вершины [x,y,z] и треугольники [i,j,k] |
| GET /v1/sessions/{uuid}/export/{ply\|glb} | Загрузка готового файла |

## Команды и уведомления

Сообщение имеет `version: 1`, `type`, при необходимости `session` в виде канонического UUID. POST отвечает `scan.status`. В WebSocket `/v1/events` доступны те же команды, сервер отправляет `scan.status` и `mesh.updated` с полями `session` и `revision`. События могут быть пропущены при переподключении, поэтому клиент может опрашивать состояние и скачивать актуальные ревизии предпросмотра по HTTP.

| Тип | Дополнительные поля и поведение |
|---|---|
| scan.start | session; новый или сохранённый сеанс, после восстановления → scanning |
| scan.pause | session; пауза захвата, ранее записанные кадры могут догружаться |
| scan.resume | session; возобновление, включая догрузку завершённой записи |
| scan.stop | session; role=blender при доступном телефоне инициирует stopping и даёт телефону отправить очередь; role=android завершает загрузку |
| scan.status | phase, accepted, integrated, pending, error, phone_online, point_revision, mesh_revision, integration_ms, mesh_ms |
| frame.meta | Метаданные внутри бинарного пакета |
| mesh.updated | Номер новой ревизии mesh |
| scan.export | session, format (ply или glb); выполняется на остановленном сеансе после очереди |
| connection.heartbeat | role=android обновляет индикатор присутствия телефона |

## Бинарный пакет и надёжность

Тело до 8 МиБ. Порядок байтов по сети:

1. ASCII `H3S1`.
2. uint32 little-endian, длина JSON UTF-8 до 16 КиБ.
3. JSON `frame.meta`. Поля `type`, `version`, `session`, `id` (возрастает на телефоне), `tracking` (TRACKING), `timestamp_ns`, `rgb_timestamp_ns`, `depth_timestamp_ns`, `confidence_timestamp_ns` (монотонное время ARCore в наносекундах), `coordinates: "arcore_c2w_row_major_m"`, `camera_to_world` (16 чисел), `width`, `height`, `rgb_width`, `rgb_height`, `intrinsics`, `rgb_intrinsics` (оба массива [fx,fy,cx,cy]), `depth_to_rgb` (два ряда affine [a,b,c,d,e,f], вход [u,v,1]).
4. `width × height` значений глубины uint16 little-endian (миллиметры Z, 0 = нет измерения).
5. `width × height` байтов confidence (0–255).
6. Один JPEG RGB без дополнительного length prefix, размер из заголовка изображения сверяется с метаданными.

Корректный ответ `{"ack":id,"duplicate":false}` или с duplicate true при тех же байтах. Повтор того же ID с другим SHA-256 даёт 409. Ответ 429 означает, что слот загрузки или очередь реконструкции заняты. Телефон повторяет передачу с backoff, пока кадр сохранён локально. Ответ 507 означает лимит или ошибку записи. Сервер ограничен одним активным сеансом и восемью кадрами в очереди, плюс один обрабатываемый. Обе стороны ограничивают отдельный сеанс 5000 кадрами и 2 ГиБ. Новое подключение после сбоя считывает сохранённые кадры с ПК и настраивает продолжение по ACK. Для воспроизводимости отправляйте ID и timestamps в порядке захвата. ACK подтверждает запись на ПК, пригодность кадра и число интеграций отражаются в scan.status.
