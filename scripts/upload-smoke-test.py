#!/usr/bin/env python3
"""Real HTTP + filesystem upload smoke, exclusively against the disposable Compose stack.
Sessions are seeded test fixtures; this is not a browser login E2E.
"""
import base64
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("BASE_URL", "http://localhost:8080/api").rstrip("/")
TOKEN = os.environ["TOKEN"]
OTHER = TOKEN + "-other"
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5l0AAAAASUVORK5CYII=")


def request(method, route, token=TOKEN, body=None, content_type=None):
    headers = {}
    if token:
        headers["authorization"] = token
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(BASE + route, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def business(method, route, success, token=TOKEN, body=None, content_type=None):
    status, raw = request(method, route, token, body, content_type)
    value = json.loads(raw)
    assert status < 500 and value.get("success") is success, (method, route, status, value)
    return value


def upload(token=TOKEN):
    boundary = "smoke-" + uuid.uuid4().hex
    body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="smoke.png"\r\n'
            'Content-Type: image/png\r\n\r\n').encode() + PNG + ("\r\n--" + boundary + "--\r\n").encode()
    return business("POST", "/upload/blog", True, token, body, "multipart/form-data; boundary=" + boundary)["data"]


def image(path):
    status, body = request("GET", "/uploads" + path, token=None)
    assert status == 200 and body == PNG, (status, path)


def main():
    subprocess.run(["docker", "compose", "exec", "-T", "redis", "redis-cli", "-n", "6", "HSET",
                    "login:token:" + OTHER, "id", "2", "nickName", "upload-other", "icon", ""], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["docker", "compose", "exec", "-T", "redis", "redis-cli", "-n", "6", "EXPIRE",
                    "login:token:" + OTHER, "1800"], check=True, stdout=subprocess.DEVNULL)
    path = upload()
    image(path)
    business("DELETE", "/upload/blog?name=" + path, False, token=OTHER)
    image(path)
    post = json.dumps({"shopId": 1, "title": "Upload lifecycle smoke", "content": "Disposable test",
                       "images": path}).encode()
    business("POST", "/blog", False, token=OTHER, body=post, content_type="application/json")
    business("POST", "/blog", True, body=post, content_type="application/json")
    business("DELETE", "/upload/blog?name=" + path, False)
    image(path)
    draft = upload()
    business("DELETE", "/upload/blog?name=/uploads" + draft, True)
    business("DELETE", "/upload/blog?name=" + draft, True)
    status, _ = request("GET", "/uploads" + draft, token=None)
    assert status == 404, ("Missing draft must return 404", status)
    print("Upload smoke passed: real upload/read, cross-user denial, publication binding, referenced-image protection, draft delete/retry")


if __name__ == "__main__":
    main()
