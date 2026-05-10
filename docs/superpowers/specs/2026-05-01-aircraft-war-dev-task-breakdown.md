# Aircraft War 客户端与后端开发任务拆分清单

本文档用于给客户端和后端分别拆分开发工作。

协议与业务语义以以下文件为准：

1. `proto/aircraft_war.proto`
2. `docs/superpowers/specs/2026-05-01-aircraft-war-protocol-design.md`
3. `docs/superpowers/specs/2026-05-01-aircraft-war-api-handoff.md`

## 1. 总体目标

完成双人房间多人对战最小闭环：

1. 创建房间
2. 加入房间
3. 双方准备
4. 房主开始
5. 对战期间通过 WebSocket 上报击败事件
6. 服务端累计分数并广播
7. 心跳检测与掉线判定
8. 掉线后恢复状态查询
9. 双方结束后生成结算
10. 查询全服排行榜

## 2. 客户端开发任务

### 2.1 协议接入

- 使用 `aircraft_war.proto` 生成 Java 代码
- 封装 HTTP Protobuf 请求发送能力
- 封装基于 `WsMessage` 的 WebSocket 二进制消息收发能力
- 建立消息类型与业务处理逻辑的映射

交付结果：

- 客户端可正确发送和解析所有已定义消息

### 2.2 房间前置流程

- 实现创建房间页面逻辑
- 实现加入房间页面逻辑
- 实现准备按钮逻辑
- 实现房主开始按钮逻辑
- 根据返回的 `Room` 状态更新 UI

交付结果：

- 双人房间可完成创建、加入、准备、开始

### 2.3 WebSocket 会话管理

- 进入对战后建立 WebSocket 连接
- 连接参数带上 `room_id` 和 `username`
- 实现连接成功、失败、关闭、重连后的状态更新
- 对战结束后关闭不再需要的连接

交付结果：

- 客户端可在对战中稳定接收和发送二进制消息

### 2.4 心跳机制

- 对战期每 3 秒发送一次 `PlayerHeartbeatEvent`
- 发送时将 `PlayerHeartbeatEvent` 包入 `WsMessage`
- 本地维护 `sequence` 递增
- 心跳发送时记录埋点信息
- 网络异常或连接关闭时触发恢复逻辑

交付结果：

- 后端可以基于客户端心跳做掉线判定

### 2.5 击败事件上报

- 本地判定敌机击败时发送 `PlayerDefeatEvent`
- 发送时将 `PlayerDefeatEvent` 包入 `WsMessage`
- 为每次击败事件生成 `client_event_id`
- 按协议填充 `enemy_type`
- 如有需要可同步填充 `score_delta` 作为调试字段

交付结果：

- 服务端可正确接收并累计分数

### 2.6 比分与状态展示

- 监听 `WsMessage` 并解析其中的 `ScoreBroadcast`
- 根据 `scores[]` 刷新双方分数
- 根据 `finished`、`status`、`finish_reason` 展示玩家状态
- 支持“自己已结束，对方仍在游戏中”的展示文案

交付结果：

- 对战页面和结束页面都能正确显示双方状态

### 2.7 主动结束流程

- 本地游戏结束时发送 `PlayerGameOverEvent`
- 发送时将 `PlayerGameOverEvent` 包入 `WsMessage`
- 发送后将自己标记为已结束
- 若房间未整体结束，则进入等待对手完成的状态展示

交付结果：

- 玩家正常结束时服务端能进入正确状态机

### 2.8 掉线恢复与状态查询

- 网络恢复后调用 `POST /rooms/state`
- 若返回自己已结束，则直接进入结束界面
- 若房间未结束但自己已结束，则继续接收比分广播
- 若房间已结束，则可直接展示结算结果

交付结果：

- 掉线恢复后的用户体验完整

### 2.9 排行榜展示

- 调用 `POST /leaderboard`
- 展示 `username`、`best_score`、`win_count`、`game_count`
- 支持分页参数 `limit`、`offset`

交付结果：

- 全服排行榜页面可用

### 2.10 客户端埋点

- 心跳发送埋点
- 心跳失败埋点
- WebSocket 断开埋点
- 掉线恢复查询埋点
- 进入结束界面原因埋点

交付结果：

- 客户端可以辅助排查网络抖动和掉线问题

## 3. 后端开发任务

### 3.1 Protobuf 协议接入

- 使用 `aircraft_war.proto` 生成 Go 代码
- 封装 HTTP Protobuf 请求解析与响应写回
- 封装基于 `WsMessage` 的 WebSocket Protobuf 二进制消息收发

交付结果：

- 后端具备所有协议消息的收发能力

