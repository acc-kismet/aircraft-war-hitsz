# Aircraft War 接口对接简版说明

本文档用于给 Java 客户端和 Go 后端快速对接使用。

正式语义说明以以下两份文件为准：

1. `proto/aircraft_war.proto`
2. `docs/superpowers/specs/2026-05-01-aircraft-war-protocol-design.md`

本文档更偏向接口表和时序说明，方便双方直接开始开发。

## 1. 基本约定

### 1.1 传输方式

- HTTP 负责低频控制与查询
- WebSocket 负责对战期实时消息
- 所有消息体使用 Protobuf 二进制
- 不使用 gRPC

### 1.2 身份约定

- 玩家唯一标识为 `username`
- 同一个房间内玩家数量固定为 2
- 房主由创建房间的玩家担任

### 1.3 排行榜约定

- 全服排行榜
- 按 `best_score` 降序排序
- 玩家被服务端判定掉线时，以当时服务端累计分数参与排行榜更新

## 2. HTTP 接口表

### 2.1 创建房间

- 方法：`POST`
- 路径：`/rooms/create`
- 请求：`CreateRoomRequest`
- 响应：`CreateRoomResponse`

请求字段：

- `username`

响应重点：

- `room.room_id`
- `room.status`
- `self.username`
- `self.is_host = true`

### 2.2 加入房间

- 方法：`POST`
- 路径：`/rooms/join`
- 请求：`JoinRoomRequest`
- 响应：`JoinRoomResponse`

请求字段：

- `room_id`
- `username`

响应重点：

- 房间成员列表变为 2 人
- 房间状态通常进入 `ROOM_STATUS_FULL`

### 2.3 玩家准备

- 方法：`POST`
- 路径：`/rooms/ready`
- 请求：`ReadyRoomRequest`
- 响应：`ReadyRoomResponse`

请求字段：

- `room_id`
- `username`

响应重点：

- 返回最新 `room`
- 当双方都准备完成后，房间状态进入 `ROOM_STATUS_READY`

### 2.4 房主开始游戏

- 方法：`POST`
- 路径：`/rooms/start`
- 请求：`StartGameRequest`
- 响应：`StartGameResponse`

请求字段：

- `room_id`
- `username`

开始条件：

- 只有房主可调用
- 房间内 2 名玩家都已准备

响应重点：

- `started = true`
- `room.status = ROOM_STATUS_PLAYING`

### 2.5 查询房间最终结算

- 方法：`POST`
- 路径：`/rooms/result`
- 请求：`GetRoomResultRequest`
- 响应：`GetRoomResultResponse`

请求字段：

- `room_id`
- `username`

响应重点：

- `result.room_id`
- `result.player_a_username`
- `result.player_b_username`
- `result.player_a_score`
- `result.player_b_score`
- `result.winner_username`
- `result.self_result`
- `result.player_a_finish_reason`
- `result.player_b_finish_reason`

### 2.6 查询全服排行榜

- 方法：`POST`
- 路径：`/leaderboard`
- 请求：`GetLeaderboardRequest`
- 响应：`GetLeaderboardResponse`

请求字段：

- `limit`
- `offset`

响应重点：

- `entries[].username`
- `entries[].best_score`
- `entries[].win_count`
- `entries[].game_count`

### 2.7 查询房间当前状态

- 方法：`POST`
- 路径：`/rooms/state`
- 请求：`GetRoomStateRequest`
- 响应：`GetRoomStateResponse`

用途：

- 掉线恢复后确认自己是否已被服务端判定结束
- 结束界面或恢复页面查询房间当前分数和对手状态

请求字段：

- `room_id`
- `username`

响应重点：

- `room`
- `scores`
- `room_finished`
- `result`

## 3. WebSocket 消息表

建议连接方式：

- `GET /ws?room_id=<room_id>&username=<username>`

传输约定：

- 一条 WebSocket binary frame 对应一个 `WsMessage`
- 客户端与服务端都通过 `WsMessage` 传输具体 WS 业务消息
- 业务侧不要直接发送裸 `PlayerHeartbeatEvent`、`PlayerDefeatEvent`、`PlayerGameOverEvent`、`ScoreBroadcast` 或 `GameFinishedBroadcast`

### 3.1 客户端 -> 服务端

客户端发送时，将以下消息分别放入 `WsMessage.payload`：

- `player_heartbeat_event`
- `player_defeat_event`
- `player_game_over_event`

#### PlayerHeartbeatEvent

