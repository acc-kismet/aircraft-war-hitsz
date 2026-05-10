# Aircraft War 多人联机协议设计

## 1. 文档范围

本文档定义 Aircraft War 项目第一版多人联机协议约定。

目标是为以下开发提供统一的协议基线：

- Java 客户端开发
- Go 后端开发
- Protobuf 消息生成与二进制序列化
- HTTP 与 WebSocket 数据交换

本版本只覆盖双人对战的最小业务闭环。

## 2. 功能边界

### 包含内容

- 仅支持双人房间
- 使用用户名作为玩家标识
- 创建房间与加入房间流程
- 准备流程
- 房主开始游戏流程
- 对战过程中敌机击败事件上报
- 对战期间心跳检测与掉线判定
- 掉线后的房间状态恢复查询
- 服务端累计分数
- WebSocket 广播比分
- 玩家游戏结束上报
- 对局结果生成
- 全服排行榜查询
- SQLite 持久化

### 不包含内容

- 反作弊
- 对局中途退出处理
- 完整游戏状态同步
- 敌机移动同步
- 道具同步
- 超过两人的房间
- 跨房间匹配

## 3. 架构决策

系统只使用 Protobuf 做数据模型定义和二进制序列化。

系统不使用 gRPC。

传输职责划分如下：

- HTTP 负责低频控制与查询请求
- WebSocket 负责对战期实时事件上报与服务端广播

`.proto` 文件只定义 `enum` 和 `message`，不定义 `service`。

## 4. 权责模型

### 客户端职责

- 运行本地单机游戏逻辑
- 在本地判定敌机被击败
- 通过 WebSocket 向后端上报击败事件
- 在对战期通过 WebSocket 周期性发送心跳
- 渲染服务端广播的比分更新
- 通过 WebSocket 上报本地游戏结束事件
- 在网络恢复后查询房间状态，并在需要时跳转到结束界面

### 服务端职责

- 管理房间生命周期
- 记录房间成员与房主
- 记录准备状态
- 校验房间是否允许开始
- 维护多人对战分数的权威状态
- 记录心跳时间并判定掉线
- 广播房间比分更新
- 判定房间何时结束
- 使用 SQLite 持久化对局结果
- 维护全服排行榜数据

服务端对房间状态、比分状态、结算状态具有最终权威。

客户端只对本地战斗执行和本地敌机击败判定负责。

## 5. 身份模型

玩家使用 `username` 标识。

本版本默认满足以下前提：

- `username` 全局唯一
- `username` 在一局对战过程中保持稳定
- 排行榜以 `username` 为主键进行聚合

如果未来允许重名，则必须引入独立的 `user_id`，并扩展所有相关协议消息。

## 6. 业务闭环

本版本的最小完整闭环如下：

1. 玩家 A 创建房间
2. 玩家 B 加入房间
3. 双方分别点击准备
4. 房主开始游戏
5. 房间进入 `PLAYING`
6. 某一名玩家在本地击败敌机
7. 客户端通过 WebSocket 上报一次击败事件
8. 服务端校验房间与玩家是否合法
9. 服务端根据敌机类型计算本次得分增量
10. 服务端更新该玩家在本房间中的分数
11. 服务端向房间内两名玩家广播最新比分状态
12. 某一名玩家本地游戏结束，通过 WebSocket 上报结束事件
13. 若某一名玩家心跳超时并在复检后仍未恢复，服务端将该玩家标记为掉线结束
14. 掉线玩家当前分数冻结，并写入排行榜统计逻辑
15. 掉线玩家网络恢复后，通过 HTTP 查询房间状态，若自己已结束则进入结束界面
16. 若另一名玩家仍在游戏中，服务端继续广播比分变化
17. 当两名玩家都结束后，服务端生成本局结算结果
18. 服务端将本局结果写入 SQLite
19. 客户端通过 HTTP 查询本局结果
20. 客户端通过 HTTP 查询全服排行榜

## 7. 房间与玩家状态模型

### RoomStatus

- `ROOM_STATUS_UNSPECIFIED`
- `ROOM_STATUS_WAITING`
- `ROOM_STATUS_FULL`
- `ROOM_STATUS_READY`
- `ROOM_STATUS_PLAYING`
- `ROOM_STATUS_FINISHED`

