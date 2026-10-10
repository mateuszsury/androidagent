FROM alpine:3.23
WORKDIR /app
COPY index.html serve.sh ./
RUN chmod 755 serve.sh
USER 1000:1000
CMD ["nc", "-lk", "-p", "8080", "-e", "/app/serve.sh"]
