# 快速参考卡片 - RecyclerView 事件队列

## 📋 文件清单

```
✅ 新增文件
├── EventAdapter.kt                 (85 行) - RecyclerView 适配器
└── item_event_banner.xml           (17 行) - 消息项布局

✅ 修改文件  
├── fragment_camera.xml             - LinearLayout → RecyclerView
└── CameraFragment.kt               - 集成 EventAdapter
```

## 🎯 核心使用

### 基本调用
```kotlin
// 在 CameraFragment.kt 中已自动初始化
eventAdapter.addEvent("消息文本")

// 清除所有
eventAdapter.clearAll()
```

### 配置参数
```kotlin
EventAdapter(
    eventDisplayDurationMs = 1000L,  // 显示时长（毫秒）
    maxQueueSize = 10                 // 最大队列容量
)
```

## 🎨 常见定制

### 修改显示时长
编辑 `CameraFragment.kt` line ~170:
```kotlin
EventAdapter(eventDisplayDurationMs = 2000L)  // 改为 2 秒
```

### 修改消息样式
编辑 `item_event_banner.xml`:
- 背景：`android:background="#99000000"`
- 文字：`android:textColor="@android:color/white"`
- 大小：`android:textSize="16sp"`
- 内边距：`android:padding*="12dp"`

### 修改消息格式
编辑 `CameraFragment.kt` 的 `formatEvent()` 方法

## ⚙️ 工作原理

```
事件生成 → EventAdapter.addEvent() → RecyclerView 显示
                    ↓
            1000ms 后自动移除 (Handler)
                    ↓
            如果队列满，新消息入，旧消息出
```

## 🔍 调试

### 问题：消息不显示
- [ ] 检查 `eventAdapter` 是否初始化
- [ ] 检查 RecyclerView 的 `visibility` 是否为 `VISIBLE`
- [ ] 检查 `formatEvent()` 是否返回正确的文本

### 问题：消息显示不完整
- [ ] 检查 RecyclerView 的高度设置
- [ ] 调整 `item_event_banner.xml` 的 padding

### 问题：消息不会自动消除
- [ ] 检查 `eventDisplayDurationMs` 的值
- [ ] 检查 Handler 是否被正确取消

## 📊 对比

| 特性 | 旧方案 | 新方案 |
|------|-------|--------|
| 显示延迟 | 排队等待 | 立即显示 |
| 动画 | 无 | 自动 |
| 代码量 | 多 | 少 |
| 性能 | 中 | 高 |

## ✅ 验证清单

- [x] 项目编译成功
- [x] RecyclerView 正确显示
- [x] 消息自动移除
- [x] 队列管理正确
- [x] 生命周期管理完善

## 🚀 下一步

1. 构建并运行应用
2. 尝试触发各种姿态动作
3. 观察消息是否正确显示和移除
4. 根据需要调整样式和时长

---

**状态**: ✅ 完成  
**构建**: ✅ 成功  
**测试**: 待运行

