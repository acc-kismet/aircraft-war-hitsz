# Aircraft War 实现前与联调 Checklist

本文档用于前后端正式开工前的准备检查，以及后续联调时的逐项确认。

协议与实现基线文件：

1. `proto/aircraft_war.proto`
2. `docs/superpowers/specs/2026-05-01-aircraft-war-protocol-design.md`
3. `docs/superpowers/specs/2026-05-01-aircraft-war-api-handoff.md`
4. `docs/superpowers/specs/2026-05-01-aircraft-war-dev-task-breakdown.md`

## 1. 开工前协议确认

- [ ] 客户端已确认使用 `proto/aircraft_war.proto` 生成 Java 代码
- [ ] 后端已确认使用 `proto/aircraft_war.proto` 生成 Go 代码
- [ ] 双方已确认不使用 gRPC，只使用 Protobuf 编解码
- [ ] 双方已确认 HTTP 请求体和响应体均为 Protobuf 二进制
- [ ] 双方已确认 WebSocket 使用 `WsMessage` 二进制消息传输 Protobuf
- [ ] 双方已确认玩家唯一标识为 `username`
- [ ] 双方已确认房间固定为双人房间
- [ ] 双方已确认排行榜按 `best_score` 排序

## 2. 开工前业务规则确认

- [ ] 双方已确认房间状态：`WAITING -> FULL -> READY -> PLAYING -> FINISHED`
- [ ] 双方已确认玩家状态：`JOINED -> READY -> PLAYING -> FINISHED`
- [ ] 双方已确认得分由服务端权威累计
- [ ] 双方已确认 `enemy_type` 为服务端计分依据
- [ ] 双方已确认 `score_delta` 只用于日志和调试
- [ ] 双方已确认单个玩家掉线后，该玩家本局结束，另一名玩家继续游戏
- [ ] 双方已确认掉线玩家恢复网络后不能继续作战，只能查询状态并进入结束界面
- [ ] 双方已确认当两名玩家都结束后，房间才整体结束

## 3. 心跳与掉线规则确认

- [ ] 客户端已确认对战期每 3 秒发送一次承载 `PlayerHeartbeatEvent` 的 `WsMessage`
- [ ] 后端已确认 9 秒未收到心跳视为超时
- [ ] 后端已确认超时后额外复检 3 秒
- [ ] 后端已确认超过 12 秒仍未恢复则判定玩家掉线
- [ ] 后端已确认掉线时冻结该玩家当前服务端累计分数
- [ ] 后端已确认掉线时更新该玩家排行榜统计逻辑
- [ ] 双方已确认 `PlayerFinishReason` 的语义

## 4. HTTP 接口准备检查

- [ ] `POST /rooms/create` 已纳入客户端和后端开发范围
- [ ] `POST /rooms/join` 已纳入客户端和后端开发范围
- [ ] `POST /rooms/ready` 已纳入客户端和后端开发范围
- [ ] `POST /rooms/start` 已纳入客户端和后端开发范围
- [ ] `POST /rooms/result` 已纳入客户端和后端开发范围
- [ ] `POST /rooms/state` 已纳入客户端和后端开发范围
- [ ] `POST /leaderboard` 已纳入客户端和后端开发范围
- [ ] 双方已确认 HTTP 错误码策略

## 5. WebSocket 消息准备检查

- [ ] 客户端可发送承载 `PlayerHeartbeatEvent` 的 `WsMessage`
- [ ] 客户端可发送承载 `PlayerDefeatEvent` 的 `WsMessage`
- [ ] 客户端可发送承载 `PlayerGameOverEvent` 的 `WsMessage`
- [ ] 后端可广播承载 `ScoreBroadcast` 的 `WsMessage`
- [ ] 后端可广播承载 `GameFinishedBroadcast` 的 `WsMessage`
- [ ] 双方已确认 WebSocket 建连参数为 `room_id` 和 `username`

## 6. 客户端实现前检查

- [ ] 已有 Protobuf Java 代码生成方案
- [ ] 已有 HTTP Protobuf 编解码方案
- [ ] 已有基于 `WsMessage` 的 WebSocket 二进制消息处理方案
- [ ] 已有房间页面与对战页面的 UI 状态切换方案
- [ ] 已有结束界面展示“自己已结束，对手仍在游戏中”的方案
- [ ] 已有掉线恢复后调用 `POST /rooms/state` 的方案
- [ ] 已有心跳发送定时器方案
- [ ] 已有客户端埋点方案

