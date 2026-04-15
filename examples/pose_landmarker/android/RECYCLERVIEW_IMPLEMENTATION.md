## 快速参考 - RecyclerView 事件队列实现

### 📋 实现清单

✅ **EventAdapter.kt** - 新增  
   - RecyclerView 适配器，管理事件消息队列
   - 自动移除机制（1000ms 后）
   - 队列满时自动删除最旧消息
   
✅ **item_event_banner.xml** - 新增  
   - 单个消息项的布局定义
   - 半透明背景，白色文本

✅ **fragment_camera.xml** - 已修改  
   - LinearLayout → RecyclerView
   - 配置 LinearLayoutManager
   
✅ **CameraFragment.kt** - 已修改  
   - 移除旧的 Handler 和回调管理代码
   - 简化事件显示逻辑
   - 集成 EventAdapter

---

### 🎯 核心功能

#### 1. 添加消息
```kotlin
eventAdapter.addEvent("新消息内容")
```

#### 2. 自动移除
- 消息在队列中停留 **1000ms** 后自动移除
- 可通过修改 `EVENT_DISPLAY_DURATION_MS` 调整

#### 3. 队列管理
- 最多容纳 **10 条** 消息
- 超过时，最旧消息被立即移除
- 可通过 EventAdapter 构造参数调整

#### 4. 清除所有
```kotlin
eventAdapter.clearAll()  // 清除所有消息和待处理任务
```

---

### 🔧 自定义配置

在 `CameraFragment.kt` 的 `onViewCreated()` 中修改：

```kotlin
eventAdapter = EventAdapter(
    eventDisplayDurationMs = 1000L,  // 显示时长（毫秒）
    maxQueueSize = 10                 // 队列最大容量
)
```

---

### 🎨 样式自定义

编辑 `item_event_banner.xml` 修改：
- 背景颜色：`android:background="#99000000"`
- 文字颜色：`android:textColor="@android:color/white"`
- 文字大小：`android:textSize="16sp"`
- 内边距：`android:paddingStart="12dp"` 等

---

### 🚀 工作流程

```
PoseActionEvent
    ↓
GameEventDispatcher
    ↓
gameEventLogListener
    ↓
enqueueEventForDisplay()
    ↓
eventAdapter.addEvent()
    ↓
RecyclerView 显示 → 1秒后自动移除
```

---

### 📱 消息类型

当前支持的事件类型：
- `ARM_RAISE_START` → "抬手开始"
- `ARM_LOWER_START` → "放下开始"
- `ARM_WAVE_START` → "挥动开始"
- `ARM_WAVE_END` → "挥动结束"

可在 `CameraFragment.formatEvent()` 中修改格式化逻辑。

---

### ✅ 构建状态

- **构建结果**: ✅ BUILD SUCCESSFUL
- **是否有编译错误**: ❌ 无
- **是否可部署**: ✅ 是

---

### 📝 注意事项

1. RecyclerView 动画自动应用，无需额外配置
2. 所有事件操作已线程安全（通过 `activity?.runOnUiThread`）
3. Handler 生命周期自动管理，避免内存泄漏
4. 队列满时的移除是 **立即** 的，不会延迟


