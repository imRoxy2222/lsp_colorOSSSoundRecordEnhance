# 录音 record_type 类型描述

> 依据：ColorOS 录音机 17.8.8 反编译源码 `com.soundrecorder.common.constant.RecordModeConstant`。
> 该常量类同时定义了 `ALL_RECORD_TYPE_LIST = [0, 1, 2, 3, 5, 6, 7]`（注意：**没有 4**，
> 4 是分组视图的 bucket 值 `BUCKET_VALUE_CALL`，不是 record_type）。

## record_type 取值表

| record_type | 常量名                | 含义         | 录音文件相对目录                          | 说明                                             |
| ----------- | --------------------- | ------------ | ----------------------------------------- | ------------------------------------------------ |
| 0           | `RECORD_TYPE_STANDARD`  | 标准录音   | `Music/Recordings/Standard Recordings/`   | 用户在 App 内手动录的普通录音                     |
| 1           | `RECORD_TYPE_CONFERENCE` | 会议录音   | `Music/Recordings/Meeting Recordings/`    | 会议模式录音                                     |
| 2           | `RECORD_TYPE_INTERVIEW`  | 采访录音   | `Music/Recordings/Interview Recordings/`  | 采访模式录音（双声道分离人声）                    |
| 3           | `RECORD_TYPE_CALL`       | 通话录音   | `Music/Recordings/Call Recordings/`       | 系统电话/VoIP（QQ、微信等）通话录音               |
| 4           | —（无此类型）          | —            | —                                         | 不是 record_type；`BUCKET_VALUE_CALL = 4` 是列表分组 id |
| 5           | `RECORD_TYPE_OTHER`      | 其他录音   | `Music/Recordings/` 根目录                | 无法归类到上述类型的导入/历史录音                 |
| 6           | `RECORD_TYPE_OPPO_SHARE` | 互传录音   | （OShare 互传接收目录）                   | 通过 OPPO 互传从其他设备接收的录音                |
| 7           | `RECORD_TYPE_WATCH`      | 手表录音   | `Music/Recordings/Watch Recordings/`      | 从 OPPO Watch 同步过来的录音                      |

## 相关概念区分

- **record_type**（`records.record_type` 列）：录音的业务类型，上表 7 种；
- **bucket**（`BUCKET_VALUE_*`）：列表页的分组 id，与 record_type 是两套编号
  （如通话分组是 4，通话录音 type 是 3）；
- **is_recycle**（`records.is_recycle` 列）：1 表示在回收站，扫描时应排除；
- **转写/摘要支持**：`ConvertSupportManager.isSupportConvert` 只做设备能力检查，
  不限制 record_type —— 全部类型都能走 `startConvertTask` 转文本与 AI 摘要管道。

## 本模块的扫描行为

录音列表菜单"转文本 / 转全部"扫描时使用查询条件：

```sql
record_type IN (0, 1, 2, 3, 5, 6, 7) AND is_recycle = 0
```

即**全部类型、排除回收站**，按 `_id DESC`（最新优先）遍历，跳过已转完的记录。
对应代码：`ScanButtonInjector.onScanClicked`（常量 `SQL_ALL_RECORD_TYPES`）。
