FROM python:3.8-alpine3.11

# hadolint ignore=DL3018
RUN apk --no-cache add git

WORKDIR /opt/tests
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY . .

ENTRYPOINT ["pytest"]
CMD [ "test.py" ]