### PlayerStatus

- `PLAYER_STATUS_UNSPECIFIED`
- `PLAYER_STATUS_JOINED`
- `PLAYER_STATUS_READY`
- `PLAYER_STATUS_PLAYING`
- `PLAYER_STATUS_FINISHED`

### 状态规则

- 新创建的房间状态为 `ROOM_STATUS_WAITING`
- 第二名玩家加入后，房间状态变为 `ROOM_STATUS_FULL`
- 两名玩家都准备完成后，房间状态变为 `ROOM_STATUS_READY`
- 房主开始游戏后，房间状态变为 `ROOM_STATUS_PLAYING`
- 单个玩家可以因为主动结束或掉线确认而进入 `PLAYER_STATUS_FINISHED`
- 只有当两名玩家都进入 `PLAYER_STATUS_FINISHED` 时，房间状态才变为 `ROOM_STATUS_FINISHED`

## 8. 心跳与掉线规则

### 心跳参数

- 客户端每 3 秒发送一次心跳
- 9 秒未收到心跳视为首次超时
- 超时后再给 3 秒复检窗口
- 超过 12 秒仍未恢复则确认掉线

### 掉线判定规则

- 若单个玩家被确认掉线，则该玩家本局立即结束
- 该玩家之后不能再继续提交击败事件
- 该玩家当前服务端累计分数被冻结
- 服务端应立即将该玩家本局分数写入排行榜统计逻辑
- 另一名玩家继续正常游戏，直到主动结束或也被判掉线
- 当两名玩家都结束后，房间进入最终完成态

### 网络恢复后的处理

- 掉线玩家恢复网络后，不能恢复继续作战
- 客户端应调用房间状态查询接口确认自己是否已结束
- 若自己已结束，则客户端直接进入游戏结束界面
- 该结束界面应能看到自己的最终分数
- 若对手仍在游戏中，客户端恢复 WebSocket 后仍可继续收到比分广播，观察对方后续分数变化

### 埋点建议

建议客户端和服务端都记录以下关键时间点或事件：

- 心跳发送时间
- 最后一次成功心跳时间
- 首次超时检测时间
- 复检开始时间
- 确认掉线时间
- 掉线时冻结分数
- 网络恢复后的房间状态查询时间
- 查询结果是否为“自己已结束、对手仍在游戏中”

## 9. 计分规则

客户端可以在击败事件中同时上传 `enemy_type` 和 `score_delta`。

后端必须以 `enemy_type` 作为权威计分输入。

`score_delta` 只作为调试或日志辅助字段，不能作为最终计分依据。

### 建议的敌机类型

- `ENEMY_TYPE_UNSPECIFIED`
- `ENEMY_TYPE_MOB`
- `ENEMY_TYPE_ELITE`
- `ENEMY_TYPE_BOSS`

### 建议的初始分值表

- `ENEMY_TYPE_MOB` = 10
- `ENEMY_TYPE_ELITE` = 20
- `ENEMY_TYPE_BOSS` = 50

如果项目内已经有既定分值规则，则后端实现应沿用既有规则，但协议层仍保持当前敌机类型定义。

## 10. 对局结果规则

当两名玩家都进入结束状态后：

- 服务端比较双方最终分数
- 分数高者获胜
- 分数低者失败
- 分数相同则为平局

结束状态的来源包括：

- 玩家主动上报 `PlayerGameOverEvent`
- 服务端通过心跳超时和复检确认该玩家掉线

建议结果枚举如下：

- `GAME_RESULT_UNSPECIFIED`
- `GAME_RESULT_WIN`
- `GAME_RESULT_LOSE`
- `GAME_RESULT_DRAW`

## 11. 排行榜规则

排行榜是全服排行榜，不是房间内排行榜。

本版本排行榜规则固定为：

- 按 `best_score` 降序排序

建议同分排序规则如下：

1. `best_score` 降序
2. `updated_at` 升序，也就是更早达到该成绩的玩家排在前面
3. `username` 升序

排行榜条目至少暴露以下字段：