用途：

- 对战期保活
- 服务端用于掉线判定

发送建议：

- 每 3 秒发送一次

关键字段：

- `room_id`
- `username`
- `client_sent_at`
- `sequence`

#### PlayerDefeatEvent

用途：

- 玩家本地击败敌机后上报

关键字段：

- `room_id`
- `username`
- `enemy_type`
- `score_delta`
- `client_event_id`

说明：

- 后端应以 `enemy_type` 为准计算得分
- `score_delta` 只用于日志和调试

#### PlayerGameOverEvent

用途：

- 玩家主动上报本地游戏结束

关键字段：

- `room_id`
- `username`
- `final_score`
- `reason`

### 3.2 服务端 -> 客户端

服务端广播时，将以下消息分别放入 `WsMessage.payload`：

- `score_broadcast`
- `game_finished_broadcast`

#### ScoreBroadcast

用途：

- 广播房间当前双方分数与结束状态

关键字段：

- `room_id`
- `scores[]`
- `updated_at`

`scores[]` 中每一项重点看：

- `username`
- `score`
- `finished`
- `status`
- `finish_reason`

说明：

- 即使一名玩家已掉线结束，只要房间未结束，另一名玩家继续游戏时仍要继续广播

#### GameFinishedBroadcast

用途：

- 房间最终结束后广播最终完成事件

关键字段：

- `room_id`
- `finished`
- `result`

## 4. 关键业务时序

### 4.1 正常开局时序

1. A 调 `POST /rooms/create`
2. B 调 `POST /rooms/join`
3. A/B 分别调 `POST /rooms/ready`
4. A 调 `POST /rooms/start`
5. A/B 建立 WebSocket 连接
6. A/B 开始周期性发送承载 `PlayerHeartbeatEvent` 的 `WsMessage`
7. 对战开始

### 4.2 正常对战时序

1. 客户端本地判定击败敌机
2. 客户端发送承载 `PlayerDefeatEvent` 的 `WsMessage`
3. 服务端校验房间和玩家
4. 服务端按 `enemy_type` 累加分数
5. 服务端广播承载 `ScoreBroadcast` 的 `WsMessage`
6. 双方客户端更新比分显示

### 4.3 单个玩家主动结束时序

1. 玩家发送承载 `PlayerGameOverEvent` 的 `WsMessage`
2. 服务端将该玩家标记为 `finished`
3. 服务端继续向房间广播承载 `ScoreBroadcast` 的 `WsMessage`
4. 若另一名玩家仍在游戏中，则继续游戏
5. 当另一名玩家也结束后，服务端广播承载 `GameFinishedBroadcast` 的 `WsMessage`

### 4.4 单个玩家掉线时序

1. 客户端未按时发送心跳
2. 服务端 9 秒未收到心跳，进入超时检测
3. 服务端再给 3 秒复检窗口
4. 若仍未恢复，则将该玩家标记为掉线结束
5. 服务端冻结该玩家当前分数
6. 服务端将该玩家本局分数纳入排行榜统计逻辑
7. 服务端继续广播承载 `ScoreBroadcast` 的 `WsMessage`
8. 另一名玩家继续游戏

### 4.5 掉线后恢复网络时序

1. 掉线玩家恢复网络
2. 客户端调用 `POST /rooms/state`
3. 服务端返回当前房间状态和双方分数状态
4. 若该玩家已被判定结束，则客户端直接进入结束界面
5. 若客户端恢复 WebSocket，则仍可继续接收承载 `ScoreBroadcast` 的 `WsMessage`
6. 当房间最终结束时，客户端可收到承载 `GameFinishedBroadcast` 的 `WsMessage` 或再次调用 `POST /rooms/result`

## 5. 客户端重点实现项

- Protobuf 请求响应编解码
- WebSocket `WsMessage` 二进制消息收发
- 心跳定时发送
- 掉线恢复后的 `POST /rooms/state` 查询
- 结束界面展示“自己已结束，对手仍在游戏中”
- 根据 `WsMessage.score_broadcast.scores[]` 展示双方分数和结束状态

## 6. 后端重点实现项

- 房间状态机
- 心跳超时检测和复检窗口
- 玩家级结束状态与结束原因维护
- 服务端分数权威累计
- 掉线时排行榜统计更新
- `POST /rooms/state` 状态恢复接口
- `WsMessage` 包裹下的 `ScoreBroadcast` 和 `GameFinishedBroadcast` 推送
