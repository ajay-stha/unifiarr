FROM node:22-alpine
WORKDIR /app
# Only what the app serves; tests and the saved config stay out of the image.
COPY server.mjs app.mjs live.mjs model.mjs qbit.mjs index.html styles.css ./
COPY assets ./assets
RUN mkdir /data && chown node:node /data
USER node
ENV HOST=0.0.0.0 PORT=4180 CONFIG=/data/unifiarr.json
VOLUME /data
EXPOSE 4180
HEALTHCHECK CMD wget -qO /dev/null http://127.0.0.1:4180/ || exit 1
CMD ["node", "server.mjs"]
