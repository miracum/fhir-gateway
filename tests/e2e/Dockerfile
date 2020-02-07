FROM python:3.8-alpine3.11

RUN apk --no-cache add git=2.24.1-r0

WORKDIR /opt/tests
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY . .

ENTRYPOINT ["pytest"]
CMD [ "test.py" ]
