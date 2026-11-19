# MiMo 面板（MiMo Panel）

一个自托管的「大模型平台账户面板」：查看 MiMo / DeepSeek / Kimi / 硅基流动 / OpenRouter 等平台的
**账户余额、逐日用量、日账单、余额走势**，带桌面小组件、余额预警推送，以及一个同样能力的电脑端版本。

- **安卓端**：单文件页面 + 手写 WebView 壳，APK 直连各家 OpenAPI（原生请求绕开 CORS），**不经过任何第三方代理**
- **桌面端**：本地 Node 服务托管同一套页面，同源转发请求（同样不需要外网代理），浏览器打开即用

> 全部代码自己跑在本机，API Key 只存在本地，不上传、不落到任何服务器。

## 目录结构

```
mimo-panel/
├── android/                 安卓端（WebView + 原生桥）
│   ├── AndroidManifest.xml
│   ├── assets/index.html    面板页面（单文件，全部 UI 与业务逻辑）
│   ├── src/…                MainActivity / Net / Alert / WidgetProvider
│   ├── res/                 布局、小组件配置、图标
│   └── build.sh             无 Gradle 构建脚本（aapt2 + ecj + d8 + apksigner）
├── desktop/                 电脑端
│   ├── server.js            本地 Node 服务（静态托管 + 同源转发 + 演示数据）
│   ├── public/index.html    与安卓端同源的页面（改为走本地接口）
│   └── package.json
└── docs/                    原理与踩坑文档
```

## 快速开始

### 电脑端

```bash
cd desktop
node server.js            # 默认 http://127.0.0.1:8787
```

打开 `http://127.0.0.1:8787/`，右上角齿轮进设置，选供应商、填 Key、建账号。
没有 Key 时可用 `http://127.0.0.1:8787/?mock=1` 查看演示数据（本地生成，不联网）。

### 安卓端

```bash
cd android
ANDROID_JAR=/path/android.jar BUILD_TOOLS=/path/build-tools \
ECJ_JAR=/path/ecj.jar KEYSTORE=./release.jks KS_PASS=xxx KEY_PASS=xxx ./build.sh
```

产物 `MiMoPanel.apk`，直接安装。覆盖升级必须用同一个 keystore 签名。

## 功能一览

| 能力 | 说明 |
|---|---|
| 多供应商 / 多账号 | MiMo、DeepSeek、Kimi、硅基流动、OpenRouter、自定义接口；同一供应商可建多个账号，顶部一键切换 |
| 余额概览 | 总可用、现金、赠金、冻结、授信；币种自动切换 ¥ / $ |
| 逐日用量 | 消费构成（输入命中/未命中/输出/插件）、Token、调用次数、缓存命中率 |
| 多模型对比 | 同屏多条折线，消费 / Token 双口径，图例可点隐藏 |
| 日账单 | 原价、折扣、赠金抵扣、实付 |
| 余额推演 | 日均消耗预测可撑天数；本地留存历史快照 |
| 缓存优化建议 | 用实际扣费反推单价，算出缓存未命中多花的钱 |
| 安卓小组件 | 可任意拉伸，横条/方形/迷你三套排布自动切换，带折线趋势位图 |
| 余额预警 | AlarmManager 定时检查，后台通知；开机自启重排 |
| 只有余额接口的厂商 | 按余额快照推算逐日消费，趋势/账单照样有 |

## 文档

| 文档 | 内容 |
|---|---|
| [01-整体架构](docs/01-整体架构.md) | 两端的分层、原生桥协议、请求链路 |
| [02-接口与多供应商](docs/02-接口与多供应商.md) | 各平台接口、字段归一化、快照推算算法 |
| [03-图表与自适应小组件](docs/03-图表与自适应小组件.md) | Canvas 绘制的坑、堆叠柱/多线图、三套排布 |
| [04-安卓适配与踩坑](docs/04-安卓适配与踩坑.md) | 状态栏、底部黑边、粘贴失效、限流 |
| [05-构建签名与发布](docs/05-构建签名与发布.md) | 无 Gradle 打包链路、签名与升级 |
| [06-桌面版](docs/06-桌面版.md) | 本地服务、同源转发、演示模式 |

## 安全说明

- Key 存在本机（安卓：`SharedPreferences` + 页面 `localStorage`；桌面：浏览器 `localStorage`）
- 桌面服务只监听 `127.0.0.1`，不对外网开放；转发只做 GET，不记录 Key
- 仓库不含任何 keystore / 已签名 APK / 真实 Key
- 自定义接口意味着页面可以把请求发到你填的任意 http(s) 地址——那是你自己的选择，别填来路不明的地址

## License

MIT
