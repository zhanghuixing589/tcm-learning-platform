# 当前阶段

2026-11-26来源阅读V2：新增process_api/processing/reader/conversion及受限转换容器，精确协议见contracts/source-reading-v2.md。当前`TCM_CONVERTER_TESTS=1 python -m pytest -q`71项通过、0跳过（runtime/source-reader-design/v2-ai-all-real.log），包含真实DOC、扫描PDF、6种媒体和隔离预算；这不替代具体图书和前后端链路验收。XML单元在构造树之前限制8MiB；EPUB局部超限保留失败位置和其余可靠单元，Word超限XML安全拒绝。复杂版式/扫描页不虚构可引用文字，完整质量边界见infra/converter/README.md。

独立 Python3.11 / FastAPI 服务，依赖锁定在 `requirements.lock`。已实现配置验证、内部凭据校验、traceId、健康、能力状态及第二阶段确定性文档解析。

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8000
```

必须配置至少32字符的 `AI_INTERNAL_TOKEN`。内部请求带 `X-Internal-Token`；内部端点不对浏览器公开。未配置模型密钥时健康正常、生成能力明确不可用；即使配置密钥，也不声称生成能力已实现。检索、索引和生成在第四阶段实施。

`POST /internal/v1/parse` 接收 Java 受控读取后的 `{fileName,contentBase64}`，返回统一响应内 `{parserName,segments:[{segmentNo,pageLabel,headingPath,body}]}`。不接收文件路径，不抓取URL，不连接业务库，不执行OCR或模型抽取。TXT必须UTF-8；DOCX用安全XML读取正文和标题；PDF必须有文本层，页码来自实际页面。TXT/DOCX只有段落定位，绝不伪造页码。

限制：原文件非空且不超过10MB，流式HTTP请求最多14MB，DOCX最多2000项/32MB解压/200倍压缩比，PDF最多500页并限制解压及解析工作量。所有格式最多200万字符/1万片段，同时最多2个解析线程。坏文档返回脱敏的 `DOCUMENT_INVALID`，请求字段错误返回 `VALIDATION_ERROR`；内部鉴权优先于解析请求体。Java负责创建不可变解析批次、90秒任务租约、45秒完整HTTP响应期限和runToken条件回写。失败尝试保留在Java任务历史中。

开发验证使用真实生成的DOCX和有文本层PDF，覆盖尺寸、坏格式、解压炸弹、加密/空PDF、路径拒绝及鉴权。命令同上，无需模型密钥或付费服务。

来源阅读扩展：解析响应保留原 `parserName` 和 `segments`，并返回 `reading:{schemaVersion:1,format,metadata,outline,chapters,assets,warnings}`。EPUB 按 spine 排序并读取 OPF 书目、EPUB3 nav 或 EPUB2 NCX；DOCX 保留标题、可识别列表、表格、基本行内格式和包内图片；TXT 连续阅读；PDF 每个物理页对应一个 chapter，空白页仍保留页码。PDF 正文来自同一次受限文本提取，页面图形由前端使用经鉴权的原 PDF 呈现。

`chapters[].nodes` 是白名单 AST，没有 HTML、事件属性或可执行样式；`blocks[].localId` 对应其节点 id，`text` 是该块的规范可见文本。嵌套容器不重复收录子块文字，偏移使用 Unicode 码点，不做兼容字符替换。节点/块 id 只在当前不可变阅读版内使用。最多 25000 节点、10000 块、64 层正文、32 层目录、200万规范文本字符；reading JSON 最多8MiB，含旧 segments 的完整解析结果最多11MiB。

图片仅输出 `{ref,path,mimeType}`，仅候选 PNG/JPEG、包内 manifest/关系白名单；不输出 base64，不获取外部资源。Java 必须从同一已校验原始包重新提取图片，校验真实 MIME、字节数和像素上限后再入私有存储，不能直接把 path 当作系统路径或公开 URL。不支持的图片会产生阅读警告。

`tests/test_reader.py` 覆盖规范块/emoji/组合字符、书目与 nav/NCX、嵌套文本不重复、富文本清洗、包内资产、DOCX 编号及上下标、PDF 空白物理页和 AST 数量限制。旧 EPUB 的 stored + data descriptor 兼容测试继续执行。

## V2 流式处理

`POST /internal/v2/process` 接收 `{jobId,runToken,fileName}`，沿内部凭据鉴权。只从配置 `BACKEND_URL`（也接受 `AI_BACKEND_URL`，默认 `http://127.0.0.1:8080`）的 `/internal/v1/source-inputs/{jobId}` 拉取原件，携带 `X-Internal-Token` 和 `X-Job-Run-Token`；禁止重定向，不接受调用者路径或 URL。原件流式写任务临时目录并计算 SHA256；有 `X-Content-SHA256` 时再比较预期摘要。实际字节和 Content-Length 均校验，下载和处理并发限制1。

响应为 StreamingResponse ZIP，包含 `result.json` 与所有 assets 声明的 `bundlePath` 实际二进制。清单根包含 `parserName,inputSha256,inputBytes,segments,reading`；不包含原图 base64。reading 的 `schemaVersion:2` 使用 `units`、`unitType`、每单元能力和实际已生成模式；outline 只反映实际目录，不为 TXT 或无标题 Word 伪造章节。阶段 metadata/conversion/preview/structure/text/media 分别包含 status/expected/succeeded/failed。局部文字/媒体/转换失败保留位置和警告，结果可为 PARTIAL_SUCCESS；无安全可用模式时 FAILED。路径逃逸、实体、CRC/解压预算等安全失败直接拒绝整个任务。

配置 `AI_PROCESS_MAX_BYTES`（默认50MiB）、`AI_PROCESS_MAX_EXPANDED`（200MiB）、`AI_PROCESS_MAX_ZIP_ITEMS`（5000）、`AI_PROCESS_MAX_RATIO`（200）、`AI_PROCESS_TIMEOUT_SECONDS`（600秒）、`AI_PROCESS_DOWNLOAD_TIMEOUT_SECONDS`（60秒）可下调。ZIP 逐项读取核对实际展开字节与 CRC。上述默认值是受控候选预算，不代表已验收50MiB真实教材；文字仍最多200万字符/1万块，AST 总数25000，清单32MiB，结果包及产物累计200MiB。

DOC 通过[隔离转换器](../infra/converter/README.md)生成真实 DOCX/PDF 衍生物后处理，原件仍声明 DOC。扫描 PDF 仅在实际 PNG 预览生成后才开放模式，不执行 OCR、搜索或引用。PNG/JPEG/GIF/WebP/SVG/BMP/TIFF 均经受限解码/静态转换，失败给出占位警告；浏览器只收到安全 PNG。普通 AI 测试不启动 Docker，真实转换专项通过 `TCM_CONVERTER_TESTS=1` 显式运行；未执行的容器专项不能宣称通过。
