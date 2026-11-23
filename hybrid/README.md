# Hybrid 3D Scanner 0.1.0

Экспериментальный RGB-D-сканер для Android, Windows и Blender. Телефон получает RGB, Raw Depth, confidence и положение камеры через ARCore. Движок на ПК хранит кадры и строит TSDF. Android и Blender показывают накопленное облако и периодически обновляемую поверхность.

Начните с [установки](docs/INSTALLATION.md). Совместная установка с Phone Rotation проверена на уровне идентификаторов и регистрации аддонов. Исходный репозиторий содержит релизный APK Phone Rotation без Android-проекта, поэтому Scanner выпускается отдельным APK.

- [Аудит Phone Rotation](docs/AUDIT.md)
- [Архитектура и координаты](docs/ARCHITECTURE.md)
- [Протокол H3S/1](docs/PROTOCOL.md)
- [Проверки и аппаратный чек-лист](docs/TESTING.md)
- [Ограничения](docs/LIMITATIONS.md)
- [Лицензии и приватность](docs/THIRD_PARTY_LICENSES.md)
