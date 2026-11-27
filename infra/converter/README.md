# 来源转换与安全预览

这是 `tcm-learning-platform` Compose 项目的独立 `converter` profile；不会随 MinIO 默认启动，也不把 Docker socket、凭据、用户目录或资料存储根挂进容器。生产转换使用一次性非 root 容器，只挂载本任务单个输入目录（只读）；产物先写配额 tmpfs，再以有界 ZIP stdout 流回宿主私有 spool，不给转换器可写宿主路径。宿主 Python 负责固定参数、超时后删除容器及清理本任务目录。测试样本生成器单独使用可写 fixture 目录，不能照搬为生产配置。

```powershell
docker compose -f infra/compose.infra.yml --profile converter build converter
$env:TCM_CONVERTER_TESTS = '1'
Push-Location ai-service
.\.venv\Scripts\python.exe -m pytest tests/test_converter_real.py -q
Pop-Location
Remove-Item Env:TCM_CONVERTER_TESTS
```

镜像固定 Debian bookworm-slim 的实际拉取 digest；apt 使用 Debian 安全维护的软件包，构建日志可记录具体版本，不宣称这些是上游最新版本。引擎为 LibreOffice Writer（DOC→DOCX、Word→PDF）、Poppler（PDF→PNG）、Pillow（实际图片解码和首帧→PNG）、清理后的 CairoSVG（SVG→PNG）、WeasyPrint（禁资源抓取的静态 XHTML→PDF→PNG）。没有 OCR、模型或运行时下载。

容器网络为 none、根文件系统只读、全部 capabilities 移除、禁止提权；1 CPU、768MiB 内存和交换合计上限、96 PIDs。`/work` 为384MiB tmpfs，`/tmp` 为64MiB tmpfs，`/output` 为200MiB tmpfs；独立 LibreOffice profile 禁宏和链接更新。进程文件大小、打开文件数、解码像素/帧数、输出字节和主机总时长另有限制。`--headless` 本身不被视为沙箱。

图片输出统一安全 PNG。GIF/TIFF 多帧只输出首帧并回报覆盖警告；SVG 移除主动内容和外部引用，不把原 SVG 交给浏览器；失败保留图片位置和图注，不能回退到原始媒体。PDF 预览来自真实渲染，扫描页没有规范文字块或虚构 segments。

复杂 EPUB 静态预览不承诺重现任意原书 CSS、竖排与全部分页；明确给出局限警告，并禁止这些单元的精确文字引用。复杂 Word 的文本框、垂直合并表格和脚注等未可靠映射时采用衍生 PDF 页预览，不将错序文字伪装成可引用结构。

官方依据：[LibreOffice 固定转换参数与独立 profile](https://help.libreoffice.org/latest/en-US/text/shared/guide/start_parameters.html)、[Docker 资源限制](https://docs.docker.com/engine/containers/resource_constraints/)、[Pillow 解码预算](https://pillow.readthedocs.io/en/stable/reference/Image.html)、[CairoSVG 安全解析](https://cairosvg.org/documentation/)、[WeasyPrint 安全边界](https://doc.courtbouillon.org/weasyprint/stable/first_steps.html#security)。真实容器测试与真实图书人工验收是不同证据，不能相互替代。
