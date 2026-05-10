# Aircraft War 前后端联调关键点

本文档给前端同学使用，用于和当前后端实现快速对齐并开始联调。

当前对齐基线：

1. `proto/aircraft_war.proto`
2. `docs/superpowers/specs/2026-05-01-aircraft-war-protocol-design.md`
3. `docs/superpowers/specs/2026-05-01-aircraft-war-api-handoff.md`
4. `docs/superpowers/specs/2026-05-01-aircraft-war-dev-task-breakdown.md`
5. `docs/superpowers/specs/2026-05-01-aircraft-war-implementation-checklist.md`

## 1. 当前最重要变化

WebSocket 不再直接发送裸 protobuf 业务消息。

现在统一使用：

- `WsMessage`

规则如下：

- 一条 WebSocket binary frame = 一条 `WsMessage`
- 客户端上行统一发送 `WsMessage`
- 服务端下行统一发送 `WsMessage`
- HTTP 请求响应不受影响，仍然直接发送各自的 protobuf 消息体

## 2. 前端必须重新生成的代码

前端需要基于最新的 `proto/aircraft_war.proto` 重新生成 Java 代码。

新增的关键结构：

- `WsMessage`
- `WsMessage.player_heartbeat_event`
- `WsMessage.player_defeat_event`
- `WsMessage.player_game_over_event`
- `WsMessage.score_broadcast`
- `WsMessage.game_finished_broadcast`

## 3. HTTP 对齐点

HTTP 接口没有新增，没有改路径，没有加 wrapper。

仍然是：

1. `POST /rooms/create`
2. `POST /rooms/join`
3. `POST /rooms/ready`
4. `POST /rooms/start`
5. `POST /rooms/result`
6. `POST /rooms/state`
7. `POST /leaderboard`

请求响应体仍直接使用各自对应的 protobuf message。

## 4. WebSocket 上行怎么发

### 4.1 心跳

发送内容：

- `WsMessage.player_heartbeat_event`

字段：

- `room_id`
- `username`
- `client_sent_at`
- `sequence`

要求：

- 每 3 秒发送一次
- `sequence` 必须递增
- `client_sent_at` 建议使用当前毫秒时间戳

### 4.2 击败事件

发送内容：

- `WsMessage.player_defeat_event`

字段：

- `room_id`
- `username`
- `enemy_type`
- `score_delta`
- `client_event_id`

要求：

- `client_event_id` 必须唯一，建议 UUID
- 服务端最终只看 `enemy_type` 计分
- `score_delta` 只用于调试，不参与最终计分

### 4.3 主动结束

发送内容：

- `WsMessage.player_game_over_event`

字段：

- `room_id`
- `username`
- `final_score`
- `reason`

说明：

- `final_score` 只是辅助信息
- 服务端最终以自己的累计分数为准

## 5. WebSocket 下行怎么收

客户端收到二进制帧后，先统一反序列化成 `WsMessage`，再根据 `payload` 分发。

### 5.1 比分广播

服务端发送：

- `WsMessage.score_broadcast`

关键字段：

- `room_id`
- `scores[]`
- `updated_at`

前端重点使用：

- `scores[].username`
- `scores[].score`
- `scores[].finished`
- `scores[].status`
- `scores[].finish_reason`

说明：

- 即使一名玩家已经掉线结束，只要房间未结束，在线玩家继续涨分时，服务端仍会继续广播

### 5.2 最终结算广播

服务端发送：

- `WsMessage.game_finished_broadcast`

关键字段：

- `room_id`
- `finished`
- `result`

前端重点使用：

- `result.winner_username`
- `result.self_result`
- `result.player_a_finish_reason`
- `result.player_b_finish_reason`

## 6. 掉线与恢复的前端处理关键点

### 6.1 掉线规则

- 客户端每 3 秒发送一次心跳
- 服务端 9 秒未收到心跳视为超时
- 服务端再给 3 秒复检窗口
- 超过 12 秒仍未恢复则判定掉线

### 6.2 掉线后端语义

- 掉线玩家本局立即结束
- 掉线玩家当前分数被冻结
- 掉线玩家后续不能继续加分
- 对手继续正常游戏
- 只有双方都结束后房间才整体结束

### 6.3 恢复后的前端动作

恢复网络后建议顺序：

1. 尝试重新建立 WebSocket
2. 立即调用 `POST /rooms/state`
3. 根据返回值决定页面状态

判断规则：

1. 如果自己已经结束
   - 直接进入结束界面
2. 如果 `room_finished = false`
   - 即使自己已结束，也仍可继续接收 `WsMessage.score_broadcast`
   - 用于观察对手后续分数变化
3. 如果 `room_finished = true`
   - 直接展示最终结算

## 7. 前端最少需要改的代码点

1. 重新生成 proto Java 代码
2. WebSocket 发送入口统一改成发送 `WsMessage`
3. WebSocket 接收入口统一改成先解 `WsMessage`
4. 心跳逻辑改成发送 `WsMessage.player_heartbeat_event`
5. 击败逻辑改成发送 `WsMessage.player_defeat_event`
6. 主动结束逻辑改成发送 `WsMessage.player_game_over_event`
7. 比分 UI 改成消费 `WsMessage.score_broadcast`
8. 结算 UI 改成消费 `WsMessage.game_finished_broadcast`
9. 掉线恢复逻辑改成依赖 `POST /rooms/state`

## 8. 推荐联调顺序

1. 前端先重新生成最新 proto 代码
2. 先打通 HTTP 创建、加入、准备、开始
3. 再打通 `WsMessage` 的发送和接收
4. 先联调正常双人对局结算
5. 再联调掉线场景
6. 最后联调排行榜和恢复查询

## 9. 最低联调验收标准

### 9.1 正常双人对局

1. 双方可以创建房间、加入、准备、开始
2. 一方击败敌机后双方都能看到比分更新
3. 双方都主动结束后能收到最终结算
4. `POST /rooms/result` 返回结果正确
5. `POST /leaderboard` 返回顺序正确

### 9.2 掉线场景

1. 一方停止心跳后，服务端约 12 秒内判定掉线
2. 掉线玩家被标记为 `PLAYER_STATUS_FINISHED`
3. 掉线玩家 `finish_reason = PLAYER_FINISH_REASON_DISCONNECTED`
4. 掉线玩家分数被冻结
5. 在线玩家仍可继续涨分
6. 掉线玩家恢复后，`POST /rooms/state` 可看到自己已结束
7. 对手结束后能看到完整结算

## 10. 后端当前已经验证通过的场景

后端已完成并通过测试的场景包括：

1. 正常双人开局和开始
2. `WsMessage` 上行心跳、击败、主动结束
3. `WsMessage` 下行比分广播和结算广播
4. 服务端权威计分
5. 掉线判定
6. 掉线分数冻结
7. `POST /rooms/state`
8. `POST /rooms/result`
9. `POST /leaderboard`
10. SQLite 结果表与排行榜表落库

## 11. 联调注意事项

1. 前端一定不要再发送裸 `PlayerHeartbeatEvent`
2. 前端一定不要再发送裸 `PlayerDefeatEvent`
3. 前端一定不要再发送裸 `PlayerGameOverEvent`
4. 前端接收时一定先解 `WsMessage`
5. 计分冲突时以服务端广播结果为准
6. 掉线恢复后不能继续作战，只能查询状态并进入结束/观察流程
