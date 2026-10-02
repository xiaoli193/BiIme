# 项目文件说明

这个仓库只有一个 Android 应用：**中英对照拼音输入法**。
它由两半组成——系统在打字时弹出的**键盘**，和你点开应用图标看到的**首页**。
总共 13 个 Java 文件、约 5000 行 Java 代码，不使用 Gradle、AndroidX、Kotlin，也不联网。

配套文档：扩展包怎么写见 [`PACKS.md`](PACKS.md)，功能与用法见 [`README.md`](README.md)。

---

## 一、先看整体

```
                      同一个应用进程（com.dsh.biime）
   ┌──────────────────────────────┬──────────────────────────────┐
   │  MainActivity（首页）        │  BiImeService（输入法本体）  │
   │  状态卡 / 使用引导           │  键盘 UI / 候选栏 / 输入逻辑 │
   │  试打演示 / 扩展包管理       │                              │
   └──────────────┬───────────────┴───────────────┬──────────────┘
                  │                               │
                  └───────────┬───────────────────┘
                              ▼
        ┌─────────────────────────────────────────────────┐
        │  Candidates  候选拼装（短语包 → 词典 → 整句切分）│
        └───────┬──────────────────┬──────────────────────┘
                ▼                  ▼
      ┌──────────────────┐  ┌──────────────┐  ┌─────────────────┐
      │  PinyinDict      │  │  Assoc       │  │  Packs          │
      │  拼音查词 / 校正 │  │  联想索引    │  │  扩展包 + 参数  │
      │  assets/pinyin.dict │ assets/assoc.idx │ SharedPreferences│
      └──────────────────┘  └──────────────┘  └─────────────────┘
                                                     │
                                              ┌──────┴──────┐
                                              │  Skin / Json │
                                              └─────────────┘
```

**两条输入路径共用同一份候选逻辑**：真键盘按键走 `BiImeService.onLetter`，
首页试打演示走 `MainActivity.padKey`，两者最后都调 `Candidates.build(...)`。
所以"应用里演示看到什么，真键盘里就是什么"。

**一次按键发生了什么：**

```
按键 → composing 追加一个字母
     → Candidates.build(ctx, dict, input, 繁体?)
          ├─ Packs.enabledEntries()      已启用词典包的词条，前缀命中
          ├─ PinyinDict.searchSmart()    精确 → 模糊音 → 拼错纠正，合并排序
          └─ PinyinDict.segmentCandidate() 整词查不到时按音节切分兜底
     → BiImeService.refreshCandidates() 把候选画成 chip（中文 + 英文）
点候选 → commitText()
          ├─ applyPolicy()   自动全角标点 / 文本转换
          ├─ updateAssociations()  拿上屏的中文查 Assoc，给出联想词
          └─ InputConnection.commitText()
```

---

## 二、目录树

```
android-bilingual-ime/
├── README.md                    功能、用法、安装、构建、已知限制
├── FILES.md                     本文件：每个文件干什么
├── PACKS.md                     扩展包开发指南
├── build.sh                     不用 Gradle 的打包脚本（80 行）
├── app/
│   ├── AndroidManifest.xml      应用/Activity/IME 服务声明、<queries>
│   ├── assets/
│   │   ├── pinyin.dict          11 万词条词典（8.9 MB，构建期生成）
│   │   └── assoc.idx            6 万条联想索引（2.0 MB，构建期生成）
│   ├── res/
│   │   ├── drawable/ic_launcher.xml   唯一的静态 drawable（矢量图标）
│   │   ├── values/strings.xml         应用名、输入法名、子类型名
│   │   └── xml/method.xml             输入法元数据（子类型、设置页入口）
│   └── src/com/dsh/biime/       （13 个 Java 文件，见下）
├── tools/build_dict.py          把 CC-CEDICT + jieba 编译成两个 assets（314 行）
└── dist/                        产物：BiIme-1.0.apk、签名用 keystore
```