- `username`
- `best_score`
- `win_count`
- `game_count`
- `updated_at`

补充约束：

- 玩家因掉线被服务端判定结束后，应以当时服务端累计分数参与排行榜更新
- 后续即使网络恢复，也不能继续提升该玩家本局分数

## 12. HTTP 协议约定

所有 HTTP 请求体与响应体都使用 Protobuf 二进制载荷。

建议的 `Content-Type` 为：

- `application/x-protobuf`

### 11.1 创建房间

- 接口：`POST /rooms/create`
- 请求消息：`CreateRoomRequest`
- 响应消息：`CreateRoomResponse`

请求字段：

- `username`

响应字段：

- `room`
- `self`

### 11.2 加入房间

- 接口：`POST /rooms/join`
- 请求消息：`JoinRoomRequest`
- 响应消息：`JoinRoomResponse`

请求字段：

- `room_id`
- `username`

响应字段：

- `room`
- `self`

### 11.3 玩家准备

- 接口：`POST /rooms/ready`
- 请求消息：`ReadyRoomRequest`
- 响应消息：`ReadyRoomResponse`

请求字段：

- `room_id`
- `username`

响应字段：

- `room`

### 11.4 开始游戏

- 接口：`POST /rooms/start`
- 请求消息：`StartGameRequest`
- 响应消息：`StartGameResponse`

请求字段：

- `room_id`
- `username`

规则约束：

- 只有房主可以开始
- 两名玩家都必须已经准备完成

响应字段：

- `room`
- `started`

### 11.5 查询房间结算结果

- 接口：`POST /rooms/result`
- 请求消息：`GetRoomResultRequest`
- 响应消息：`GetRoomResultResponse`

请求字段：

- `room_id`
- `username`

响应字段：

- `result`

### 11.6 查询排行榜

- 接口：`POST /leaderboard`
- 请求消息：`GetLeaderboardRequest`
- 响应消息：`GetLeaderboardResponse`

请求字段：

- `limit`
- `offset`

响应字段：

- 重复字段 `entries`

### 12.7 查询房间当前状态

- 接口：`POST /rooms/state`
- 请求消息：`GetRoomStateRequest`
- 响应消息：`GetRoomStateResponse`

用途：

- 供网络恢复后的客户端查询当前房间状态
- 供已掉线玩家确认自己是否已被服务端判定结束

请求字段：

- `room_id`
- `username`

响应字段：

- `room`
- 当前双方分数列表 `scores`
- `room_finished`
- 若房间已完成则返回 `result`

## 13. WebSocket 协议约定

WebSocket 用于玩家进入多人对战之后的实时消息交换。

建议连接形式：

- `GET /ws?room_id=<room_id>&username=<username>`

后端应基于该连接绑定目标房间与玩家身份。

传输约定：

- 一条 WebSocket binary frame 对应一个 `WsMessage`
- 客户端与服务端都只传输 `WsMessage` 的 Protobuf 二进制
- 具体业务消息通过 `WsMessage.payload` 中的 `oneof` 承载
- HTTP 请求响应继续直接使用各自的消息体，不使用 `WsMessage`

### 客户端到服务端消息

客户端发送时，应将以下消息包入 `WsMessage`：

- `WsMessage.player_heartbeat_event`
- `WsMessage.player_defeat_event`
- `WsMessage.player_game_over_event`

#### 13.1 PlayerDefeatEvent

用途：

- 上报玩家在本地击败了一个敌机

必填字段：

- `room_id`
- `username`
- `enemy_type`
- `client_event_id`

可选字段：

- `score_delta`

`client_event_id` 用于基础去重和问题追踪。

#### 13.2 PlayerHeartbeatEvent

用途：

- 在对战期维持在线状态
- 供服务端判断是否发生超时与掉线

字段：

- `room_id`
- `username`
- `client_sent_at`
- `sequence`

建议：

- 客户端每 3 秒发送一次
- `sequence` 可用于客户端与服务端排查丢包、乱序和网络抖动问题

#### 13.3 PlayerGameOverEvent

用途：

- 上报玩家本地战斗已经结束

必填字段：

- `room_id`
- `username`

可选字段：

- `final_score`
- `reason`

