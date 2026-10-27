# 车机 HUD 协议笔记

设备：本田 TCC8050_AVN / Android 10 / Magisk。
以下内容来自对车机 `MeterService` 的互操作观察和实车验证，不包含厂商源码。

## 1. 绑定服务

```text
包名/动作: com.alap.honda30ea.aochr.meterservice / .MeterService
描述符    : com.alpine.servicebinder.IMeterServiceManager
```

只给 `package + action` 绑定：

```java
Intent i = new Intent("com.alap.honda30ea.aochr.meterservice.MeterService");
i.setPackage("com.alap.honda30ea.aochr.meterservice");
bindService(i, conn, BIND_AUTO_CREATE);
```

不要写死 `ComponentName`：真实实现类名是
`...meterservice.service.MeterService`，比 action 多一级 `service`。

## 2. Transaction

| code | 方法 |
|---|---|
| 1 | `sendByteToMS(int id, byte v)` |
| 2 | `sendByteArrayToMS(int id, byte[] v)` |
| 3 | `sendStringToMS(int id, String v)` |
| 4 | `sendBitmapToMS(int id, Bitmap v)` |
| 5 | `sendTBTInfoToMS(List<String>)` |
| 6 | `sendMapInMeterState(int state)` |
| 7 | `sendMapResState(int)` |
| 8 | `sendMapInMeterSetting(bean)` |
| 9/10 | 注册/注销 CAN 回调 |
| 11/12 | `getBCanState` / `getMCanState` |

Parcel 格式按 AIDL 生成代码：先 `writeInterfaceToken(DESCRIPTOR)`，再按字段写参数，
最后 `binder.transact(code, data, reply, 0)` 并 `reply.readException()`。

## 3. 状态

`sendMapInMeterState` 的实车验证值：

| state | 含义 | tbtApp |
|---|---|---|
| 7 | CarPlay map-in-meter 进入 | 2 |
| 8 | Native 进入 | 1 |
| 9 | CarLife/文本进入 | 0 |
| 10 | CarPlay 退出 | - |
| 11 | Native 退出 | - |
| 12 | CarLife/清屏 | - |

导航用 **7**，音乐用 **9**，清屏用 **12**。

## 4. 导航字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `1558` | String | 引导点/路名；剩余距离 >1km 时仪表可能不显示 |
| `561036824` | String | CarPlay 当前道路名；长距离时用它保持路名 |
| `561033318` | String | 剩余距离，十进制字符串 |
| `1541` | byte | 旧版导向箭头字段（实车不出箭头） |
| `1538` | byte | 旧版图标序号；`7` 表示清空 |
| `1537` | byte | 箭头颜色，0 为默认 |
| `1284` | byte[2] | `[maneuverType, angle]`，真正控制箭头 |

`1284` 的 `maneuverType` 实车验证：

```text
1 = 左转
2 = 右转
3 = 直行
4 = 掉头
```

`angle` 是出口角度 / 22.5，普通 90° 路口填 0：

```text
右前 45°：type=2, angle=2
左前 45°：type=1, angle=14
右后 45°：type=2, angle=6
左后 45°：type=1, angle=10
靠右并线：type=2, angle=2
靠左并线：type=1, angle=14
环岛：type=6, angle=0
```

写导航的顺序：

```text
transact(6, 7)
transact(3, 1558, road)
transact(3, 561036824, road)
transact(3, 561033318, distance)
transact(2, 1284, [type, angle])
```

## 5. 音乐字段

本地音乐源下，CarPlay 的音频屏字段 `561036833/561036822` 实测不显示。
最终使用 `mode 9` + 文本通道：

```text
transact(6, 9)
transact(3, 1558, "歌名 - 歌手")
transact(3, 561033318, "0")
transact(1, 1538, 7)
```

仪表文本帧是**锁存**的：同一模式下只改 `1558` 不会刷新。每次换歌前要先清一次屏：

```text
transact(6, 12)
transact(1, 561033300, 0)
transact(3, 561033300, "1")
transact(3, 1558, " ")
transact(3, 561033318, "0")
transact(1, 1541, 0)
transact(1, 1538, 7)
```

然后等 300–500ms 再写新歌名。

## 6. 高德广播

```text
action: AUTONAVI_STANDARD_BROADCAST_SEND
KEY_TYPE=10019: EXTRA_STATE 8=进入导航，9/2=结束
KEY_TYPE=10001: ICON / SEG_REMAIN_DIS / ROUTE_REMAIN_DIS / NEXT_ROAD_NAME
```

实车注意：

- 高德有时只发 `10001` 数据，不发 `10019/EXTRA_STATE=8`。
  只要有有效 `ICON + NEXT_ROAD_NAME`，就应当推断导航已开始。
- 高德退出时会连发 `EXTRA_STATE=9` 和 `2`。只在第一次结束时清屏，
  第二个结束事件必须忽略，否则会把刚写回去的音乐清成空白。
- 路口/到达前会出现没有 `NEXT_ROAD_NAME` 的数据帧，不能因为一帧空路名就清屏。
- 导航数据可能几秒才来一条；HUD 应保持 60 秒再交还音乐。
- 原车媒体区可能周期性覆盖文本帧；导航期间每秒重写最近一帧可保持显示。

## 7. 选择媒体会话

`MediaSessionManager.getActiveSessions()` 可能包含占位会话，例如
「暂无播放内容」。需要过滤这些标题，并优先选择 `STATE_PLAYING` 的会话。
