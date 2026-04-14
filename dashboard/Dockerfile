FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
# Optional: if present, a pre-rendered AsyncAPI HTML bundle is copied in.
# The image-builder phase in docker-compose stages this directory into the
# dashboard build context as `lowcode/` before invoking `docker build`.
COPY lowcode ./lowcode
EXPOSE 5001
CMD ["python", "app.py"]