---

## 三、每个文件干什么

### 输入法本体

**`BiImeService.java`（1173 行，最大的文件）** —— `InputMethodService` 子类，系统弹出的键盘就是它。

| 区块 | 方法 | 说明 |
| --- | --- | --- |
| 生命周期 | `onCreate` / `onCreateInputView` / `onStartInputView` | 每次都先 `loadTheme()` 重读扩展包参数，所以改主题/键盘样式后"下次弹出"生效 |
| 键盘构建 | `buildPage(p)` | 四个页面：字母 / 中文标点 / 数字符号 / 更多符号 |
| | `addSymbolRows()` | 符号页按 **10 / 9 / 7** 三行 26 格渲染，和字母页同形 |
| | `makeKey` / `letterKey` / `symbolKey` / `digitKey` / `quickKey` | 各种按键；`skin.key(style)` 出背景 |
| 候选栏 | `buildCandidateBar()` | 左边滚动候选 + 右边三个控制键（中/EN、简/繁、⌄） |
| | `refreshCandidates()` | 候选渲染总入口：标点备选 → 联想词 → 正常候选 |
| | `makeChip()` | 一个候选 = 中文 + 英文两行 |
| 输入逻辑 | `onLetter` / `onBackspace` / `commitText` / `commitTopCandidate` | 组合区维护与上屏 |
| 上屏策略 | `applyPolicy()` | 自动全角标点 + 文本转换（`textTransform`） |
| 标点 | `attachAlternatives()` / `altChip()` | 长按标点键 → 候选栏给出其它写法 |
| 联想 | `updateAssociations()` | 上屏后拿中文尾巴查 `Assoc` |
| 容错 | `keyboardErrorView()` | 键盘构建失败也不让输入法进程挂掉 |

### 应用首页

**`MainActivity.java`（1048 行）** —— 唯一的 Activity，全部用代码搭 View（没有 XML 布局）。

| 区块 | 说明 |
| --- | --- |
| 状态卡 | 用 `InputMethodManager.getEnabledInputMethodList()` 判断是否已启用；**不能用** `Settings.Secure.ENABLED_INPUT_METHODS`（Android 14 起对 targetSdk 34 抛 SecurityException，这是曾经的闪退元凶） |
| 使用引导 | 三步说明 + 「去系统设置启用 / 切换输入法」按钮 |
| 试打演示 | 自带小键盘（26 键 + ⌫），和系统当前用哪个输入法无关；点候选上屏中文、长按上屏英文、自动接联想，和真键盘同一份逻辑 |
| 扩展包 | 可折叠区块：导入文件 / 从剪贴板导入 / 分类筛选 / 卡片列表 / 删除；折叠状态记在 SharedPreferences |
| `onActivityResult` | 读系统文件选择器返回的扩展包文件 |

### 数据层

**`PinyinDict.java`（806 行）** —— 离线拼音词典 + 拼音校正。

* 加载：把 `assets/pinyin.dict`（8.9 MB）整个读成 `byte[]`，用 `int[]` 记每行偏移，
  二分查找前缀。**不产生 11 万个 String 对象**，加载约 90 ms。
* 搜索：`search()`（精确/前缀）、`searchSmart()`（精确 + 模糊音 + 拼错纠正，合并按词频排序）、
  `segment()` / `segmentCandidate()`（整句兜底切分，DP：字频 + 成词奖励 − 切分惩罚，
  先按最长匹配定音节边界）。
* 特殊表：`charFreqTable()` 汉字词频表（按 BMP 码位索引），用于整句切分打分。
* `FUZZY` 数组是模糊音规则表，加规则改这里。

**`Assoc.java`（217 行）** —— 联想索引。读 `assets/assoc.idx`（2 MB），
二分查找"以某个词开头的所有词"再按词频排序。加载约 20 ms。

**`Candidate.java`（68 行）** —— 一条候选：简体/繁体/拼音/英文/英文单词/原样输入/命中键/词频分。

