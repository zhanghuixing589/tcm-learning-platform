FROM debian:bookworm-slim@sha256:e67eeead736ff7bbe258ef088bff683635c561b1fcfefad0806c1df4ab49ce0e
ENV DEBIAN_FRONTEND=noninteractive
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get -o Acquire::Retries=1 -o Acquire::http::Timeout=30 update \
    && apt-get -o Acquire::Retries=1 -o Acquire::http::Timeout=30 install -y --no-install-recommends ca-certificates \
    && sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=1 -o Acquire::https::Timeout=60 update \
    && apt-get -o Acquire::Retries=1 -o Acquire::https::Timeout=60 install -y --no-install-recommends \
    libreoffice-writer libreoffice-draw poppler-utils python3 python3-pil \
    python3-cairosvg python3-defusedxml weasyprint fonts-noto-cjk \
    && groupadd --gid 10001 converter && useradd --uid 10001 --gid 10001 --no-create-home converter
COPY entrypoint.py /opt/converter/entrypoint.py
ENV HOME=/tmp/home TMPDIR=/work
USER 10001:10001
ENTRYPOINT ["/usr/bin/python3", "/opt/converter/entrypoint.py"]
