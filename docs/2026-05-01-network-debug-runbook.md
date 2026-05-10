# Aircraft War 联调启动说明

本文档用于前后端实际联调时快速启动环境、明确命令和排查关键点。

适用分支基线：

1. `feature/network`
2. `network-backend`
3. `network-frontend`

## 1. 当前联调前提

当前多人联机协议有两个关键前提：

1. HTTP 请求响应直接使用 protobuf 二进制消息体
2. WebSocket 一条 binary frame 对应一条 `WsMessage`

因此联调时必须确认：

1. 前端已经重新生成最新 `proto/aircraft_war.proto`
2. 前端不再发送裸 `PlayerHeartbeatEvent`
3. 前端不再发送裸 `PlayerDefeatEvent`
4. 前端不再发送裸 `PlayerGameOverEvent`
5. 前端接收时先统一反序列化 `WsMessage`

## 2. 后端数据库说明

后端当前使用固定 SQLite 文件：

- `backend/aircraft-war.sqlite`

说明：

1. 主程序不再读取环境变量
2. 这是一个小项目，数据库文件直接固定在 `backend/` 目录内
3. 若需要重新开始联调，建议先删除旧数据库文件，避免历史数据干扰排行榜和结果查询

删除命令：

```bash
rm -f backend/aircraft-war.sqlite
```

## 3. 启动后端

工作目录：

```bash
/root/cs-course/aircraft-war-hitsz/.worktrees/network-backend/backend
```

启动命令：

```bash
go run ./cmd/server
```

默认监听：

```text
http://localhost:8080
```

后端接口：

1. HTTP
   - `POST /rooms/create`
   - `POST /rooms/join`
   - `POST /rooms/ready`
   - `POST /rooms/start`
   - `POST /rooms/result`
   - `POST /rooms/state`
   - `POST /leaderboard`
2. WebSocket
   - `GET /ws?room_id=<room_id>&username=<username>`

## 4. 启动前端

前端请基于自己的 `network-frontend` 工作区启动。

前端启动前必须先做：

1. rebase 到最新 `feature/network`
2. 重新生成 proto Java 代码
3. 检查 WebSocket 发包入口是否已经切到 `WsMessage`
4. 检查 WebSocket 收包入口是否已经先解 `WsMessage`

## 5. 推荐联调顺序

### 5.1 第一阶段：房间前置流程

目标：先确认 HTTP protobuf 编解码没问题。

联调顺序：

1. A 调 `POST /rooms/create`
2. B 调 `POST /rooms/join`
3. A/B 分别调 `POST /rooms/ready`
4. A 调 `POST /rooms/start`

预期：

1. 创建房间后 `room.status = ROOM_STATUS_WAITING`
2. 第二人加入后 `room.status = ROOM_STATUS_FULL`
3. 双方准备完成后 `room.status = ROOM_STATUS_READY`
4. 房主开始后 `room.status = ROOM_STATUS_PLAYING`

### 5.2 第二阶段：正常双人对局

目标：验证 `WsMessage` 上下行和服务端权威计分。

联调顺序：

1. A/B 建立 WebSocket
2. A/B 每 3 秒发送一次心跳
3. A 击败敌机，发送 `WsMessage.player_defeat_event`
4. 后端广播 `WsMessage.score_broadcast`
5. B 再击败敌机，继续广播比分
6. A/B 分别发送 `WsMessage.player_game_over_event`
7. 后端广播 `WsMessage.game_finished_broadcast`
8. 前端调用 `POST /rooms/result`
9. 前端调用 `POST /leaderboard`

预期：

1. 分数以服务端广播为准
2. 双方都结束后房间才最终完成
3. `winner_username` 正确
4. `self_result` 正确
5. 排行榜按 `best_score` 排序

### 5.3 第三阶段：掉线场景

目标：验证心跳超时、分数冻结和恢复查询。

联调顺序：

1. A/B 正常开始对局
2. A 先打一些分
3. A 停止发送心跳
4. B 继续发送心跳
5. 等待服务端完成掉线判定
6. B 继续击败敌机并接收新的比分广播
7. A 恢复网络后调用 `POST /rooms/state`
8. B 主动结束
9. A/B 查看最终结算和排行榜

预期：

1. 服务端约 12 秒内判定 A 掉线
2. A 的 `finish_reason = PLAYER_FINISH_REASON_DISCONNECTED`
3. A 的分数被冻结，不再增长
4. B 仍可继续涨分
5. A 恢复后不能继续作战，只能看状态和结算

## 6. 实际命令建议

### 6.1 运行后端测试

工作目录：

```bash
/root/cs-course/aircraft-war-hitsz/.worktrees/network-backend/backend
```

命令：

```bash
go test ./... -count=1
```

### 6.2 仅运行正常双人结算测试

```bash
go test ./tests -run TestRoomLifecycleScoreResultAndLeaderboard -count=1
```

### 6.3 仅运行掉线模拟测试

```bash
go test ./tests -run TestDisconnectFreezesScoreAndStateRecovery -count=1
```

### 6.4 清理数据库再重新启动后端

```bash
rm -f backend/aircraft-war.sqlite && go run ./cmd/server
```

## 7. 联调时前端重点观察字段

### 7.1 比分广播

看：

1. `scores[].username`
2. `scores[].score`
3. `scores[].finished`
4. `scores[].status`
5. `scores[].finish_reason`

### 7.2 最终结算

看：

1. `winner_username`
2. `self_result`
3. `player_a_finish_reason`
4. `player_b_finish_reason`

### 7.3 恢复查询

看：

1. `room_finished`
2. `scores[]`
3. `result`

## 8. 常见问题排查

### 8.1 WebSocket 建连成功但服务端没反应

重点检查：

1. 前端是否发送的是 `WsMessage`
2. 前端是否误发裸 protobuf 消息
3. `room_id` 和 `username` 是否与建连参数一致

### 8.2 击败事件发出后比分不更新

重点检查：

1. `client_event_id` 是否为空
2. `enemy_type` 是否正确填写
3. 玩家是否已经被服务端标记为 `FINISHED`
4. 房间是否已进入 `PLAYING`

### 8.3 恢复后还能继续操作游戏

这属于错误行为。

正确行为：

1. 掉线玩家一旦被服务端判定结束
2. 恢复后只能调用 `POST /rooms/state`
3. 前端应进入结束界面或观察状态
4. 不能继续提交有效击败事件

## 9. 当前后端已验证通过的事实

后端当前已经通过以下测试验证：

1. 正常双人完整结算
2. SQLite 结果表落库
3. SQLite 排行榜落库
4. 掉线冻结分数
5. 掉线恢复后查询状态
6. 在线玩家继续作战直到最终结算
