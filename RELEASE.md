<!-- GitHub Release 正文。标题填：WPAForge 1.9.27 · 已知可打名单标记 + WPS 通道诊断 -->
<!-- tag: v1.9.27   target: main -->

## WPS 目标

- 目标行新增「已知可打（Pixie）」标记：比对 OneShot 维护的 vulnwsc 名单
  （按需下载，约 3.5 KB，不打包进 APK）。
- 名单里写明的默认 PIN 会一起带出来（如 Tenda 123456）。
- WPS 页新增通道诊断：读不到设备名与能力时，直接说明是系统策略拦了回包，
  不再静默显示「能力未知」。

## 匹配口径

- 按设备自报的 WPS 设备名比对；型号在名单但固件版本不同时标「可打?」并注明版本不同。
- 裸泛称设备名（Wireless Router / Wireless Access Point / ADSL Router 等）不再算命中 ——
  这类名字会被长条目反向包含，实测 6/6 全是误标。
- 名单源许可不明，因此不打包进安装包，按需下载并在界面标注来源。

## 说明

- 部分系统（实测 ColorOS 15 / Android 15）的 SELinux 不允许 supplicant 向 root 域回包，
  需要一条放行规则才能读到 WPS 能力与设备名；见 README「WPS 通道」。
- 本版未改判定主链，破解与判定行为与 1.9.26 一致。
