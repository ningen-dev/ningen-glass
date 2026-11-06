# ningen-glass

从「凝恩浏览器」里抽出的两个自研内核：**液态玻璃视觉层** 与 **多引擎搜索**。
纯 Java，零第三方依赖，可直接塞进任何 Android 项目。

> **最低要求：Android 13（API 33）**
> 玻璃效果基于 `android.graphics.RuntimeShader`（AGSL），API 33 才引入。
> 在 Android 12 及以下设备上，`RuntimeShader` 相关类根本不存在，项目无法编译或直接崩溃。
> `compileSdk` 建议 35，`minSdk` 必须是 33。

---

## 目录

```
src/com/ningen/browser/
  render/          液态玻璃视觉层
    GlassShaders.java   AGSL 着色器源码（158 行）
    GlassSurface.java   玻璃表面绘制入口（197 行）
    GlassPanel.java     玻璃面板容器，FrameLayout 子类（278 行）
    Blur.java           降采样模糊（95 行）
    WallProvider.java   壁纸采样中心（163 行）
    Theme.java          尺寸与色值（70 行）
    Tilt.java           重力倾斜视差（137 行）
    VideoWall.java      视频动态壁纸（257 行）
    LiquidNavBar.java   液态导航条（1102 行）
  ui/
    BackdropView.java   背景压暗与渐隐衔接（174 行）
    Screen.java         刘海/状态栏安全区（84 行）
    SystemBars.java     沉浸式状态栏适配（82 行）
  core/
    Ease.java           缓动曲线（86 行）
    Spring.java         弹簧动画（60 行）
    Threads.java        线程池（38 行）
  data/
    SearchEngine.java   多引擎搜索与结果聚合（541 行）
    Models.java         数据模型（99 行）
```

共 17 个文件，约 3600 行。

---

## 核心思路

玻璃面板不重抓整棵 View 树（那样每层嵌套都要递归截图，卡且脏），而是：

1. 由宿主持有一张底图（`WallProvider.set(bitmap)`）
2. 每块玻璃按自己在窗口中的位置，从**同一张底图**上采样对应区域
3. 采样结果交给 AGSL 着色器，做折射、色散、磨砂

好处是每块玻璃看到的都是同一片背景的局部，视差和折射天然一致，多层嵌套也不会互相污染。

---

## 快速接入

### 1. 注入壁纸（必须）

```java
// 本库不内置任何图片素材，底图由你给
WallProvider.set(BitmapFactory.decodeResource(getResources(), R.drawable.your_wallpaper));
WallProvider.setWindow(widthPx, heightPx);   // 窗口尺寸变化时同步
```

没注入之前 `WallProvider.ready()` 返回 false，玻璃会退化成纯色。

### 2. 放一块玻璃

```java
GlassPanel panel = new GlassPanel(context)
        .corner(24f)      // 圆角，单位 dp
        .frost(1.0f)      // 磨砂强度 0~1
        .glow(0.6f)       // 边缘辉光
        .interactive();   // 触摸时轻微形变

parent.addView(panel);

// 内容层变了、壁纸换了、面板挪位置了，都要通知它重新采样
panel.invalidateSnap();
```

要更"克制"的观感可以 `.flat()`（去掉厚度感）或 `.noMorph()`（关掉入场形变）。

### 3. 液态导航条

```java
LiquidNavBar bar = new LiquidNavBar(context);
bar.setHostView(contentRoot);        // 内容层，作为背景采样的来源
bar.setSelectedIndex(0, false);
root.addView(bar, new FrameLayout.LayoutParams(
        MATCH_PARENT, (int) LiquidNavBar.heightPx(context), Gravity.BOTTOM));

bar.setOnTabSelectedListener(index -> {
    // 切换页面
});
```

`setCollapse(float t)` 可以随滚动做收起动画，`0` 展开、`1` 收起。

### 4. 视频壁纸（可选）

```java
VideoWall.useVideo(true);
VideoWall.setSource("/sdcard/.../wall.mp4");   // 本地绝对路径或 http(s) 地址
VideoWall.addTo(rootFrameLayout);              // 挂在最底层
```

放在 assets 里的视频，请自己先复制到缓存目录再把路径传进来——本库不读 assets。

---

## 搜索引擎

三个公开接口，无需 API Key：

- 必应 **RSS 输出**（`format=rss`，微软官方提供的公开接口，结果结构最规整）
- 百度搜索联想（输入补全）
- 搜狗搜索结果页（用于补充中文结果）

```java
List<Models.Hit> hits = SearchEngine.search("液态玻璃", 0);   // 第二参是页码
List<String> tips = SearchEngine.suggest("液态");             // 联想词

// 都是阻塞调用，别放主线程
```

内部带结果缓存与去重排序，重复查询不再走网。

---

## 说明几件事

**本库不含音乐播放。** 凝恩浏览器里的音乐模块直接对接第三方平台接口取播放直链，那部分代码不在此开源范围内，也请不要在 issue 里要。

**零第三方依赖。** 所有 import 只有 `android.*`、`java.*`、`org.json`、`org.xmlpull.v1`（后两个是 Android 平台自带的）。没有 androidx，没有网络框架，没有图片库。所以也不存在许可证传染问题。

**素材归你。** 仓库里没有一张图片、一个字体、一段音频。壁纸、图标、音效全部由使用方自行提供。

**图标是矢量画的。** 原项目里所有图标都是自绘 `Path`，没有一张 PNG。这部分没有单独抽出来，但 `Icon` 那套路径写法可以参考。

---

## 编译

不依赖 Gradle 也能编（原项目就是纯命令行构建）：

```bash
javac -source 11 -target 11 -encoding UTF-8 \
      -classpath $ANDROID_SDK/platforms/android-35/android.jar \
      -d out $(find src -name "*.java")
```

用在 Gradle 项目里，把 `src/` 整个拷进去即可，不需要改包名。

---

## License

MIT。随便用，改了也不用告诉我。唯一的要求是保留版权声明。

原作者：凝恩（ningen-dev）
