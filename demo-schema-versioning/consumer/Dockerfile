FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1

RUN pip install --no-cache-dir \
        fastapi==0.115.0 \
        pydantic==2.9.2 \
        "uvicorn[standard]==0.30.6"

WORKDIR /app
COPY schemas /app/schemas
COPY consumer /app/consumer

ENV PYTHONPATH=/app PORT=8080
EXPOSE 8080
CMD ["sh", "-c", "uvicorn consumer.main:app --host 0.0.0.0 --port ${PORT} --reload --reload-dir /app/consumer --reload-dir /app/schemas"]
