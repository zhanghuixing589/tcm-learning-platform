# 本机基础服务

用户确认将MinIO和未来需要的Redis归为Docker Desktop中的同一个`tcm-learning-platform`项目；每个服务各用独立容器和数据卷。目前只声明MinIO。

项目根目录执行：

```powershell
python scripts/dev_minio.py start
python scripts/dev_minio.py status
python scripts/dev_minio.py stop
```

API：http://127.0.0.1:19000；控制台：http://127.0.0.1:19001；私有桶：`tcm-learning-sources`。

根账号仅初始化，应用身份只能访问本项目桶。凭据保存在被Git忽略的`runtime/minio.env`；`runtime/dev-minio.json`仅记录非秘密连接信息和凭据文件位置，不在聊天或文档中复制凭据。不要把`docker compose config`的完整渲染输出贴入日志，它可能包含环境变量值。

使用本机已存在并核实的MinIO RELEASE.2025-09-07T16-13-09Z镜像，compose固定digest；不是对“最新版本”的声明。数据卷为`tcm-learning-platform_minio-data`，不复用D:/minio/data或其他项目的卷。停止服务保留数据；不使用down -v或prune。已有卷而凭据文件丢失时，脚本拒绝自动重置。

启动会用应用身份写入、读回并删除本次健康探针，验证匿名对象访问403。其他对象和其他项目容器不变。

本设施部署不代表现有Java后端已切换MinIO。接入、历史文件校验拷贝和按fileId保留授权的迁移属于来源阅读改造后续实施；在完成前，当前站点仍沿原本地文件存储工作。
