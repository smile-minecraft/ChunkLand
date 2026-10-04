[English](../../en/reference/) · [繁體中文](../../zh-TW/reference/) · 简体中文

← [文档索引](../README.md) · [回到项目说明](../../../README.zh-CN.md)

# 参考索引

四份查表用的文件，每一份都是可以直接扫过去的表格，不需要从头读。

| 文件 | 你要查的是 |
| --- | --- |
| [命令参考](commands.md) | `/land` 与 `/chunkland` 每个子命令的完整用法、需要哪个权限节点、能不能从主控台执行，以及玩家名称怎么解析 |
| [配置参考](configuration.md) | `config.yml` 每个键的作用、默认值、接受范围，以及哪些设置还没接上功能 |
| [权限参考](permissions.md) | 29 个权限节点各自把什么关，以及要开哪几个给普通玩家 |
| [API 参考](api.md) | `ChunkLandApi`、事件总线、领域类型，以及哪两个方法还没接到正式数据 |

要的是操作流程而不是查表，回指南：

| 你的情况 | 读这一份 |
| --- | --- |
| 圈地、管自己的领地 | [玩家指南](../user-guide.md) |
| 安装、配置、备份、修问题 | [服务器管理指南](../server-guide.md) |
| 改 ChunkLand 本身 | [开发者指南](../developer-guide.md) |
| 想知道哪里还有问题 | [已知限制](../limitations.md) |

命令与权限以 `plugin.yml` 和 `lang/en_US.yml` 里的 `usage` 字符串为准，配置以 `config.yml` 为准，实际行为看 `command/` 下的 handler 与 `runtime/api/ChunkLandReadApi.java`。