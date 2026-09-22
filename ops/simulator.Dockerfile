FROM node:24-alpine
WORKDIR /app
COPY simulator/device-simulator.mjs ./device-simulator.mjs
USER node
ENTRYPOINT ["node", "/app/device-simulator.mjs"]
