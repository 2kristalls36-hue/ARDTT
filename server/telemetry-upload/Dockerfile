FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

ENV TELEMETRY_LOG_ROOT=/var/logs/app
ENV TELEMETRY_LISTEN=0.0.0.0:9200

EXPOSE 9200

CMD ["gunicorn", "--bind", "0.0.0.0:9200", "--workers", "2", "--timeout", "120", "app:app"]
