#!/bin/sh
# Offline static demo server using the nc applet shipped in Alpine.
# Consume the request headers before closing to avoid a TCP reset.
cr=$(printf '\r')
while IFS= read -r line; do
    [ "$line" = "$cr" ] || [ -z "$line" ] && break
done
printf 'HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %s\r\nConnection: close\r\n\r\n' "$(wc -c < /app/index.html)"
cat /app/index.html
