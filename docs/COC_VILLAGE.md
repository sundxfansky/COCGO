# COC 村庄详情

在客户端对应账号的设置启用「导出游戏内 JSON」，运行账号后，在「设备工具 → 村庄详情」查看。已经保存在 `sunFiles/exports` 的国服和国际服导出也可以直接查看。按账号选择快照，可按分类查看或用中英文名称和游戏 ID 搜索。升级和冷却数值显示的是导出时记录的时长。

启用远端同步后，可对当前快照点「发布到远程」。远程配置页面在对应设备的「设备工具 → 村庄详情」读取已发布快照。每个设备、区服和账号保留一份最新发布的快照。发布内容包含玩家标签与页面显示所需的字段。

## 调研和素材

- CoC Base Tracker 的 MIT 项目已有导入、离线 ID 映射和升级计时。客户端解析参考其中的导出字段结构，包括夜世界分组、精制防御及模块。补充图鉴使用其公开的 ID 和部分图片索引。[CoC Base Tracker](https://github.com/MasteR-NY/CoC-Base-Tracker)
- ClashKing Assets 提供按游戏 ID 组织的静态数据、简中名称、建筑等级图及其他图标。项目打包对应的离线缩略图，两端共用。[ClashKing Assets](https://github.com/ClashKingInc/ClashKingAssets)
- `clash-of-clans-data` 提供 `dataId` 和分等级的图片，适合建筑数据对照。[clash-of-clans-data](https://github.com/chiefpansancolt/clash-of-clans-data)

素材版本和署名见 `web/public/coc-village/CREDITS.txt`。维护时安装 Pillow 并运行 `python3 web/scripts/import-coc-catalog.py --cache /tmp/coc-catalog-cache`，生成的索引和缩略图随 Web 前端与 Android 构建一起分发。
