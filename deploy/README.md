# 部署边界

后续Compose包含nginx、backend、ai-service、mysql、redis、neo4j；Chroma首次可由AI服务本地持久化嵌入使用，迁移为独立服务时需对应持久化卷及版本兼容验证。

持久化分别覆盖MySQL、原始文件、Chroma及Neo4j。生产仅公开Nginx；业务服务与数据库内网通信。初版不交付未经docker compose config验证的配置文件。

恢复顺序：恢复MySQL与原始文件→校验正式版本和证据→重建Chroma/Neo4j→重建Redis→执行来源、问答、测验抽检。仅导出SQL不能称为完整备份方案。
