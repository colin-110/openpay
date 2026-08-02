# Static React SPA: no build-time secrets, calls the API from the browser rather than the
# container, so the same image works against any host that publishes the gateway on 8080.
#
# Two stages: build with Node, serve the compiled output with plain nginx. The final image
# contains no Node runtime at all — just static files and a web server.

FROM node:22-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.27-alpine AS runtime

# Client-side routing: any path nginx doesn't recognise as a file falls back to index.html, so
# refreshing the browser on a route the SPA owns doesn't 404.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
