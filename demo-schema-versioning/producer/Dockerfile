FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1

RUN pip install --no-cache-dir \
        confluent-kafka==2.5.3 \
        pydantic==2.9.2 \
        watchfiles==0.24.0

WORKDIR /app
COPY schemas /app/schemas
COPY producer /app/producer

ENV PYTHONPATH=/app
CMD ["python", "-m", "watchfiles", "--filter", "python", "python -m producer.main", "/app"]
