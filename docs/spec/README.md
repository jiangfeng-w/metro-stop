# spec 目录约定与状态规范

> **本文件只讲规则，不列需求清单。** 需求状态速览表在 `docs/README.md`（唯一清单）。

## 一、目录结构

```
docs/spec/
├── README.md            本文件：命名 / 状态 / frontmatter / 搬家规则
├── 需求与方案.md         跨期总纲（整个项目一份，不属于单个需求）
├── active/              开发中：规划中 + 待执行 + 开发中（三态用 frontmatter 区分）
│   └── <需求-slug>/
│       ├── 需求.md        主需求文档（必须）
│       ├── <子文档>.md    设计 / 规格 / 验收等（按需）
│       └── assets/       图、示例数据等小体积资源（按需）
├── done/                已完成：验收通过后整体搬入，内容冻结
│   └── <需求-slug>/
└── suspended/           挂起：做到一半因别的事中断的需求
    └── <需求-slug>/
```

## 二、命名

- 目录用**英文小写 slug**，连字符分隔，**不带序号**：`mvp-stop-counter`、`v2-habit-auto-start`；
- 文档文件名用**中文**：`需求.md`、`状态机规格.md`；
- 一个目录 = 一个需求；跨期内容写在根 `需求与方案.md`，不在需求目录里重复。

## 三、状态定义与流转

`planned`（规划中）→ `ready`（待执行）→ `in-progress`（开发中）→ `done`（已完成）；任意阶段可转 `suspended`（挂起），恢复后回到原状态。

| 状态 | 含义 | 所在目录 | 触发条件 |
|---|---|---|---|
| `planned` | 已讨论、未细化 | `active/` | 从总纲「后续优化」立项 |
| `ready` | 需求写清、可开工 | `active/` | 主需求文档完成 |
| `in-progress` | 正在开发 | `active/` | 开始写代码 |
| `done` | 已验收 | `done/` | 验收标准全部通过 |
| `suspended` | 中途搁置 | `suspended/` | 明确记录搁置原因与恢复条件 |

## 四、frontmatter 模板

```yaml
---
title: MVP 核心数站提醒
status: ready
created: 2026-09-25
updated: 2026-09-25
---
```

（不再使用 `id` 序号字段。）文末保留「变更记录」表；`done/` 内的文档视为冻结，只允许追加变更记录。

## 五、状态变更操作（三处联动）

1. `git mv docs/spec/<旧目录> docs/spec/<新状态目录>/`（`active` / `done` / `suspended` 之间互转）；
2. 改主需求文档 frontmatter 的 `status` 与 `updated`；
3. 改 `docs/README.md` 速览表对应那一行。

**三处不一致即视为文档事故。**

## 六、不放进 spec 的东西

- 实测 CSV、大体积数据 / 二进制 → 代码仓库 `app/src/test/resources/replay/`（回放用例）或运行期目录；
- 构建 / 调试细节 → `docs/development.md`；
- 需求清单 → `docs/README.md`（本文件不维护清单）。