后端应以房间内维护的分数状态为准，`final_score` 仅作辅助参考。

### 服务端到客户端消息

服务端广播时，应将以下消息包入 `WsMessage`：

- `WsMessage.score_broadcast`
- `WsMessage.game_finished_broadcast`

#### 13.4 ScoreBroadcast

用途：

- 向两名玩家广播当前房间最新比分状态

必填字段：

- `room_id`
- 重复字段形式的双方分数列表
- `updated_at`

补充要求：

- 每个玩家分数项应能够表达该玩家是否已结束
- 每个玩家分数项应能够表达结束原因，例如正常结束或掉线结束
- 即使只有一名玩家已结束，只要房间还未完成，仍应继续广播另一名玩家的分数变化

#### 13.5 GameFinishedBroadcast

用途：

- 通知两名玩家该房间对局已经完成结算

必填字段：

- `room_id`
- `finished`

可选字段：

- 简要结算结果快照

## 14. Protobuf 消息集合

`.proto` 文件至少需要定义以下消息组。

### 基础消息

- `Player`
- `Room`
- `RoomPlayerScore`
- `RoomResult`
- `LeaderboardEntry`

### HTTP 请求与响应消息

- `CreateRoomRequest`
- `CreateRoomResponse`
- `JoinRoomRequest`
- `JoinRoomResponse`
- `ReadyRoomRequest`
- `ReadyRoomResponse`
- `StartGameRequest`
- `StartGameResponse`
- `GetRoomResultRequest`
- `GetRoomResultResponse`
- `GetRoomStateRequest`
- `GetRoomStateResponse`
- `GetLeaderboardRequest`
- `GetLeaderboardResponse`

### WebSocket 消息

- `PlayerDefeatEvent`
- `PlayerHeartbeatEvent`
- `PlayerGameOverEvent`
- `ScoreBroadcast`
- `GameFinishedBroadcast`
- `WsMessage`

### 枚举类型

- `RoomStatus`
- `PlayerStatus`
- `EnemyType`
- `GameResultType`
- `PlayerFinishReason`

## 15. SQLite 持久化模型

### 14.1 对局结果表

建议字段：

- `room_id`
- `player_a_username`
- `player_b_username`
- `player_a_score`
- `player_b_score`
- `winner_username`
- `player_a_finish_reason`
- `player_b_finish_reason`
- `created_at`
- `finished_at`

### 14.2 排行榜表

建议字段：

- `username`
- `best_score`
- `win_count`
- `game_count`
- `updated_at`

## 16. 错误处理建议

协议层可以有两种处理方式：

- 为每个响应统一增加 `success/code/message` 包装字段
- 使用 HTTP 状态码表示错误，成功时返回对应的 Protobuf 响应体

为了保持第一版足够简单，建议采用第二种方式：

- HTTP 状态码表示传输或业务失败
- 请求成功时返回对应的强类型 Protobuf 响应体

示例：

- `400` 请求字段非法
- `404` 房间不存在
- `409` 房间状态非法，例如双方未准备完毕就尝试开始
- `410` 房间已经结束

对于掉线恢复后的状态查询，还可能出现：

- `403` 玩家不属于该房间
- `410` 玩家已被判定结束，客户端应进入结束界面

如果未来需要在 Protobuf 响应体内部表达统一的跨平台业务错误，可以再新增共享的 `ErrorInfo` 消息。

## 17. 测试预期

客户端与后端至少需要验证以下内容：

- 创建房间与加入房间流程
- 双方准备流程
- 开始游戏的门禁校验
- 击败事件上报与分数累计
- 心跳发送与超时检测
- 超时后的复检窗口处理
- 单个玩家掉线后分数冻结
- 单个玩家掉线后另一名玩家继续游戏
- 掉线玩家网络恢复后的房间状态查询
- 比分广播正确性
- 双方都结束后才能结算的条件
- 结果持久化
- 排行榜排序正确性

## 18. 第一版交付物

第一版需要两个共享产物：

1. `proto/aircraft_war.proto`
2. 本协议设计 Markdown 文档

Markdown 文档是传输方式和业务语义的事实依据。

`.proto` 文件是序列化消息结构的事实依据。