### 3.2 房间数据模型与状态机

- 设计房间内存结构或服务层结构
- 维护 `room_id`、房主、玩家列表、状态
- 管理 `WAITING -> FULL -> READY -> PLAYING -> FINISHED`
- 管理玩家 `JOINED -> READY -> PLAYING -> FINISHED`

交付结果：

- 房间状态流转完整可控

### 3.3 HTTP 接口实现

- 实现 `POST /rooms/create`
- 实现 `POST /rooms/join`
- 实现 `POST /rooms/ready`
- 实现 `POST /rooms/start`
- 实现 `POST /rooms/result`
- 实现 `POST /rooms/state`
- 实现 `POST /leaderboard`

交付结果：

- 所有前置控制和查询接口可用

### 3.4 WebSocket 会话管理

- 根据 `room_id` 和 `username` 绑定连接
- 维护房间内在线连接集合
- 处理连接建立、断开、异常关闭
- 支持向房间内两名玩家广播消息

交付结果：

- 房间级实时推送链路可用

### 3.5 服务端权威计分

- 接收 `PlayerDefeatEvent`
- 从 `WsMessage` 解包并分发 `PlayerDefeatEvent`
- 校验玩家是否属于房间且仍处于可游戏状态
- 根据 `enemy_type` 计算分值
- 更新房间内玩家分数
- 广播 `ScoreBroadcast`

交付结果：

- 服务端累计分数成为唯一权威来源

### 3.6 心跳超时与掉线判定

- 接收 `PlayerHeartbeatEvent`
- 从 `WsMessage` 解包并分发 `PlayerHeartbeatEvent`
- 记录每个玩家最后一次心跳时间
- 9 秒超时检测
- 3 秒复检窗口
- 超过 12 秒仍无恢复则判定掉线
- 将该玩家标记为 `FINISHED`
- 设置 `finish_reason = PLAYER_FINISH_REASON_DISCONNECTED`

交付结果：

- 掉线判定逻辑稳定可复现

### 3.7 掉线后的分数冻结与排行榜更新

- 玩家掉线确认后冻结当前分数
- 拒绝该玩家后续新的击败事件
- 用该分数更新排行榜统计逻辑
- 若该分数高于历史 `best_score`，则更新排行榜记录

交付结果：

- 掉线玩家成绩能够按协议正确入榜

### 3.8 房间状态恢复接口

- 实现 `POST /rooms/state`
- 返回房间基础状态
- 返回当前双方分数与结束状态
- 房间已结束时直接附带 `RoomResult`

交付结果：

- 客户端掉线恢复后可立即知道自己是否已结束

### 3.9 对局最终结算

- 玩家主动结束或掉线结束都视为玩家进入 finished
- 当两名玩家都 finished 时生成 `RoomResult`
- 计算 `winner_username`
- 计算调用方视角的 `self_result`
- 广播承载 `GameFinishedBroadcast` 的 `WsMessage`

交付结果：

- 对局结算逻辑完整闭环

### 3.10 SQLite 持久化

- 设计对局结果表
- 设计排行榜表
- 在对局完成时写入对局结果
- 在玩家掉线结束或正常结束时更新排行榜统计

交付结果：

- 排行榜和历史结果可持久化查询

### 3.11 后端埋点与日志

- 心跳接收日志
- 超时检测日志
- 复检窗口日志
- 掉线确认日志
- 掉线时冻结分数日志
- 状态恢复查询日志
- 排行榜更新日志

交付结果：

- 后端可定位网络抖动和误判问题

## 4. 联调清单

客户端和后端联调时，至少覆盖以下场景：

1. 正常创建房间和加入房间
2. 双方准备并开始游戏
3. 一方连续击败敌机，双方都能收到比分变化
4. 双方都正常结束，能看到最终结算
5. 一方掉线，服务端 12 秒内完成掉线确认
6. 掉线玩家恢复网络后，能通过 `POST /rooms/state` 看到自己已结束
7. 掉线玩家仍能看到对手继续上涨的分数
8. 对手最终结束后，双方都能看到完整结算
9. 排行榜正确更新最高单局分数

## 5. 建议开发顺序

建议顺序如下：

1. 双方先基于 `aircraft_war.proto` 完成消息编解码
2. 后端先打通房间前置 HTTP 接口
3. 客户端接入房间页面与准备/开始流程
4. 双方打通 WebSocket 建连与比分广播
5. 后端接入服务端权威计分
6. 客户端接入击败事件上报
7. 双方补齐心跳与掉线判定
8. 双方补齐 `POST /rooms/state` 恢复查询
9. 最后联调排行榜和完整结算
