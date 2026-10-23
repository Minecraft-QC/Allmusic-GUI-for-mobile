# AllMusic GUI for Mobile

AllMusic 客户端点歌界面的**移动端移植版**，专为 **Foldcraft Launcher** 与 **Zalith Launcher 2**（Android 触摸模拟鼠标的 Minecraft Java 启动器）优化。

原桌面版在这些启动器上可以运行，但操作非常不便：没有右键、没有滚轮、ESC 键难以触发、按钮太小手指点不中。本仓库在保留全部功能的前提下重做了触摸交互。

## 移动端适配内容

| 问题（桌面版） | 移动版方案 |
|---|---|
| 右键收藏/删除无法触发 | **行内操作按钮**：每行右侧「▶ 播 / ♥ 收藏 / ✕ 删除」小按钮，手指直接点 |
| 没有鼠标滚轮 | **触摸拖动滚动**：按住列表上下拖动即可滚动，带滚动条 |
| 长按无法替代右键 | **长按菜单**：按住列表行 500ms 弹出收藏菜单（等价右键） |
| ESC 键缺失，返回困难 | **右上角 ✕ 按钮**：弹窗 → 二级页 → 一级，逐级返回，最后关闭界面 |
| 按钮太小（18px） | 全部按钮加高至 **24px**，页签 28px，列表行高 **34px** |
| 窄屏 460px 固定宽度溢出 | **响应式宽度**：铺满屏幕（留 8px 边距，上限 520px） |
| 「我的」页双栏挤压 | **窄屏单列布局**：屏幕宽度 <420px 时自动切换上下布局 |
| 回车搜索难触发 | 输入框旁新增**「搜索」按钮** |
| 歌单管理依赖右键 | 新建/清空/删除歌单全部改为**显式按钮** |

桌面端行为完全兼容：左键、右键、滚轮、键盘导航（方向键 + Enter + Delete）全部保留。

## 支持版本

| 目录 | Minecraft 版本 | 说明 |
|---|---|---|
| `AllMusic-GUI/1.21.1/` | 1.21.1 | GLFW 输入层 + GuiGraphics 渲染 |
| `AllMusic-GUI/26.1.2/` | 26.1.2 | KeyEvent/MouseButtonEvent 输入层 + GuiGraphicsExtractor 渲染 |
| `AllMusic-GUI/26.3/` | 26.3 | SDL3 输入层（键码/鼠标码均已适配） |

三个版本功能一致，仅随 Minecraft API 演进调整实现。

## 下载 / 构建

从 GitHub Actions 的 Artifacts 下载，或本地构建：

```bash
cd AllMusic-GUI/1.21.1   # 或 26.1.2 / 26.3
gradlew build            # Windows 用 gradlew.bat
```

产物在 `build/libs/allmusic-gui-<version>.jar`。需要 JDK 21+（1.21.1）/ JDK 25（26.x）。

国内网络可用 `download_mirror.cmd`（BMCLAPI 镜像）预热 loom 缓存，或用 `build_1211.cmd` 一键构建。

## 使用

1. 安装 AllMusic 服务端插件并安装本 mod（客户端）+ Fabric API
2. 进服后按 **G**（可在 控制 设置中改）打开点歌界面
3. 触屏操作：点行播放 / 拖动滚动 / 长按收藏 / ✕ 返回或关闭

## 功能

- **搜索**：网易云 单曲 / 歌手 / 专辑 / 歌单，点歌手/专辑/歌单可下钻
- **本地歌单**：多歌单管理，默认「喜欢」，新建/清空/删除
- **云歌单**：按 ID 同步网易云歌单；登录后自动拉取「我的歌单」
- **指令**：静音、切歌投票、HUD 开关等常用指令一键发送
- **我的**：账号登录（MUSIC_U Cookie）、设置

点歌通过 `/music <ID>` 发送，需要服务端 [AllMusic](https://github.com/ACGaming/AllMusic) 插件。

## License

MIT
