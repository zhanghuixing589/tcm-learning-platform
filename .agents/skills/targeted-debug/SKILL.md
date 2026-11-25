---
name: targeted-debug
description: Use when this project's runtime logs, traceId, HTTP failures, build errors, or tests reveal an exception or unexpected behavior.
---

先取一份最小证据：发生动作、实际/预期、traceId、时间、首个业务栈帧。日志只读相关 runtime/*.log 的尾部或命中前后，脱敏后记录。

按 [模块索引](../../../MODULE_INDEX.md) 加载相关设计/函数，核实当前分支、实际启动进程与配置。不要仅因 dev-services.json 存在就认定服务在运行；不要打印 dev-secrets / DB 密码。

每轮只验证一个根因假设；区分确定原因、假设和未验证项。同一错误再现时停下原样重试，检查输入、环境和最早失败点；条件未改变不重跑同一大套件。

修复限定于授权范围，补能复现原错误的最小回归，再按依赖扩大。状态记录复现命令、根因、修改、结果和未覆盖项，不能以健康检查代替业务闭环。