**`Candidates.java`（82 行）** —— 候选拼装，键盘和首页共用：
已启用词典包 → `searchSmart` → 整句切分兜底；另提供 `associate()` 联想入口。

### 扩展包

**`Packs.java`（1055 行）** —— 扩展包内核，本项目最"重"的业务文件。

* 内置目录：23 个内置包（主题 5 / 键盘 4 / 标点 3 / 词典 3 / 功能 8）；
* 导入目录：`files/packs/<id>.json`，解析与校验（`importPack`）、删除（`deletePack`）；
* 参数模型：`Theme`（11 色）、`Keys`（圆角/键高/字号/数字行/快捷语行）、`Punct`（标点集）、
  `Func`（9 个功能模块）、`Entry`（词条）；
* 合并：`tuning()` 从默认值开始按目录顺序让每个已启用包覆盖，产出 `Tuning` 给键盘消费；
* 状态：`SharedPreferences("biime_packs")` 存主题、启用开关、界面折叠状态、输入法心跳。

**`Json.java`（274 行）** —— 手写的极小 JSON 解析器（约 270 行）。
不用 Android 自带的 `org.json`，**因为那样就没法在 JVM 上跑测试**。

**`Skin.java`（102 行）** —— 把主题色变成实际 `Drawable`（`StateListDrawable` + `GradientDrawable`）。
键盘和首页共用，所以换主题是"键盘 + 应用"一起变。项目里**没有静态的按键背景 XML**，
全部运行时生成。

### 基础设施

**`BiApp.java`（16 行）** —— `Application`，只做一件事：尽早装上崩溃自诊断。

**`CrashLog.java`（126 行）** —— 崩溃时把堆栈写进应用私有目录，
并通过 MediaStore 落一份到 `Download/biime-crash-*.txt`（API 29+ 不需要任何权限）。
当初就是靠它把"打开即闪退"的堆栈捞出来的。

### 资源

| 文件 | 说明 |
| --- | --- |
| `AndroidManifest.xml` | Activity、IME 服务（`BIND_INPUT_METHOD`）、`<queries>` 声明输入法 intent |
| `res/xml/method.xml` | 输入法元数据：两个子类型（zh_CN / en_US）、设置页指向 `MainActivity` |
| `res/values/strings.xml` | 4 个字符串 |
| `res/drawable/ic_launcher.xml` | 矢量图标（唯一的静态 drawable） |

### 数据资产与构建

**`tools/build_dict.py`（314 行）** —— 构建期脚本，一次产出两个资源：

```
CC-CEDICT（中英词典）─┐
                      ├─→ app/assets/pinyin.dict   11 万条，7 列，拼音序
jieba 词频表 ─────────┘    app/assets/assoc.idx    6 万条，3 列，中文序
```

* `pinyin.dict`：`key \t 简体 \t 繁体 \t 带声调拼音 \t 英文释义 \t 音节数 \t 词频分`
* `assoc.idx`：`中文 \t 词频分 \t 英文`（只收 2–4 字且有词频的词，用作联想）
* 派生词条：CC-CEDICT 缺常用短语，用 jieba 里"读音唯一的字"拼出拼音补一批

**`build.sh`（80 行）** —— 六步打包，不需要 Gradle：

```
aapt package（资源 + R.java）→ javac → d8（dex）→ 塞进 APK → zipalign → apksigner
```

---

## 四、想改某个东西，该动哪个文件

