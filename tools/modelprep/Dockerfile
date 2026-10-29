ARG PYTHON_IMAGE=python@sha256:1aaa65a85fda306ffb8b910824d4e93bdce61e212c7e87168123ea3073b41a1a
FROM ${PYTHON_IMAGE}
ENV PYTHONUNBUFFERED=1 TF_CPP_MIN_LOG_LEVEL=2 TF_USE_LEGACY_KERAS=1 OMP_NUM_THREADS=2 TF_NUM_INTRAOP_THREADS=2 TF_NUM_INTEROP_THREADS=1
RUN apt-get update && apt-get install -y --no-install-recommends git libglib2.0-0 libgomp1 libgl1 && rm -rf /var/lib/apt/lists/*
RUN python -m pip install --no-cache-dir torch==2.11.0 torchvision==0.26.0 --index-url https://download.pytorch.org/whl/cpu
COPY requirements.txt /opt/modelprep/requirements.txt
RUN python -m pip install --no-cache-dir -r /opt/modelprep/requirements.txt
RUN git clone https://github.com/Megvii-BaseDetection/YOLOX.git /opt/YOLOX && git -C /opt/YOLOX checkout 6ddff4824372906469a7fae2dc3206c7aa4bbaee
COPY export_baseline.py test_focus_fusion.py source-lock.json /opt/modelprep/
ENV PYTHONPATH=/opt/YOLOX
RUN python /opt/modelprep/test_focus_fusion.py
WORKDIR /work
ENTRYPOINT ["python", "/opt/modelprep/export_baseline.py"]