## 7. 后端实现前检查

- [ ] 已有 Protobuf Go 代码生成方案
- [ ] 已有 HTTP Protobuf 请求解析方案
- [ ] 已有基于 `WsMessage` 的 WebSocket 二进制消息处理方案
- [ ] 已有房间内存模型或服务层模型方案
- [ ] 已有服务端权威计分方案
- [ ] 已有心跳超时检测与复检方案
- [ ] 已有排行榜更新方案
- [ ] 已有 SQLite 持久化方案
- [ ] 已有后端日志和埋点方案

## 8. 第一阶段自测 Checklist

- [ ] 可成功创建房间
- [ ] 可成功加入房间
- [ ] 可成功完成双方准备
- [ ] 房主可成功开始游戏
- [ ] 可成功建立 WebSocket 连接
- [ ] 可成功发送承载 `PlayerHeartbeatEvent` 的 `WsMessage`
- [ ] 可成功上报承载 `PlayerDefeatEvent` 的 `WsMessage`
- [ ] 可成功接收承载 `ScoreBroadcast` 的 `WsMessage`
- [ ] 可成功上报承载 `PlayerGameOverEvent` 的 `WsMessage`

## 9. 掉线场景联调 Checklist

- [ ] 单个玩家停止发送心跳后，后端能在约 12 秒内判定掉线
- [ ] 掉线玩家被标记为 `PLAYER_STATUS_FINISHED`
- [ ] 掉线玩家被标记为 `PLAYER_FINISH_REASON_DISCONNECTED`
- [ ] 掉线玩家分数被冻结
- [ ] 掉线玩家后续击败事件会被拒绝
- [ ] 另一名玩家仍可继续游戏并继续涨分
- [ ] 服务端仍持续广播承载 `ScoreBroadcast` 的 `WsMessage`
- [ ] 掉线玩家恢复网络后可调用 `POST /rooms/state`
- [ ] `POST /rooms/state` 返回自己已结束、对手仍在游戏中的状态
- [ ] 掉线玩家进入结束界面后仍能继续看到对手分数变化

## 10. 最终结算联调 Checklist

- [ ] 双方都正常结束时能生成正确结算
- [ ] 一方掉线、一方正常结束时能生成正确结算
- [ ] 双方都掉线时能生成正确结算
- [ ] `RoomResult.winner_username` 正确
- [ ] `RoomResult.self_result` 正确
- [ ] `player_a_finish_reason` 和 `player_b_finish_reason` 正确
- [ ] 房间最终进入 `ROOM_STATUS_FINISHED`
- [ ] 后端能广播承载 `GameFinishedBroadcast` 的 `WsMessage`

## 11. 排行榜联调 Checklist

- [ ] 正常结束后可更新排行榜
- [ ] 掉线结束后可更新排行榜
- [ ] 若本局分数高于历史 `best_score`，则成功更新
- [ ] 若本局分数不高于历史 `best_score`，则不错误覆盖
- [ ] `POST /leaderboard` 返回顺序正确
- [ ] 排行榜分页参数 `limit`、`offset` 可用

## 12. 埋点与问题排查 Checklist

- [ ] 客户端有心跳发送埋点
- [ ] 客户端有连接断开埋点
- [ ] 客户端有恢复查询埋点
- [ ] 后端有心跳接收日志
- [ ] 后端有超时检测日志
- [ ] 后端有复检窗口日志
- [ ] 后端有掉线确认日志
- [ ] 后端有排行榜更新日志

## 13. 交付前最终检查

- [ ] `proto/aircraft_war.proto` 已作为唯一协议文件发给客户端和后端
- [ ] 双方确认本地生成代码版本一致
- [ ] 双方确认没有私自新增未对齐字段
- [ ] 双方确认没有私自修改枚举语义
- [ ] 联调中发现的问题已回写到协议文档或任务清单

## 14. 建议使用方式

建议在实际推进中这样使用这几份文档：

1. `proto/aircraft_war.proto` 作为代码生成和字段结构基线
2. `2026-05-01-aircraft-war-protocol-design.md` 作为完整业务语义基线
3. `2026-05-01-aircraft-war-api-handoff.md` 作为快速接口对接说明
4. `2026-05-01-aircraft-war-dev-task-breakdown.md` 作为前后端任务拆分清单
5. 本文档作为开工前和联调时逐项核对的执行 checklist
