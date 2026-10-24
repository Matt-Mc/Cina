FROM python:3.13-slim-bookworm@sha256:e32be0e55814a08977501ca863271d9bd02443710db8f2afaf8372e2c4508530
RUN apt-get update && apt-get install -y --no-install-recommends openssl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home cina \
    && mkdir /data && chown cina:cina /data
WORKDIR /app
COPY --chmod=0444 cina.py /app/cina.py
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 CINA_DATA=/data
EXPOSE 8443
CMD ["python", "cina.py", "serve"]