| 想改什么 | 改哪里 |
| --- | --- |
| 候选排序规则 | `PinyinDict.search()` / `searchSmart()` 的惩罚分常量 |
| 模糊音覆盖面 | `PinyinDict.FUZZY` 数组 |
| 拼错纠正策略（相邻键、颠倒、漏字母） | `PinyinDict.typoVariants()` / `neighboursOf()` |
| 整句切分（`woaini → 我爱你`） | `PinyinDict.segmentPieces()` 的打分权重 |
| 联想来源 / 条数 / 排除自己 | `Assoc.lookup()`、`Candidates.associate()` |
| 键盘页面与按键排布 | `BiImeService.buildPage()` / `addSymbolRows()` / `bottomRow()` |
| 候选 chip 的长相 | `BiImeService.makeChip()` |
| 上屏前的文本处理 | `BiImeService.applyPolicy()` |
| 键盘配色 | `Packs.DEFAULT_THEME` 与各内置主题；渲染在 `Skin` |
| 首页任何一块 | `MainActivity.buildRoot()` 及其子方法（`statusCard` / `guideCard` / `demoCard` / `packHeader`） |
| 扩展包格式 / 新校验 | `Packs.parse()` / `importPack()` / `Tuning`，再在 `BiImeService` 消费端接上 |
| 内置扩展包 | `Packs.builtin()` |
| 词典内容 | `tools/build_dict.py`（改完必须重新生成 assets 再 `build.sh`） |
| 打包流程 | `build.sh` |

---

## 五、几个刻意的设计取舍

1. **不用 Gradle / AndroidX**：这台机器（arm64 容器）没有 Gradle，官方 `aapt2` 只有 x86_64 版也跑不起来。
   于是用 Ubuntu 自带的原生 `aapt` + build-tools 里的 Java 版 `d8`/`apksigner` 直接出包，
   界面全部用 framework 的 View 手搭，不引任何依赖。
2. **手写 JSON 解析器**：Android 有 `org.json`，但用了它就没法在 JVM 上跑单元测试。
   扩展包解析是安全性关键路径，必须可测。
3. **词典用字节数组 + 偏移索引**：11 万词条如果用 `String[]`，光对象头就几十 MB；
   现在整份文件常驻 9 MB，二分查找直接比字节。
4. **扩展包是纯数据、没有脚本引擎**：装任何来源的包都无法执行代码、碰不到输入内容。
   代价是社区能组合的能力上限由应用暴露的接口决定（见 `PACKS.md` 最后一节）。
5. **崩溃自诊断走 MediaStore**：应用零权限、不联网，但需要一条把堆栈交给开发者的路，
   `Download/` 是 API 29+ 免权限可写、且容器侧可读的唯一位置。

---

## 六、构建与自测

### 打包

```bash
export ANDROID_SDK_ROOT=/opt/android-sdk
# 依赖：JDK 17、Android SDK（platforms;android-34、build-tools;34.0.0）、系统 aapt + zipalign
python3 tools/build_dict.py --cedict <cedict.txt> --freq <jieba_dict.txt> \
        --out app/assets/pinyin.dict --assoc app/assets/assoc.idx
bash build.sh          # 产物 dist/BiIme-1.0.apk
```

### 不上设备也能验的部分

数据层（`Packs` / `Json` / `PinyinDict` / `Assoc` / `Candidates`）**完全不依赖 Android 运行时**，
可以用几个 stub 在桌面 JVM 上跑：

```
/root/ime-test/
├── stubs/android/content/{Context,SharedPreferences,MemPrefs}.java
├── stubs/android/content/res/AssetManager.java   # 直接读 app/assets/
├── stubs/android/util/Log.java
├── Test6/7/8.java    扩展包导入、校验、参数合并
├── Test9.java        联想 + 模糊音 + 拼错纠正
└── Test10/12.java    26 格符号页、内置目录
```

```bash
cd /root/ime-test
javac -encoding UTF-8 -d out $(find stubs -name '*.java') \
  /sdcard/dsh/android-bilingual-ime/app/src/com/dsh/biime/{Json,Packs,Candidates,Candidate,PinyinDict,Assoc}.java \
  Test9.java
java -Dfile.encoding=UTF-8 -cp out Test9
```

**唯一测不到的是 UI**：`BiImeService` 和 `MainActivity` 的 View 代码只能在真机上跑，
改布局后请实机看一眼。
