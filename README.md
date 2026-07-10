



![yatori-go-console](https://socialify.git.ci/yatori-dev/yatori-go-android/image?font=Raleway&forks=1&issues=1&logo=https%3A%2F%2Favatars.githubusercontent.com%2Fu%2F185567923%3Fs%3D1000%26v%3D4&name=1&owner=1&pattern=Charlie%20Brown&pulls=1&stargazers=1&theme=Dark)

<div align="center"><h1>Yatori-core系列</h1></div>

<div align="center"><h2>Yatori-go-android</h2></div>

<div align="center"><img width="125px" src="https://img.shields.io/badge/Android-building-r.svg?logo=android"></img> <img width="80px" src="https://img.shields.io/github/stars/yatori-dev/yatori-go-android.svg"></img> <img width="90px" src="https://img.shields.io/github/downloads/yatori-dev/yatori-go-android/total.svg"></img> <img width="140px" src="https://img.shields.io/badge/license-AGPL--3.0--or--later-blue.svg"></img></div>

## 简介

Yatori课程助手，支持英华慕课(英华学堂及所有英华套壳平台)、海旗科技、超星学习通、智慧职教资源库等平台。其它平台开发中。

该项目主要用于解放大学生网课，减少无意义的水课网课让大学生能够做其他更值得去做的事情，而不是把时间浪费在网课上（指无意义的网课）。

当然对于有意义的网课我们还是不提倡使用yatori的，我们主要针对无意义网课。

Tips: 本项目**yatori-go-android**是基于**yatori-go-console/core**项目，将核心逻辑使用`go-mobile`移植而成。


## 📢作者有话说

> 1、项目刚开始，众多功能未能使用以及存在bug是正常的，开发需要平台账号进行测试。
>
> 2、史山轻喷（Orz），正火热开发中。
>
> 3、运行环境要求：① 安卓手机 ② 安卓版本≥13（miniSDK=33）
>

## 🤔问题咨询

> 推荐的一些计算机技术QQ交流群：
>
> * [932447008](https://qm.qq.com/q/KREkme4rYc) (未满)
>

## 🎯功能/特性：

| 功能/特性         | 状态 |
|---------------------| ---- |
| 独立程序，不依赖浏览器 | ✅ |
| AI自动识别跳过验证码 | ✅ |
| 多账号同刷 | ✅ |
| 支持状态邮箱通知 | ✅ |
| 支持自动考试 | ✅ |
| 答题支撑AI大模型加持 | ✅ |
| 答题支撑第三方题库加持 | ✅ |
| 灵活配置文件 | ✅ |
| 自动继续上次记录时长刷课 | ✅ |
| 部分平台支持暴力模式（无视前置课程学习限制所有视屏同刷） | ✅ |

## 🎯支持平台：

| 平台           | 描述                     | 状态     |
|----------------|---------------------------| ----------- |
| 英华学堂 | 支持暴力模式（会被检测到） | 已初步可用 ✅ |
| 仓辉实训 | 支持暴力模式（套壳英华版本会被检测到） | 移植完成待验证 🚧 |
| 创能实训 | 支持暴力模式（会被检测到） | 移植完成待验证 🚧 |
| 学习通 | 支持自动写章测、作业、考试。<br>支持多课程模式和多任务点模式。 | 已完成 ✅ |
| 海旗科技 | 支持暴力模式 | 移植完成待验证 🚧 |
| 智慧职教（资源库） | 默认秒刷(目前只支持Cookie登录方式)  | 移植完成待验证 🚧 |
| 社会公益课 | 支持暴力模式（会被检测到） | 移植完成待验证 🚧 |
| 重庆工业学院CQIE | 支持暴力模式（支持秒刷） | 移植完成待验证 🚧 |
| 学习公社（ENAEA） | 支持暴力模式（倍速刷） | 移植完成待验证 🚧 |
| 大学生网络党校（ENAEA） | 支持暴力模式（倍速刷） | 移植完成待验证 🚧 |
| 中小学网络党校（ENAEA） | 支持暴力模式（倍速刷） | 移植完成待验证 🚧 |
| 码上研训 | 默认秒刷  | 移植完成待验证 🚧 |
| 随行课堂 | 支持秒刷完成度以及学时累计刷取 | 移植完成待验证 🚧 |
| 青书学堂 | 只支持普通模式 | 移植完成待验证 🚧 |
| 学习公社（ttcdw） | 无 | 开发中 🚧 |

> [!TIP]
> 英华限制性暴力模式指的是如果你学校英华平台的课程视屏没有前置视屏观看限制那么就可以开，这个前置视屏观看限制指的是，一个章节的视屏你要观看必须要先把前面章节的视屏看完才能看，这就叫做前置视屏观看限制。重庆工程学院CQIE可以做到真正意义上的秒刷，使用暴力模式即可。码上研训也可以秒，默认普通模式即为秒。

## 🎉食用方式：

> 下载releases中的APK文件，安装，然后启动安卓app。
>
> 注意：填url的时候是填写学校英华的链接，要用自己学校的链接，每个学校的链接都不同，这个可以自己去找去问。
>


## 🎉贡献者

<a href="https://github.com/yatori-dev/yatori-go-android/graphs/contributors">   <img src="https://contrib.rocks/image?repo=yatori-dev/yatori-go-android" /></a>

## 免责声明：

> 本项目基于 AGPL-3.0-or-later 开源。使用、修改、分发或提供网络服务时，请遵守 AGPL 协议要求，不要闭源套壳、假冒或用于违法滥用，若对贵公司造成损失立马删库（保命(doge)）。
> 
> 他人或组织使用本代码进行的任何违法行为与本人无关，该代码纯技术学习交流。

## 开源协议

本项目使用 AGPL-3.0-or-later，详见 [LICENSE](LICENSE)。

## 相关技术参考引用：
> Yatori系列项目：
> * [yatori-go-console](https://github.com/yatori-dev/yatori-go-console)
> * [yatori-go-core](https://github.com/yatori-dev/yatori-go-core)
