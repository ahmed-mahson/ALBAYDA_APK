FROM python:3.11-slim
WORKDIR /app
COPY server/sync_server.py /app/
ENV PORT=8765
EXPOSE 8765
CMD ["python", "sync_server.py"]
