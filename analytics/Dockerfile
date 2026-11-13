FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY planner_analytics ./planner_analytics
COPY worker.py ./worker.py
COPY outbox_worker.py ./outbox_worker.py
COPY incremental_processor.py ./incremental_processor.py
ENTRYPOINT ["python","-m","planner_analytics.export"]
